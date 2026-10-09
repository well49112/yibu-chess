package cn.yibu.chess

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.core.*
import cn.yibu.chess.data.GameDatabase
import cn.yibu.chess.data.PlayPreferences
import cn.yibu.chess.engine.RemoteStockfishClient
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerHighlightsTest {
    @Before fun reset() { GameDatabase.resetForTests() }
    @After fun close() { GameDatabase.resetForTests() }
    private fun waitFor(model: GameViewModel, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(20)
        }
        assertTrue("State did not settle: ${model.state.value.status}, ${model.state.value.error}", condition())
    }
    @Test fun whitePlayerTourRequestsOnlyWhitePliesAndReusesThemWithoutAnOpponentCache() = verify(true)
    @Test fun blackPlayerTourRequestsOnlyBlackPliesAndReusesThemWithoutAnOpponentCache() = verify(false)

    private fun verify(humanWhite: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE))
        val histories = CopyOnWriteArrayList<List<String>>()
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val payload = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                    val moves = payload.getValue("position").jsonObject.getValue("moves").jsonArray.map { it.jsonPrimitive.content }
                    histories.add(moves)
                    val move = payload.getValue("playedMove").jsonPrimitive.content
                    val item = """{"depth":22,"score":{"type":"cp","value":20},"pv":["$move"]}"""
                    return MockResponse().setBody("""{"best":$item,"played":$item,"comparison":{"canCompare":true,"commonDepth":22},"engine":{"name":"Stockfish","version":"19"}}""")
                }
            }; start()
        }
        val model = GameViewModel(app, RemoteStockfishClient({ "test-token" }, server.url("/").toString()))
        val store = ViewModelStore().apply { put("tour", model) }
        try {
            waitFor(model) { model.state.value.ready && !model.state.value.busy }
            val game = GameRecord(moves = listOf("e2e4", "e7e5", "g1f3", "b8c6"), humanWhite = humanWhite, finished = true)
            model.load(game)
            model.saveSettings(PlaySettings(stockfishToken = "test-token"))
            model.reviewHighlights()
            waitFor(model) { !model.state.value.busy && model.state.value.highlightsOpen }
            val plies = if (humanWhite) listOf(1, 3) else listOf(2, 4)
            assertEquals(plies, model.state.value.game.reviews.map { it.ply })
            assertEquals(plies.map { game.moves.take(it - 1) }, histories.toList())
            assertEquals(2, server.requestCount)
            assertTrue(model.state.value.highlights.isEmpty())
            assertEquals(game.moves, model.state.value.game.moves)
            assertNull(model.state.value.error)
            model.closeHighlights()
            model.saveSettings(PlaySettings())
            model.reviewHighlights()
            waitFor(model) { !model.state.value.busy && model.state.value.highlightsOpen }
            assertEquals(2, server.requestCount)
            assertTrue(model.state.value.highlights.isEmpty())
            assertNull(model.state.value.error)
        } finally { store.clear(); server.shutdown() }
    }
}
