package cn.yibu.chess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModelStore
import cn.yibu.chess.*
import cn.yibu.chess.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpeningImportUiTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun close() { store.clear() }
    private fun model() = GameViewModel(ApplicationProvider.getApplicationContext()).also { store.put("ui", it) }
    private fun tap(square: String, flipped: Boolean) {
        val i = ChessRules.squareIndex(square)
        val col = if (flipped) 7 - i % 8 else i % 8
        val row = if (flipped) i / 8 else 7 - i / 8
        compose.onNodeWithContentDescription("国际象棋棋盘，${if (flipped) "黑方" else "白方"}视角")
            .performTouchInput { click(Offset((col + .5f) * width / 8f, (row + .5f) * height / 8f)) }
    }
    @Test fun libraryContainsOnlyGamesAndImportWithSavedUsernameAndNoTrainingCards() {
        val vm = model()
        val pgn = """[White "Alice"]
[Black "Bob"]
[Result "1-0"]

1.e4 e5 1-0"""
        val imported = ChessComImport.parse(pgn, "alice", "https://www.chess.com/game/live/42", 1700000000)
        val state = AppState(ready = true, page = 2, games = listOf(imported), chessComUsername = "alice")
        compose.setContent { ChessScreen(state, vm) }
        compose.onNodeWithText("我的棋谱").assertIsDisplayed()
        compose.onNodeWithTag("practice-card").assertDoesNotExist()
        compose.onNodeWithText("个人弱点").assertDoesNotExist()
        compose.onNodeWithText("Chess.com · Bob").assertIsDisplayed()
        compose.featureScreenshot("library-clean")
        compose.onNodeWithText("本地对弈").performClick()
        compose.onNodeWithText("Chess.com · Bob").assertDoesNotExist()
        compose.onNodeWithTag("chesscom-import-button").performClick()
        compose.onNodeWithTag("chesscom-username").assertTextContains("alice")
        compose.onNodeWithText("不需要账号密码。用户名会记住，可随时修改。").assertExists()
        compose.featureScreenshot("chesscom-import")
    }
    @Test fun trainingHubGroupsCoursesByColorAndKeepsPersonalTrainingOnSeparateSection() {
        val vm = model()
        compose.setContent { ChessTheme { TrainingHub(AppState(ready = true), vm) } }
        compose.onNodeWithTag("course-italian").assertIsDisplayed()
        compose.onNodeWithTag("practice-card").assertDoesNotExist()
        compose.featureScreenshot("opening-courses")
        compose.onNodeWithText("执黑 · 5 套").performClick()
        compose.onNodeWithTag("course-italian").assertDoesNotExist()
        compose.onNodeWithTag("course-double-pawn").assertIsDisplayed()
        compose.onNodeWithTag("course-caro-kann").assertIsDisplayed()
    }
    @Test fun personalTrainingContainsWeaknessAndMistakePracticeInsteadOfCourseCards() {
        val vm = model()
        compose.setContent { ChessTheme { TrainingHub(AppState(ready = true, trainingSection = 1), vm) } }
        compose.onNodeWithTag("practice-card").assertIsDisplayed()
        compose.onNodeWithTag("course-italian").assertDoesNotExist()
        compose.onNodeWithText("我的错题练习").assertIsDisplayed()
    }
    private fun workspace(initial: OpeningSession) {
        val state = mutableStateOf(initial)
        compose.setContent { ChessTheme { Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(70.dp))
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                OpeningWorkspace(state.value, false, true, emptySet(), emptyList(), {},
                    { state.value = state.value.changeRoute(it) }, { state.value = state.value.seek(it) },
                    { state.value = state.value.quiz() }, { state.value = state.value.copy(hint = true) },
                    { state.value = state.value.explore() }, { state.value = state.value.answer(it) },
                    { state.value = state.value.copy(freeMoves = state.value.freeMoves.dropLast(1)) }, {}, {}, { _, _ -> })
            }
            Spacer(Modifier.height(80.dp))
        } } }
    }
    @Test fun manualStepsShowAuthoredReasonsAndWhiteQuizExplainsCastlingAfterBoardAnswer() {
        workspace(OpeningSession("italian"))
        compose.mainClock.advanceTimeBy(5000)
        compose.onNodeWithTag("opening-note").assertDoesNotExist() // No autoplay.
        compose.onNodeWithText("下一步").assertIsDisplayed().performClick()
        compose.onNodeWithTag("opening-note").assertTextContains("控制d5和f5", substring = true)
        compose.onNodeWithText("分支练习").assertIsDisplayed().performClick()
        compose.onNodeWithTag("opening-answer").assertDoesNotExist()
        tap("e1", false); tap("g1", false)
        compose.onNodeWithTag("opening-answer").assertTextContains("王放到g1", substring = true).assertIsDisplayed()
        compose.featureScreenshot("opening-answer")
        compose.onNodeWithText("自由试走").performClick()
        tap("d7", false); tap("d6", false)
        compose.onNodeWithText("撤回").assertIsEnabled().performClick()
        compose.onNodeWithText("现在黑方走").assertExists()
    }
    @Test fun blackQuizUsesBlackOrientationAndRespondsToTheSecondMoveAfterCorrectAnswer() {
        workspace(OpeningSession("caro-kann").quiz())
        tap("c8", true); tap("f5", true)
        compose.onNodeWithTag("opening-answer").assertExists()
        compose.onNodeWithText("自由试走").performClick()
        tap("e4", true); tap("g3", true)
        compose.onNodeWithText("现在黑方走").assertExists()
        tap("f5", true); tap("g6", true)
        compose.onNodeWithText("现在白方走").assertExists()
        compose.onNodeWithText("从这里陪练").assertIsDisplayed().assertIsEnabled()
    }
}
