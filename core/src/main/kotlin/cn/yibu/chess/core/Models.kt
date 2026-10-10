package cn.yibu.chess.core

import kotlinx.serialization.Serializable

@Serializable
data class Evaluation(
    val depth: Int,
    val multiPv: Int = 1,
    val cp: Int? = null,
    val mate: Int? = null,
    val win: Int = 0,
    val draw: Int = 1000,
    val loss: Int = 0,
    val pv: List<String> = emptyList(),
) {
    val expected: Double get() = when {
        mate != null -> if (mate > 0) 1.0 else 0.0
        win + draw + loss == 0 -> RatingRules.expectedPoints(this, 500)
        else -> (win + draw * 0.5) / (win + draw + loss)
    }
    fun display(whitePerspective: Boolean = false, moverWhite: Boolean = true): String {
        val sign = if (whitePerspective && !moverWhite) -1 else 1
        return mate?.let { "${if (it * sign > 0) "+" else "−"}M${kotlin.math.abs(it)}" }
            ?: String.format(java.util.Locale.ROOT, "%+.2f", (cp ?: 0) * sign / 100.0)
    }
    fun whiteScore(moverWhite: Boolean): Double {
        val value = mate?.let { if (it > 0) 10.0 else -10.0 } ?: ((cp ?: 0) / 100.0)
        return if (moverWhite) value else -value
    }
}

@Serializable
enum class Grade(val symbol: String, val chinese: String) {
    BRILLIANT("!!", "精彩"), GREAT("!", "关键好棋"), BEST("✓", "最佳"), EXCELLENT("", "优秀"),
    GOOD("", "不错"), INACCURACY("?!", "不精确"), MISTAKE("?", "失误"), BLUNDER("??", "严重失误"),
    FORCED("", "被迫走法"), UNSTABLE("…", "待复评")
}

@Serializable
data class MoveReview(
    val ply: Int,
    val uci: String,
    val san: String,
    val best: Evaluation,
    val played: Evaluation,
    val second: Evaluation? = null,
    val grade: Grade,
    val explanation: String,
    val provisional: Boolean = true,
    val engineVersion: String = "Stockfish 17.1",
    val algorithmVersion: Int = 1,
    val scoringElo: Int = 500,
    val bestExpectedPoints: Double? = null,
    val playedExpectedPoints: Double? = null,
    val brilliantReason: String? = null,
    val brilliantPlan: String? = null,
    val deeplySearched: Boolean = false,
    val analysisProfile: String = "",
    val analyzedAt: Long = 0,
) {
    val pointsLost: Double get() = ((bestExpectedPoints ?: best.expected) - (playedExpectedPoints ?: played.expected)).coerceAtLeast(0.0)
    val bestMove: String get() = best.pv.firstOrNull() ?: uci
    val moverWhite: Boolean get() = ply % 2 == 1
    fun canReuseDeep(elo: Int, expectedEngine: String = "Stockfish 17.1", requiredProfile: String? = null): Boolean = algorithmVersion == 3 && scoringElo == elo &&
        engineVersion == expectedEngine && (deeplySearched || !provisional) &&
        grade != Grade.UNSTABLE && best.depth >= 12 && best.depth == played.depth &&
        (requiredProfile == null || analysisProfile == requiredProfile ||
            requiredProfile == "lightning" && analysisProfile == "deep" ||
            analysisProfile.isEmpty() && deeplySearched)
}

@Serializable
enum class Difficulty(val chinese: String, val description: String, val skill: Int) {
    MATCHED("匹配我的 Elo", "Maia 拟人对手，随分数调整；本局结算个人 Elo", 0),
    RELAXED("轻松练习", "会出现可利用的失误，适合基础训练", 0),
    LIGHT("接近挑战", "减少失误，练习发现对手的威胁", 2),
    CHALLENGE("进阶挑战", "Stockfish 技能等级 5", 5),
    STRONG("最强", "Stockfish 全棋力，3 秒／步；不改变个人 Elo", 20);
    companion object { val choices = listOf(MATCHED, STRONG) }
}

