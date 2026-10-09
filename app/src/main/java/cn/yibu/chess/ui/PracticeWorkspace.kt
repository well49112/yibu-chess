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

@Composable
internal fun PracticeCard(questions: List<PracticeQuestion>, progress: Map<String, PracticeProgress>,
    enabled: Boolean, onStart: () -> Unit) {
    val now = System.currentTimeMillis()
    val due = questions.count { MistakePractice.effective(it, progress).nextDueAt <= now }
    val correct = questions.sumOf { MistakePractice.effective(it, progress).independentCorrect }
    Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("practice-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("我的错题练习", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("${questions.size} 道错题 · $due 道到期 · 独立答对 $correct 次", color = Muted, fontSize = 12.sp)
            Text(if (questions.isEmpty()) "完成复盘后，把有明确战术证据的失误变成练习题。"
                else "从最近 20 盘已结束的保留棋谱出题。先自己找走法，需要时逐级提示；练习不改变 Elo。", fontSize = 12.sp, lineHeight = 19.sp)
            FilledTonalButton(onClick = feedbackClick(onStart), enabled = enabled && questions.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (due > 0) "练习到期错题" else "重练已有错题")
            }
            if (questions.isNotEmpty() && due == 0) Text("已安排后续复习，也可以主动重练。每轮最多 10 题。", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
internal fun PracticeWorkspace(session: PracticeSession, onClose: () -> Unit, onAnswer: (String) -> Unit,
    onHint: () -> Unit, onReveal: () -> Unit, onNext: () -> Unit, onExplain: () -> Unit) {
    val question = session.current
    if (question == null) {
        Column(Modifier.fillMaxSize().padding(20.dp).testTag("practice-complete"),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("本轮练习完成", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("独立答对 ${session.independent} 题 · 辅助完成 ${session.assisted} 题", modifier = Modifier.padding(vertical = 16.dp))
            Text("独立答对后逐步间隔 1、3、7、14 天；用提示、看答案或多次尝试的题十分钟后再练。", color = Muted, lineHeight = 22.sp)
            TextButton(onClick = feedbackClick(onClose)) { Text("返回训练") }
        }
        return
    }
    var selected by remember(question.key, session.finished) { mutableStateOf<Int?>(null) }
    val feedback = LocalSoundFeedback.current
    var promotion by remember(question.key) { mutableStateOf<List<String>>(emptyList()) }
    val legal = remember(question) { ChessRules.legal(question.history) }
    val targets = remember(selected, legal) { legal.filter { it.take(2) == selected?.let(ChessRules::squareName) }
        .map { ChessRules.squareIndex(it.substring(2, 4)) }.toSet() }
    val history = question.history + listOfNotNull(session.solvedMove)
    val solvedMove = session.solvedMove
    val fen = remember(history) { ChessRules.board(history).fen }
    val wrongMove = remember(question) { PracticeCoach.wrongMove(question) }
    val explanation = remember(question, session.finished, session.solvedMove) {
        if (session.finished) PracticeCoach.explain(question, session.solvedMove ?: question.bestMove) else null
    }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("practice-workspace")) {
        val boardSize = minOf(maxWidth - 8.dp, maxHeight * .48f, 340.dp).coerceAtLeast(140.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("错题练习 ${session.index + 1} / ${session.questions.size}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("你执${if (question.humanWhite) "白" else "黑"} · 回到第 ${question.ply} 步之前", color = Muted, fontSize = 12.sp)
                }
                TextButton(onClick = feedbackClick(onClose)) { Text("返回训练", fontSize = 12.sp) }
            }
            Box(Modifier.align(Alignment.CenterHorizontally).size(boardSize).background(Ink, RoundedCornerShape(15.dp)).padding(4.dp)) {
                ChessBoard(fen, !question.humanWhite, selected, if (session.finished) emptySet() else targets,
                    session.solvedMove, arrow = if (session.revealed) question.bestMove else null,
                    animationKey = question.gameId + question.ply) { square ->
                    if (!session.finished) {
                        val choices = legal.filter { it.take(2) == selected?.let(ChessRules::squareName) && it.substring(2, 4) == ChessRules.squareName(square) }
                        when {
                            choices.size > 1 -> { promotion = choices; selected = null }
                            choices.size == 1 -> { onAnswer(choices.first()); selected = null }
                            legal.any { it.take(2) == ChessRules.squareName(square) } -> { feedback(SoundCue.SELECT); selected = if (selected == square) null else square }
                            else -> { if (selected != null) feedback(SoundCue.ILLEGAL); selected = null }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().background(Soft, RoundedCornerShape(16.dp))
                .verticalScroll(key(question.key, session.finished) { rememberScrollState() }).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("你当时的错误走法：$wrongMove", color = Danger, fontSize = 13.sp, lineHeight = 21.sp,
                    modifier = Modifier.testTag("practice-wrong-move"))
                Text(if (!session.finished) "找一着比实战更好的走法。点击棋子，再点击落点。" else session.message,
                    fontSize = 14.sp, lineHeight = 22.sp, modifier = Modifier.testTag("practice-prompt"))
                if (!session.finished && session.message.isNotBlank()) Text(session.message, color = Danger, fontSize = 13.sp, lineHeight = 20.sp)
                if (session.hints > 0 && !session.finished) Text(MistakePractice.hint(question, session.hints), color = Accent,
                    fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("practice-hint"))
                if (session.finished) {
                    Text(if (solvedMove != null) "你答的是 ${ChessRules.san(question.history, solvedMove)}"
                        else "推荐 ${ChessRules.san(question.history, question.bestMove)}", color = Accent, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.testTag("practice-answer"))
                    Text("原走法为什么不好", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(explanation!!.mistake, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.testTag("practice-mistake-reason"))
                    Text("这着为什么更好", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(explanation.improvement, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.testTag("practice-improvement"))
                    if (session.solvedMove != null && session.solvedMove != question.bestMove)
                        Text("你找到的是同深度、评分接近的另一候选。上面解释的是你这着；引擎首选是 ${ChessRules.san(question.history, question.bestMove)}。", color = Muted, fontSize = 11.sp)
                    Text("后续参考应对", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    explanation.continuation.forEachIndexed { index, step ->
                        Text("${index + 1}. ${step.title}\n${step.explanation}", fontSize = 12.sp, lineHeight = 20.sp)
                    }
                    Text("以上为已保存的参考变化，对手可以改走。点击对照讲解，用棋盘逐步比较完整路线。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!session.finished) {
                    OutlinedButton(onClick = feedbackClick(onHint), enabled = session.hints < 2, modifier = Modifier.weight(1f)) {
                        Text(if (session.hints == 0) "给点提示" else "再提示一步", fontSize = 12.sp)
                    }
                    TextButton(onClick = feedbackClick(onReveal), modifier = Modifier.weight(1f)) { Text("查看答案", fontSize = 12.sp) }
                } else {
                    OutlinedButton(onClick = feedbackClick(onExplain), modifier = Modifier.weight(1f)) { Text("对照讲解", fontSize = 12.sp) }
                    FilledTonalButton(onClick = feedbackClick(onNext), modifier = Modifier.weight(1f)) {
                        Text(if (session.index == session.questions.lastIndex) "完成" else "下一题", fontSize = 12.sp)
                    }
                }
            }
        }
    }
    if (promotion.isNotEmpty()) AlertDialog(onDismissRequest = { promotion = emptyList() }, title = { Text("选择升变棋子") }, text = {
        Column { listOf('q' to "后", 'r' to "车", 'b' to "象", 'n' to "马").forEach { (piece, title) ->
            TextButton(onClick = feedbackClick { promotion.find { it.last() == piece }?.let(onAnswer); promotion = emptyList() }) { Text(title) }
        } }
    }, confirmButton = {})
}
