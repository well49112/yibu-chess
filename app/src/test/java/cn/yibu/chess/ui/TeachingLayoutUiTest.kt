package cn.yibu.chess.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
@Config(sdk = [35], qualifiers = "w392dp-h852dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TeachingLayoutUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun close() { store.clear() }
    private fun model() = GameViewModel(ApplicationProvider.getApplicationContext()).also { store.put("ui", it) }

    @Test fun brandHeaderOnlyAppearsInPlayAndLessonFocusRestoresNavigationOnExit() {
        val vm = model()
        val state = mutableStateOf(AppState(ready = true))
        compose.setContent { ChessScreen(state.value, vm) }
        compose.onNodeWithTag("app-header").assertIsDisplayed()
        for (page in listOf(1, 2, 3)) {
            compose.runOnIdle { state.value = state.value.copy(page = page) }
            compose.onNodeWithTag("app-header").assertDoesNotExist()
            compose.onNodeWithText("新局").assertDoesNotExist()
            compose.onNodeWithText("对弈").assertIsDisplayed()
        }
        compose.featureScreenshot("training-no-header")
        compose.runOnIdle { state.value = state.value.copy(opening = OpeningSession("italian")) }
        compose.onNodeWithText("对弈").assertDoesNotExist()
        compose.onNodeWithText("返回").assertIsDisplayed()
        compose.onNodeWithTag("opening-continue").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(opening = null) }
        compose.onNodeWithText("对弈").assertIsDisplayed()
        compose.onNodeWithTag("training-hub").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(page = 0) }
        compose.onNodeWithTag("app-header").assertIsDisplayed()
    }

    private fun demandingStates(course: OpeningCourse, routeIndex: Int): List<OpeningSession> {
        val intro = OpeningSession(course.id, routeIndex)
        val tasks = intro.lessonPlies.indices.map { intro.continueLesson().copy(lessonStep = it) }
        val hardest = tasks.maxBy { it.lessonTask.prompt.length + it.lessonTask.hint.length }
        val fullTask = tasks.maxBy { it.lessonTask.prompt.length + if (it.lessonPly > 0) it.route.notes[it.lessonPly - 1].length else 0 }
        val other = ChessRules.legal(hardest.history).first { it !in hardest.lessonTask.answers }
        val answer = tasks.map { it.answer(it.route.moves[it.lessonPly]) }.maxBy { it.message.length }
        var completed = intro.continueLesson()
        while (completed.lessonPhase != OpeningLessonPhase.DONE) {
            completed = completed.answer(completed.route.moves[completed.lessonPly]).continueLesson()
        }
        val longestNote = intro.route.notes.indices.maxBy { intro.route.notes[it].length }
        return listOf(intro, fullTask, hardest.answer(other).lessonHint(), hardest.answer(other).lessonHint().lessonHint(),
            answer, intro.seek(0), intro.seek(longestNote + 1), completed)
    }

    private fun checkFits(label: String) {
        val frame = compose.onNodeWithTag("opening-workspace").fetchSemanticsNode().boundsInRoot
        val board = compose.onNodeWithTag("opening-board-frame").fetchSemanticsNode().boundsInRoot
        val coach = compose.onNodeWithTag("opening-coach").fetchSemanticsNode().boundsInRoot
        assertEquals("$label square board", board.width, board.height, .1f)
        assertTrue("$label full width", board.width >= frame.width - 1f)
        assertTrue("$label board fits", board.bottom <= frame.bottom)
        compose.onNodeWithTag("opening-tools").assertIsDisplayed()
        compose.onAllNodes(hasAnyAncestor(hasTestTag("opening-workspace")) and hasScrollAction()).assertCountEquals(0)
        val texts = compose.onAllNodes(hasAnyAncestor(hasTestTag("opening-coach")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$label has explanation", texts.isNotEmpty())
        texts.forEach { node ->
            val value = node.config[SemanticsProperties.Text].joinToString { it.text }
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            assertTrue("$label measured $value", layouts.isNotEmpty())
            // Compose rounds intrinsic text width to pixels; didOverflowWidth may be true
            // for a fraction of a pixel even when the full text fits its available width.
            val clipped = layouts.any { result -> result.didOverflowHeight ||
                result.multiParagraph.width > result.layoutInput.constraints.maxWidth + 1f ||
                (0 until result.lineCount).any(result::isLineEllipsized) }
            if (clipped) compose.featureScreenshot("teaching-overflow")
            assertFalse("$label clipped $value: ${layouts.map { "size=${it.size}, paragraph=${it.multiParagraph.width}x${it.multiParagraph.height}, constraints=${it.layoutInput.constraints}" }}", clipped)
            assertTrue("$label outside coach: $value", node.boundsInRoot.top >= coach.top - 1f &&
                node.boundsInRoot.bottom <= coach.bottom + 1f)
        }
    }

    private fun checkAllCourses(fontScale: Float) {
        val vm = model()
        val state = mutableStateOf(AppState(ready = true, page = 3, opening = OpeningSession("italian")))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ChessScreen(state.value, vm)
            }
        }
        val initialBoard = compose.onNodeWithTag("opening-board-frame").fetchSemanticsNode().boundsInRoot
        OpeningCourses.all.forEach { course -> course.routes.indices.forEach { route ->
            demandingStates(course, route).forEach { session ->
                compose.runOnIdle { state.value = state.value.copy(opening = session) }
                checkFits("${session.key}:${session.mode}:${session.lessonPhase}:${session.hintLevel}")
                val board = compose.onNodeWithTag("opening-board-frame").fetchSemanticsNode().boundsInRoot
                if (session.mode == OpeningMode.GUIDE) assertEquals("fixed board during lesson", initialBoard, board)
                if (session.lessonPhase == OpeningLessonPhase.FEEDBACK && session.mode == OpeningMode.GUIDE) {
                    compose.onNodeWithTag("opening-answer").assertTextEquals(session.message)
                }
            }
        } }
        compose.runOnIdle { state.value = state.value.copy(opening = OpeningSession("italian").continueLesson().answer("e2e4")) }
        compose.featureScreenshot(if (fontScale > 1f) "teaching-large-font" else "teaching-small-screen")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun allFortyRoutesFitCompactScreenWithFullReasonsHintsErrorsAndSummary() = checkAllCourses(1f)

    @Test fun allFortyRoutesFitPhoneWithLargerFontWithoutMovingOrScrollingBoard() = checkAllCourses(1.2f)
}
