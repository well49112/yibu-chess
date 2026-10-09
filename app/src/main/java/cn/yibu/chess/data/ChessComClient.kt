package cn.yibu.chess.data

import cn.yibu.chess.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ChessComBatch(val games: List<GameRecord>, val skipped: Int, val notes: List<String>, val month: String)

/** Sequential public archive requests; no Chess.com password or Stockfish token is sent. */
class ChessComClient(baseUrl: String = "https://api.chess.com/", private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()) {
    private val base = baseUrl.toHttpUrl()
    private suspend fun get(path: String): JsonObject {
        val request = Request.Builder().url(base.newBuilder().addPathSegments(path).build())
            .header("User-Agent", "YibuChess/1.0.0 (Android personal chess training)").header("Accept", "application/json").build()
        val content = suspendCancellableCoroutine<String> { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use {
                            check(it.isSuccessful) { when (it.code) {
                                404 -> "找不到该用户名或公开棋谱，请检查 Chess.com 用户名"
                                429 -> "Chess.com 请求较多，请稍后再导入"
                                else -> "Chess.com 暂时无法访问（HTTP ${it.code}），已导入棋局会保留"
                            } }
                            requireNotNull(it.body).string()
                        }
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
                }
            })
        }
        return withContext(Dispatchers.Default) { Json.parseToJsonElement(content).jsonObject }
    }

    /** null limit means all archived games; each month is saved before requesting the next. */
    suspend fun import(username: String, limit: Int?, onStatus: (String) -> Unit, onBatch: suspend (ChessComBatch) -> Unit) {
        val owner = ChessComImport.username(username)
        require(limit == null || limit > 0)
        onStatus("正在读取 $owner 的公开棋谱目录…")
        val archive = get("pub/player/$owner/games/archives")["archives"]?.jsonArray ?: error("Chess.com 未返回棋谱目录")
        val months = archive.mapNotNull { element ->
            val url = runCatching { element.jsonPrimitive.content.toHttpUrl() }.getOrNull() ?: return@mapNotNull null
            val match = Regex("^/pub/player/([^/]+)/games/(\\d{4})/(\\d{2})$").matchEntire(url.encodedPath) ?: return@mapNotNull null
            if (!match.groupValues[1].equals(owner, true) || url.host != "api.chess.com" || url.scheme != "https") return@mapNotNull null
            "${match.groupValues[2]}/${match.groupValues[3]}".takeIf { match.groupValues[3].toInt() in 1..12 }
        }.distinct().sortedDescending()
        if (archive.isNotEmpty() && months.isEmpty()) error("Chess.com 棋谱目录格式无效")
        var found = 0
        for ((index, month) in months.withIndex()) {
            currentCoroutineContext().ensureActive()
            onStatus("读取 $month · ${index + 1}/${months.size} 个月…")
            val objects = get("pub/player/$owner/games/$month")["games"]?.jsonArray ?: error("该月棋谱数据不完整，可稍后重试")
            val batch = withContext(Dispatchers.Default) {
                var skipped = 0
                val notes = mutableListOf<String>()
                val games = mutableListOf<GameRecord>()
                for (element in objects.sortedByDescending { it.jsonObject["end_time"]?.jsonPrimitive?.longOrNull ?: 0 }) {
                    ensureActive()
                    if (limit != null && found + games.size >= limit) break
                    val item = element.jsonObject
                    if (item["rules"]?.jsonPrimitive?.content != "chess") { skipped++; continue }
                    try {
                        val white = item["white"]?.jsonObject
                        val black = item["black"]?.jsonObject
                        games += ChessComImport.parse(item.getValue("pgn").jsonPrimitive.content, owner,
                            item.getValue("url").jsonPrimitive.content, item.getValue("end_time").jsonPrimitive.long,
                            white?.get("rating")?.jsonPrimitive?.intOrNull, black?.get("rating")?.jsonPrimitive?.intOrNull,
                            item["time_class"]?.jsonPrimitive?.content.orEmpty(), item["time_control"]?.jsonPrimitive?.content.orEmpty())
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        skipped++
                        if (notes.size < 3) notes += e.message?.take(120) ?: "棋谱无法解析"
                    }
                }
                ChessComBatch(games, skipped, notes, month)
            }
            onBatch(batch)
            found += batch.games.size
            if (limit != null && found >= limit) break
        }
    }
}
