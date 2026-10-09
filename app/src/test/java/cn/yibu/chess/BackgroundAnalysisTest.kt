package cn.yibu.chess

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import cn.yibu.chess.background.*
import cn.yibu.chess.core.*
import cn.yibu.chess.data.*
import cn.yibu.chess.engine.RemoteStockfishClient
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundAnalysisTest {
    @Before fun reset() { GameDatabase.resetForTests(); AutoReview.resetForTests() }
    @After fun close() { GameDatabase.resetForTests(); AutoReview.resetForTests() }
    private fun until(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition() && System.nanoTime() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(20) }
        assertTrue("State did not settle", condition())
    }
    @Test fun independentQueueSurvivesMoreHumanAndMaiaMovesUiBackgroundAndViewModelDestruction() {
        val app = RuntimeEnvironment.getApplication()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val server = MockWebServer().apply { dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (calls.incrementAndGet() == 1) { entered.countDown(); release.await(15, TimeUnit.SECONDS) }
                val payload = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
                val move = payload["playedMove"]!!.jsonPrimitive.content
                val ev = """{"depth":22,"score":{"type":"cp","value":20,"bound":"exact"},"pv":["$move"]}"""
                return MockResponse().setBody("""{"best":$ev,"played":$ev,"comparison":{"canCompare":true,"commonDepth":22},"engine":{"name":"Stockfish","version":"19"}}""")
            }
        }; start() }
        val vmClient = RemoteStockfishClient({ "token" }, server.url("/").toString())
        val queueClient = RemoteStockfishClient({ "token" }, server.url("/").toString())
        val model = GameViewModel(app, vmClient)
        val store = ViewModelStore().apply { put("background", model) }
        val repo = GameRepository(app)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var job: Job? = null
        try {
            until { model.state.value.ready && !model.state.value.busy }
            val game = GameRecord(moves = listOf("e2e4", "e7e5"), playerEloAtStart = 500, opponentElo = 500)
            runBlocking { repo.save(game) }
            model.load(game); model.saveSettings(PlaySettings(color = ColorPreference.WHITE, stockfishToken = "token")); model.page(0)
            val control = object : AutoReviewControl {
                override val revision get() = AutoReview.revision
                override fun settings() = PlayPreferences(app).read()
                override fun online() = true
                override fun paused() = false
                override fun interactive() = AutoReview.interactive
                override fun importing() = false
                override fun activeGameId() = game.id
                override fun authFailed(token: String) {}
                override fun searching(value: Boolean) {}
                override fun publish(state: AutoReviewState) = AutoReview.publish(state)
                override suspend fun awaitWake(ms: Long) = AutoReview.awaitWake(ms)
            }
            job = scope.launch { AutoReviewRunner(app, repo, queueClient, AutoReviewRetries(app), control, idleGraceMs = 0).run() }
            until { entered.count == 0L && model.state.value.analyzing }
            model.play("g1f3")
            until { !model.state.value.busy && model.state.value.game.moves.size == 4 }
            val moves = model.state.value.game.moves
            model.pauseForBackground() // Cancels the UI client's work, not the queue client's request.
            release.countDown()
            until { runBlocking { repo.find(game.id)!!.reviews.size } == 4 }
            until { model.state.value.game.reviews.size == 4 }
            assertEquals(moves, model.state.value.game.moves)
            assertEquals(4, calls.get())
            // New historical work still finishes after the screen's owner is destroyed.
            val imported = game.copy(id = game.id + 20, finished = true)
            runBlocking { repo.save(imported) }
            AutoReview.wake()
            job = scope.launch { AutoReviewRunner(app, repo, queueClient, AutoReviewRetries(app), control, idleGraceMs = 0).run() }
            store.clear()
            until { runBlocking { repo.find(imported.id)!!.reviews.size } == 2 }
            assertEquals(6, calls.get())
            assertTrue(runBlocking { repo.find(game.id)!!.reviews.all { it.analysisProfile == "lightning" } })
        } finally {
            release.countDown(); store.clear(); runBlocking { job?.cancelAndJoin() }; scope.cancel(); queueClient.stop(); server.shutdown()
        }
    }
}
