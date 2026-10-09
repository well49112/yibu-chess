package cn.yibu.chess

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.core.*
import cn.yibu.chess.data.*
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
class GuidedOpeningViewModelTest {
    @Before fun reset() { GameDatabase.resetForTests() }
    @After fun close() { GameDatabase.resetForTests() }
    private fun ready(model: GameViewModel) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while ((!model.state.value.ready || model.state.value.busy) && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(20)
        }
        assertTrue(model.state.value.ready && !model.state.value.busy)
    }
    @Test fun guidedCompletionPersistsWithoutChangingTheGameEloOrLegacyProgressAndAssistanceIsNotIndependentCompletion() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayPreferences(app).save(PlaySettings(stockfishToken = ""))
        val legacy = setOf("italian:main:learn", "italian:branch:quiz")
        OpeningPreferences(app).save(legacy)
        val store = ViewModelStore()
        val model = GameViewModel(app).also { store.put("lesson", it) }
        try {
            ready(model)
            val game = model.state.value.game
            val profile = model.state.value.profile
            model.openCourse("italian", 2)
            model.courseContinue()
            model.courseHint(); model.courseShowMove(); model.courseContinue()
            var count = 0
            while (model.state.value.opening!!.lessonPhase != OpeningLessonPhase.DONE && count++ < 30) {
                val lesson = model.state.value.opening!!
                model.courseAnswer(lesson.route.moves[lesson.lessonPly]); model.courseContinue()
            }
            assertEquals(OpeningLessonPhase.DONE, model.state.value.opening!!.lessonPhase)
            assertTrue(OpeningPreferences(app).progress().containsAll(legacy + setOf("italian:center:learn", "italian:center:guided")))
            assertFalse("italian:center:quiz" in OpeningPreferences(app).progress())
            assertEquals(game, model.state.value.game)
            assertEquals(profile, model.state.value.profile)
            model.courseGuided(); model.courseContinue()
            while (model.state.value.opening!!.lessonPhase != OpeningLessonPhase.DONE) {
                val lesson = model.state.value.opening!!
                model.courseAnswer(lesson.route.moves[lesson.lessonPly]); model.courseContinue()
            }
            assertTrue("italian:center:quiz" in OpeningPreferences(app).progress())
            model.openCourse("caro-kann", 3); model.courseContinue(); model.courseAnswer("c7c6")
            val position = model.state.value.opening!!.history
            model.pauseForBackground(); model.resumeForeground()
            assertEquals(position, model.state.value.opening!!.history)
            store.clear()
            val restored = GameViewModel(app).also { store.put("restored", it) }
            ready(restored)
            assertTrue(restored.state.value.openingProgress.containsAll(legacy + setOf("italian:center:guided", "italian:center:quiz")))
            assertEquals(profile, restored.state.value.profile)
        } finally { store.clear() }
    }
}
