package cn.yibu.chess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.*
import cn.yibu.chess.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpeningLessonUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun close() { store.clear() }
    private fun board(flipped: Boolean = false) = compose.onNodeWithContentDescription("国际象棋棋盘，${if (flipped) "黑方" else "白方"}视角")
    private fun tap(square: String, flipped: Boolean = false) {
        val i = ChessRules.squareIndex(square)
        val col = if (flipped) 7 - i % 8 else i % 8
        val row = if (flipped) i / 8 else 7 - i / 8
        board(flipped).performScrollTo().performTouchInput { click(Offset((col + .5f) * width / 8f, (row + .5f) * height / 8f)) }
    }
    private fun workspace(initial: OpeningSession) = mutableStateOf(initial).also { state ->
        compose.setContent { ChessTheme { Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(70.dp))
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                OpeningWorkspace(state.value, false, true, setOf("italian:main:learn"), emptyList(), {},
                    { state.value = state.value.changeRoute(it) }, { state.value = state.value.seek(it) },
                    { state.value = state.value.quiz() }, { state.value = state.value.lessonHint() },
                    { state.value = state.value.explore() }, { state.value = state.value.answer(it) },
                    { state.value = state.value.copy(freeMoves = state.value.freeMoves.dropLast(1)) }, {}, {}, { _, _ -> },
                    { state.value = state.value.continueLesson() }, { state.value = state.value.guided() }, { state.value = state.value.showLessonMove() })
            }
            Spacer(Modifier.height(80.dp))
        } } }
    }
    @Test fun teachingBoardHasExactlyThePlayBoardWidthOnTheActualPhoneLayout() {
        val vm = GameViewModel(ApplicationProvider.getApplicationContext()).also { store.put("ui", it) }
        val state = mutableStateOf(AppState(ready = true, page = 0))
        compose.setContent { ChessScreen(state.value, vm) }
        val playWidth = board().fetchSemanticsNode().size.width
        compose.runOnIdle { state.value = state.value.copy(page = 3, opening = OpeningSession("italian")) }
        val teaching = board().fetchSemanticsNode().size
        assertEquals(playWidth, teaching.width)
        assertEquals(teaching.width, teaching.height)
        assertTrue(teaching.width > 350)
        compose.onNodeWithTag("opening-continue").assertIsDisplayed()
        compose.featureScreenshot("interactive-course")
    }
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun smallScreensKeepTheFullBoardAndAllFourNamedRoutesWithLegacyProgress() {
        val state = workspace(OpeningSession("italian"))
        assertEquals(280, board().fetchSemanticsNode().size.width)
        compose.onNodeWithTag("opening-continue").assertIsDisplayed()
        compose.onNodeWithTag("opening-routes").performClick()
        (0..3).forEach { compose.onNodeWithTag("opening-route-$it").assertIsDisplayed() }
        compose.onNodeWithText("已读过 · 可再互动练习").assertIsDisplayed()
        compose.featureScreenshot("course-routes-small")
        compose.onNodeWithTag("opening-route-3").performClick()
        compose.runOnIdle { assertEquals("evans", state.value.route.id) }
        compose.onNodeWithTag("opening-continue").performClick()
        tap("e2"); tap("e4")
        compose.onNodeWithTag("opening-answer").assertIsDisplayed()
        compose.onNodeWithTag("opening-continue").assertIsDisplayed()
    }
    @Test fun whiteLessonExplainsAnswersWaitsForContinueAndRepeatsAssistedTargets() {
        val state = workspace(OpeningSession("italian"))
        compose.mainClock.advanceTimeBy(5000)
        compose.runOnIdle { assertEquals(OpeningLessonPhase.INTRO, state.value.lessonPhase) }
        compose.onNodeWithTag("opening-continue").performClick()
        tap("a2"); tap("a3")
        compose.onNodeWithTag("opening-answer").assertTextContains("这着合法", substring = true)
        tap("e2"); tap("e4")
        compose.onNodeWithTag("opening-answer").assertTextContains("控制d5和f5", substring = true).assertIsDisplayed()
        compose.mainClock.advanceTimeBy(5000)
        compose.runOnIdle { assertEquals(listOf("e2e4"), state.value.history) }
        compose.featureScreenshot("course-answer")
        compose.onNodeWithTag("opening-continue").performClick()
        compose.runOnIdle { assertEquals(listOf("e2e4", "e7e5"), state.value.history) }
        compose.onNodeWithText("给我提示").performClick()
        compose.onNodeWithText("看示范").performClick()
        compose.onNodeWithTag("opening-continue").performClick()
        repeat(6) {
            val move = state.value.route.moves[state.value.lessonPly]
            tap(move.take(2)); tap(move.substring(2, 4))
            compose.onNodeWithTag("opening-continue").performClick()
        }
        compose.runOnIdle { assertTrue(state.value.retrying); assertEquals(listOf(0, 2), state.value.lessonDeck) }
        repeat(2) {
            val move = state.value.route.moves[state.value.lessonPly]
            tap(move.take(2)); tap(move.substring(2, 4))
            compose.onNodeWithTag("opening-continue").performClick()
        }
        compose.onNodeWithTag("opening-complete").assertIsDisplayed()
        compose.onNodeWithText("再练一次").assertIsDisplayed()
        compose.featureScreenshot("course-complete")
    }
    @Test fun blackLessonSupportsConsecutiveAnswersAndExplorationFromTheShownPosition() {
        val state = workspace(OpeningSession("caro-kann"))
        compose.onNodeWithTag("opening-continue").performClick()
        tap("c7", true); tap("c6", true)
        compose.onNodeWithTag("opening-answer").assertTextContains("控制d5", substring = true)
        compose.onNodeWithTag("opening-continue").performClick()
        tap("d7", true); tap("d5", true)
        compose.runOnIdle { assertEquals(listOf("e2e4", "c7c6", "d2d4", "d7d5"), state.value.history) }
        compose.onNodeWithTag("opening-tools").performClick()
        compose.onNodeWithText("自由试走").performClick()
        tap("b1", true); tap("c3", true)
        tap("d5", true); tap("e4", true)
        compose.runOnIdle { assertEquals(6, state.value.history.size) }
        compose.onNodeWithText("撤回").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(5, state.value.history.size) }
    }
}
