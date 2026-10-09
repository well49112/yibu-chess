package cn.yibu.chess.ui

import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import cn.yibu.chess.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HighlightsWorkspaceTest {
    @get:Rule val compose = createComposeRule()
    private fun advance(ms: Int) { repeat((ms + 15) / 16) {
        shadowOf(Looper.getMainLooper()).idle(); compose.mainClock.advanceTimeByFrame(); shadowOf(Looper.getMainLooper()).idle(); compose.waitForIdle()
    } }
    @Test fun anEmptyPlayerTourExplainsTheSelectionAndCanReturnToStepByStepReview() {
        var closed = false
        compose.setContent { ChessTheme {
            HighlightsWorkspace(GameRecord(moves = listOf("e2e4", "e7e5")), emptyList(), false, { closed = true }, {})
        } }
        compose.onNodeWithTag("highlights-empty").assertIsDisplayed()
        compose.onNodeWithText("本局暂无值得单独讲解的关键点").assertIsDisplayed()
        compose.onNodeWithText("查看逐步复盘").performClick()
        assertTrue(closed)
    }
    @Test fun tourOnlyChangesAfterManualPressAndSoundsFollowTheSelectedLineOnSmallScreen() {
        val game = GameRecord(moves = listOf("e2e4", "e7e5", "g1f3"))
        fun lesson(ply: Int, line: List<String>): MoveLesson {
            val root = game.moves.take(ply - 1)
            val ev = Evaluation(22, cp = 20, pv = line)
            return MoveCoach.explain(root, MoveReview(ply, game.moves[ply - 1], ChessRules.san(root, game.moves[ply - 1]),
                ev, ev, grade = Grade.GOOD, explanation = ""))
        }
        val longerLine = listOf("d2d4", "d7d5", "c2c4", "e7e6", "b1c3", "g8f6", "c1g5", "f8e7", "e2e3", "e8g8", "g1f3", "b8d7")
        val highlights = listOf(ReviewHighlight(1, "值得改进的一步", "先看看实战 e4", lesson(1, longerLine)),
            ReviewHighlight(3, "阶段回顾", "马的发展", lesson(3, listOf("g1f3", "b8c6"))))
        var closed = false
        val sounds = mutableListOf<SoundCue>()
        compose.setContent { CompositionLocalProvider(LocalReviewSound provides { before, after -> sounds += SoundEvents.preview(before, after).map { it.cue } }) { ChessTheme { Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(70.dp))
            Box(Modifier.weight(1f)) { HighlightsWorkspace(game, highlights, false, { closed = true }, {}) }
            Spacer(Modifier.height(80.dp))
        } } } }
        compose.mainClock.autoAdvance = false
        try {
            advance(8000)
            compose.onNodeWithTag("highlight-position").assertTextEquals("第 1 步之前")
            assertTrue(sounds.isEmpty())
            compose.onNodeWithText("下一步").assertIsDisplayed().performClick()
            advance(32)
            compose.onNodeWithTag("highlight-position").assertTextEquals("实战 · 白方 e4")
            assertEquals(listOf(SoundCue.MOVE), sounds)
            advance(6000)
            compose.onNodeWithTag("highlight-position").assertTextEquals("实战 · 白方 e4")
            compose.onNodeWithText("下一步").performClick()
            advance(32)
            compose.onNodeWithTag("highlight-position").assertTextEquals("回到起点 · 看推荐走法")
            compose.onNodeWithTag("highlight-explanation").assertTextContains("建议白方走 d4", substring = true)
            compose.onNodeWithText("下一步").performClick()
            advance(32)
            compose.onNodeWithTag("highlight-position").assertTextEquals("推荐路线 1 / 12")
            compose.onNodeWithTag("highlight-explanation").assertTextContains("白方 d4", substring = true)
            compose.onNodeWithContentDescription("国际象棋棋盘，白方视角").assertIsDisplayed()
            advance(320)
            compose.featureScreenshot("manual-recommended-line-small-screen")
            compose.onNodeWithText("上一步").assertIsDisplayed().performClick()
            advance(32)
            compose.onNodeWithTag("highlight-position").assertTextEquals("回到起点 · 看推荐走法")
            repeat(longerLine.size) { compose.onNodeWithText("下一步").performClick(); advance(32) }
            compose.onNodeWithTag("highlight-position").assertTextEquals("推荐路线 12 / 12")
            compose.onNodeWithTag("highlight-explanation").assertTextContains("黑方 Nbd7", substring = true)
            assertTrue(sounds.contains(SoundCue.CASTLE))
            advance(4000)
            compose.onNodeWithTag("highlight-position").assertTextEquals("推荐路线 12 / 12")
            compose.onNodeWithText("上一步").performClick(); advance(32)
            compose.onNodeWithTag("highlight-position").assertTextEquals("推荐路线 11 / 12")
            compose.onNodeWithText("下个点").assertIsDisplayed().performClick()
            advance(6000)
            compose.onNodeWithTag("highlight-position").assertTextEquals("第 3 步之前")
            compose.onNodeWithTag("highlight-title").assertTextContains("2 / 2", substring = true)
            repeat(4) { compose.onNodeWithText("下一步").performClick(); advance(32) }
            compose.onNodeWithText("完成").performClick()
            advance(32)
            compose.onNodeWithText("复盘完成").assertIsDisplayed()
            compose.onNodeWithText("重看").performClick()
            advance(32)
            compose.onNodeWithTag("highlight-title").assertTextContains("1 / 2", substring = true)
            compose.onNodeWithTag("highlight-position").assertTextEquals("第 1 步之前")
            compose.onNodeWithText("逐步复盘").performClick()
            assertTrue(closed)
            assertEquals(listOf("e2e4", "e7e5", "g1f3"), game.moves)
            assertTrue(game.lessons.isEmpty())
        } finally { compose.mainClock.autoAdvance = true }
    }
}
