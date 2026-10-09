package cn.yibu.chess.background

import android.content.Context
import cn.yibu.chess.core.AutoAnalysis
import cn.yibu.chess.core.GameRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class DeferredStep(val identity: String, val attempts: Int, val nextAt: Long)

/** Persist only deferred work. Completed work is already in Room. */
class AutoReviewRetries(context: Context) {
    private val prefs = context.getSharedPreferences("auto-review-retries", Context.MODE_PRIVATE)
    private fun key(game: GameRecord, ply: Int) = "${game.id}:$ply"
    private fun identity(game: GameRecord, ply: Int) = "${AutoAnalysis.scoringElo(game, ply)}:" + game.moves.take(ply).joinToString(" ")
    private fun read(game: GameRecord, ply: Int): DeferredStep? = runCatching {
        prefs.getString(key(game, ply), null)?.let { Json.decodeFromString<DeferredStep>(it) }
    }.getOrNull()?.takeIf { it.identity == identity(game, ply) }
    fun nextAt(game: GameRecord, ply: Int): Long = read(game, ply)?.nextAt ?: 0
    fun failed(game: GameRecord, ply: Int, now: Long) {
        val attempt = ((read(game, ply)?.attempts ?: 0) + 1).coerceAtMost(5)
        prefs.edit().putString(key(game, ply), Json.encodeToString(DeferredStep(identity(game, ply), attempt,
            now + AutoAnalysis.retryDelay(attempt)))).apply()
    }
    fun done(game: GameRecord, ply: Int) { prefs.edit().remove(key(game, ply)).apply() }
    fun clear() { prefs.edit().clear().apply() }
    fun retain(ids: Set<Long>) {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.substringBefore(':').toLongOrNull() !in ids }.forEach(edit::remove)
        edit.apply()
    }
}
