package cn.yibu.chess.background

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import cn.yibu.chess.data.PlayPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

/** A service-owned client uses this gate with interactive searches, one request at a time. */
object CloudSearchGate { val mutex = Mutex() }

data class AutoReviewState(val running: Boolean = false, val message: String = "", val pendingGames: Int = 0,
    val pendingSteps: Int = 0, val gameId: Long? = null, val ply: Int = 0, val completed: Int = 0,
    val paused: Boolean = false, val needsToken: Boolean = false)

object AutoReview {
    private val mutable = MutableStateFlow(AutoReviewState())
    val state = mutable.asStateFlow()
    private val signals = MutableStateFlow(0L)
    val revision: Long get() = signals.value
    @Volatile var foreground = false; private set
    @Volatile var active = false; private set
    @Volatile private var starting = false
    @Volatile var interactive = false; private set
    @Volatile var importing = false; private set
    @Volatile var activeGameId: Long? = null; private set
    private val pendingWrites = java.util.concurrent.atomic.AtomicInteger()
    val saving: Boolean get() = pendingWrites.get() > 0
    fun saving(context: Context, value: Boolean) { if (value) pendingWrites.incrementAndGet() else pendingWrites.decrementAndGet(); request(context) }
    private var failedStartAt = Long.MIN_VALUE
    private fun prefs(context: Context) = context.getSharedPreferences("auto-review", Context.MODE_PRIVATE)
    fun paused(context: Context) = prefs(context).getBoolean("paused", false)
    fun blocked(context: Context, token: String) = prefs(context).getString("blocked-token", null) == tokenHash(token)
    private fun tokenHash(token: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    fun authFailed(context: Context, token: String) { prefs(context).edit().putString("blocked-token", tokenHash(token)).apply() }
    fun wake() { signals.update { it + 1 } }
    suspend fun awaitWake(ms: Long) {
        val before = revision
        withTimeoutOrNull(ms.coerceAtLeast(1)) { signals.first { it != before } }
    }
    fun publish(state: AutoReviewState) { mutable.value = state }
    fun visible(context: Context, value: Boolean) { foreground = value; if (value) request(context) else wake() }
    fun interactive(value: Boolean, gameId: Long?) {
        if (interactive != value || activeGameId != gameId) { interactive = value; activeGameId = gameId; wake() }
    }
    fun importing(context: Context, value: Boolean) { importing = value; request(context) }
    @Synchronized fun request(context: Context) {
        wake()
        if (active || starting) return
        val token = PlayPreferences(context).read().stockfishToken
        when {
            paused(context) -> publish(state.value.copy(running = false, paused = true, message = "自动分析已暂停"))
            token.isBlank() || blocked(context, token) -> publish(state.value.copy(running = false, paused = false,
                needsToken = true, message = if (token.isBlank()) "自动分析等待云端口令" else "云端口令失效，请更新后继续"))
            foreground && (failedStartAt == Long.MIN_VALUE || SystemClock.elapsedRealtime() - failedStartAt > 10_000) -> {
                starting = true
                try { context.startForegroundService(Intent(context, AutoReviewService::class.java)) }
                catch (e: RuntimeException) {
                    starting = false; failedStartAt = SystemClock.elapsedRealtime()
                    publish(state.value.copy(running = false, message = "后台分析尚未启动，回到 App 后重试"))
                }
            }
        }
    }
    fun attached() { active = true; starting = false; failedStartAt = Long.MIN_VALUE }
    fun detached() { active = false; starting = false; mutable.update { it.copy(running = false) } }
    fun pause(context: Context) {
        prefs(context).edit().putBoolean("paused", true).apply()
        publish(state.value.copy(running = false, paused = true, message = "自动分析已暂停，已完成结果已保存"))
        context.stopService(Intent(context, AutoReviewService::class.java)); wake()
    }
    fun resume(context: Context) {
        prefs(context).edit().putBoolean("paused", false).remove("blocked-token").apply()
        publish(state.value.copy(paused = false, needsToken = false, message = "继续自动分析…")); request(context)
    }
    internal fun resetForTests() {
        foreground = false; active = false; starting = false; interactive = false; importing = false
        activeGameId = null; pendingWrites.set(0); failedStartAt = Long.MIN_VALUE; mutable.value = AutoReviewState(); wake()
    }
}
