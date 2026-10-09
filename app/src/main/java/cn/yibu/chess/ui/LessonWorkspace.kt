package cn.yibu.chess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yibu.chess.AppState
import cn.yibu.chess.core.ChessRules
import cn.yibu.chess.core.MoveCoach
import cn.yibu.chess.core.LessonStep

/** Board and controls stay visible; only the explanation area scrolls. */
@Composable
internal fun LessonWorkspace(
    state: AppState, flipped: Boolean, onClose: () -> Unit, onFlip: () -> Unit,
    onSeek: (Int) -> Unit, onRetry: () -> Unit, onPause: () -> Unit,
    onRoute: (Boolean) -> Unit = {},
) {
    val lesson = state.chosenLesson
    val history = remember(state.game.moves, state.cursor) { state.game.moves.take((state.cursor - 1).coerceAtLeast(0)) }
    val line = remember(history, state.variation) { ChessRules.legalVariation(history, state.variation) }
    val steps = remember(lesson, history, state.lessonPlayed, line) {
        if (lesson == null) emptyList() else {
            val saved = if (state.lessonPlayed) lesson.playedSteps else lesson.steps
            if (saved.map(LessonStep::uci) == line) saved else MoveCoach.annotatedSteps(history, line)
        }
    }
    val fen = remember(state.boardHistory) { ChessRules.board(state.boardHistory).fen }
    var tab by remember(state.game.id, state.cursor) { mutableIntStateOf(0) }
    val notesScroll = rememberScrollState()
    LaunchedEffect(state.variationStep, state.lessonPlayed) {
        tab = if (state.variationStep == 0) 0 else 1
        notesScroll.scrollTo(0)
    }
    LaunchedEffect(tab) { notesScroll.scrollTo(0) }
    fun seek(index: Int) { onSeek(index) }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("lesson-workspace")) {
        val boardSize = minOf(maxWidth - 8.dp, maxHeight * .35f, 340.dp).coerceAtLeast(140.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("讲解第 ${state.cursor} 步", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (lesson == null) "走法原因与后续思路" else
                        "${if (state.lessonPlayed) "实战后的应对" else "推荐走法"} · 深度 ${lesson.depth}",
                        color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = feedbackClick { onClose() }) { Text(if (state.practice != null) "返回练习" else "返回复盘", fontSize = 12.sp) }
                IconAction(ChessIcon.FLIP, "翻转讲解棋盘", onFlip)
            }
            if (lesson != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.lessonPlayed, onClick = feedbackClick { onRoute(true) }, modifier = Modifier.weight(1f),
                    label = { Text("实战线", fontSize = 12.sp) })
                FilterChip(selected = !state.lessonPlayed, onClick = feedbackClick { onRoute(false) }, modifier = Modifier.weight(1f),
                    label = { Text(if (lesson.recommendedMove == state.game.moves.getOrNull(state.cursor - 1)) "推荐线 · 首着一致" else "推荐线", fontSize = 12.sp) })
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.width(boardSize).background(Ink, RoundedCornerShape(14.dp)).padding(4.dp)
                    .testTag("lesson-board")) {
                    ChessBoard(fen, flipped = flipped, selected = null, targets = emptySet(), lastMove = state.boardHistory.lastOrNull(), animationKey = state.game.id,
                        arrow = line.getOrNull(state.variationStep)) {}
                }
            }
            LazyRow(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(selected = state.variationStep == 0, onClick = feedbackClick { seek(0) }, label = { Text("起点", fontSize = 11.sp) })
                }
                itemsIndexed(steps) { index, step ->
                    FilterChip(selected = state.variationStep == index + 1, onClick = feedbackClick { seek(index + 1) },
                        label = { Text("${index + 1}. ${ChessRules.san(history + line.take(index), step.uci)}", fontSize = 11.sp) })
                }
            }
            Surface(Modifier.fillMaxWidth().weight(1f), color = Panel, shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        listOf("为什么这样走", "后续思路", "全文").forEachIndexed { index, text ->
                            TextButton(onClick = feedbackClick { tab = index }, modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 2.dp)) {
                                Text(text, fontSize = 12.sp, fontWeight = if (tab == index) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (tab == index) Accent else Muted)
                            }
                        }
                    }
                    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(notesScroll).testTag("lesson-notes"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (lesson == null) {
                            if (state.busy) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp)); Text("正在生成这一步的讲解…", fontSize = 13.sp)
                                }
                                Text("棋盘停在落子前，完成后可以逐步演示。", color = Muted, fontSize = 12.sp)
                                TextButton(onClick = feedbackClick(onPause)) { Text("暂停") }
                            } else {
                                state.error?.let { Text(it, color = Danger, fontSize = 12.sp) }
                                FilledTonalButton(onClick = feedbackClick(onRetry), enabled = state.ready) { Text("继续生成讲解") }
                            }
                        } else when (tab) {
                            0 -> {
                                Text(if (state.lessonPlayed) lesson.playedExplanation else lesson.why,
                                    modifier = Modifier.testTag("lesson-why"), fontSize = 13.sp, lineHeight = 20.sp)
                                if (state.lessonPlayed && steps.size <= 1)
                                    Text("只展示实际落子；引擎未提供合法的后续应对，不能用推荐线代替。", color = Muted, fontSize = 11.sp)
                                Text("点“下一步”，在棋盘上查看后续应对。", color = Muted, fontSize = 11.sp)
                                Text("高亮刚走的一着，箭头提示下一着参考应对。", color = Muted, fontSize = 11.sp)
                            }
                            1 -> {
                                val index = (state.variationStep - 1).coerceAtLeast(0)
                                steps.getOrNull(index)?.let { step ->
                                    Text("${index + 1} / ${steps.size} · ${step.title}", modifier = Modifier.testTag("lesson-step-title"), color = Accent,
                                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                    Text(step.explanation, modifier = Modifier.testTag("lesson-step-explanation"), fontSize = 13.sp, lineHeight = 20.sp)
                                }
                                if (state.variationStep == 0) Text("棋盘是落子前的位置，点“下一步”演示这着棋。", color = Muted, fontSize = 11.sp)
                                Text("${if (state.lessonPlayed) "实战后的引擎应对" else "推荐后的引擎应对"}是参考变化，并非之后实际下出的棋谱。切换路线会回到同一个起点。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
                            }
                            else -> {
                                Text("为什么这样走", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(lesson.why, modifier = Modifier.testTag("lesson-why"), fontSize = 13.sp, lineHeight = 20.sp)
                                Text("后续思路", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(lesson.plan, modifier = Modifier.testTag("lesson-plan"), fontSize = 13.sp, lineHeight = 20.sp)
                                Text("实战线与后续应对", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(lesson.playedExplanation, fontSize = 13.sp, lineHeight = 20.sp)
                                lesson.playedSteps.forEachIndexed { index, step ->
                                    Text("${index + 1}. ${step.title}。${step.explanation}", fontSize = 13.sp, lineHeight = 20.sp)
                                }
                            }
                        }
                    }
                }
            }
            Surface(color = Soft, shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ready = lesson != null && state.variation.isNotEmpty()
                    TextButton(onClick = feedbackClick { seek(0) }, modifier = Modifier.weight(1f), enabled = ready) { Text("起始", fontSize = 11.sp) }
                    TextButton(onClick = feedbackClick { seek(state.variationStep - 1) }, modifier = Modifier.weight(1f),
                        enabled = ready && state.variationStep > 0) { Text("上一步", fontSize = 11.sp) }
                    TextButton(onClick = feedbackClick { seek(state.variationStep + 1) }, modifier = Modifier.weight(1f),
                        enabled = ready && state.variationStep < state.variation.size) { Text("下一步", fontSize = 11.sp) }
                    TextButton(onClick = feedbackClick { seek(state.variation.size) }, modifier = Modifier.weight(1f), enabled = ready) { Text("末尾", fontSize = 11.sp) }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}
