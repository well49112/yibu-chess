package cn.yibu.chess.core

import org.junit.Assert.*
import org.junit.Test

class WeaknessStatsTest {
    private fun game(root: List<String>, best: List<String>, played: List<String>,
        bestMate: Int? = null, playedMate: Int? = null, id: Long = 1): GameRecord {
        assertEquals(best, ChessRules.legalVariation(root, best))
        assertEquals(played, ChessRules.legalVariation(root, played))
        val review = MoveReview(root.size + 1, played.first(), ChessRules.san(root, played.first()),
            Evaluation(22, cp = 30, mate = bestMate, pv = best), Evaluation(22, cp = -800, mate = playedMate, pv = played),
            grade = Grade.BLUNDER, explanation = "", provisional = false, algorithmVersion = 3, deeplySearched = true,
            bestExpectedPoints = .8, playedExpectedPoints = .2)
        return GameRecord(id = id, startedAt = id, humanWhite = root.size % 2 == 0, moves = root + played.first(),
            reviews = listOf(review), finished = true)
    }
    private fun queen(id: Long = 1) = game(listOf("e2e4", "e7e5", "d1h5", "b8c6"),
        listOf("h5f3", "g8f6"), listOf("h5e5", "c6e5"), id = id)

    @Test fun whiteAndBlackLossesIdentifyTheActualVictimAndNeverCountOpponents() {
        val white = queen()
        val black = game(listOf("c2c4", "e7e5", "b1c3", "d8h4", "g2g3"),
            listOf("h4d8", "g1f3"), listOf("h4e4", "c3e4"), id = 2)
        val report = WeaknessStats.build(listOf(white, black))
        assertEquals(2, report.confirmedMoves)
        assertEquals(2, report.analyzedGames)
        assertEquals(6, report.playerMoves)
        val group = report.groups.single()
        assertEquals(WeaknessType.HANGING_PIECE, group.type)
        assertEquals(2, group.count)
        assertEquals(2, group.games)
        assertTrue(group.examples.any { it.evidence.contains("e5 的后") && it.evidence.contains("Nxe5") })
        assertTrue(group.examples.any { it.evidence.contains("e4 的后") && it.evidence.contains("Nxe4") })
        assertTrue(WeaknessStats.build(listOf(white.copy(humanWhite = false), black.copy(humanWhite = true))).groups.isEmpty())
    }

    @Test fun missedAndAllowedMateRequireAnActualLegalMatingContinuation() {
        val allowed = game(listOf("f2f3", "e7e5"), listOf("b1c3", "b8c6"), listOf("g2g4", "d8h4"), playedMate = -1)
        val missed = game(listOf("f2f3", "e7e5", "g2g4"), listOf("d8h4"), listOf("b8c6", "e2e3"), bestMate = 1, id = 2)
        val groups = WeaknessStats.build(listOf(allowed, missed)).groups.associateBy { it.type }
        assertEquals(1, groups.getValue(WeaknessType.ALLOWED_MATE).count)
        assertTrue(groups.getValue(WeaknessType.ALLOWED_MATE).examples.single().evidence.contains("Qh4#"))
        assertEquals(1, groups.getValue(WeaknessType.MISSED_MATE).count)
        val short = allowed.copy(reviews = listOf(allowed.reviews.single().let { it.copy(played = it.played.copy(pv = listOf("g2g4"))) }))
        assertTrue(WeaknessStats.build(listOf(short)).groups.isEmpty())
    }

    @Test fun missedForkNeedsTwoTargetsAndAnActualCaptureInTheShownLine() {
        val root = listOf("e2e4", "e7e5", "b1c3", "b8c6", "c3b5", "d7d5", "e4d5", "d8d5", "d2d3", "g8f6")
        val game = game(root, listOf("b5c7", "e8d7", "c7a8"), listOf("g1f3", "c8f5", "c1e3"))
        val group = WeaknessStats.build(listOf(game)).groups.single()
        assertEquals(WeaknessType.MISSED_FORK, group.type)
        assertTrue(group.examples.single().evidence.contains("a8 的车"))
        assertTrue(group.examples.single().evidence.contains("净赢 6"))
        val short = game.copy(reviews = listOf(game.reviews.single().let { it.copy(best = it.best.copy(pv = it.best.pv.take(2))) }))
        assertTrue(WeaknessStats.build(listOf(short)).groups.isEmpty())
    }

