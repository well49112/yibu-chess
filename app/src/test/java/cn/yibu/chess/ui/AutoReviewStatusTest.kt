package cn.yibu.chess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cn.yibu.chess.background.AutoReviewState
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutoReviewStatusTest {
    @get:Rule val compose = createComposeRule()
    @Test fun compactProgressPauseResumeAndSettingsRemainVisibleOnSmallScreens() {
        val state = mutableStateOf(AutoReviewState(running = true, message = "自动分析 · 第 12/68 步", pendingGames = 8, pendingSteps = 402))
        var resumed = false
        compose.setContent { ChessTheme { Column(Modifier.fillMaxSize()) {
            AutoReviewStatus(state.value, { state.value = state.value.copy(running = false, paused = true, message = "自动分析已暂停") }, { resumed = true })
        } } }
        compose.onNodeWithText("自动分析 · 第 12/68 步").assertIsDisplayed()
        compose.onNodeWithText("后台运行设置").assertIsDisplayed()
        compose.onNodeWithText("暂停").assertIsDisplayed().performClick()
        compose.onNodeWithText("继续分析").assertIsDisplayed().performClick()
        assertTrue(resumed)
    }
    @Test fun completedQueueIsACompactLineAndNoTokenHasAnActionableExplanation() {
        val state = mutableStateOf(AutoReviewState(message = "所有棋谱已完成自动分析"))
        compose.setContent { ChessTheme { AutoReviewStatus(state.value, {}, {}) } }
        compose.onNodeWithText("暂停").assertDoesNotExist()
        compose.onNodeWithText("后台运行设置").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(needsToken = true, message = "自动分析等待云端口令") }
        compose.onNodeWithText("请在对局设置中填写或更新朋友提供的 Access Token。").assertIsDisplayed()
    }
}
