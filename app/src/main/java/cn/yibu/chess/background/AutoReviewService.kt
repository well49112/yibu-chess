package cn.yibu.chess.background

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.*
import cn.yibu.chess.MainActivity
import cn.yibu.chess.R
import cn.yibu.chess.data.GameRepository
import cn.yibu.chess.data.PlayPreferences
import cn.yibu.chess.engine.RemoteStockfishClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged

class AutoReviewService : Service() {
    companion object { const val CHANNEL = "automatic-review"; const val NOTIFICATION = 1001; const val PAUSE = "cn.yibu.chess.PAUSE_AUTO_REVIEW" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private var failedStart = false
    private var shuttingDown = false
    private lateinit var remote: RemoteStockfishClient
    private lateinit var preferences: PlayPreferences
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var connectivity: ConnectivityManager
    private var lastNotificationAt = 0L
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { AutoReview.wake() }
        override fun onLost(network: Network) { AutoReview.wake() }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { AutoReview.wake() }
    }
    override fun onCreate() {
        super.onCreate()
        preferences = PlayPreferences(this)
        remote = RemoteStockfishClient({ preferences.read().stockfishToken })
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "yibu:auto-review").apply { setReferenceCounted(false) }
        connectivity = getSystemService(ConnectivityManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "自动复盘进度", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "后台整理棋谱时显示进度"; setSound(null, null) })
        try {
            val notification = notification(AutoReviewState(running = true, message = "正在检查未完成分析的棋谱…"))
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION, notification)
            AutoReview.attached()
        } catch (e: RuntimeException) {
            failedStart = true
            AutoReview.publish(AutoReviewState(message = "系统暂未允许后台分析，打开 App 后继续"))
            stopSelf(); return
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        scope.launch {
            GameRepository(this@AutoReviewService).ids.distinctUntilChanged().collect { AutoReview.wake() }
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (failedStart) return START_NOT_STICKY
        if (intent?.action == PAUSE) { AutoReview.pause(this); shutdown(); return START_NOT_STICKY }
        if (AutoReview.paused(this) || AutoReview.blocked(this, preferences.read().stockfishToken)) { shutdown(); return START_NOT_STICKY }
        if (worker?.isActive != true) worker = scope.launch {
            try {
                val control = object : AutoReviewControl {
                    override val revision get() = AutoReview.revision
                    override fun settings() = preferences.read()
                    override fun online(): Boolean = connectivity.activeNetwork?.let { connectivity.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
                    override fun paused() = AutoReview.paused(this@AutoReviewService)
                    override fun interactive() = AutoReview.interactive
                    override fun importing() = AutoReview.importing || AutoReview.saving || AutoReview.foreground
                    override fun activeGameId() = AutoReview.activeGameId
                    override fun authFailed(token: String) = AutoReview.authFailed(this@AutoReviewService, token)
                    override fun searching(value: Boolean) {
                        if (value) wakeLock.acquire(60_000) else if (wakeLock.isHeld) wakeLock.release()
                    }
                    override fun publish(state: AutoReviewState) {
                        AutoReview.publish(state)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastNotificationAt >= 1000 || !state.running) {
                            lastNotificationAt = now
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state))
                        }
                    }
                    override suspend fun awaitWake(ms: Long) = AutoReview.awaitWake(ms)
                }
                AutoReviewRunner(this@AutoReviewService, GameRepository(this@AutoReviewService), remote,
                    AutoReviewRetries(this@AutoReviewService), control).run()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { AutoReview.publish(AutoReview.state.value.copy(running = false, message = "自动分析已保存，打开 App 后重试")) }
            finally { withContext(NonCancellable + Dispatchers.Main.immediate) { shutdown() } }
        }
        return START_STICKY
    }
    private fun notification(state: AutoReviewState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, AutoReviewService::class.java).setAction(PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("弈步 · 自动复盘").setContentText(state.message)
            .setStyle(Notification.BigTextStyle().bigText("${state.message}\n剩余 ${state.pendingGames} 盘 · ${state.pendingSteps} 步，本次已补齐 ${state.completed} 步"))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "暂停", pause).build()).build()
    }
    private fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        if (wakeLock.isHeld) wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        AutoReview.publish(AutoReview.state.value.copy(running = false, message = "达到系统后台时限，已保存进度；打开 App 后继续"))
        scope.cancel(); remote.stop(); shutdown()
    }
    override fun onDestroy() {
        scope.cancel(); remote.stop()
        if (wakeLock.isHeld) wakeLock.release()
        if (!failedStart) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        AutoReview.detached(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
