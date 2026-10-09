package cn.yibu.chess.core

import org.junit.Assert.*
import org.junit.Test

class PracticeCoachTest {
    private fun question(root: List<String>, best: List<String>, played: List<String>, bestMate: Int? = null,
        playedMate: Int? = null, second: Evaluation? = null): PracticeQuestion {
        assertEquals(best, ChessRules.legalVariation(root, best))
        assertEquals(played, ChessRules.legalVariation(root, played))
        val review = MoveReview(root.size + 1, played.first(), ChessRules.san(root, played.first()),
            Evaluation(22, cp = 30, mate = bestMate, pv = best), Evaluation(22, cp = -800, mate = playedMate, pv = played),
            second = second, grade = Grade.BLUNDER, explanation = "", provisional = false, algorithmVersion = 3,
            deeplySearched = true, bestExpectedPoints = .8, playedExpectedPoints = .2)
        val game = GameRecord(humanWhite = root.size % 2 == 0, moves = root + played.first(), reviews = listOf(review), finished = true)
        return MistakePractice.questions(listOf(game), WeaknessStats.build(listOf(game))).single()
    }
    private val queenRoot = listOf("e2e4", "e7e5", "d1h5", "b8c6")
    private fun queen(second: Evaluation? = null) = question(queenRoot, listOf("h5f3", "g8f6"),
        listOf("h5e5", "c6e5"), second = second)

    @Test fun whiteQueenLossExplainsThePawnTemptationRecaptureAndSafeAlternative() {
        val question = queen()
        assertEquals("Qxe5+（后从h5到e5）", PracticeCoach.wrongMove(question))
        val result = PracticeCoach.explain(question)
        assertTrue(result.mistake.contains("只吃到 1 点"))
        assertTrue(result.mistake.contains("Nxe5"))
        assertTrue(result.mistake.contains("e5的后（9 点）"))
        assertTrue(result.mistake.contains("没有合法的立即回吃"))
        assertTrue(result.mistake.contains("净少 8 点"))
        assertTrue(result.improvement.contains("把后从h5移到f3"))
        assertTrue(result.improvement.contains("对手没有合法的一步吃掉f3"))
        assertTrue(result.improvement.contains("净子力相差 8 点"))
        assertEquals(listOf("h5f3", "g8f6"), result.continuation.map { it.uci })
        listOf("局面质量", "调整站位", "提升主动性", "衔接", "引擎评分").forEach { phrase ->
            assertFalse(result.improvement.contains(phrase))
        }
    }

    @Test fun blackQueenLossUsesBlackPiecesAndTheCorrectSquares() {
        val q = question(listOf("c2c4", "e7e5", "b1c3", "d8h4", "g2g3"),
            listOf("h4d8", "g1f3"), listOf("h4e4", "c3e4"))
        val result = PracticeCoach.explain(q)
        assertTrue(result.mistake.contains("Nxe4"))
        assertTrue(result.mistake.contains("e4的后（9 点）"))
        assertTrue(result.improvement.contains("把后从h4移到d8"))
        assertTrue(result.improvement.contains("净子力相差 9 点"))
        assertTrue(result.continuation.first().title.startsWith("黑方 Qd8"))
    }

    @Test fun avoidingMateShowsTheConcreteDefenseAgainstTheOriginalCheckingMove() {
        val q = question(listOf("f2f3", "e7e5"), listOf("b1c3", "b8c6"), listOf("g2g4", "d8h4"), playedMate = -1)
        val result = PracticeCoach.explain(q)
        assertTrue(result.mistake.contains("Qh4#"))
        assertTrue(result.improvement.contains("Qh4+"))
        assertTrue(result.improvement.contains("g3（兵从g2到g3）"))
        assertTrue(result.improvement.contains("解将"))
        assertFalse(result.improvement.contains("已经获胜"))
    }

    @Test fun missedMateNamesTheMatedKingAndTheActualMatingMove() {
        val q = question(listOf("f2f3", "e7e5", "g2g4"), listOf("d8h4"), listOf("b8c6", "e2e3"), bestMate = 1)
        val result = PracticeCoach.explain(q)
        assertTrue(result.improvement.contains("Qh4#"))
        assertTrue(result.improvement.contains("将杀e1的王"))
        assertTrue(result.improvement.contains("没有合法解将"))
        assertTrue(result.continuation.single().explanation.contains("形成将杀"))
    }

    @Test fun aForkExplainsBothTargetsAndWhereTheMaterialIsActuallyWon() {
        val root = listOf("e2e4", "e7e5", "b1c3", "b8c6", "c3b5", "d7d5", "e4d5", "d8d5", "d2d3", "g8f6")
        val q = question(root, listOf("b5c7", "e8d7", "c7a8"), listOf("g1f3", "c8f5", "c1e3"))
        val result = PracticeCoach.explain(q)
        assertTrue(result.improvement.contains("e8的黑王"))
        assertTrue(result.improvement.contains("a8的黑车"))
        assertTrue(result.improvement.contains("Kd7"))
        assertTrue(result.improvement.contains("Nxa8 吃掉a8的车"))
        assertTrue(result.improvement.contains("答案净多 6 点"))
        assertTrue(result.continuation.last().explanation.contains("吃掉a8的车（5 点）"))
    }

    @Test fun aMissedCaptureNamesTheVictimAndAccountsForTheShownReplies() {
        val q = question(queenRoot + "h5e5", listOf("c6e5", "g1f3"), listOf("f8e7", "e5g3"))
        val result = PracticeCoach.explain(q)
        assertTrue(result.improvement.contains("Nxe5 直接吃掉e5的后（9 点子力）"))
        assertTrue(result.improvement.contains("答案净多 9 点"))
        assertFalse(result.improvement.contains("赢 8 个兵"))
    }

    @Test fun anAcceptedSecondCandidateIsExplainedWithItsOwnLineAndDestination() {
        val q = queen(Evaluation(22, cp = 28, pv = listOf("h5e2", "g8f6")))
        assertTrue("h5e2" in q.answers)
        val result = PracticeCoach.explain(q, "h5e2")
        assertTrue(result.improvement.contains("把后从h5移到e2"))
        assertFalse(result.improvement.contains("Qf3"))
        assertFalse(result.improvement.contains("移到f3"))
        assertEquals("h5e2", result.continuation.first().uci)
    }

    @Test fun corruptSuffixesAreTruncatedInsteadOfInventingRepliesOrCrashing() {
        val q = queen().let { it.copy(review = it.review.copy(best = it.review.best.copy(pv = it.review.best.pv + "a1a8"))) }
        val result = PracticeCoach.explain(q)
        assertEquals(listOf("h5f3", "g8f6"), result.continuation.map { it.uci })
        assertFalse(result.improvement.contains("a8"))
    }
}
