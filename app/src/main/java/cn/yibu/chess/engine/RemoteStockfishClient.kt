package cn.yibu.chess.engine

import cn.yibu.chess.core.*
import cn.yibu.chess.diagnostics.AnalysisTiming
import cn.yibu.chess.diagnostics.HttpTimingListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.coroutines.resumeWithException

@Serializable
internal data class PositionDto(val initialFen: String = ChessRules.START_FEN, val moves: List<String>)

@Serializable
internal data class EvaluateReqDto(val position: PositionDto, val profile: String, val multiPv: Int)

@Serializable
internal data class AnalysisLimitsDto(val depth: Int, val maxTimeMs: Int)

@Serializable
internal data class AnalyzeMoveReqDto(val position: PositionDto, val playedMove: String, val profile: String,
    val multiPv: Int = 2, val limits: AnalysisLimitsDto? = null)

@Serializable
internal data class ReviewGameReqDto(val moves: List<String>, val initialFen: String? = null,
    val profile: String = "lightning", val concurrency: Int? = null, val requestId: String? = null)

// Exactly matches review_game's analyze_move cache key in UniChessServer.
// Null FEN, no time limit, one candidate, and ten PV plies must stay identical.
@Serializable
internal data class BatchPositionDto(val initialFen: String? = null, val moves: List<String>)

@Serializable
internal data class BatchDetailReqDto(val position: BatchPositionDto, val playedMove: String,
    val profile: String = "lightning", val multiPv: Int = 1, val maxPvPlies: Int = 10, val requestId: String)

@Serializable
internal data class GameMoveDto(val ply: Int, val move: String, val turn: String)

@Serializable
internal data class ReviewGameRespDto(val totalPlies: Int, val analyzedPlies: Int,
    val moves: List<GameMoveDto>, val elapsedMs: Long? = null, val cacheHits: Int? = null)

@Serializable
internal data class ScoreDetailDto(val type: String, val value: Int, val bound: String = "exact")

@Serializable
internal data class WdlDetailDto(val win: Int, val draw: Int, val loss: Int)

@Serializable
internal data class EvalItemDto(
    val move: String? = null,
    val depth: Int,
    val score: ScoreDetailDto? = null,
    val wdl: WdlDetailDto? = null,
    val pv: List<String> = emptyList()
) {
    fun toEvaluation(history: List<String>, rank: Int = 1): Evaluation {
        val value = requireNotNull(score) { "远端结果缺少分值，请重新分析" }
        require(value.type in setOf("cp", "mate") && depth > 0) { "远端分值或搜索深度无效" }
        val line = pv.ifEmpty { listOfNotNull(move) }
        require(line.isNotEmpty() && (move == null || move == line.first()) &&
            ChessRules.legalVariation(history, line) == line) { "远端返回了无效的推荐变化" }
        wdl?.let {
            require(listOf(it.win, it.draw, it.loss).all { n -> n in 0..1000 } &&
                it.win + it.draw + it.loss == 1000) { "远端 WDL 无效" }
        }
        return Evaluation(depth, multiPv = rank,
            cp = value.value.takeIf { value.type == "cp" },
            mate = value.value.takeIf { value.type == "mate" },
            win = wdl?.win ?: 0, draw = wdl?.draw ?: 0, loss = wdl?.loss ?: 0, pv = line)
    }
}

@Serializable
internal data class EngineInfoDto(val name: String? = null, val version: String? = null) {
    fun label(): String {
        val engine = name?.takeIf { it.isNotBlank() } ?: "Stockfish"
        val revision = version?.takeIf { it.isNotBlank() }
        return when {
            revision != null && !engine.contains(revision) -> "$engine $revision"
            engine == "Stockfish" -> "Stockfish ${revision ?: "19"}"
            else -> engine
        }
    }
}

@Serializable
internal data class ComparisonResultDto(val canCompare: Boolean, val commonDepth: Int? = null,
    val diffCp: Int? = null, val diffWdlLoss: Int? = null)

@Serializable
internal data class HealthRespDto(val status: String, val engine: EngineInfoDto? = null)

@Serializable
internal data class EvaluateRespDto(val completedDepth: Int, val best: EvalItemDto? = null,
    val candidates: List<EvalItemDto> = emptyList(), val engine: EngineInfoDto? = null)

@Serializable
internal data class AnalyzeMoveRespDto(val best: EvalItemDto? = null, val played: EvalItemDto? = null,
    val second: EvalItemDto? = null, val previousBest: EvalItemDto? = null,
    val comparison: ComparisonResultDto, val engine: EngineInfoDto? = null, val stats: JsonElement? = null)

class RemoteHttpException(val statusCode: Int, message: String) : IOException(message)