    @Test fun missedCaptureIncludesBothSidesExchangesAndHasOneLabelPerMove() {
        val root = listOf("e2e4", "e7e5", "d1h5", "b8c6", "h5e5")
        val game = game(root, listOf("c6e5", "g1f3"), listOf("f8e7", "e5g3"))
        val group = WeaknessStats.build(listOf(game)).groups.single()
        assertEquals(WeaknessType.MISSED_CAPTURE, group.type)
        assertEquals(1, group.count)
        assertTrue(group.examples.single().evidence.contains("净赢 9"))
    }

    @Test fun equalTradesSoundSacrificesAndScoreOnlyDifferencesAreNotWeaknesses() {
        val trade = game(listOf("e2e4", "d7d5"), listOf("g1f3", "g8f6"), listOf("e4d5", "d8d5"))
        val quiet = game(emptyList(), listOf("e2e4", "e7e5"), listOf("d2d4", "d7d5"), id = 2)
        val root = listOf("e2e4", "e7e5", "g1f3", "d7d6", "f1c4", "c8g4", "b1c3", "g7g6")
        val sacrifice = game(root, listOf("f3e5", "g4d1", "c4f7", "e8e7", "c3d5"),
            listOf("f3e5", "g4d1", "c4f7", "e8e7", "c3d5"), bestMate = 3, playedMate = 3, id = 3)
        assertTrue(WeaknessStats.build(listOf(trade, quiet, sacrifice)).groups.isEmpty())
    }

    @Test fun unfinishedStaleShallowAndMismatchedReviewsDoNotCountAsConfirmedCoverage() {
        val game = queen()
        val review = game.reviews.single()
        val unsafe = listOf(review.copy(provisional = true), review.copy(algorithmVersion = 2), review.copy(grade = Grade.UNSTABLE),
            review.copy(played = review.played.copy(depth = 8)), review.copy(uci = "h5h3"),
            review.copy(best = review.best.copy(pv = listOf("h5e8"))), review.copy(played = review.played.copy(pv = listOf("h5f3"))))
        unsafe.forEach { candidate ->
            val report = WeaknessStats.build(listOf(game.copy(reviews = listOf(candidate))))
            assertEquals(0, report.confirmedMoves)
            assertTrue(report.groups.isEmpty())
            assertEquals(3, report.playerMoves)
        }
        assertEquals(0, WeaknessStats.build(listOf(game.copy(finished = false))).games)
        assertEquals(1, WeaknessStats.build(listOf(game.copy(reviews = listOf(review, review)))).confirmedMoves)
        val forced = WeaknessStats.build(listOf(game.copy(reviews = listOf(review.copy(grade = Grade.FORCED)))))
        assertEquals(1, forced.confirmedMoves)
        assertTrue(forced.groups.isEmpty())
        assertTrue(WeaknessStats.build(listOf(game.copy(reviews = listOf(review.copy(grade = Grade.BRILLIANT))))).groups.isEmpty())
    }

    @Test fun onlyTheLatestTwentyRetainedGamesCountAndDeletingARecordRemovesItsEvidence() {
        val games = (1L..21L).map(::queen)
        val tracker = WeaknessStats.Tracker()
        val report = tracker.build(games)
        assertEquals(20, report.games)
        assertEquals(20, report.groups.single().count)
        assertFalse(report.groups.single().examples.any { it.gameId == 1L })
        assertEquals(WeaknessStats.build(games), report)
        val retained = games.filter { it.id != 21L }
        val afterDelete = tracker.build(retained)
        assertEquals(20, afterDelete.groups.single().count)
        assertFalse(afterDelete.groups.single().examples.any { it.gameId == 21L })
        assertTrue(afterDelete.groups.single().examples.any { it.gameId == 1L })
        assertTrue(tracker.build(emptyList()).groups.isEmpty())
        assertEquals(1, tracker.build(listOf(queen())).groups.single().count)
    }

    @Test fun aSavedReevaluationUpdatesExistingStatisticsWithoutDuplicatingTheMove() {
        val game = queen()
        val tracker = WeaknessStats.Tracker()
        assertEquals(1, tracker.build(listOf(game)).groups.single().count)
        assertEquals(tracker.build(listOf(game)), tracker.build(listOf(game.copy(lessons = listOf(MoveCoach.explain(game.moves.take(4), game.reviews.single()))))))
        val review = game.reviews.single().copy(bestExpectedPoints = .2, playedExpectedPoints = .2, grade = Grade.GOOD)
        assertTrue(tracker.build(listOf(game.copy(reviews = listOf(review)))).groups.isEmpty())
        assertEquals(1, tracker.build(listOf(game.copy(reviews = listOf(review)))).confirmedMoves)
    }
}
