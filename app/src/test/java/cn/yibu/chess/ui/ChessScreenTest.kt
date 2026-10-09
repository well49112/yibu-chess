package cn.yibu.chess.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import cn.yibu.chess.AppState
import cn.yibu.chess.GameViewModel
import cn.yibu.chess.core.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChessScreenTest {
    @get:Rule val compose = createComposeRule()
    private val first = MoveReview(1, "e2e4", "e4", Evaluation(14, cp = 123, pv = listOf("e2e4")),
        Evaluation(14, cp = 123, pv = listOf("e2e4")), grade = Grade.BEST, explanation = "普通评级说明", provisional = false)
    private val second = first.copy(ply = 2, uci = "e7e5", san = "e5", grade = Grade.BLUNDER,
        best = Evaluation(14, cp = 123, pv = listOf("e7e5")), played = Evaluation(14, cp = -500, pv = listOf("e7e5")))
    private val game = EloRules.newGame(PlayerProfile(), humanWhite = true).copy(
        moves = listOf("e2e4", "e7e5"), reviews = listOf(first, second))

    @Test fun reviewHasDistinctPlayerHighlightsAndFullDeepReevaluationEntries() {
        val model = GameViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent { ChessScreen(AppState(game = game, ready = true, page = 1, cursor = 2), model) }
        compose.onNodeWithText("全局复盘 · 手动看关键点").assertIsDisplayed()
        compose.onNodeWithText("整盘深度复评").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("导出 PGN").performScrollTo().assertIsDisplayed()
    }

    @Test fun ordinaryGradesAndRecommendationsOnlyAppearInReview() {
        val model = GameViewModel(ApplicationProvider.getApplicationContext())
        val state = mutableStateOf(AppState(game = game, ready = true, cursor = 2))
        compose.setContent { ChessScreen(state.value, model) }
        compose.onNodeWithText("✓").assertDoesNotExist()
        compose.onNodeWithText("??").assertDoesNotExist()
        compose.onAllNodesWithText("推荐", substring = true).assertCountEquals(0)
        compose.onNodeWithText("普通评级说明").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(page = 1) }
        compose.onNodeWithText("e5 · 严重失误").performScrollTo().assertExists()
        compose.onAllNodesWithText("推荐", substring = true).assertAny(hasText("推荐", substring = true))
    }
    @Test fun confirmedBrilliantsFromEitherSideExplainThePlan() {
        val model = GameViewModel(ApplicationProvider.getApplicationContext())
        val white = first.copy(grade = Grade.BRILLIANT, brilliantReason = "弃象后仍有足够补偿", brilliantPlan = "对手 Kxh7 后，接 Ng5+")
        val black = second.copy(grade = Grade.BRILLIANT, brilliantReason = "弃车后仍有强制将杀", brilliantPlan = "对手 Kxh2 后，接 Qh4+")
        val state = mutableStateOf(AppState(game = game, ready = true, brilliantNotices = listOf(white, black)))
        compose.setContent { ChessScreen(state.value, model) }
        compose.onNodeWithText("你 · e4 !!").assertExists()
        compose.onNodeWithText("对手 · e5 !!").assertExists()
        compose.onNodeWithText("对手 Kxh7 后，接 Ng5+").assertExists()
        compose.onNodeWithText("对手 Kxh2 后，接 Qh4+").assertExists()
        compose.runOnIdle { state.value = state.value.copy(brilliantNotices = listOf(white.copy(provisional = true))) }
        compose.onAllNodesWithText("!!", substring = true).assertCountEquals(0)
    }
    @Test fun newGameStartsWithoutPromptAndSettingsAreExplicit() {
        val model = GameViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent { ChessScreen(AppState(game = game, ready = true), model) }
        compose.onNodeWithText("新局").performClick()
        compose.onNodeWithText("开始对弈").assertDoesNotExist()
        compose.onNodeWithText("随机").assertDoesNotExist()
        compose.onNodeWithContentDescription("对局设置").performClick()
        compose.onNodeWithText("随机").assertIsSelected()
        compose.onNodeWithText("黑方").performClick().assertIsSelected()
    }
}
