package cn.yibu.chess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                Text("一小步一小步练：先走出来，再理解原因。", color = Muted, fontSize = 12.sp)
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
                        Text("${course.routes.size} 条路线 · 学过 $learned/${course.routes.size} · 练过 $practiced/${course.routes.size}", color = Muted, fontSize = 11.sp)
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
    onExample: (GameRecord, Int) -> Unit, onContinue: () -> Unit = {}, onGuided: () -> Unit = {}, onShowMove: () -> Unit = {}) {
    val course = session.course
    val route = session.route
    val history = session.history
    var selected by remember(history, session.key, session.mode) { mutableStateOf<Int?>(null) }
    var promotion by remember(session.key, session.mode, history) { mutableStateOf<List<String>>(emptyList()) }
    var routesOpen by remember { mutableStateOf(false) }
    var toolsOpen by remember { mutableStateOf(false) }
    val legal = remember(history) { ChessRules.legal(history) }
    val fen = remember(history) { ChessRules.board(history).fen }
    val targets = legal.filter { it.take(2) == selected?.let(ChessRules::squareName) }.map { ChessRules.squareIndex(it.substring(2, 4)) }.toSet()
    val guideTask = session.mode == OpeningMode.GUIDE && session.lessonPhase == OpeningLessonPhase.TASK
    val interactive = !thinking && (session.mode == OpeningMode.FREE || guideTask || session.mode == OpeningMode.QUIZ && session.solvedMove == null)
    val feedback = LocalSoundFeedback.current
    Column(Modifier.fillMaxSize().testTag("opening-workspace"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(course.title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                Text(route.title, color = Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = feedbackClick { routesOpen = true }, contentPadding = PaddingValues(6.dp), modifier = Modifier.testTag("opening-routes")) {
                Text("路线 ${session.routeIndex + 1}/${course.routes.size} ▾", fontSize = 12.sp)
            }
            TextButton(onClick = feedbackClick(onClose), contentPadding = PaddingValues(4.dp)) { Text("返回", fontSize = 12.sp) }
        }
        if (session.mode == OpeningMode.GUIDE) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (session.retrying && session.lessonPhase != OpeningLessonPhase.DONE) "再练 ${session.lessonStep + 1}/${session.lessonDeck.size}"
                    else "目标 ${session.lessonDone}/${session.lessonPlies.size}", color = Accent, fontSize = 11.sp)
                LinearProgressIndicator(progress = { session.lessonDone.toFloat() / session.lessonPlies.size }, modifier = Modifier.weight(1f), color = Accent, trackColor = Soft)
                Text("执${if (course.humanWhite) "白" else "黑"}", color = Muted, fontSize = 11.sp)
            }
        }
        // Fixed full-width board. Neither the board nor the coach pane is a scroll container.
        Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Ink, RoundedCornerShape(15.dp)).testTag("opening-board-frame").padding(4.dp)) {
            ChessBoard(fen, !course.humanWhite, selected, if (interactive) targets else emptySet(), history.lastOrNull(),
                arrow = when {
                    session.mode == OpeningMode.LEARN -> route.moves.getOrNull(session.cursor)
                    guideTask && session.hintLevel >= 2 -> route.moves[session.lessonPly]
                    else -> null
                }, animationKey = session.key.hashCode().toLong()) { square ->
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
        OpeningCoachCard(session, Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (session.mode) {
                OpeningMode.GUIDE -> when (session.lessonPhase) {
                    OpeningLessonPhase.INTRO -> Button(onClick = feedbackClick(onContinue), modifier = Modifier.weight(1f).testTag("opening-continue")) { Text("开始这一课") }
                    OpeningLessonPhase.TASK -> {
                        OutlinedButton(onClick = feedbackClick(onHint), enabled = session.hintLevel < 2, modifier = Modifier.weight(1f)) { Text(if (session.hintLevel == 0) "给我提示" else if (session.hintLevel == 1) "再提示一点" else "提示已显示", fontSize = 12.sp) }
                        TextButton(onClick = feedbackClick(onShowMove), modifier = Modifier.weight(1f)) { Text("看示范", fontSize = 12.sp) }
                    }
                    OpeningLessonPhase.FEEDBACK -> Button(onClick = feedbackClick(onContinue), modifier = Modifier.weight(1f).testTag("opening-continue")) { Text(if (session.lessonStep == session.lessonDeck.lastIndex && !session.retrying && session.retryPlies.isNotEmpty()) "开始再练" else "继续") }
                    OpeningLessonPhase.DONE -> {
                        OutlinedButton(onClick = feedbackClick(onGuided), modifier = Modifier.weight(1f)) { Text("再练一次", fontSize = 12.sp) }
                        Button(onClick = feedbackClick { onRoute((session.routeIndex + 1) % course.routes.size) }, modifier = Modifier.weight(1f)) { Text("换一条路线", fontSize = 12.sp) }
                    }
                }
                OpeningMode.LEARN -> {
                    TextButton(onClick = feedbackClick { onSeek(session.cursor - 1) }, enabled = session.cursor > 0, modifier = Modifier.weight(1f)) { Text("上一步", fontSize = 12.sp) }
                    FilledTonalButton(onClick = feedbackClick { onSeek(session.cursor + 1) }, enabled = session.cursor < route.moves.size, modifier = Modifier.weight(1f)) { Text("下一步", fontSize = 12.sp) }
                    TextButton(onClick = feedbackClick(onQuiz)) { Text("分支练习", fontSize = 12.sp) }
                }
                OpeningMode.QUIZ -> {
                    TextButton(onClick = feedbackClick(if (session.solvedMove == null) onHint else onQuiz), modifier = Modifier.weight(1f)) { Text(if (session.solvedMove == null) "提示" else "再练一次", fontSize = 12.sp) }
                    TextButton(onClick = feedbackClick { onSeek(history.size) }, modifier = Modifier.weight(1f)) { Text("回到课程", fontSize = 12.sp) }
                }
                OpeningMode.FREE -> {
                    TextButton(onClick = feedbackClick(onUndo), enabled = !thinking && history.size > session.cursor, modifier = Modifier.weight(1f)) { Text("撤回", fontSize = 12.sp) }
                    TextButton(onClick = feedbackClick(onReply), enabled = ready && !thinking && (history.size % 2 == 0) != course.humanWhite && ChessRules.outcome(history) == null, modifier = Modifier.weight(1f)) { Text(if (thinking) "思考中…" else "Maia 走一着", fontSize = 12.sp) }
                    TextButton(onClick = feedbackClick { onSeek(session.cursor) }) { Text("回到课程", fontSize = 12.sp) }
                }
            }
            TextButton(onClick = feedbackClick { toolsOpen = true }, contentPadding = PaddingValues(4.dp), modifier = Modifier.testTag("opening-tools")) { Text("更多", fontSize = 12.sp) }
        }
    }
    if (routesOpen) AlertDialog(onDismissRequest = { routesOpen = false }, title = { Text("选一条路线") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            course.routes.forEachIndexed { index, item ->
                OutlinedButton(onClick = feedbackClick { routesOpen = false; onRoute(index) }, modifier = Modifier.fillMaxWidth().testTag("opening-route-$index")) {
                    Column(Modifier.fillMaxWidth()) {
                        Text(item.title, fontSize = 13.sp)
                        Text(if ("${course.id}:${item.id}:guided" in progress) "互动课已完成" else if ("${course.id}:${item.id}:learn" in progress) "已读过 · 可再互动练习" else "${if (index == 0) "主线" else "常见分支"} · ${item.moves.size / 2} 回合", color = Muted, fontSize = 11.sp)
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { routesOpen = false }) { Text("取消") } })
    if (toolsOpen) AlertDialog(onDismissRequest = { toolsOpen = false }, title = { Text("这条路线还能怎样练") }, text = {
        Column {
            if (session.mode != OpeningMode.GUIDE) TextButton(onClick = feedbackClick { toolsOpen = false; onGuided() }) { Text("互动学习") }
            TextButton(onClick = feedbackClick { toolsOpen = false; onSeek(history.size) }) { Text("逐步讲解") }
            if (session.mode != OpeningMode.FREE) TextButton(onClick = feedbackClick { toolsOpen = false; onExplore() }) { Text("自由试走") }
            TextButton(onClick = feedbackClick { toolsOpen = false; onTrain() }, enabled = ready && !thinking && history.isNotEmpty() && ChessRules.outcome(history) == null) { Text("从这里陪练") }
            games.asSequence().filter { it.source != null && it.humanWhite == course.humanWhite }
                .mapNotNull { game -> OpeningCourses.match(game)?.takeIf { it.course.id == course.id }?.let { game to it } }
                .take(3).forEach { (game, match) ->
                    TextButton(onClick = feedbackClick { toolsOpen = false; onExample(game, match.sharedPlies) }) {
                        Text("看我的实战：对 ${game.source!!.opponent(game.humanWhite)}", fontSize = 12.sp)
                    }
                }
        }
    }, confirmButton = { TextButton(onClick = { toolsOpen = false }) { Text("取消") } })
    if (promotion.isNotEmpty()) AlertDialog(onDismissRequest = { promotion = emptyList() }, title = { Text("选择升变棋子") }, text = {
        Column { listOf('q' to "后", 'r' to "车", 'b' to "象", 'n' to "马").forEach { (piece, title) ->
            TextButton(onClick = feedbackClick { promotion.firstOrNull { it.last() == piece }?.let(onAnswer); promotion = emptyList() }) { Text(title) }
        } }
    }, confirmButton = {})
}

private data class CoachLine(val text: String, val tag: String? = null, val strong: Boolean = false, val muted: Boolean = false)

/** Remove repeated assignment prose, without changing the original recorded answer or evaluation. */
private fun retryText(message: String): String = if (message.contains("这着合法") || message.contains("是合法走法"))
    message.substringBefore("。").substringBefore(" 是合法走法") + "。这着合法，本关先完成上面的目标，再试试。" else message

@Composable
private fun OpeningCoachCard(session: OpeningSession, modifier: Modifier) {
    val course = session.course
    val route = session.route
    val history = session.history
    val lines = remember(session) { buildList {
        when (session.mode) {
            OpeningMode.GUIDE -> when (session.lessonPhase) {
                OpeningLessonPhase.INTRO -> {
                    add(CoachLine("这节课，亲手完成 ${session.lessonPlies.size} 个目标", strong = true))
                    add(CoachLine(course.goal))
                    add(CoachLine("走完再看原因，点继续才推进；需要帮助的目标会在课末再练。", muted = true))
                }
                OpeningLessonPhase.TASK -> {
                    add(CoachLine(session.lessonTask.prompt, "opening-task", strong = true))
                    if (session.message.isNotBlank()) add(CoachLine(retryText(session.message), "opening-answer"))
                    if (session.hintLevel > 0) add(CoachLine(if (session.hintLevel == 1) session.lessonTask.hint
                        else "提示：${ChessRules.san(history, route.moves[session.lessonPly])}，按棋盘箭头走。"))
                    if (session.message.isBlank() && session.hintLevel == 0 && session.lessonPly > 0) {
                        val before = route.moves.take(session.lessonPly - 1)
                        add(CoachLine("对手刚走 ${ChessRules.san(before, route.moves[session.lessonPly - 1])}：${route.notes[session.lessonPly - 1]}", muted = true))
                    }
                }
                OpeningLessonPhase.FEEDBACK -> {
                    add(CoachLine("做到了 · ${ChessRules.san(history.dropLast(1), history.last())}", strong = true))
                    add(CoachLine(session.message, "opening-answer"))
                }
                OpeningLessonPhase.DONE -> {
                    add(CoachLine("完成本课 · 首次独立 ${session.firstTry}/${session.lessonPlies.size}", "opening-complete", strong = true))
                    add(CoachLine("路线重点：${route.notes[route.checkpoint.atPly]}"))
                    add(CoachLine("当前局面：${route.notes.last()}"))
                    add(CoachLine("记住：${course.watchOut}", muted = true))
                    if (session.retrying) add(CoachLine("需要帮助的目标已再练一遍。", muted = true))
                }
            }
            OpeningMode.LEARN -> {
                if (session.cursor == 0) {
                    add(CoachLine(course.goal, strong = true))
                    add(CoachLine("计划：${course.plan}"))
                    add(CoachLine("留意：${course.watchOut}", muted = true))
                } else {
                    add(CoachLine("第 ${session.cursor}/${route.moves.size} 步 · ${ChessRules.san(history.dropLast(1), history.last())}", strong = true))
                    add(CoachLine(route.notes[session.cursor - 1], "opening-note"))
                }
            }
            OpeningMode.QUIZ -> {
                add(CoachLine(if (session.solvedMove == null) route.checkpoint.prompt else "答对了：${ChessRules.san(history.dropLast(1), history.last())}", strong = true))
                if (session.hint && session.solvedMove == null) add(CoachLine(route.checkpoint.hint))
                if (session.message.isNotBlank()) add(CoachLine(if (session.solvedMove == null) retryText(session.message) else session.message, "opening-answer"))
            }
            OpeningMode.FREE -> {
                add(CoachLine(if (history.size % 2 == 0) "现在白方走" else "现在黑方走", strong = true))
                add(CoachLine("可替双方试走；让 Maia 回应，或从这里开始不计分陪练。", muted = true))
                ChessRules.outcome(history)?.let { add(CoachLine("局面已结束：$it")) }
                if (session.message.isNotBlank()) add(CoachLine(session.message))
            }
        }
    } }
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val baseStyle = LocalTextStyle.current
        val textWidth = with(density) { (maxWidth - 24.dp).roundToPx() }.coerceAtLeast(1)
        val textHeight = with(density) { (maxHeight - 16.dp).roundToPx() }
        val gap = with(density) { 4.dp.roundToPx() }
        fun style(size: Int, line: CoachLine) = baseStyle.copy(fontSize = (size + if (line.strong) 1 else 0).sp,
            lineHeight = (size + 5).sp, fontWeight = if (line.strong) FontWeight.SemiBold else FontWeight.Normal)
        val fontSize = listOf(14, 13, 12).firstOrNull { size ->
            lines.sumOf { line -> measurer.measure(AnnotatedString(line.text), style(size, line),
                constraints = Constraints(maxWidth = textWidth)).size.height } + gap * (lines.size - 1) <= textHeight
        } ?: 12
        Surface(color = Soft, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().testTag("opening-coach")) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { line -> Text(line.text, style = style(fontSize, line), color = if (line.muted) Muted else if (line.strong) Accent else Ink,
                    modifier = Modifier.testTag(line.tag ?: "opening-coach-text")) }
            }
        }
    }
}
