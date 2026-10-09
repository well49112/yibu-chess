package cn.yibu.chess

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.core.*
import cn.yibu.chess.data.PlayPreferences
import cn.yibu.chess.engine.RemoteStockfishClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LessonCacheTest {
    @Before fun resetDatabase() { cn.yibu.chess.data.GameDatabase.resetForTests() }
    @After fun tearDown() { cn.yibu.chess.data.GameDatabase.resetForTests() }
    private fun waitFor(model: GameViewModel, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            Thread.sleep(20)
        }
        assertTrue("State did not settle: ${model.state.value.status}, ${model.state.value.error}", condition())
    }

    @Test fun openingAnOldLessonRegeneratesDetailedTextWithoutTokenOrAnotherCloudRequest() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE))
        val server = MockWebServer().apply { start() }
        val client = RemoteStockfishClient({ "" }, server.url("/").toString())
        val model = GameViewModel(app, client)
        val store = ViewModelStore().apply { put("lesson", model) }
        try {
            waitFor(model) { model.state.value.ready && !model.state.value.busy }
            val root = listOf("e2e4", "e7e5", "d1h5", "b8c6")
            val review = MoveReview(5, "h5e5", "Qxe5+", Evaluation(22, cp = 30, pv = listOf("h5f3", "g8f6")),
                Evaluation(22, cp = -800, pv = listOf("h5e5", "c6e5")), grade = Grade.BLUNDER, explanation = "",
                provisional = false, algorithmVersion = 3, engineVersion = "Stockfish 19", deeplySearched = true,
                bestExpectedPoints = .7, playedExpectedPoints = .1)
            val oldLesson = MoveLesson(5, "h5f3", "旧原因", "旧思路", listOf("h5f3"), 22, algorithmVersion = 2)
            val game = GameRecord(moves = root + review.uci, reviews = listOf(review), lessons = listOf(oldLesson), finished = true)
            model.load(game)
            model.explainSelected()
            waitFor(model) { !model.state.value.busy && model.state.value.chosenLesson?.algorithmVersion == MoveCoach.ALGORITHM_VERSION }
            assertTrue(model.state.value.chosenLesson!!.why.contains("没有合法的立即回吃"))
            assertEquals(listOf("h5f3", "g8f6"), model.state.value.variation)
            assertEquals(listOf("h5e5", "c6e5"), model.state.value.chosenLesson!!.playedVariation)
            model.lessonRoute(true)
            assertEquals(root, model.state.value.boardHistory)
            model.lessonSeek(1)
            assertEquals(root + "h5e5", model.state.value.boardHistory)
            model.lessonSeek(2)
            assertEquals(root + listOf("h5e5", "c6e5"), model.state.value.boardHistory)
            model.lessonRoute(false)
            assertEquals(root, model.state.value.boardHistory)
            assertFalse(model.state.value.lessonPlayed)
            assertEquals(listOf("h5f3", "g8f6"), model.state.value.variation)
            assertEquals(0, model.state.value.variationStep)
            assertTrue(model.state.value.lessonOpen)
            assertEquals(game.moves, model.state.value.game.moves)
            assertEquals(game.reviews, model.state.value.game.reviews)
            assertNull(model.state.value.error)
            assertEquals(0, server.requestCount)
            model.closeLesson()
            val savedLesson = model.state.value.chosenLesson
            model.explainSelected()
            assertEquals(savedLesson, model.state.value.chosenLesson)
            assertTrue(model.state.value.lessonOpen)
            assertFalse(model.state.value.busy)
            assertEquals(0, server.requestCount)
            waitFor(model) { model.state.value.weaknesses.groups.singleOrNull()?.count == 1 }
            assertEquals(WeaknessType.HANGING_PIECE, model.state.value.weaknesses.groups.single().type)
            model.page(2)
            model.openWeakness(game.id, 5)
            waitFor(model) { !model.state.value.busy && model.state.value.lessonOpen }
            assertEquals(5, model.state.value.cursor)
            assertEquals(root, model.state.value.boardHistory)
            assertEquals(0, server.requestCount)
            model.closeLesson()
            model.openWeakness(game.id, 5)
            model.closeLesson()
            waitFor(model) { !model.state.value.busy }
            assertFalse(model.state.value.lessonOpen)
            assertTrue(model.state.value.variation.isEmpty())
            model.delete(model.state.value.game)
            waitFor(model) { !model.state.value.transitioning && model.state.value.games.none { it.id == game.id } && model.state.value.weaknesses.groups.isEmpty() }
            assertEquals(0, server.requestCount)
        } finally { store.clear(); server.shutdown() }
    }

    @Test fun aReevaluationInvalidatesStaleMistakeTextEvenWhenTheBestMoveIsUnchanged() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE, stockfishToken = "test-token"))
        val server = MockWebServer().apply { start() }
        val client = RemoteStockfishClient({ "test-token" }, server.url("/").toString())
        val model = GameViewModel(app, client)
        val store = ViewModelStore().apply { put("lesson", model) }
        try {
            waitFor(model) { model.state.value.ready && !model.state.value.busy }
            val best = Evaluation(22, cp = 34, pv = listOf("e7e5"))
            val review = MoveReview(2, "c7c5", "c5", best, best.copy(cp = -800, pv = listOf("c7c5")),
                grade = Grade.BLUNDER, explanation = "", provisional = false, algorithmVersion = 3,
                engineVersion = "Stockfish 19", deeplySearched = true)
            val game = GameRecord(moves = listOf("e2e4", "c7c5"), reviews = listOf(review),
                lessons = listOf(MoveCoach.explain(listOf("e2e4"), review)), finished = true)
            model.load(game)
            fun item(move: String, value: Int) = """{"move":"$move","depth":22,"score":{"type":"cp","value":$value},"pv":["$move"]}"""
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"best":${item("e7e5", 34)},"played":${item("c7c5", 28)},"comparison":{"canCompare":true,"commonDepth":22},"engine":{"name":"Stockfish","version":"19"}}"""))
            model.analyzeSelected()
            waitFor(model) { !model.state.value.busy && server.requestCount == 1 }
            assertNull(model.state.value.error)
            assertEquals("e7e5", model.state.value.chosenReview!!.bestMove)
            assertTrue(model.state.value.game.lessons.isEmpty())
            assertNotEquals(Grade.BLUNDER, model.state.value.chosenReview!!.grade)
            assertEquals(game.moves, model.state.value.game.moves)
        } finally { store.clear(); server.shutdown() }
    }
}
