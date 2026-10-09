package cn.yibu.chess.background

import android.content.Context
import cn.yibu.chess.core.*
import cn.yibu.chess.data.GameRepository
import cn.yibu.chess.diagnostics.AnalysisTimings
import cn.yibu.chess.engine.RemoteHttpException
import cn.yibu.chess.engine.RemoteStockfishClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock

interface AutoReviewControl {
    val revision: Long
    fun settings(): PlaySettings
    fun online(): Boolean
    fun paused(): Boolean
    fun interactive(): Boolean
    fun importing(): Boolean
    fun activeGameId(): Long?
    fun authFailed(token: String)
    fun searching(value: Boolean)
    fun publish(state: AutoReviewState)
    suspend fun awaitWake(ms: Long)
}

/** Work derives from saved games; restart resumes from the first missing comparable review. */
class AutoReviewRunner(private val context: Context, private val repository: GameRepository,
    private val remote: RemoteStockfishClient, private val retries: AutoReviewRetries, private val control: AutoReviewControl,
    private val clock: () -> Long = System::currentTimeMillis, private val idleGraceMs: Long = 5000) {
    private val analyzer = MoveAnalyzer(remote)
    suspend fun run() {
        var revision = Long.MIN_VALUE
        val games = linkedMapOf<Long, GameRecord>()
        var completed = 0
        var emptySince: Long? = null
        while (currentCoroutineContext().isActive && !control.paused()) {
            if (revision != control.revision) {
                revision = control.revision
                games.clear(); repository.all().forEach { games[it.id] = it }
                retries.retain(games.keys)
            }
            val pending = games.values.map { it to AutoAnalysis.missing(it, remote.engineName) }.filter { it.second.isNotEmpty() }
            val base = AutoReviewState(running = true, pendingGames = pending.size, pendingSteps = pending.sumOf { it.second.size }, completed = completed)
            val settings = control.settings()
            if (settings.stockfishToken.isBlank()) { control.publish(base.copy(running = false, needsToken = true, message = "自动分析等待云端口令")); return }
            if (pending.isEmpty() && !control.importing()) {
                val start = emptySince ?: clock().also { emptySince = it }
                val remaining = idleGraceMs - (clock() - start)
                if (remaining > 0) { control.publish(base.copy(message = "等待新棋步…")); control.awaitWake(remaining); continue }
                control.publish(base.copy(running = false, message = "所有棋谱已完成自动分析")); return
            } else emptySince = null
            if (control.interactive()) { control.publish(base.copy(message = "等手动复盘或最强对手完成后继续")); control.awaitWake(2000); continue }
            if (!control.online()) { control.publish(base.copy(message = "等待网络恢复，已完成结果已保存")); control.awaitWake(300_000); continue }
            if (pending.isEmpty()) { control.publish(base.copy(message = "已有棋步已分析，等待新棋局或棋步…")); control.awaitWake(300_000); continue }
            val prioritized = pending.sortedBy { (game, _) -> if (game.id == control.activeGameId()) 0 else 1 }
            val now = clock()
            val task = prioritized.firstNotNullOfOrNull { (game, plies) ->
                val eligible = plies.filter { retries.nextAt(game, it) <= now }
                val ply = if (game.id == control.activeGameId() && !game.finished) eligible.find { it >= game.moves.size - 1 } ?: eligible.firstOrNull()
                    else eligible.firstOrNull()
                ply?.let { game to it }
            }
            if (task == null) {
                val next = pending.minOf { (game, plies) -> plies.minOf { retries.nextAt(game, it) } }
                control.publish(base.copy(message = "部分步骤等待重试，已完成结果已保存"))
                control.awaitWake((next - now).coerceIn(1000, 300_000)); continue
            }
            val (candidate, ply) = task
            var authFailed = false
            CloudSearchGate.mutex.withLock {
                if (control.paused() || control.interactive()) return@withLock
                val game = repository.find(candidate.id)
                if (game == null) { games.remove(candidate.id); return@withLock }
                games[game.id] = game
                if (ply !in 1..game.moves.size || AutoAnalysis.complete(game, ply, remote.engineName)) return@withLock
                if (control.settings().stockfishToken != settings.stockfishToken) return@withLock
                val timing = AnalysisTimings.begin(context, game.id, ply, true, AutoAnalysis.PROFILE)
                timing.put("automatic_queue", true); timing.put("scoring_elo", AutoAnalysis.scoringElo(game, ply))
                control.publish(base.copy(gameId = game.id, ply = ply, message = "自动分析 · 第 $ply/${game.moves.size} 步"))
                control.searching(true)
                try {
                    withContext(timing) {
                        val start = timing.now()
                        val review = try { analyzer.analyze(game.moves.take(ply - 1), game.moves[ply - 1], true,
                            AutoAnalysis.scoringElo(game, ply), AutoAnalysis.PROFILE) }
                        finally { timing.duration("analysis_ms", start) }
                        currentCoroutineContext().ensureActive()
                        val savedAt = timing.now()
                        val saved = try { repository.saveReview(game, review) } finally { timing.duration("database_ms", savedAt) }
                        if (saved == null) games.remove(game.id) else {
                            games[game.id] = saved
                            if (AutoAnalysis.complete(saved, ply, remote.engineName)) { retries.done(saved, ply); completed++ }
                            else retries.failed(saved, ply, clock())
                        }
                        timing.finish(if (saved == null) "discarded" else "success")
                    }
                } catch (e: CancellationException) { timing.finish("cancelled"); throw e }
                catch (e: Exception) {
                    timing.finish("failed", e.javaClass.simpleName)
                    if (e is RemoteHttpException && e.statusCode in listOf(401, 403)) {
                        control.authFailed(settings.stockfishToken); authFailed = true
                        control.publish(base.copy(running = false, needsToken = true, message = "云端口令失效，请更新后继续"))
                    } else retries.failed(game, ply, clock())
                } finally { control.searching(false) }
            }
            if (authFailed) return
        }
    }
}
