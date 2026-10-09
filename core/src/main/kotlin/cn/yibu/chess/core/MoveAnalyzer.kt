package cn.yibu.chess.core

import kotlin.math.abs
import kotlin.math.exp

object RatingRules {
    fun ordinary(loss: Double, actualBest: Boolean): Grade = when {
        actualBest -> Grade.BEST
        loss >= 0.20 - 1e-9 -> Grade.BLUNDER
        loss >= 0.10 - 1e-9 -> Grade.MISTAKE
        loss >= 0.05 - 1e-9 -> Grade.INACCURACY
        loss >= 0.02 - 1e-9 -> Grade.GOOD
        else -> Grade.EXCELLENT
    }
    // The public classification cutoffs are used with our own human-play curve.
    // Chess.com's rating-dependent curve is not published; engine WDL is not that model.
    fun expectedPoints(evaluation: Evaluation, playerElo: Int): Double {
        evaluation.mate?.let { return if (it > 0) 1.0 else 0.0 }
        val scale = (330.0 - 0.07 * (playerElo.coerceIn(100, 2800) - 500)).coerceIn(170.0, 360.0)
        return 1.0 / (1.0 + exp(-(evaluation.cp ?: 0).coerceIn(-6000, 6000) / scale))
    }
}

class MoveAnalyzer(private val service: StockfishService) {
    constructor(engine: ChessEngine) : this(EngineToServiceAdapter(engine))

    suspend fun analyze(history: List<String>, uci: String, deep: Boolean, playerElo: Int = 500, profileOverride: String? = null): MoveReview {
        val legal = ChessRules.legal(history)
        require(uci in legal)
        val analysis = service.analyzeMove(history, uci, deep, profileOverride)
        var best = analysis.best
        var played = analysis.played
        // Terminal game outcomes override statistical WDL, including mandatory draws.
        val outcome = ChessRules.outcome(history + uci)
        outcome?.let { (result, _) ->
            played = when (result) {
                "1/2-1/2" -> played.copy(cp = 0, mate = null, win = 0, draw = 1000, loss = 0)
                else -> played.copy(mate = 1, win = 1000, draw = 0, loss = 0)
            }
            if (best.pv.firstOrNull() == uci) best = played
        }
        val sameDepth = best.depth == played.depth
        val bestPoints = RatingRules.expectedPoints(best, playerElo)
        val playedPoints = if (outcome?.first == "1/2-1/2") 0.5 else RatingRules.expectedPoints(played, playerElo)
        val negative = playedPoints - bestPoints > 0.025
        val loss = (bestPoints - playedPoints).coerceAtLeast(0.0)
        val second = analysis.second
        val nearThreshold = listOf(0.02, 0.05, 0.10, 0.20).any { abs(loss - it) < 0.006 }
        val stable = analysis.canCompare && sameDepth && analysis.commonDepth == best.depth && !negative
        val tied = best.mate == played.mate && best.cp == played.cp && abs(bestPoints - playedPoints) < 1e-9
        var grade = RatingRules.ordinary(loss, best.pv.firstOrNull() == uci || tied)
        if (!stable) grade = Grade.UNSTABLE
        else if (legal.size == 1) grade = Grade.FORCED
        else if (loss < 0.02 && deep && best.depth >= 14) {
            val previous = analysis.previousBest
            val stableBest = previous?.pv?.firstOrNull() == best.pv.firstOrNull() && previous != null && abs(RatingRules.expectedPoints(previous, playerElo) - bestPoints) < 0.025
            if (stableBest && playedPoints >= 0.50 && second != null && RatingRules.expectedPoints(second, playerElo) < 0.85 && ChessRules.substantialSacrifice(history, played.pv)) grade = Grade.BRILLIANT
            else if (stableBest && second != null && bestPoints - RatingRules.expectedPoints(second, playerElo) >= 0.10 && best.pv.firstOrNull() == uci && (best.mate == null || best.mate > 1)) grade = Grade.GREAT
        }
        val bestSan = ChessRules.san(history, best.pv.firstOrNull() ?: uci)
        val brilliant = if (grade == Grade.BRILLIANT) ChessRules.brilliantNote(history, played) else null
        val explanation = when (grade) {
            Grade.UNSTABLE -> "两次搜索尚未得到一致评价，建议深度复评后再判断。"
            Grade.FORCED -> "此处只有一着合法走法。"
            Grade.BRILLIANT -> requireNotNull(brilliant).reason
            Grade.GREAT -> "这是关键好棋：其他候选会明显降低局面质量。"
            Grade.BEST -> "找到了引擎当前认为的最佳走法。"
            Grade.EXCELLENT, Grade.GOOD -> "这步保持了局面质量；推荐 $bestSan，可比较两条变化。"
            else -> ChessRules.replyExplanation(history, played) ?: "推荐 $bestSan，能更好地保持局面质量。点击两条变化比较。"
        } + if (best.mate != null && best.mate > 0 && played.mate == null) " 这步错过了引擎发现的强制将杀。" else ""
        return MoveReview(history.size + 1, uci, ChessRules.san(history, uci), best, played, second,
            grade, explanation, provisional = !deep || !stable || nearThreshold || best.depth < 12,
            engineVersion = analysis.engineName,
            algorithmVersion = 3, scoringElo = playerElo, bestExpectedPoints = bestPoints, playedExpectedPoints = playedPoints,
            brilliantReason = brilliant?.reason, brilliantPlan = brilliant?.plan, deeplySearched = deep,
            analysisProfile = profileOverride ?: if (deep) "deep" else "fast", analyzedAt = ReviewIds.next())
    }
}
