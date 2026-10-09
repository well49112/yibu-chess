package cn.yibu.chess.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class WeaknessCardTest {
    @get:Rule val compose = createComposeRule()
    @Test fun reportShowsCoverageEvidenceAndATargetAndOpensTheCorrectSavedMove() {
        val example = WeaknessExample(123L, 5, "Qxe5+", "Nxe5 吃掉 e5 的后，不能回吃。")
        val report = WeaknessReport(4, 2, 50, 20, listOf(WeaknessGroup(WeaknessType.HANGING_PIECE, listOf(example))))
        var selected: Pair<Long, Int>? = null
        compose.setContent { ChessTheme { WeaknessCard(report, { id, ply -> selected = id to ply }) } }
        compose.onNodeWithTag("weakness-coverage").assertTextContains("20 / 50", substring = true)
        compose.onNodeWithText("漏看直接丢子").assertExists()
        compose.onNodeWithTag("weakness-goal").assertTextEquals(WeaknessType.HANGING_PIECE.goal)
        compose.onNodeWithText("看例子").performClick()
        compose.onNodeWithText(example.evidence).assertIsDisplayed()
        compose.onNodeWithTag("weakness-example-123-5").performClick()
        assertEquals(123L to 5, selected)
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithText(example.evidence).assertDoesNotExist()
    }
    @Test fun absenceOfEvidenceIsNotPresentedAsPerfectPlay() {
        compose.setContent { ChessTheme { WeaknessCard(WeaknessReport(2, 1, 30, 10), { _, _ -> }) } }
        compose.onNodeWithText("目前没有足够证据归类战术问题，不能据此判断没有失误。").assertExists()
        compose.onNodeWithTag("weakness-goal").assertDoesNotExist()
        compose.onNodeWithText("看例子").assertDoesNotExist()
    }
}