class RemoteStockfishClient(
    private val tokenProvider: () -> String,
    private val baseUrl: String = "https://chess.jeefy.top",
    client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
) : StockfishService {
    companion object { const val BATCH_TIMEOUT_SECONDS = 900L }
    private val client = client.newBuilder().eventListenerFactory { call ->
        HttpTimingListener(call.request().tag(AnalysisTiming::class.java))
    }.build()
    private val batchClient = this.client.newBuilder().readTimeout(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(BATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    @Volatile var engineName: String = "Stockfish 19"
        private set

    suspend fun checkHealth(customToken: String? = null): Result<String> = try {
        val request = request("health", customToken).get().build()
        val resp = json.decodeFromString<HealthRespDto>(executeRequest(request))
        check(resp.status.lowercase() in setOf("ready", "ok", "healthy")) { "远端引擎尚未就绪" }
        Result.success(resp.engine?.label() ?: engineName)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { Result.failure(e) }

    override suspend fun evaluate(history: List<String>, profile: String, multiPv: Int): RemoteEvaluation {
        val dto = EvaluateReqDto(PositionDto(moves = history), profile, multiPv)
        val request = request("evaluate").post(json.encodeToString(dto).toRequestBody(jsonMediaType)).build()
        val resp = json.decodeFromString<EvaluateRespDto>(executeRequest(request))
        val bestDto = requireNotNull(resp.best) { "远端服务尚未返回最佳走法，请重试" }
        check(bestDto.score?.bound == "exact") { "远端最佳走法尚未完成搜索，请重试" }
        val best = bestDto.toEvaluation(history)
        val candidates = resp.candidates.mapIndexed { i, line -> line.toEvaluation(history, i + 1) }
        check(best.depth == resp.completedDepth && candidates.all { it.depth == best.depth }) {
            "远端候选没有完成同一搜索深度，请重试"
        }
        engineName = resp.engine?.label() ?: engineName
        return RemoteEvaluation(best.depth, best.pv.first(), best, candidates, engineName)
    }

    override suspend fun analyzeMove(history: List<String>, playedMove: String, deep: Boolean, profileOverride: String?): RemoteMoveAnalysis {
        val timing = currentCoroutineContext()[AnalysisTiming]
        val preparation = timing?.now()
        require(playedMove in ChessRules.legal(history)) { "待分析走法非法" }
        val profile = profileOverride ?: if (deep) "deep" else "fast"
        require(profile in setOf("fast", "deep", "lightning")) { "分析模式无效" }
        // Preserve the existing bounded single-step budget; batch cache reads use profile defaults.
        val limits = if (profile == "lightning") AnalysisLimitsDto(depth = 22, maxTimeMs = 500) else null
        val dto = AnalyzeMoveReqDto(PositionDto(moves = history), playedMove, profile, limits = limits)
        timing?.put("profile", profile)
        limits?.let { timing?.put("target_depth", it.depth); timing?.put("search_budget_ms", it.maxTimeMs) }
        val builder = request("analyze-move").post(json.encodeToString(dto).toRequestBody(jsonMediaType))
        if (timing != null) builder.header("X-Request-ID", timing.id).tag(AnalysisTiming::class.java, timing)
        val request = builder.build()
        preparation?.let { timing?.duration("request_preparation_ms", it) }
        val content = executeRequest(request)
        val processing = timing?.now()
        try {
            val resp = json.decodeFromString<AnalyzeMoveRespDto>(content)
            timing?.serverStats(resp.stats)
            return decodeAnalysis(resp, history, playedMove, timing)
        } finally { processing?.let { timing?.duration("response_processing_ms", it) } }
    }

    override suspend fun reviewGame(moves: List<String>, profile: String, requestedPlies: Set<Int>?,
        onAnalysis: suspend (Int, RemoteMoveAnalysis) -> Unit) {
        require(profile == "lightning") { "整盘分析使用 lightning 模式" }
        require(moves.isNotEmpty() && ChessRules.legalVariation(emptyList(), moves) == moves) { "待分析棋谱非法" }
        val targets = (requestedPlies ?: (1..moves.size).toSet()).sorted()
        require(targets.all { it in 1..moves.size }) { "待分析步数无效" }
        if (targets.isEmpty()) return
        val timing = currentCoroutineContext()[AnalysisTiming]
        val requestId = timing?.id ?: UUID.randomUUID().toString()
        val builder = request("review").post(json.encodeToString(ReviewGameReqDto(moves, requestId = requestId)).toRequestBody(jsonMediaType))
        timing?.let { builder.header("X-Request-ID", it.id).tag(AnalysisTiming::class.java, it) }
        val req = builder.build()
        // Capture one token for this operation, even if settings change while it is running.
        val token = req.header("X-Access-Token")!!
        timing?.put("target_depth", 22); timing?.put("search_budget_ms", null)
        timing?.put("multi_pv", 1); timing?.put("detail_concurrency", 4)
        val started = timing?.now()
        val response = try { json.decodeFromString<ReviewGameRespDto>(executeRequest(req, batchClient)) }
            finally { started?.let { timing?.duration("batch_http_ms", it) } }
        timing?.put("batch_server_elapsed_ms", response.elapsedMs)
        timing?.put("batch_cache_hits", response.cacheHits)
        require(response.totalPlies == moves.size && response.analyzedPlies in 0..moves.size &&
            response.moves.map { it.ply }.distinct().size == response.moves.size &&
            response.moves.all { it.ply in 1..moves.size && it.move == moves[it.ply - 1] &&
                it.turn == if (it.ply % 2 == 1) "white" else "black" }) { "远端批量结果与棋谱不一致" }
        val present = response.moves.map { it.ply }.toSet()
        var failure: Exception? = if (targets.any { it !in present })
            IOException("服务器未返回全部棋步，已完成的结果将保存") else null
        // The public batch response contains summaries only. Fetch its cached full
        // comparisons in bounded parallel requests; never invent played scores or PVs.
        coroutineScope {
            val semaphore = Semaphore(4)
            val details = targets.filter { it in present }.map { ply -> async {
                semaphore.withPermit {
                    try {
                        val dto = BatchDetailReqDto(BatchPositionDto(moves = moves.take(ply - 1)), moves[ply - 1], requestId = "$requestId-$ply")
                        val detailRequest = request("analyze-move", token)
                            .post(json.encodeToString(dto).toRequestBody(jsonMediaType)).build()
                        val resp = json.decodeFromString<AnalyzeMoveRespDto>(executeRequest(detailRequest, batchClient))
                        Result.success(decodeAnalysis(resp, moves.take(ply - 1), moves[ply - 1], null))
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Result.failure<RemoteMoveAnalysis>(e) }
                }
            } }
            targets.filter { it in present }.zip(details).forEach { (ply, result) ->
                currentCoroutineContext().ensureActive()
                val value = result.await()
                value.getOrNull()?.let { onAnalysis(ply, it) }
                val error = value.exceptionOrNull() as? Exception
                if (error is RemoteHttpException && error.statusCode in listOf(401, 403)) throw error
                if (error != null && failure == null) failure = error
            }
        }
        failure?.let { throw it }
    }

    private fun decodeAnalysis(resp: AnalyzeMoveRespDto, history: List<String>, playedMove: String,
        timing: AnalysisTiming?): RemoteMoveAnalysis {
        val bestDto = requireNotNull(resp.best) { "最佳走法尚未分析完成，请重试" }
        val playedDto = requireNotNull(resp.played) { "实战走法尚未分析完成，请重试" }
        val best = bestDto.toEvaluation(history)
        val played = playedDto.toEvaluation(history)
        require(played.pv.first() == playedMove) { "远端分析结果与实战走法不一致" }
        val second = resp.second?.takeIf { it.score?.bound == "exact" && it.depth == best.depth }
            ?.toEvaluation(history, 2)
        val previous = resp.previousBest?.takeIf { it.score?.bound == "exact" && it.depth < best.depth }
            ?.toEvaluation(history)
        val comp = resp.comparison
        engineName = resp.engine?.label() ?: engineName
        timing?.put("best_depth", best.depth)
        timing?.put("played_depth", played.depth)
        return RemoteMoveAnalysis(best, played, second, previous,
            canCompare = comp.canCompare && bestDto.score?.bound == "exact" && playedDto.score?.bound == "exact",
            commonDepth = comp.commonDepth ?: 0, diffCp = comp.diffCp, diffWdlLoss = comp.diffWdlLoss,
            engineName = engineName)
    }

    override fun stop() { client.dispatcher.cancelAll() }

    private fun request(path: String, customToken: String? = null): Request.Builder {
        val token = (customToken ?: tokenProvider()).trim()
        check(token.isNotEmpty()) { "请在对局设置中填写朋友提供的 Access Token" }
        return Request.Builder().url("${baseUrl.trimEnd('/')}/sf/v1/$path").header("X-Access-Token", token)
    }

    private fun httpError(request: Request, response: Response, content: String): IOException {
        val message = when (response.code) {
            401, 403 -> "访问口令无效或已过期，请在对局设置中更新"
            429 -> "服务器正在忙，请稍后重试"
            503 -> "远端引擎暂不可用，请稍后重试"
            504 -> "远端计算超时，请重试"
            else -> runCatching {
                val obj = json.parseToJsonElement(content).jsonObject
                (obj["detail"] as? JsonPrimitive)?.contentOrNull
                    ?: (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
            }.getOrNull()?.take(300) ?: "远端请求失败（HTTP ${response.code}）"
        }
        val token = request.header("X-Access-Token").orEmpty()
        return RemoteHttpException(response.code, if (token.isNotEmpty()) message.replace(token, "[已隐藏]") else message)
    }

    private suspend fun executeRequest(request: Request, transport: OkHttpClient = client): String {
        val timing = request.tag(AnalysisTiming::class.java)
        val started = timing?.now()
        return try { suspendCancellableCoroutine { cont ->
            val call = transport.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching {
                        response.use {
                            timing?.put("http_status", it.code)
                            timing?.serverTiming(it.header("Server-Timing"))
                            val content = it.body?.string().orEmpty()
                            if (!it.isSuccessful) throw httpError(request, it, content)
                            content
                        }
                    }
                    if (cont.isActive) cont.resumeWith(result)
                }
            })
        } } finally { started?.let { timing?.duration("http_ms", it) } }
    }
}
