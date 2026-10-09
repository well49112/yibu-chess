package cn.yibu.chess.core

import org.junit.Assert.*
import org.junit.Test

class MistakePracticeTest {
    private val root = listOf("e2e4", "e7e5", "d1h5", "b8c6")
    private val review = MoveReview(5, "h5e5", "Qxe5+", Evaluation(22, cp = 30, pv = listOf("h5f3", "g8f6")),
        Evaluation(22, cp = -800, pv = listOf("h5e5", "c6e5")), grade = Grade.BLUNDER, explanation = "",
        provisional = false, algorithmVersion = 3, engineVersion = "Stockfish 19", deeplySearched = true,
        bestExpectedPoints = .7, playedExpectedPoints = .1)
    private val game = GameRecord(moves = root + review.uci, reviews = listOf(review), finished = true)
    private fun question(review: MoveReview = this.review): PracticeQuestion {
        val game = game.copy(reviews = listOf(review))
        return MistakePractice.questions(listOf(game), WeaknessStats.build(listOf(game))).single()
    }

    @Test fun questionsUseOnlyRetainedOwnConfirmedTacticalMistakesAndKeepTheOriginalPosition() {
        val report = WeaknessStats.build(listOf(game))
        val question = MistakePractice.questions(listOf(game), report).single()
        assertEquals(root, question.history)
        assertEquals("h5f3", question.bestMove)
        assertTrue(question.evidence.contains("后"))
        assertEquals(listOf("h5f3"), question.answers.toList())
        assertTrue(MistakePractice.questions(emptyList(), report).isEmpty())
        assertTrue(MistakePractice.questions(listOf(game.copy(humanWhite = false)), report).isEmpty())
        assertTrue(MistakePractice.questions(listOf(game.copy(reviews = listOf(review.copy(provisional = true)))), report).isEmpty())
        assertEquals(root + "h5e5", game.moves)
    }

    @Test fun comparableNearEqualSecondCandidatesAreAcceptedButShallowOrWorseOnesAreNot() {
        val alternative = Evaluation(22, cp = 28, pv = listOf("h5e2", "g8f6"))
        assertEquals(setOf("h5f3", "h5e2"), question(review.copy(second = alternative)).answers)
        assertEquals(setOf("h5f3"), question(review.copy(second = alternative.copy(depth = 10))).answers)
        assertEquals(setOf("h5f3"), question(review.copy(second = alternative.copy(cp = -800))).answers)
        assertEquals(setOf("h5f3"), question(review.copy(second = alternative.copy(pv = listOf("h5e8")))).answers)
    }

    @Test fun firstHintDoesNotRevealTheAnswerAndSecondHintNamesOnlyTheOriginPiece() {
        val question = question()
        assertTrue(MistakePractice.hint(question, 1).contains("回吃"))
        assertFalse(MistakePractice.hint(question, 1).contains("Qf3"))
        assertFalse(MistakePractice.hint(question, 1).contains("h5"))
        assertTrue(MistakePractice.hint(question, 2).contains("h5 的后"))
        assertFalse(MistakePractice.hint(question, 2).contains("f3"))
    }

    @Test fun independentAnswersExpandSpacingAndAssistedCompletionResetsIt() {
        val question = question()
        var record = PracticeProgress(question.key, question.bestMove)
        val now = 1_000_000L
        listOf(1L, 3L, 7L, 14L).forEachIndexed { index, days ->
            record = MistakePractice.record(question, record, true, now)
            assertEquals(now + days * 86_400_000L, record.nextDueAt)
            assertEquals(index + 1, record.independentCorrect)
        }
        record = MistakePractice.record(question, record, false, now)
        assertEquals(5, record.completed)
        assertEquals(4, record.independentCorrect)
        assertEquals(0, record.streak)
        assertEquals(now + 600_000L, record.nextDueAt)
    }

    @Test fun changedRecommendationsInvalidateOldMasteryAndFutureQuestionsCanBeRepracticed() {
        val question = question()
        val old = PracticeProgress(question.key, "h5e2", completed = 8, independentCorrect = 8, streak = 4, nextDueAt = Long.MAX_VALUE)
        val records = mapOf(question.key to old)
        assertEquals(0, MistakePractice.effective(question, records).completed)
        assertEquals(listOf(question), MistakePractice.queue(listOf(question), records, 0))
        val fresh = MistakePractice.record(question, old, true, 0)
        assertEquals(1, fresh.completed)
        assertEquals(1, fresh.streak)
        assertEquals(listOf(question), MistakePractice.queue(listOf(question), mapOf(question.key to fresh), 1))
    }

    @Test fun dueQuestionsArePrioritizedAndTheSessionIsLimitedToTen() {
        val questions = (1L..15).map { question().copy(gameId = it) }
        val records = questions.associate { it.key to PracticeProgress(it.key, it.bestMove, nextDueAt = if (it.gameId <= 3) 200 else 0) }
        val queue = MistakePractice.queue(questions, records, 100)
        assertEquals(10, queue.size)
        assertTrue(queue.none { it.gameId <= 3 })
        assertTrue(MistakePractice.queue(emptyList(), emptyMap(), 100).isEmpty())
    }

    @Test fun theNextQuestionClearsHintsAndAttemptsButPreservesSessionTotals() {
        val question = question()
        val session = PracticeSession(listOf(question), hints = 2, wrongAttempts = 2, revealed = true, assisted = 1)
        assertTrue(session.finished)
        val next = session.next()
        assertNull(next.current)
        assertFalse(next.finished)
        assertEquals(0, next.hints)
        assertEquals(0, next.wrongAttempts)
        assertEquals(1, next.assisted)
    }
}
