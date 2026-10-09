package cn.yibu.chess.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AutoAnalysisTest {
    private val moves = listOf("e2e4", "e7e5", "g1f3")
    private fun review(ply: Int, revision: Long = 10) = MoveReview(ply, moves[ply - 1], ChessRules.san(moves.take(ply - 1), moves[ply - 1]),
        Evaluation(22, cp = 20, pv = listOf(moves[ply - 1])), Evaluation(22, cp = 20, pv = listOf(moves[ply - 1])),
        grade = Grade.BEST, explanation = "", provisional = false, engineVersion = "Stockfish 19", algorithmVersion = 3,
        deeplySearched = true, analysisProfile = "lightning", analyzedAt = revision)
    @Test fun bothColorsEverySavedMoveAndAllSourcesAreEligibleWhileValidDeepCachesAreKept() {
        val base = GameRecord(moves = moves, reviews = listOf(review(1).copy(analysisProfile = "deep")))
        listOf(base, base.copy(finished = true), base.copy(source = ChessComSource("me", "url", "me", "other")),
            base.copy(openingTraining = OpeningTraining("italian", "main", 3))).forEach { assertEquals(listOf(2, 3), AutoAnalysis.missing(it)) }
        assertEquals(listOf(1, 2, 3), AutoAnalysis.missing(base.copy(reviews = listOf(review(1).copy(grade = Grade.UNSTABLE)))))
        assertEquals(listOf(1, 2, 3), AutoAnalysis.missing(base.copy(reviews = listOf(review(1).copy(best = Evaluation(10))))))
        assertEquals(listOf(1, 2, 3), AutoAnalysis.missing(base.copy(reviews = listOf(review(1).copy(engineVersion = "Stockfish 17.1")))))
    }
    @Test fun delayedSnapshotsKeepNewReviewsResultRatingAndValidLessonsButDiscardDifferentBranches() {
        val game = GameRecord(moves = moves)
        val fresh = review(1, 100)
        val lesson = MoveCoach.explain(emptyList(), fresh)
        val change = EloRules.calculate(PlayerProfile(), 500, 1.0)
        val saved = game.copy(reviews = listOf(fresh), lessons = listOf(lesson), finished = true, result = "1-0", ratingChange = change)
        val merged = GameSnapshots.merge(game.copy(moves = moves.take(2), reviews = listOf(review(1, 1))), saved)
        assertEquals(moves, merged.moves); assertTrue(merged.finished); assertEquals(change, merged.ratingChange)
        assertEquals(listOf(fresh), merged.reviews); assertEquals(listOf(lesson), merged.lessons)
        val branch = game.copy(moves = listOf("d2d4", "d7d5", "g1f3"))
        assertTrue(GameSnapshots.merge(branch, game.copy(reviews = listOf(fresh))).reviews.isEmpty())
    }
    @Test fun oldReviewsDecodeAndNewerResultsWinWithoutChangingPlayerScores() {
        val old = Json.decodeFromString<MoveReview>("""{"ply":1,"uci":"e2e4","san":"e4","best":{"depth":22},"played":{"depth":22},"grade":"BEST","explanation":""}""")
        assertEquals(0, old.analyzedAt)
        val game = GameRecord(moves = moves, reviews = listOf(review(1, 30)), playerEloAtStart = 436, opponentElo = 520)
        assertEquals(436, AutoAnalysis.scoringElo(game, 1)); assertEquals(520, AutoAnalysis.scoringElo(game, 2))
        assertEquals(40, GameSnapshots.merge(game.copy(reviews = listOf(review(1, 40))), game).reviews.single().analyzedAt)
        assertEquals(900_000, AutoAnalysis.retryDelay(30))
    }
}
