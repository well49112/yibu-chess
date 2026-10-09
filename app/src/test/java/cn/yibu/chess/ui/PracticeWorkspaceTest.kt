package cn.yibu.chess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.yibu.chess.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PracticeWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private fun question(root: List<String> = listOf("e2e4", "e7e5", "d1h5", "b8c6"), best: String = "h5f3", played: String = "h5e5"): PracticeQuestion {
        val review = MoveReview(root.size + 1, played, ChessRules.san(root, played), Evaluation(22, cp = 30, pv = listOf(best)),
            Evaluation(22, cp = -800, pv = listOf(played)), grade = Grade.BLUNDER, explanation = "")
        return PracticeQuestion(123, root.size + 1, root, review, WeaknessType.HANGING_PIECE, "这步允许对手吃掉后。", setOf(best))
    }
    private fun tap(square: String, flipped: Boolean) {
        val index = ChessRules.squareIndex(square)
        val col = if (flipped) 7 - index % 8 else index % 8
        val row = if (flipped) index / 8 else 7 - index / 8
        compose.onNodeWithContentDescription("国际象棋棋盘，${if (flipped) "黑方" else "白方"}视角")
            .performTouchInput { click(Offset((col + .5f) * width / 8f, (row + .5f) * height / 8f)) }
    }

    @Test fun answersStayHiddenHintsAreGradualAndRevealingDoesNotCountAsIndependentSuccess() {
        val question = question()
        val state = mutableStateOf(PracticeSession(listOf(question)))
        compose.setContent { ChessTheme { Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(70.dp))
            Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                PracticeWorkspace(state.value, {}, {}, onHint = { state.value = state.value.copy(hints = state.value.hints + 1) },
                    onReveal = { state.value = state.value.copy(revealed = true, assisted = 1, message = "已查看答案") },
                    onNext = { state.value = state.value.next() }, onExplain = {})
            }
            Spacer(Modifier.height(80.dp))
        } } }
        compose.onNodeWithTag("practice-answer").assertDoesNotExist()
        compose.onNodeWithText("这步允许对手吃掉后。").assertDoesNotExist()
        compose.onNodeWithText("给点提示").assertIsDisplayed().performClick()
        compose.onNodeWithTag("practice-hint").assertTextContains("回吃", substring = true)
        compose.onNodeWithText("再提示一步").performClick()
        compose.onNodeWithTag("practice-hint").assertTextContains("h5 的后", substring = true)
        compose.onNodeWithTag("practice-answer").assertDoesNotExist()
        compose.onNodeWithText("查看答案").assertIsDisplayed().performClick()
        compose.onNodeWithTag("practice-answer").assertTextEquals("推荐 Qf3")
        assertEquals(0, state.value.independent)
        compose.featureScreenshot("practice-revealed-small-screen")
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithTag("practice-complete").assertIsDisplayed()
        compose.onNodeWithText("独立答对 0 题 · 辅助完成 1 题").assertExists()
    }

    @Test fun whiteCanAnswerByTappingThePieceAndDestination() = answer(question())
    @Test fun blackCanAnswerWithTheBoardFacingTheirColor() = answer(question(
        listOf("c2c4", "e7e5", "b1c3", "d8h4", "g2g3"), "h4d8", "h4e4"))
    private fun answer(question: PracticeQuestion) {
        var received: String? = null
        compose.setContent { ChessTheme { PracticeWorkspace(PracticeSession(listOf(question)), {}, { received = it }, {}, {}, {}, {}) } }
        tap(question.bestMove.take(2), !question.humanWhite)
        tap(question.bestMove.substring(2, 4), !question.humanWhite)
        assertEquals(question.bestMove, received)
        compose.onNodeWithTag("practice-answer").assertDoesNotExist()
    }

    @Test fun promotionAsksWhichPieceAndSubmitsTheFullUciMove() {
        val root = listOf("a2a4", "h7h5", "a4a5", "h5h4", "a5a6", "h4h3", "a6b7", "h3g2")
        val question = question(root, "b7a8q", "b7c8n")
        var received: String? = null
        compose.setContent { ChessTheme { PracticeWorkspace(PracticeSession(listOf(question)), {}, { received = it }, {}, {}, {}, {}) } }
        tap("b7", false); tap("a8", false)
        compose.onNodeWithText("选择升变棋子").assertIsDisplayed()
        assertNull(received)
        compose.onNodeWithText("后").performClick()
        assertEquals("b7a8q", received)
    }
}
