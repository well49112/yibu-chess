package cn.yibu.chess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import cn.yibu.chess.core.*

/** A manual tour: only an explicit button press changes the board or teaching point. */
@Composable
internal fun HighlightsWorkspace(game: GameRecord, highlights: List<ReviewHighlight>, flipped: Boolean,
    onClose: () -> Unit, onFlip: () -> Unit) {
    if (highlights.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp).testTag("highlights-empty"),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("本局暂无值得单独讲解的关键点", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Text("只挑选你已确认的失误和有具体学习价值的好棋，普通换子不会凑进来。", color = Muted, lineHeight = 24.sp)
            Spacer(Modifier.height(24.dp))
            FilledTonalButton(onClick = feedbackClick(onClose)) { Text("查看逐步复盘") }
        }
        return
    }
    var point by remember(game.id, highlights) { mutableIntStateOf(0) }
    var frame by remember(game.id, highlights) { mutableIntStateOf(0) }
    var complete by remember(game.id, highlights) { mutableStateOf(false) }
    val feedback = LocalSoundFeedback.current
    val reviewSound = LocalReviewSound.current
    val lines = remember(game.moves, highlights) { highlights.map { chosen ->
        ChessRules.legalVariation(game.moves.take(chosen.ply - 1),
            chosen.lesson?.variation.orEmpty().take(MoveCoach.MAX_VARIATION_PLIES))
    } }
    fun lastFrame(index: Int): Int = if (lines[index].isEmpty()) 1 else lines[index].size + 2
    fun historyAt(index: Int, stage: Int): List<String> {
        val chosen = highlights[index]
        val root = game.moves.take(chosen.ply - 1)
        return when {
            stage == 1 -> root + game.moves[chosen.ply - 1]
            stage >= 3 -> root + lines[index].take(stage - 2)
            else -> root
        }
    }
    fun seek(index: Int, stage: Int) {
        reviewSound(historyAt(point, frame), historyAt(index, stage))
        point = index; frame = stage; complete = false
    }
    val highlight = highlights[point]
    val root = remember(game.moves, highlight.ply) { game.moves.take(highlight.ply - 1) }
    val line = lines[point]
    val steps = remember(root, line, highlight.lesson) {
        highlight.lesson?.steps?.takeIf { it.map(LessonStep::uci) == line } ?: MoveCoach.annotatedSteps(root, line)
    }
    val history = historyAt(point, frame)
    val fen = remember(history) { ChessRules.board(history).fen }
    val step = steps.getOrNull(frame - 3)
    val actor = if (highlight.ply % 2 == 1) "白方" else "黑方"
    val san = remember(game.id, highlight.ply, game.moves) { ChessRules.san(root, game.moves[highlight.ply - 1]) }
    val label = when {
        frame == 0 -> "第 ${highlight.ply} 步之前"
        frame == 1 -> "实战 · $actor $san"
        frame == 2 -> "回到起点 · 看推荐走法"
        else -> "推荐路线 ${frame - 2} / ${line.size}"
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val boardSize = minOf(maxWidth - 8.dp, maxHeight * .43f, 340.dp).coerceAtLeast(140.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (complete) "复盘完成" else "本局关键点", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("${point + 1} / ${highlights.size} · ${highlight.title}", color = Muted, fontSize = 13.sp,
                        modifier = Modifier.testTag("highlight-title"))
                }
                TextButton(onClick = feedbackClick(onClose)) { Text("逐步复盘") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                highlights.forEachIndexed { index, _ ->
                    LinearProgressIndicator(progress = { if (index <= point) 1f else 0f },
                        modifier = Modifier.weight(1f).height(5.dp), color = Accent, trackColor = Soft)
                }
            }
            Box(Modifier.align(Alignment.CenterHorizontally).size(boardSize)
                .background(Ink, RoundedCornerShape(15.dp)).padding(4.dp).testTag("highlight-board")) {
                ChessBoard(fen, flipped, null, emptySet(), if (frame == 0 || frame == 2) null else history.lastOrNull(),
                    arrow = if (frame == 2) line.firstOrNull() else null,
                    animationKey = game.id + highlight.ply) {}
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).testTag("highlight-position"))
                IconAction(ChessIcon.FLIP, "翻转棋盘", onFlip)
            }
            Column(Modifier.weight(1f).fillMaxWidth().background(Soft, RoundedCornerShape(16.dp))
                .verticalScroll(key(point, frame) { rememberScrollState() }).padding(14.dp).testTag("highlight-notes"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    frame == 0 -> highlight.reason
                    frame == 1 -> highlight.lesson?.playedExplanation?.takeIf { it.isNotBlank() } ?: highlight.reason
                    frame == 2 -> highlight.lesson?.why.orEmpty()
                    else -> step?.title.orEmpty()
                }, fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("highlight-explanation"))
                if (frame >= 3) Text(step?.explanation.orEmpty(), fontSize = 14.sp, lineHeight = 22.sp)
                if (frame >= 2) Text(if (frame == lastFrame(point))
                    "本条参考路线到此；搜索结束不等于计划已完成，对手改变走法时需要重新判断。"
                    else "手动查看每一着及应对；对手改变走法时，需要重新判断。", color = Muted, fontSize = 11.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = feedbackClick { seek(point - 1, 0) }, enabled = point > 0) { Text("上个点", fontSize = 12.sp) }
                TextButton(onClick = feedbackClick {
                    if (frame > 0) seek(point, frame - 1) else seek(point - 1, lastFrame(point - 1))
                }, enabled = point > 0 || frame > 0) { Text("上一步", fontSize = 12.sp) }
                FilledTonalButton(onClick = feedbackClick {
                    when {
                        complete -> seek(0, 0)
                        frame < lastFrame(point) -> seek(point, frame + 1)
                        point < highlights.lastIndex -> seek(point + 1, 0)
                        else -> { complete = true; feedback(SoundCue.CONFIRM) }
                    }
                }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(if (complete) "重看" else if (point == highlights.lastIndex && frame == lastFrame(point)) "完成" else "下一步", fontSize = 13.sp)
                }
                TextButton(onClick = feedbackClick { seek(point + 1, 0) }, enabled = point < highlights.lastIndex) { Text("下个点", fontSize = 12.sp) }
            }
        }
    }
}