@Serializable
data class ChessComSource(val username: String, val url: String, val white: String, val black: String,
    val whiteRating: Int? = null, val blackRating: Int? = null, val timeClass: String = "",
    val timeControl: String = "", val pgn: String = "") {
    fun opponent(humanWhite: Boolean): String = if (humanWhite) black else white
    fun playerRating(humanWhite: Boolean): Int? = if (humanWhite) whiteRating else blackRating
}

@Serializable
data class OpeningTraining(val courseId: String, val routeId: String, val startPly: Int)

@Serializable
data class GameRecord(
    val id: Long = GameIds.next(),
    val startedAt: Long = System.currentTimeMillis(),
    val humanWhite: Boolean = true,
    val difficulty: Difficulty = Difficulty.MATCHED,
    val moves: List<String> = emptyList(),
    val reviews: List<MoveReview> = emptyList(),
    val result: String = "*",
    val ending: String = "",
    val finished: Boolean = false,
    val rated: Boolean = false,
    val playerEloAtStart: Int? = null,
    val opponentElo: Int? = null,
    val ratingChange: RatingChange? = null,
    val opponentEngine: String = "Stockfish 17.1",
    val modelElo: Int? = null,
    val policySeed: Long = 0,
    val lessons: List<MoveLesson> = emptyList(),
    val source: ChessComSource? = null,
    val openingTraining: OpeningTraining? = null,
) {
    val mode: Difficulty get() = if (difficulty == Difficulty.STRONG) Difficulty.STRONG else Difficulty.MATCHED
    val opponentLabel: String get() = if (source != null) "Chess.com · ${source.opponent(humanWhite)} · Elo ${opponentElo ?: "未提供"}"
        else if (openingTraining != null) "开局陪练 · Maia · 不计 Elo"
        else if (mode == Difficulty.STRONG) "最强 · 不计 Elo"
        else "匹配对手 · Elo ${opponentElo ?: 500}${if (rated) "" else " · 不计分"}"
    fun isPlayerMove(ply: Int): Boolean = (ply % 2 == 1) == humanWhite && ply > (openingTraining?.startPly ?: 0)
}

/** Generated on request from one legal, deeply searched engine variation. */
@Serializable
data class MoveLesson(
    val ply: Int,
    val recommendedMove: String,
    val why: String,
    val plan: String,
    val variation: List<String>,
    val depth: Int,
    val algorithmVersion: Int = 1,
    val steps: List<LessonStep> = emptyList(),
    val playedExplanation: String = "",
    val playedVariation: List<String> = emptyList(),
    val playedSteps: List<LessonStep> = emptyList(),
)

@Serializable
data class LessonStep(val uci: String, val title: String, val explanation: String)

internal object ReviewIds {
    private val last = java.util.concurrent.atomic.AtomicLong()
    fun next(): Long = last.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
}

private object GameIds {
    private val last = java.util.concurrent.atomic.AtomicLong()
    fun next(): Long = last.updateAndGet { maxOf(System.currentTimeMillis(), it + 1) }
}

data class SearchRequest(
    val timeMs: Int = 500,
    val depth: Int = 0,
    val multiPv: Int = 3,
    val skill: Int = 20,
    val threads: Int = 1,
    val hashMb: Int = 64,
    val restricted: List<String> = emptyList(),
    val reuseSearch: Boolean = false,
)

data class SearchResult(val bestMove: String, val snapshots: Map<Int, List<Evaluation>>) {
    val lines: List<Evaluation> get() = snapshots.maxByOrNull { it.key }?.value.orEmpty()
    val best: Evaluation get() = lines.firstOrNull() ?: error("引擎尚未返回完整评价，请重新分析")
}

interface ChessEngine {
    suspend fun search(history: List<String>, request: SearchRequest): SearchResult
    fun stop()
}

