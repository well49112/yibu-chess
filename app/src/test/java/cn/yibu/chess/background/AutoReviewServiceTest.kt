package cn.yibu.chess.background

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Looper
import cn.yibu.chess.data.GameDatabase
import cn.yibu.chess.data.PlayPreferences
import cn.yibu.chess.core.PlaySettings
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoReviewServiceTest {
    @Before fun reset() { AutoReview.resetForTests(); GameDatabase.resetForTests() }
    @After fun close() { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50)); GameDatabase.resetForTests(); AutoReview.resetForTests() }
    @Test fun serviceIsForegroundWithoutNotificationPermissionAndSurvivesLeavingTheUi() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val controller = Robolectric.buildService(AutoReviewService::class.java).create()
        val service = controller.get()
        try {
            assertTrue(AutoReview.active)
            assertNotNull(Shadows.shadowOf(service).lastForegroundNotification)
            val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(AutoReviewService.CHANNEL)
            assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
            assertNull(channel.sound)
            AutoReview.visible(app, false)
            assertTrue(AutoReview.active)
            assertNotNull(Shadows.shadowOf(service).lastForegroundNotification)
        } finally { controller.destroy() }
        assertFalse(AutoReview.active)
    }
    @Test fun systemTimeoutStopsTheServiceAndReleasesTheForegroundStateWithProgressRetained() {
        val controller = Robolectric.buildService(AutoReviewService::class.java).create()
        val service = controller.get()
        try {
            AutoReview.publish(AutoReviewState(running = true, pendingGames = 3, pendingSteps = 44, completed = 7))
            service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
            assertFalse(AutoReview.state.value.running)
            assertEquals(7, AutoReview.state.value.completed)
            assertEquals(44, AutoReview.state.value.pendingSteps)
            assertTrue(AutoReview.state.value.message.contains("时限"))
        } finally { controller.destroy() }
    }
    @Test fun pauseAndAuthenticationBlockPersistAndChangingTheTokenUnblocksAutomaticStart() {
        val app = RuntimeEnvironment.getApplication()
        PlayPreferences(app).save(PlaySettings(stockfishToken = "first-token"))
        AutoReview.pause(app)
        assertTrue(AutoReview.paused(app))
        AutoReview.resume(app)
        assertFalse(AutoReview.paused(app))
        AutoReview.authFailed(app, "first-token")
        assertTrue(AutoReview.blocked(app, "first-token"))
        assertFalse(AutoReview.blocked(app, "second-token"))
        AutoReview.request(app)
        assertTrue(AutoReview.state.value.needsToken)
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }
    @Test fun retryCooldownSurvivesRecreationAndDoesNotApplyToChangedHistoryOrDeletedGames() {
        val app = RuntimeEnvironment.getApplication()
        val game = cn.yibu.chess.core.GameRecord(moves = listOf("e2e4"))
        AutoReviewRetries(app).failed(game, 1, 1000)
        assertEquals(31_000, AutoReviewRetries(app).nextAt(game, 1))
        assertEquals(0, AutoReviewRetries(app).nextAt(game.copy(moves = listOf("d2d4")), 1))
        AutoReviewRetries(app).retain(emptySet())
        assertEquals(0, AutoReviewRetries(app).nextAt(game, 1))
    }
}
