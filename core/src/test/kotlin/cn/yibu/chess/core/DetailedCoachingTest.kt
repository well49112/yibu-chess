package cn.yibu.chess.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DetailedCoachingTest {
    private fun review(history: List<String>, best: List<String>, played: List<String>,
        bestMate: Int? = null, playedMate: Int? = null, provisional: Boolean = false): MoveReview =
        MoveReview(history.size + 1, played.first(), ChessRules.san(history, played.first()),
            Evaluation(22, cp = 30, mate = bestMate, pv = best),
            Evaluation(22, cp = -800, mate = playedMate, pv = played),
            grade = Grade.BLUNDER, explanation = "", provisional = provisional, algorithmVersion = 3,
            engineVersion = "Stockfish 19", deeplySearched = true, bestExpectedPoints = .7, playedExpectedPoints = .1)

    @Test fun whiteQueenBlunderShowsTheReplyUnrecoverableMaterialAndBetterAlternative() {
        val history = listOf("e2e4", "e7e5", "d1h5", "b8c6")
        val review = review(history, listOf("h5f3", "g8f6"), listOf("h5e5", "c6e5"))
        val lesson = MoveCoach.explain(history, review)
        assertTrue(lesson.why.contains("为什么这步有问题"))
        assertTrue(lesson.why.contains("把后走到 e5"))
        assertTrue(lesson.why.contains("对手如何应对：Nxe5"))
        assertTrue(lesson.why.contains("没有合法的立即回吃"))
        assertTrue(lesson.why.contains("净少了 8 点子力"))
        assertTrue(lesson.why.contains("推荐路线的净子力多 8 点"))
        assertTrue(lesson.why.contains("推荐的改进：Qf3"))
        assertTrue(lesson.why.contains("不是确定会损失这么多兵"))
        assertTrue(lesson.playedExplanation.contains("Qxe5+ → Nxe5"))
    }

    @Test fun blackMistakesUseTheMoverPerspectiveAndCorrectPieceSquares() {
        val history = listOf("c2c4", "e7e5", "b1c3", "d8h4", "g2g3")
        val lesson = MoveCoach.explain(history, review(history, listOf("h4d8", "g1f3"), listOf("h4e4", "c3e4")))
        assertTrue(lesson.why.contains("建议黑方走 Qd8"))
        assertTrue(lesson.why.contains("把后走到 e4"))
        assertTrue(lesson.why.contains("净少了 9 点子力"))
        assertTrue(lesson.why.contains("推荐路线的净子力多 9 点"))
        assertTrue(lesson.why.contains("-8.00"))
    }

    @Test fun allowingMateExplainsKingSafetyAndStopsAtActualMate() {
        val history = listOf("f2f3", "e7e5")
        val lesson = MoveCoach.explain(history, review(history, listOf("g1h3", "b8c6"),
            listOf("g2g4", "d8h4", "e2e3"), playedMate = -1))
        assertTrue(lesson.why.contains("留下了强制将杀路线"))
        assertTrue(lesson.why.contains("对手如何应对：Qh4#"))
        assertTrue(lesson.why.contains("直接将杀"))
        assertFalse(lesson.playedExplanation.contains("e3"))
    }

    @Test fun missingOrIllegalPlayedContinuationNeverInventsAPunishment() {
        val review = review(emptyList(), listOf("e2e4", "e7e5"), listOf("d2d4", "d7d4", "g1f3"))
        val lesson = MoveCoach.explain(emptyList(), review)
        assertTrue(lesson.why.contains("没有给出合法的实战后续"))
        assertFalse(lesson.why.contains("对手如何应对"))
        assertFalse(lesson.why.contains("净少了"))
    }

    @Test fun unconfirmedReviewsDoNotPresentBlundersOrMaterialComparisonsAsSettled() {
        val history = listOf("e2e4", "e7e5", "d1h5", "b8c6")
        val lesson = MoveCoach.explain(history, review(history, listOf("h5f3", "g8f6"),
            listOf("h5e5", "c6e5"), provisional = true))
        assertTrue(lesson.why.contains("本步评价尚未确认"))
        assertFalse(lesson.why.contains("为什么这步有问题"))
        assertFalse(lesson.why.contains("推荐路线的净子力多"))
        assertFalse(lesson.why.contains("净少了"))
    }

    @Test fun anEvenPawnExchangeIsNotDescribedAsLosingMaterial() {
        val history = listOf("e2e4", "d7d5")
        val lesson = MoveCoach.explain(history, review(history, listOf("g1f3", "g8f6"), listOf("e4d5", "d8d5")))
        assertTrue(lesson.why.contains("不能仅因被回吃就断言亏子"))
        assertFalse(lesson.why.contains("净少了"))
    }

    @Test fun enPassantExplainsTheVictimsActualSquareAndTheLegalRecapture() {
        val history = listOf("e2e4", "a7a6", "e4e5")
        val lesson = MoveCoach.explain(history, review(history, listOf("g8f6", "g1f3"), listOf("d7d5", "e5d6")))
        assertTrue(lesson.playedExplanation.contains("把兵走到 d5"))
        assertTrue(lesson.playedExplanation.contains("吃过路兵"))
        assertTrue(lesson.playedExplanation.contains("本方虽可用 cxd6 回吃"))
        assertFalse(lesson.playedExplanation.contains("没有保住 d6"))
    }

    @Test fun oldAlgorithmsAndShallowSearchesRemainTentativeWhenRefreshingTheirLessons() {
        val base = review(emptyList(), listOf("e2e4", "e7e5"), listOf("d2d4", "d7d5"))
        listOf(base.copy(algorithmVersion = 1), base.copy(best = base.best.copy(depth = 8), played = base.played.copy(depth = 8)))
            .forEach { review ->
                val lesson = MoveCoach.explain(emptyList(), review)
                assertTrue(lesson.playedExplanation.contains("本步评价尚未确认"))
                assertFalse(lesson.playedExplanation.contains("为什么这步有问题"))
            }
    }

    @Test fun missingAMateIsExplainedSeparatelyFromLosingMaterial() {
        val history = listOf("f2f3", "e7e5", "g2g4")
        val lesson = MoveCoach.explain(history, review(history, listOf("d8h4"), listOf("b8c6", "e2e3"), bestMate = 1))
        assertTrue(lesson.playedExplanation.contains("原本存在将杀路线"))
        assertTrue(lesson.why.contains("建议黑方走 Qh4#"))
    }

    @Test fun currentLessonsRoundTripAndOlderCacheIsUpgradedWithoutMutatingTheRecord() {
        val review = review(emptyList(), listOf("e2e4", "e7e5"), listOf("d2d4", "d7d5"))
        val lesson = MoveCoach.explain(emptyList(), review)
        assertTrue(MoveCoach.canReuse(lesson, review))
        assertFalse(MoveCoach.canReuse(lesson.copy(algorithmVersion = 2), review))
        assertFalse(MoveCoach.canReuse(lesson, review.copy(best = review.best.copy(pv = listOf("e2e4")))))
        assertEquals(lesson, Json.decodeFromString<MoveLesson>(Json.encodeToString(MoveLesson.serializer(), lesson)))
        val old = Json.decodeFromString<MoveLesson>("""{"ply":1,"recommendedMove":"e2e4","why":"旧原因","plan":"旧思路","variation":["e2e4"],"depth":22}""")
        assertEquals("", old.playedExplanation)
        assertFalse(MoveCoach.canReuse(old, review))
        assertEquals("旧原因", old.why)
    }

    @Test fun quietKnightMistakeExplainsLostTempoAndTheOpponentsConcreteCentralBreak() {
        val history = listOf("e2e4", "e7e6", "b1c3", "e6e5", "f1c4", "f8c5", "d2d3", "g8f6", "f2f3", "c7c6", "g1e2")
        val review = review(history, listOf("b7b5", "c4b3", "d7d5", "e4d5", "c6d5"),
            listOf("b8a6", "d3d4", "e5d4", "e2d4", "d7d6"))
        val lesson = MoveCoach.explain(history, review)
        val text = lesson.playedExplanation
        assertTrue(text.contains("b5 用兵攻击c4的白象"))
        assertTrue(text.contains("Na6 没有制造这层压力"))
        assertTrue(text.contains("Bb3"))
        assertTrue(text.contains("把兵从d3推进中心d4"))
        assertTrue(text.contains("c5的黑象"))
        assertTrue(text.contains("e5的黑兵"))
        assertTrue(text.indexOf("为什么这步有问题") < text.indexOf("引擎参考评价"))
        assertFalse(text.contains("本方的局面质量下降"))
        val reason = MoveCoach.keyReason(history, review)!!
        assertTrue(reason.contains("c4的白象"))
        assertTrue(reason.contains("d4"))
        assertFalse(reason.contains("百分点"))
    }

    @Test fun aQuietMoveWithOnlyAScoreGapAndNoConcreteEvidenceIsNotATeachingPoint() {
        val history = listOf("e2e4", "e7e5", "g1f3", "b8c6")
        val review = review(history, listOf("a2a3"), listOf("h2h3"))
        assertNull(MoveCoach.keyReason(history, review))
        val lesson = MoveCoach.explain(history, review)
        assertTrue(lesson.playedExplanation.contains("没有展示出能解释差距的具体后果"))
        assertFalse(lesson.playedExplanation.contains("局面质量下降"))
    }

    @Test fun anOldVersionThreeLessonIsRegeneratedFromTheExistingEngineEvidence() {
        val review = review(emptyList(), listOf("e2e4", "e7e5"), listOf("d2d4", "d7d5"))
        val current = MoveCoach.explain(emptyList(), review)
        assertEquals(4, current.algorithmVersion)
        assertFalse(MoveCoach.canReuse(current.copy(algorithmVersion = 3), review))
        assertTrue(MoveCoach.canReuse(current, review))
    }
}