data class RemoteEvaluation(
    val completedDepth: Int,
    val bestMove: String,
    val best: Evaluation,
    val candidates: List<Evaluation> = emptyList(),
    val engineName: String = "Stockfish 19",
)

data class RemoteMoveAnalysis(
    val best: Evaluation,
    val played: Evaluation,
    val second: Evaluation? = null,
    val previousBest: Evaluation? = null,
    val canCompare: Boolean = true,
    val commonDepth: Int = 0,
    val diffCp: Int? = null,
    val diffWdlLoss: Int? = null,
    val engineName: String = "Stockfish 19",
)

interface StockfishService {
    suspend fun evaluate(history: List<String>, profile: String = "standard", multiPv: Int = 1): RemoteEvaluation
    suspend fun analyzeMove(history: List<String>, playedMove: String, deep: Boolean, profileOverride: String? = null): RemoteMoveAnalysis
    /** Each result uses the position before its one-based ply, including the full move history. */
    suspend fun reviewGame(moves: List<String>, profile: String = "lightning", requestedPlies: Set<Int>? = null,
        onAnalysis: suspend (ply: Int, analysis: RemoteMoveAnalysis) -> Unit) {
        // Local engines and test services can keep their single-position implementation.
        for (index in moves.indices.filter { requestedPlies == null || it + 1 in requestedPlies })
            onAnalysis(index + 1, analyzeMove(moves.take(index), moves[index], true, profile))
    }
    fun stop()
}

class EngineToServiceAdapter(private val engine: ChessEngine) : StockfishService {
    override suspend fun evaluate(history: List<String>, profile: String, multiPv: Int): RemoteEvaluation {
        val timeMs = if (profile == "fast") 1500 else 3000
        val res = engine.search(history, SearchRequest(timeMs = timeMs, multiPv = multiPv, skill = 20, threads = 2, hashMb = 128))
        return RemoteEvaluation(res.best.depth, res.bestMove, res.best, res.lines, "Stockfish 17.1")
    }

    override suspend fun analyzeMove(history: List<String>, playedMove: String, deep: Boolean, profileOverride: String?): RemoteMoveAnalysis {
        val legalSize = ChessRules.legal(history).size
        val request = SearchRequest(
            timeMs = if (deep) 6000 else 1500,
            depth = if (deep) 22 else 18,
            multiPv = minOf(if (deep) 2 else 3, legalSize),
            threads = if (deep) 8 else 2,
            hashMb = if (deep) 512 else 128,
            reuseSearch = deep
        )
        var root = engine.search(history, request)
        var best = root.best
        var actual = root.lines.find { it.pv.firstOrNull() == playedMove }
        if (actual == null) {
            val forced = engine.search(history, request.copy(depth = best.depth, multiPv = 1, restricted = listOf(playedMove)))
            var commonDepth = root.snapshots.keys.intersect(forced.snapshots.keys).maxOrNull()
            if (commonDepth == null) {
                root = engine.search(history, request.copy(depth = minOf(best.depth, forced.best.depth)))
                commonDepth = root.snapshots.keys.intersect(forced.snapshots.keys).maxOrNull()
            }
            if (commonDepth != null) {
                best = root.snapshots.getValue(commonDepth).first()
                actual = forced.snapshots.getValue(commonDepth).first()
            } else actual = forced.best
        }
        val played = requireNotNull(actual)
        val second = root.snapshots[best.depth]?.getOrNull(1)
        val previous = root.snapshots.filterKeys { it < best.depth }.maxByOrNull { it.key }?.value?.firstOrNull()
        val canCompare = best.depth == played.depth
        val diffCp = if (played.cp != null && best.cp != null) played.cp - best.cp else null
        val diffWdlLoss = played.loss - best.loss
        return RemoteMoveAnalysis(best, played, second, previous, canCompare, best.depth, diffCp, diffWdlLoss, "Stockfish 17.1")
    }

    override fun stop() { engine.stop() }
}
