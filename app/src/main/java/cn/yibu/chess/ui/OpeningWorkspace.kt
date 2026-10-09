package cn.yibu.chess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yibu.chess.AppState
import cn.yibu.chess.GameViewModel
import cn.yibu.chess.core.*

@Composable
internal fun TrainingHub(state: AppState, model: GameViewModel) {
    var white by rememberSaveable { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize().testTag("training-hub"), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Text("训练", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("开局课程", "我的训练").forEachIndexed { index, label ->
                    FilterChip(state.trainingSection == index, onClick = feedbackClick { model.trainingSection(index) }, label = { Text(label) })
                }
            }
        }
        if (state.trainingSection == 1) {
            item { PracticeCard(state.practiceQuestions, state.practiceProgress, state.ready && !state.busy && !state.transitioning, model::startPractice) }
            item { WeaknessCard(state.weaknesses, model::openWeakness, enabled = state.ready && !state.transitioning) }
        } else {
            item {
                Text("先理解为什么这样走，再上棋盘试一试。", color = Muted, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(white, onClick = feedbackClick { white = true }, label = { Text("执白 · 5 套") })
                    FilterChip(!white, onClick = feedbackClick { white = false }, label = { Text("执黑 · 5 套") })
                }
            }
            items(OpeningCourses.all.filter { it.humanWhite == white }, key = { it.id }) { course ->
                val learned = course.routes.count { "${course.id}:${it.id}:learn" in state.openingProgress }
                val practiced = course.routes.count { "${course.id}:${it.id}:quiz" in state.openingProgress }
                Surface(color = Panel, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()
                    .testTag("course-${course.id}").clickable(onClick = feedbackClick { model.openCourse(course.id) })) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(course.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("进入 ›", color = Accent, fontSize = 12.sp)
                        }
                        Text(course.goal, fontSize = 13.sp, lineHeight = 21.sp)
                        Text("主线 + 常见分支 · 学过 $learned/2 · 练过 $practiced/2", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            item { Text("课程与练习可离线使用。参考路线不是强制走法，对手改走后先看将军、吃子和威胁。开局陪练不计 Elo。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp) }
        }
    }
}

@Composable
internal fun OpeningWorkspace(session: OpeningSession, thinking: Boolean, ready: Boolean, progress: Set<String>, games: List<GameRecord>,
    onClose: () -> Unit, onRoute: (Int) -> Unit, onSeek: (Int) -> Unit, onQuiz: () -> Unit, onHint: () -> Unit,
    onExplore: () -> Unit, onAnswer: (String) -> Unit, onUndo: () -> Unit, onReply: () -> Unit, onTrain: () -> Unit,
    onExample: (GameRecord, Int) -> Unit) {
    val course = session.course
    val route = session.route
    val history = session.history
    var selected by remember(history, session.key, session.mode) { mutableStateOf<Int?>(null) }
    var promotion by remember(session.key, session.mode) { mutableStateOf<List<String>>(emptyList()) }
    val legal = remember(history) { ChessRules.legal(history) }
    val fen = remember(history) { ChessRules.board(history).fen }
    val targets = legal.filter { it.take(2) == selected?.let(ChessRules::squareName) }.map { ChessRules.squareIndex(it.substring(2, 4)) }.toSet()
    val interactive = !thinking && (session.mode == OpeningMode.FREE || session.mode == OpeningMode.QUIZ && session.solvedMove == null)
    val feedback = LocalSoundFeedback.current
    BoxWithConstraints(Modifier.fillMaxSize().testTag("opening-workspace")) {
        val boardSize = minOf(maxWidth - 8.dp, maxHeight * .36f, 300.dp).coerceAtLeast(120.dp)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(course.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                    Text("执${if (course.humanWhite) "白" else "黑"} · ${when (session.mode) { OpeningMode.LEARN -> "手动学习"; OpeningMode.QUIZ -> "分支练习"; OpeningMode.FREE -> "自由试走" }}", color = Muted, fontSize = 11.sp)
                }
                TextButton(onClick = feedbackClick(onClose), contentPadding = PaddingValues(4.dp)) { Text("返回训练", fontSize = 12.sp) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(course.routes.size) { index ->
                    FilterChip(session.routeIndex == index, onClick = feedbackClick { onRoute(index) }, label = { Text(if (index == 0) "主线" else "常见分支", fontSize = 12.sp) })
                }
            }
            Box(Modifier.align(Alignment.CenterHorizontally).size(boardSize).background(Ink, RoundedCornerShape(15.dp)).padding(4.dp)) {
                ChessBoard(fen, !course.humanWhite, selected, if (interactive) targets else emptySet(), history.lastOrNull(),
                    arrow = if (session.mode == OpeningMode.LEARN) route.moves.getOrNull(session.cursor) else null,
                    animationKey = session.key.hashCode().toLong()) { square ->
                    if (interactive) {
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
            Column(Modifier.weight(1f).fillMaxWidth().background(Soft, RoundedCornerShape(15.dp))
                .verticalScroll(key(session.key, session.mode, history.size) { rememberScrollState() }).padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(route.title, color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                when (session.mode) {
                    OpeningMode.LEARN -> {
                        if (session.cursor == 0) {
                            Text(course.goal, fontSize = 14.sp, lineHeight = 22.sp)
                            Text("接下来做什么", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(course.plan, fontSize = 13.sp, lineHeight = 21.sp)
                            Text("容易踩的坑", color = Danger, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(course.watchOut, fontSize = 13.sp, lineHeight = 21.sp)
                        } else {
                            val side = if (session.cursor % 2 == 1) "白方" else "黑方"
                            Text("第 ${session.cursor}/${route.moves.size} 步 · $side ${ChessRules.san(history.dropLast(1), history.last())}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(route.notes[session.cursor - 1], fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.testTag("opening-note"))
                        }
                        if ("${session.key}:learn" in progress) Text("这条路线已学完，可以尝试分支练习。", color = Accent, fontSize = 12.sp)
                        if (session.cursor == route.moves.size || session.cursor == 0) {
                            games.asSequence().filter { it.source != null && it.humanWhite == course.humanWhite }
                                .mapNotNull { game -> OpeningCourses.match(game)?.takeIf { it.course.id == course.id }?.let { game to it } }
                                .take(3).forEach { (game, match) ->
                                    TextButton(onClick = feedbackClick { onExample(game, match.sharedPlies) }) {
                                        Text("看我的实战：对 ${game.source!!.opponent(game.humanWhite)} · ${match.deviationPly?.let { "第 $it 步开始不同" } ?: "已有相同开局"}", fontSize = 11.sp)
                                    }
                                }
                        }
                    }
                    OpeningMode.QUIZ -> {
                        Text(if (session.solvedMove == null) route.checkpoint.prompt else "答对了：${ChessRules.san(history.dropLast(1), history.last())}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 22.sp)
                        if (session.solvedMove == null) Text("点击棋子，再点击落点。", color = Muted, fontSize = 12.sp)
                        if (session.hint && session.solvedMove == null) Text(route.checkpoint.hint, color = Accent, fontSize = 13.sp, lineHeight = 21.sp)
                        if (session.message.isNotBlank()) Text(session.message, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.testTag("opening-answer"))
                    }
                    OpeningMode.FREE -> {
                        Text(if (history.size % 2 == 0) "现在白方走" else "现在黑方走", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("可替双方试走。轮到对手时，可让离线 Maia 回应一着；想连续对局，点‘从这里陪练’。", fontSize = 12.sp, lineHeight = 20.sp)
                        ChessRules.outcome(history)?.let { Text("局面已结束：$it", color = Accent, fontSize = 12.sp) }
                        if (session.message.isNotBlank()) Text(session.message, color = Accent, fontSize = 12.sp, lineHeight = 20.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when (session.mode) {
                    OpeningMode.LEARN -> {
                        TextButton(onClick = feedbackClick { onSeek(session.cursor - 1) }, enabled = session.cursor > 0, modifier = Modifier.weight(1f)) { Text("上一步", fontSize = 12.sp) }
                        FilledTonalButton(onClick = feedbackClick { onSeek(session.cursor + 1) }, enabled = session.cursor < route.moves.size, modifier = Modifier.weight(1f)) { Text("下一步", fontSize = 12.sp) }
                    }
                    OpeningMode.QUIZ -> {
                        TextButton(onClick = feedbackClick(if (session.solvedMove == null) onHint else onQuiz), modifier = Modifier.weight(1f)) { Text(if (session.solvedMove == null) "提示" else "再练一次", fontSize = 12.sp) }
                        TextButton(onClick = feedbackClick { onSeek(session.cursor) }, modifier = Modifier.weight(1f)) { Text("回到课程", fontSize = 12.sp) }
                    }
                    OpeningMode.FREE -> {
                        TextButton(onClick = feedbackClick(onUndo), enabled = !thinking && history.size > session.cursor, modifier = Modifier.weight(1f)) { Text("撤回", fontSize = 12.sp) }
                        TextButton(onClick = feedbackClick(onReply), enabled = ready && !thinking && (history.size % 2 == 0) != course.humanWhite && ChessRules.outcome(history) == null, modifier = Modifier.weight(1f)) { Text(if (thinking) "思考中…" else "Maia 走一着", fontSize = 12.sp) }
                        TextButton(onClick = feedbackClick { onSeek(session.cursor) }, modifier = Modifier.weight(1f)) { Text("回到课程", fontSize = 12.sp) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (session.mode == OpeningMode.LEARN) TextButton(onClick = feedbackClick(onQuiz), modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("分支练习", fontSize = 12.sp) }
                if (session.mode != OpeningMode.FREE) TextButton(onClick = feedbackClick(onExplore), modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("自由试走", fontSize = 12.sp) }
                TextButton(onClick = feedbackClick(onTrain), enabled = ready && !thinking && history.isNotEmpty() && ChessRules.outcome(history) == null,
                    modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("从这里陪练", fontSize = 12.sp) }
            }
        }
    }
    if (promotion.isNotEmpty()) AlertDialog(onDismissRequest = { promotion = emptyList() }, title = { Text("选择升变棋子") }, text = {
        Column { listOf('q' to "后", 'r' to "车", 'b' to "象", 'n' to "马").forEach { (piece, title) ->
            TextButton(onClick = feedbackClick { promotion.firstOrNull { it.last() == piece }?.let(onAnswer); promotion = emptyList() }) { Text(title) }
        } }
    }, confirmButton = {})
}
