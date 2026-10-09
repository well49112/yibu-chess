package cn.yibu.chess

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.core.*
import cn.yibu.chess.data.*
import cn.yibu.chess.engine.RemoteStockfishClient
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FullReviewTest {
    @Before fun reset() { GameDatabase.resetForTests() }
    @After fun close() { GameDatabase.resetForTests() }
    private fun waitFor(model: GameViewModel, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(20)
        }
        assertTrue("State did not settle: ${model.state.value.error}", condition())
    }
    @Test fun aFullDeepReviewReanalyzesBothColorsEvenWhenEveryPlyHasAValidLightningCache() = verify(false)
    @Test fun aFailureKeepsCompletedResultsAndNeverClaimsTheWholeGameWasFinished() = verify(true)
    private fun verify(fail: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE))
        val histories = CopyOnWriteArrayList<List<String>>()
        val profiles = CopyOnWriteArrayList<String>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    histories.add(payload.getValue("position").jsonObject.getValue("moves").jsonArray.map { it.jsonPrimitive.content })
                    profiles.add(payload.getValue("profile").jsonPrimitive.content)
                    if (fail && histories.size == 2) return MockResponse().setResponseCode(500).setBody("test failure")
                    val move = payload.getValue("playedMove").jsonPrimitive.content
                    val item = """{"depth":22,"score":{"type":"cp","value":20},"pv":["$move"]}"""
                    return MockResponse().setBody("""{"best":$item,"played":$item,"comparison":{"canCompare":true,"commonDepth":22},"engine":{"name":"Stockfish","version":"19"}}""")
                }
            }; start()
        }
        val model = GameViewModel(app, RemoteStockfishClient({ "token" }, server.url("/").toString()))
        val store = ViewModelStore().apply { put("full", model) }
        try {
            waitFor(model) { model.state.value.ready && !model.state.value.busy }
            val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6")
            val reviews = moves.mapIndexed { index, move ->
                val ev = Evaluation(22, cp = 20, pv = listOf(move))
                MoveReview(index + 1, move, ChessRules.san(moves.take(index), move), ev, ev, grade = Grade.BEST,
                    explanation = "old", provisional = false, algorithmVersion = 3, engineVersion = "Stockfish 19",
                    deeplySearched = true, analysisProfile = "lightning")
            }
            model.load(GameRecord(moves = moves, reviews = reviews, finished = true))
            model.saveSettings(PlaySettings(stockfishToken = "token"))
            model.reviewAll()
            waitFor(model) { !model.state.value.busy }
            assertEquals(if (fail) 2 else 4, server.requestCount)
            assertEquals((0 until histories.size).map { moves.take(it) }, histories.toList())
            assertTrue(profiles.all { it == "deep" })
            assertEquals(if (fail) 1 else 4, model.state.value.reviewDone)
            assertEquals("deep", model.state.value.game.reviews.first().analysisProfile)
            assertEquals(moves, model.state.value.game.moves)
            assertFalse(model.state.value.highlightsOpen)
            if (fail) {
                assertNotNull(model.state.value.error)
                assertTrue(model.state.value.status.contains("未完成"))
            } else {
                assertNull(model.state.value.error)
                assertTrue(model.state.value.game.reviews.all { it.analysisProfile == "deep" })
            }
        } finally { store.clear(); server.shutdown() }
    }
}
