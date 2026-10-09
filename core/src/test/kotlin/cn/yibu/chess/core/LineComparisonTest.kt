package cn.yibu.chess.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LineComparisonTest {
    private val root = listOf("e2e4", "e7e5", "d1h5", "b8c6")
    private val review = MoveReview(5, "h5e5", "Qxe5+", Evaluation(22, cp = 30, pv = listOf("h5f3", "g8f6")),
        Evaluation(22, cp = -800, pv = listOf("h5e5", "c6e5")), grade = Grade.BLUNDER, explanation = "",
        provisional = false, algorithmVersion = 3, deeplySearched = true)

    @Test fun bothLinesStartAtTheSamePositionAndTheActualReplyNamesTheLostQueen() {
        val lesson = MoveCoach.explain(root, review)
        assertEquals(listOf("h5f3", "g8f6"), lesson.variation)
        assertEquals(listOf("h5e5", "c6e5"), lesson.playedVariation)
        assertEquals(lesson.playedVariation, lesson.playedSteps.map { it.uci })
        assertTrue(lesson.playedSteps[1].explanation.contains("e5的后"))
        assertNotEquals(ChessRules.board(root + lesson.variation).fen, ChessRules.board(root + lesson.playedVariation).fen)
        assertEquals(lesson, Json.decodeFromString<MoveLesson>(Json.encodeToString(MoveLesson.serializer(), lesson)))
    }

    @Test fun missingAndWrongActualPvsNeverBorrowTheRecommendedContinuation() {
        listOf(emptyList(), listOf("h5f3", "g8f6"), listOf("h5e5", "a8a1")).forEach { pv ->
            val lesson = MoveCoach.explain(root, review.copy(played = review.played.copy(pv = pv)))
            assertEquals(listOf("h5e5"), lesson.playedVariation)
            assertEquals(1, lesson.playedSteps.size)
            assertFalse(lesson.playedVariation.contains("g8f6"))
        }
    }

    @Test fun matingActualLinesStopAtMateAndOldLessonsUpgradeFromSavedAnalysis() {
        val history = listOf("f2f3", "e7e5")
        val review = review.copy(ply = 3, uci = "g2g4", san = "g4",
            best = Evaluation(22, cp = 0, pv = listOf("b1c3", "b8c6")),
            played = Evaluation(22, mate = -1, pv = listOf("g2g4", "d8h4", "e2e3")))
        val lesson = MoveCoach.explain(history, review)
        assertEquals(listOf("g2g4", "d8h4"), lesson.playedVariation)
        assertTrue(lesson.playedSteps.last().explanation.contains("直接将杀"))
        assertFalse(MoveCoach.canReuse(lesson.copy(algorithmVersion = 4, playedVariation = emptyList(), playedSteps = emptyList()), review))
        assertEquals(listOf("g2g4", "d8h4"), MoveCoach.explain(history, review).playedVariation)
    }

    @Test fun identicalFirstMovesRemainHonestAndChangedActualEvidenceInvalidatesTheLesson() {
        val same = review.copy(best = review.played, grade = Grade.BEST)
        val lesson = MoveCoach.explain(root, same)
        assertEquals(lesson.variation, lesson.playedVariation)
        assertTrue(MoveCoach.canReuse(lesson, same))
        assertFalse(MoveCoach.canReuse(lesson, same.copy(played = same.played.copy(pv = listOf("h5e5")))))
    }
}
