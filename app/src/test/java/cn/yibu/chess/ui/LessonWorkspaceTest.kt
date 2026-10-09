package cn.yibu.chess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.yibu.chess.AppState
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
class LessonWorkspaceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothRoutesResetToTheSameRootAndShowTheirOwnReplyWithoutChangingTheScore() {
        val root = listOf("e2e4", "e7e5", "d1h5", "b8c6")
        val review = MoveReview(5, "h5e5", "Qxe5+", Evaluation(22, cp = 30, pv = listOf("h5f3", "g8f6")),
            Evaluation(22, cp = -800, pv = listOf("h5e5", "c6e5")), grade = Grade.BLUNDER, explanation = "",
            provisional = false, algorithmVersion = 3)
        val lesson = MoveCoach.explain(root, review)
        val game = GameRecord(moves = root + review.uci, reviews = listOf(review), lessons = listOf(lesson))
        val state = mutableStateOf(AppState(game = game, ready = true, page = 1, cursor = 5, lessonOpen = true,
            variation = lesson.variation, variationBase = 4))
        compose.setContent { ChessTheme {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.height(70.dp))
                Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    LessonWorkspace(state.value, false, {}, {}, onSeek = {
                        state.value = state.value.copy(variationStep = it.coerceIn(0, state.value.variation.size))
                    }, onRetry = {}, onPause = {}, onRoute = { played ->
                        state.value = state.value.copy(lessonPlayed = played,
                            variation = if (played) lesson.playedVariation else lesson.variation, variationStep = 0)
                    })
                }
                Spacer(Modifier.height(80.dp))
            }
        } }
        compose.onNodeWithText("实战线").performClick().assertIsSelected()
        assertEquals(root, state.value.boardHistory)
        compose.onNodeWithText("下一步").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lesson-step-title").assertTextContains("白方 Qxe5+", substring = true)
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithTag("lesson-step-title").assertTextContains("黑方 Nxe5", substring = true)
        compose.onNodeWithTag("lesson-step-explanation").assertTextContains("e5的后", substring = true)
        assertEquals(root + lesson.playedVariation, state.value.boardHistory)
        compose.onNodeWithText("推荐线").performClick().assertIsSelected()
        assertEquals(root, state.value.boardHistory)
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithTag("lesson-step-title").assertTextContains("白方 Qf3", substring = true)
        compose.onNodeWithText("末尾").performClick()
        assertEquals(root + lesson.variation, state.value.boardHistory)
        assertEquals(game, state.value.game)
        compose.onNodeWithText("实战线").performClick()
        compose.onNodeWithText("为什么这样走").performClick()
        compose.onNodeWithTag("lesson-why").assertTextEquals(lesson.playedExplanation)
        compose.onNodeWithContentDescription("国际象棋棋盘，白方视角").assertIsDisplayed()
    }

    @Test fun legacyLessonKeepsBoardVisibleWhileItsFullTextScrollsAndStepsChange() {
        val root = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "g8f6")
        val line = listOf("e1g1", "f8c5", "d2d3", "d7d6")
        val eval = Evaluation(22, cp = 30, pv = line)
        val review = MoveReview(7, "d2d3", "d3", eval, eval, grade = Grade.GOOD, explanation = "")
        val lesson = MoveCoach.explain(root, review).copy(steps = emptyList())
        val state = mutableStateOf(AppState(game = GameRecord(moves = root + "d2d3", humanWhite = false, lessons = listOf(lesson)),
            ready = true, page = 1, cursor = 7, lessonOpen = true, variation = line, variationBase = 6))
        compose.setContent {
            ChessTheme {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(70.dp))
                    Box(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        LessonWorkspace(state.value, true, onClose = {}, onFlip = {}, onSeek = {
                            state.value = state.value.copy(variationStep = it.coerceIn(0, line.size))
                        }, onRetry = {}, onPause = {})
                    }
                    Spacer(Modifier.height(80.dp))
                }
            }
        }
        val board = compose.onNodeWithTag("lesson-board").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("国际象棋棋盘，黑方视角").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lesson-step-explanation").assertTextContains("通过易位", substring = true)
        assertEquals(line.take(1), state.value.boardHistory.drop(root.size))
        compose.onNodeWithText("全文").performClick()
        compose.onNodeWithTag("lesson-why").assertTextEquals(lesson.why)
        compose.onNodeWithTag("lesson-plan").assertTextEquals(lesson.plan)
        compose.onNodeWithTag("lesson-notes").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 250f) }
        assertEquals(board, compose.onNodeWithTag("lesson-board").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("国际象棋棋盘，黑方视角").assertIsDisplayed()
        compose.onNodeWithText("上一步").assertIsDisplayed().performClick()
        assertEquals(root, state.value.boardHistory)
        compose.onNodeWithText("末尾").performClick()
        assertEquals(root + line, state.value.boardHistory)
        compose.onNodeWithTag("lesson-step-title").assertTextContains("4 / 4", substring = true)
    }
}
