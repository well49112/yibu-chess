package cn.yibu.chess

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.core.*
import cn.yibu.chess.data.*
import cn.yibu.chess.engine.RemoteStockfishClient
import okhttp3.mockwebserver.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpeningImportViewModelTest {
    @Before fun reset() { GameDatabase.resetForTests() }
    @After fun close() { GameDatabase.resetForTests() }
    private fun waitFor(model: GameViewModel, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(20) }
        assertTrue("State did not settle: ${model.state.value.status}; ready=${model.state.value.ready}; busy=${model.state.value.busy}; ${model.state.value.error}; ${model.state.value.importError}", condition())
    }
    @Test fun coursesRememberProgressAndBothTrainingColorsKeepThePrefixWithoutRatingOrAnalysisRequests() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE))
        val server = MockWebServer().apply { start() }
        val model = GameViewModel(app, RemoteStockfishClient({ "" }, server.url("/").toString()))
        val store = ViewModelStore().apply { put("opening", model) }
        try {
            waitFor(model) { model.state.value.ready && !model.state.value.busy }
            val original = model.state.value.game
            val profile = model.state.value.profile
            model.openCourse("italian")
            model.courseSeek(16)
            model.courseQuiz(); model.courseAnswer("e1g1")
            assertTrue(OpeningPreferences(app).progress().containsAll(listOf("italian:main:learn", "italian:main:quiz")))
            assertEquals(original, model.state.value.game)
            model.courseSeek(16); model.trainOpening()
            waitFor(model) { !model.state.value.transitioning && !model.state.value.busy }
            assertTrue(model.state.value.game.humanWhite)
            assertEquals(16, model.state.value.game.openingTraining!!.startPly)
            assertFalse(model.state.value.game.rated)
            model.openCourse("caro-kann")
            model.courseSeek(15)
            val root = model.state.value.opening!!.history
            model.trainOpening()
            waitFor(model) { !model.state.value.transitioning && !model.state.value.busy }
            assertFalse(model.state.value.game.humanWhite)
            assertEquals(root, model.state.value.game.moves)
            assertEquals(15, model.state.value.game.openingTraining!!.startPly)
            model.resign()
            waitFor(model) { !model.state.value.busy && model.state.value.game.finished }
            assertNull(model.state.value.game.ratingChange)
            assertEquals(profile, model.state.value.profile)
            assertEquals(0, server.requestCount)
        } finally { store.clear(); server.shutdown() }
    }
    @Test fun freeBlackCourseCanAskMaiaForTheOpeningWhiteMoveAndThenAcceptBlackReply() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE))
        val model = GameViewModel(app)
        val store = ViewModelStore().apply { put("free", model) }
        try {
            waitFor(model) { model.state.value.ready && !model.state.value.busy }
            val before = model.state.value.game
            model.openCourse("double-pawn"); model.courseExplore(); model.courseReply()
            assertTrue(model.state.value.openingThinking)
            waitFor(model) { !model.state.value.openingThinking && model.state.value.opening!!.history.size == 1 }
            val white = model.state.value.opening!!.history
            assertTrue(white.single() in ChessRules.legal(emptyList()))
            model.courseAnswer(ChessRules.legal(white).first())
            assertEquals(2, model.state.value.opening!!.history.size)
            assertEquals(before, model.state.value.game)
            model.courseReply(); model.courseRoute(1)
            assertFalse(model.state.value.openingThinking)
            assertTrue(model.state.value.opening!!.history.isEmpty())
            assertEquals(1, model.state.value.opening!!.routeIndex)
            assertEquals(PlayerProfile(), model.state.value.profile)
            // A fresh reply must work after cancellation; settle native work before the test looper is destroyed.
            model.courseRoute(0); model.courseExplore(); model.courseReply()
            waitFor(model) { !model.state.value.openingThinking && model.state.value.opening!!.history.size == 1 }
            assertEquals(OpeningMode.FREE, model.state.value.opening!!.mode)
        } finally { store.clear() }
    }
    @Test fun rememberedUsernameSurvivesNetworkFailureAndImportDoesNotInterruptTheCurrentBoard() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE))
        val server = MockWebServer().apply { start() }
        val model = GameViewModel(app, chessCom = ChessComClient(server.url("/").toString()))
        val store = ViewModelStore().apply { put("import", model) }
        try {
            waitFor(model) { model.state.value.ready && !model.state.value.busy }
            model.page(2)
            val before = model.state.value.game
            server.enqueue(MockResponse().setResponseCode(404))
            model.importChessCom(" Alice ")
            assertEquals("alice", ImportPreferences(app).username())
            waitFor(model) { !model.state.value.importing }
            assertTrue(model.state.value.importError!!.contains("用户名"))
            assertEquals(before, model.state.value.game)
            assertEquals(PlayerProfile(), model.state.value.profile)
            assertEquals(2, model.state.value.page)
            model.clearImportStatus()
            assertNull(model.state.value.importError)
            server.enqueue(MockResponse().setBody("""{"archives":["https://api.chess.com/pub/player/alice/games/2026/10"]}"""))
            val pgn = """[White "Bob"]
[Black "Alice"]
[Result "0-1"]
[WhiteElo "520"]
[BlackElo "500"]

1.e4 e5 0-1"""
            val batch = buildJsonObject { putJsonArray("games") { add(buildJsonObject {
                put("pgn", pgn); put("url", "https://www.chess.com/game/live/57"); put("end_time", 1700000000); put("rules", "chess")
            }) } }.toString()
            server.enqueue(MockResponse().setBody(batch))
            model.importChessCom("alice")
            waitFor(model) { !model.state.value.importing && model.state.value.games.any { it.source != null } }
            assertEquals(1, model.state.value.importCount)
            val game = model.state.value.games.first { it.source != null }
            assertFalse(game.humanWhite)
            assertEquals(before, model.state.value.game)
            model.load(game)
            assertEquals("Bob", model.state.value.game.opponentEngine)
            assertEquals(500, model.state.value.game.playerEloAtStart)
            assertEquals(1, model.state.value.page)
            assertEquals(PlayerProfile(), model.state.value.profile)
        } finally { store.clear(); server.shutdown() }
    }
}
