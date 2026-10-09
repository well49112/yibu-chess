package cn.yibu.chess.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
@OptIn(ExperimentalFoundationApi::class)
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
    val scroll = rememberScrollState()
    val coach = remember { BringIntoViewRequester() }
    LaunchedEffect(session.key, session.mode, session.lessonStep, session.lessonPhase, session.retrying, session.message, session.hintLevel) {
        if (session.mode == OpeningMode.GUIDE && (session.lessonPhase == OpeningLessonPhase.FEEDBACK || session.lessonPhase == OpeningLessonPhase.DONE || session.message.isNotBlank() || session.hintLevel > 0)) {
            withFrameNanos { }; coach.bringIntoView()
        } else scroll.scrollTo(0)
    }
    val legal = remember(history) { ChessRules.legal(history) }
    val fen = remember(history) { ChessRules.board(history).fen }
    val targets = legal.filter { it.take(2) == selected?.let(ChessRules::squareName) }.map { ChessRules.squareIndex(it.substring(2, 4)) }.toSet()
    val guideTask = session.mode == OpeningMode.GUIDE && session.lessonPhase == OpeningLessonPhase.TASK
    val interactive = !thinking && (session.mode == OpeningMode.FREE || guideTask || session.mode == OpeningMode.QUIZ && session.solvedMove == null)
    val feedback = LocalSoundFeedback.current
    Column(Modifier.fillMaxSize().testTag("opening-workspace"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(course.title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton(onClick = feedbackClick { routesOpen = true }, contentPadding = PaddingValues(6.dp), modifier = Modifier.testTag("opening-routes")) {
                Text("路线 ${session.routeIndex + 1}/${course.routes.size} ▾", fontSize = 12.sp)
            }
            TextButton(onClick = feedbackClick(onClose), contentPadding = PaddingValues(4.dp)) { Text("返回", fontSize = 12.sp) }
        }
        if (session.mode == OpeningMode.GUIDE) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (session.retrying && session.lessonPhase != OpeningLessonPhase.DONE) "再练 ${session.lessonStep + 1}/${session.lessonDeck.size} · 刚才需要帮助的目标"
                    else "完成 ${session.lessonDone}/${session.lessonPlies.size} 个目标", color = Accent, fontSize = 11.sp)
                Text("执${if (course.humanWhite) "白" else "黑"}", color = Muted, fontSize = 11.sp)
            }
            LinearProgressIndicator(progress = { session.lessonDone.toFloat() / session.lessonPlies.size }, modifier = Modifier.fillMaxWidth(), color = Accent, trackColor = Soft)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("opening-content"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(route.title, color = Muted, fontSize = 12.sp)
            if (guideTask) Text(session.lessonTask.prompt, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.testTag("opening-task"))
            // Identical width, border and aspect ratio to the play screen. Never shrink to fit height.
            Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Ink, RoundedCornerShape(15.dp)).padding(4.dp).testTag("opening-board-frame")) {
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
            Surface(color = Soft, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().bringIntoViewRequester(coach)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    when (session.mode) {
                        OpeningMode.GUIDE -> when (session.lessonPhase) {
                            OpeningLessonPhase.INTRO -> {
                                Text("这节课，只练一条路线", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(course.goal, fontSize = 14.sp, lineHeight = 22.sp)
                                Text("你将亲手完成 ${session.lessonPlies.size} 个小目标。每次走完再看原因，需要帮助的目标会在课末再练一遍。", fontSize = 12.sp, color = Muted, lineHeight = 20.sp)
                            }
                            OpeningLessonPhase.TASK -> {
                                if (session.message.isNotBlank()) Text(session.message, fontSize = 13.sp, lineHeight = 21.sp, modifier = Modifier.testTag("opening-answer"))
                                else Text("你来走这一着 · 点击棋子，再点击落点", color = Accent, fontSize = 13.sp)
                                if (session.hintLevel > 0) Text(if (session.hintLevel == 1) session.lessonTask.hint
                                    else "参考走法：${ChessRules.san(history, route.moves[session.lessonPly])}。棋盘箭头标出了起点与落点。", fontSize = 13.sp, lineHeight = 21.sp, color = Accent)
                                if (session.lessonPly > 0) {
                                    val before = route.moves.take(session.lessonPly - 1)
                                    Text("对手刚走 ${ChessRules.san(before, route.moves[session.lessonPly - 1])}：${route.notes[session.lessonPly - 1]}", color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                                }
                            }
                            OpeningLessonPhase.FEEDBACK -> {
                                Text("做到了 · ${ChessRules.san(history.dropLast(1), history.last())}", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                                Text(session.message, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.testTag("opening-answer"))
                                Text(if (session.lessonStep != session.lessonDeck.lastIndex) "点继续，再看对手怎么应对。"
                                    else if (!session.retrying && session.retryPlies.isNotEmpty()) "接下来，把需要帮助的目标再练一遍。"
                                    else "点继续，看看这节课的总结。", color = Muted, fontSize = 12.sp)
                            }
                            OpeningLessonPhase.DONE -> {
                                Text("完成本课", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, modifier = Modifier.testTag("opening-complete"))
                                Text("首次独立完成 ${session.firstTry}/${session.lessonPlies.size} 个目标${if (session.retrying) " · 已把需要帮助的目标再练一遍" else ""}", fontSize = 13.sp, lineHeight = 21.sp)
                                Text("这条路线的重点：${route.notes[route.checkpoint.atPly]}", fontSize = 14.sp, lineHeight = 23.sp)
                                Text("当前局面：${route.notes.last()}", fontSize = 13.sp, lineHeight = 21.sp)
                                Text("记住这个风险：${course.watchOut}", fontSize = 13.sp, lineHeight = 21.sp)
                            }
                        }
                        OpeningMode.LEARN -> {
                            if (session.cursor == 0) {
                                Text(course.goal, fontSize = 14.sp, lineHeight = 22.sp)
                                Text("下一阶段：${course.plan}", fontSize = 13.sp, lineHeight = 21.sp)
                                Text("留意：${course.watchOut}", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                            } else {
                                Text("第 ${session.cursor}/${route.moves.size} 步 · ${ChessRules.san(history.dropLast(1), history.last())}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                Text(route.notes[session.cursor - 1], fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.testTag("opening-note"))
                            }
                        }
                        OpeningMode.QUIZ -> {
                            Text(if (session.solvedMove == null) route.checkpoint.prompt else "答对了：${ChessRules.san(history.dropLast(1), history.last())}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 22.sp)
                            if (session.hint && session.solvedMove == null) Text(route.checkpoint.hint, color = Accent, fontSize = 13.sp, lineHeight = 21.sp)
                            if (session.message.isNotBlank()) Text(session.message, fontSize = 13.sp, lineHeight = 22.sp, modifier = Modifier.testTag("opening-answer"))
                        }
                        OpeningMode.FREE -> {
                            Text(if (history.size % 2 == 0) "现在白方走" else "现在黑方走", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("可以替双方试走；让 Maia 回应，或从这里开始不计分陪练。", fontSize = 12.sp, lineHeight = 20.sp)
                            ChessRules.outcome(history)?.let { Text("局面已结束：$it", color = Accent, fontSize = 12.sp) }
                            if (session.message.isNotBlank()) Text(session.message, color = Accent, fontSize = 12.sp, lineHeight = 20.sp)
                        }
                    }
                }
            }
            if (session.mode == OpeningMode.GUIDE && session.lessonPhase == OpeningLessonPhase.DONE || session.mode == OpeningMode.LEARN && session.cursor == route.moves.size) {
                games.asSequence().filter { it.source != null && it.humanWhite == course.humanWhite }
                    .mapNotNull { game -> OpeningCourses.match(game)?.takeIf { it.course.id == course.id }?.let { game to it } }
                    .take(3).forEach { (game, match) ->
                        TextButton(onClick = feedbackClick { onExample(game, match.sharedPlies) }) {
                            Text("看我的实战：对 ${game.source!!.opponent(game.humanWhite)} · ${match.deviationPly?.let { "第 $it 步开始不同" } ?: "已有相同开局"}", fontSize = 12.sp)
                        }
                    }
            }
            Spacer(Modifier.height(2.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            when (session.mode) {
                OpeningMode.GUIDE -> when (session.lessonPhase) {
                    OpeningLessonPhase.INTRO -> Button(onClick = feedbackClick(onContinue), modifier = Modifier.weight(1f).testTag("opening-continue")) { Text("开始这一课") }
                    OpeningLessonPhase.TASK -> {
                        OutlinedButton(onClick = feedbackClick(onHint), enabled = session.hintLevel < 2, modifier = Modifier.weight(1f)) { Text(if (session.hintLevel == 0) "给我提示" else if (session.hintLevel == 1) "再提示一点" else "提示已显示", fontSize = 12.sp) }
                        TextButton(onClick = feedbackClick(onShowMove), modifier = Modifier.weight(1f)) { Text("看示范", fontSize = 12.sp) }
                    }
                    OpeningLessonPhase.FEEDBACK -> Button(onClick = feedbackClick(onContinue), modifier = Modifier.weight(1f).testTag("opening-continue")) { Text(if (session.lessonStep == session.lessonDeck.lastIndex && !session.retrying && session.retryPlies.isNotEmpty()) "再练需要帮助的目标" else "继续") }
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
        }
    }, confirmButton = { TextButton(onClick = { toolsOpen = false }) { Text("取消") } })
    if (promotion.isNotEmpty()) AlertDialog(onDismissRequest = { promotion = emptyList() }, title = { Text("选择升变棋子") }, text = {
        Column { listOf('q' to "后", 'r' to "车", 'b' to "象", 'n' to "马").forEach { (piece, title) ->
            TextButton(onClick = feedbackClick { promotion.firstOrNull { it.last() == piece }?.let(onAnswer); promotion = emptyList() }) { Text(title) }
        } }
    }, confirmButton = {})
}
