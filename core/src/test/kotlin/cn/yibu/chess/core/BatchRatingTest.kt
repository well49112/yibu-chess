package cn.yibu.chess.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BatchRatingTest {
    private val service = object : StockfishService {
        var calls = 0
        override suspend fun evaluate(history: List<String>, profile: String, multiPv: Int): RemoteEvaluation = error("unused")
        override suspend fun analyzeMove(history: List<String>, playedMove: String, deep: Boolean, profileOverride: String?): RemoteMoveAnalysis {
            calls++
            return RemoteMoveAnalysis(Evaluation(22, cp = 40, pv = listOf("e2e4")),
                Evaluation(22, cp = 30, pv = listOf(playedMove)), canCompare = true, commonDepth = 22)
        }
        override fun stop() {}
    }
    @Test fun receivedBatchAnalysisUsesTheSameRatingsWithoutStartingAnotherSearch() = runBlocking {
        val analyzer = MoveAnalyzer(service)
        val analysis = service.analyzeMove(emptyList(), "d2d4", true, "lightning")
        val batch = analyzer.fromAnalysis(emptyList(), "d2d4", analysis, true, 800, "lightning")
        assertEquals(1, service.calls)
        val single = analyzer.analyze(emptyList(), "d2d4", true, 800, "lightning")
        assertEquals(single.copy(analyzedAt = batch.analyzedAt), batch)
        assertEquals(Grade.EXCELLENT, batch.grade)
        assertEquals(800, batch.scoringElo)
        assertEquals("lightning", batch.analysisProfile)
    }
    @Test fun incomparableBatchDataStillCannotBecomeConfirmedRatings() {
        val analyzer = MoveAnalyzer(service)
        val analysis = RemoteMoveAnalysis(Evaluation(22, cp = 40, pv = listOf("e2e4")),
            Evaluation(21, cp = 30, pv = listOf("d2d4")), canCompare = true, commonDepth = 22)
        val review = analyzer.fromAnalysis(emptyList(), "d2d4", analysis, true, 500, "lightning")
        assertEquals(Grade.UNSTABLE, review.grade)
        assertTrue(review.provisional)
    }
}
