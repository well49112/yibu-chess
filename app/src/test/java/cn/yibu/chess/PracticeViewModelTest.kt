package cn.yibu.chess

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.core.*
import cn.yibu.chess.data.*
import cn.yibu.chess.engine.RemoteStockfishClient
import okhttp3.mockwebserver.MockWebServer
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
class PracticeViewModelTest {
    @Before fun reset() { GameDatabase.resetForTests() }
    @After fun close() { GameDatabase.resetForTests() }
    private fun waitFor(model: GameViewModel, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(20)
        }
        assertTrue("State did not settle: ${model.state.value.error}", condition())
    }
    @Test fun answersHintsSavedSpacingAndExplanationNeverChangeTheGameOrEloOrCallTheServer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(color = ColorPreference.WHITE))
        val server = MockWebServer().apply { start() }
        val model = GameViewModel(app, RemoteStockfishClient({ "" }, server.url("/").toString()))
        val store = ViewModelStore().apply { put("practice", model) }
        try {
            waitFor(model) { model.state.value.ready && !model.state.value.busy }
            val root = listOf("e2e4", "e7e5", "d1h5", "b8c6")
            val review = MoveReview(5, "h5e5", "Qxe5+", Evaluation(22, cp = 30, pv = listOf("h5f3", "g8f6")),
                Evaluation(22, cp = -800, pv = listOf("h5e5", "c6e5")), grade = Grade.BLUNDER, explanation = "",
                provisional = false, algorithmVersion = 3, engineVersion = "Stockfish 19", deeplySearched = true,
                bestExpectedPoints = .7, playedExpectedPoints = .1)
            val game = GameRecord(moves = root + review.uci, reviews = listOf(review), finished = true)
            model.load(game)
            model.page(2)
            waitFor(model) { model.state.value.practiceQuestions.size == 1 }
            val profile = model.state.value.profile
            model.startPractice()
            val question = model.state.value.practice!!.current!!
            model.practiceAnswer(review.uci)
            assertEquals(1, model.state.value.practice!!.wrongAttempts)
            assertFalse(model.state.value.practice!!.finished)
            model.practiceHint()
            model.practiceAnswer(question.bestMove)
            val assisted = model.state.value.practiceProgress.getValue(question.key)
            assertEquals(1, assisted.completed)
            assertEquals(0, assisted.independentCorrect)
            assertEquals(600_000L, assisted.nextDueAt - assisted.lastReviewedAt)
            model.practiceReveal()
            assertEquals(assisted, model.state.value.practiceProgress.getValue(question.key))
            model.practiceExplain()
            waitFor(model) { !model.state.value.busy && model.state.value.lessonOpen }
            assertNotNull(model.state.value.practice)
            model.closeLesson()
            assertEquals(2, model.state.value.page)
            assertTrue(model.state.value.practice!!.finished)
            model.practiceNext()
            assertNull(model.state.value.practice!!.current)
            model.closePractice()
            model.startPractice()
            model.practiceAnswer(question.bestMove)
            val independent = model.state.value.practiceProgress.getValue(question.key)
            assertEquals(1, independent.independentCorrect)
            assertEquals(86_400_000L, independent.nextDueAt - independent.lastReviewedAt)
            assertEquals(independent, PracticePreferences(app).read().getValue(question.key))
            assertEquals(profile, model.state.value.profile)
            assertEquals(game.moves, model.state.value.game.moves)
            assertEquals(game.reviews, model.state.value.game.reviews)
            assertEquals(0, server.requestCount)
            model.closePractice()
            model.delete(model.state.value.game)
            waitFor(model) { !model.state.value.transitioning && model.state.value.practiceQuestions.isEmpty() }
            assertFalse(PracticePreferences(app).read().containsKey(question.key))
            assertEquals(profile, model.state.value.profile)
        } finally { store.clear(); server.shutdown() }
    }
}
