package cn.yibu.chess.ui

import androidx.compose.foundation.Canvas
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.yibu.chess.AppState
import cn.yibu.chess.BuildConfig
import cn.yibu.chess.diagnostics.AnalysisTimings
import cn.yibu.chess.GameViewModel
import cn.yibu.chess.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.tanh

private fun gradeColor(grade: Grade): Color = when (grade) {
    Grade.BRILLIANT -> Color(0xFF147D82)
    Grade.GREAT, Grade.BEST, Grade.EXCELLENT -> Accent
    Grade.INACCURACY -> Gold
    Grade.MISTAKE -> Color(0xFFB46C27)
    Grade.BLUNDER -> Danger
    else -> Muted
}

@Composable
fun ChessApp(model: GameViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.error) { if (state.error != null) model.playFeedback(SoundCue.ERROR) }
    CompositionLocalProvider(LocalSoundFeedback provides model::playFeedback, LocalReviewSound provides model::reviewSound) {
        ChessScreen(state, model)
    }
}

@Composable
internal fun AnalysisFrameTiming(requestId: String?) {
    LaunchedEffect(requestId) {
        requestId?.let { id -> withFrameNanos { AnalysisTimings.frame(id) } }
    }
}

@Composable
internal fun ChessScreen(state: AppState, model: GameViewModel) {
    AnalysisFrameTiming(state.lastAnalysisTimingId)
    val context = LocalContext.current
    var newDialog by remember { mutableStateOf(false) }
    var aboutDialog by remember { mutableStateOf(false) }
    var resignDialog by remember { mutableStateOf(false) }
    var promotion by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember(state.game.id, state.boardHistory) { mutableStateOf<Int?>(null) }
    var flipOverride by remember(state.game.id) { mutableStateOf(false) }
    val contentScroll = rememberScrollState()
    LaunchedEffect(state.page, state.lessonOpen, state.highlightsOpen) { contentScroll.scrollTo(0) }
    BackHandler(enabled = state.page == 1 && state.lessonOpen, onBack = model::closeLesson)
    BackHandler(enabled = state.page == 1 && state.highlightsOpen, onBack = model::closeHighlights)
    BackHandler(enabled = state.page == 3 && state.practice != null, onBack = model::closePractice)
    BackHandler(enabled = state.page == 3 && state.opening != null, onBack = model::closeCourse)
    val fen = remember(state.boardHistory) { ChessRules.board(state.boardHistory).fen }
    val legal = remember(state.boardHistory) { ChessRules.legal(state.boardHistory) }
    val targets = remember(selected, legal) { legal.filter { it.take(2) == selected?.let(ChessRules::squareName) }.map { ChessRules.squareIndex(it.substring(2, 4)) }.toSet() }
    ChessTheme {
        Scaffold(containerColor = Background, bottomBar = {
            if (state.page != 3 || state.opening == null) {
                Surface(color = Background) {
                    Column {
                        HorizontalDivider(color = Line.copy(alpha = .6f))
                        NavigationBar(containerColor = Background, tonalElevation = 0.dp) {
                            listOf(Triple("对弈", ChessIcon.KNIGHT, 0), Triple("复盘", ChessIcon.REVIEW, 1),
                                Triple("训练", ChessIcon.TRAIN, 3), Triple("棋谱", ChessIcon.LIBRARY, 2)).forEach { (name, icon, page) ->
                                NavigationBarItem(selected = state.page == page, onClick = feedbackClick { model.page(page) },
                                    icon = { LineIcon(icon) }, label = { Text(name, fontSize = 12.sp) },
                                    colors = NavigationBarItemDefaults.colors(indicatorColor = Soft,
                                        selectedIconColor = Accent, selectedTextColor = Accent,
                                        unselectedIconColor = Muted, unselectedTextColor = Muted))
                            }
                        }
                    }
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 16.dp)
                    .padding(top = if (state.page == 0) 0.dp else 8.dp)) {
                    if (state.page == 0) AppHeader(state, model) { aboutDialog = true }
                    if (state.page == 3 && state.practice != null) {
                        Box(Modifier.weight(1f)) {
                            PracticeWorkspace(state.practice, model::closePractice, model::practiceAnswer, model::practiceHint,
                                model::practiceReveal, model::practiceNext, model::practiceExplain)
                        }
                    } else if (state.page == 3 && state.opening != null) {
                        Box(Modifier.weight(1f)) {
                            OpeningWorkspace(state.opening, state.openingThinking, state.ready, state.openingProgress, state.games,
                                model::closeCourse, model::courseRoute, model::courseSeek, model::courseQuiz, model::courseHint,
                                model::courseExplore, model::courseAnswer, model::courseUndo, model::courseReply, model::trainOpening,
                                { game, ply -> model.load(game); model.cursor(ply) },
                                model::courseContinue, model::courseGuided, model::courseShowMove)
                        }
                    } else if (state.page == 3) {
                        TrainingHub(state, model)
                    } else if (state.page == 2) {
                        Library(state, model)
                    } else if (state.page == 1 && state.highlightsOpen) {
                        Box(Modifier.weight(1f)) {
                            HighlightsWorkspace(state.game, state.highlights, !state.game.humanWhite xor flipOverride,
                                onClose = model::closeHighlights, onFlip = { flipOverride = !flipOverride })
                        }
                    } else if (state.page == 1 && state.lessonOpen) {
                        Box(Modifier.weight(1f)) {
                            LessonWorkspace(state, !state.game.humanWhite xor flipOverride,
                                onClose = model::closeLesson, onFlip = { flipOverride = !flipOverride },
                                onSeek = model::lessonSeek, onRetry = model::explainSelected, onPause = model::pauseReview,
                                onRoute = model::lessonRoute)
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(contentScroll),
                            verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (state.page == 1) "逐步复盘" else if (state.game.mode == Difficulty.STRONG) "挑战最强" else "日常对弈",
                                        style = MaterialTheme.typography.titleLarge)
                                    Text(if (state.page == 1) "${state.game.moves.size} 步 · ${model.resultChinese(state.game)}"
                                        else if (state.game.rated) "匹配你的棋力，每盘进步一点" else "专注练习 · 本局不计分",
                                        color = Muted, fontSize = 12.sp)
                                }
                                IconAction(ChessIcon.SETTINGS, "对局设置", { newDialog = true }, state.ready && !state.transitioning)
                                if (state.page != 0) StatusPill(if (!state.game.humanWhite xor flipOverride) "黑方在下" else "白方在下")
                            }
                            if (state.page == 1) {
                                PrimaryAction("全局复盘 · 手动看关键点", model::reviewHighlights, Modifier.fillMaxWidth(),
                                    state.ready && !state.busy && state.game.moves.isNotEmpty(), ChessIcon.REVIEW)
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                OpponentRow(state)
                                Box(Modifier.shadow(5.dp, RoundedCornerShape(15.dp), ambientColor = Ink.copy(alpha = .12f), spotColor = Ink.copy(alpha = .12f))
                                    .background(Ink, RoundedCornerShape(15.dp)).padding(4.dp)) {
                                    key(state.page) {
                                    ChessBoard(fen, flipped = !state.game.humanWhite xor flipOverride,
                                        selected = selected, targets = if (state.page == 0 && !state.busy) targets else emptySet(),
                                        lastMove = state.boardHistory.lastOrNull(),
                                        animationKey = state.game.id,
                                        kingBreak = if (state.page == 0) state.kingBreak else null,
                                        onKingBreakFinished = model::finishKingBreak,
                                        onKingBreakStarted = model::kingBreakStarted,
                                        arrow = if (state.variation.isNotEmpty() && state.variationStep == 0) state.variation.first() else null) { square ->
                                        if (state.page == 0 && state.ready && !state.busy && state.humanTurn && !state.game.finished) {
                                            val choices = legal.filter { it.take(2) == selected?.let(ChessRules::squareName) && it.substring(2, 4) == ChessRules.squareName(square) }
                                            when {
                                                choices.size > 1 -> { promotion = choices; selected = null }
                                                choices.size == 1 -> { model.play(choices.first()); selected = null }
                                                legal.any { it.take(2) == ChessRules.squareName(square) } -> {
                                                    model.playFeedback(SoundCue.SELECT)
                                                    selected = if (selected == square) null else square
                                                }
                                                else -> { if (selected != null) model.playFeedback(SoundCue.ILLEGAL); selected = null }
                                            }
                                        }
                                    }
                                }
                                }
                                PlayerRow(state) { flipOverride = !flipOverride }
                            }
                            if (state.page == 1) {
                                ReviewNavigation(state, model)
                                EvaluationChart(state.game, state.cursor, model::cursor)
                            }
                            MoveStrip(state, model)
                            if (state.page == 1) RatingCard(state, model) else BrilliantCards(state)
                            state.game.ratingChange?.let { change ->
                                Surface(color = Soft, shape = RoundedCornerShape(16.dp)) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text("${model.resultChinese(state.game)} · ${state.game.ending}", fontWeight = FontWeight.SemiBold)
                                            Text("本局 Elo ${change.before} → ${change.after}", color = Muted, fontSize = 12.sp)
                                        }
                                        Text("${if (change.delta > 0) "+" else ""}${change.delta}", color = Accent,
                                            fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            if (state.busy || (!state.ready && state.error == null) || (state.analyzing && state.page == 1)) {
                                Row(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(14.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(state.status, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                    if (state.page == 1 && state.busy) TextButton(onClick = feedbackClick(model::pauseReview)) { Text("暂停") }
                                }
                            }
                            if (state.error != null) {
                                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(state.error, fontSize = 13.sp, color = Danger)
                                        TextButton(onClick = feedbackClick(model::retry), enabled = state.ready && !state.busy) { Text("继续／重试") }
                                    }
                                }
                            }
                            if (state.page == 1) {
                                PrimaryAction("整盘深度复评", model::reviewAll, Modifier.fillMaxWidth(),
                                    state.ready && !state.busy && state.game.moves.isNotEmpty(), ChessIcon.REVIEW)
                                Text("lightning · 目标 22 层 · 0.5 秒搜索预算，网络耗时另计。重新分析双方每一着；上方全局复盘只挑选你的关键点。", color = Muted, fontSize = 11.sp)
                                OutlinedButton(onClick = feedbackClick { context.startActivity(model.share(false)) },
                                    enabled = state.game.moves.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                                    LineIcon(ChessIcon.SHARE, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导出 PGN")
                                }
                                Text("点击讲解后，可对照实战线与推荐线，按步保存。", color = Muted, fontSize = 11.sp)
                            } else {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = feedbackClick { if (state.game.finished) model.reviewHighlights() else model.page(1) }, enabled = state.game.moves.isNotEmpty() && !state.busy,
                                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(14.dp)) {
                                        LineIcon(ChessIcon.REVIEW, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                                        Text(if (state.game.finished) "关键点复盘" else "查看复盘")
                                    }
                                    if (!state.game.finished) TextButton(onClick = feedbackClick { resignDialog = true }, enabled = !state.busy) { Text("认输", color = Muted) }
                                }
                                if (!state.game.finished && state.humanTurn && ChessRules.drawClaim(state.game.moves) != null)
                                    OutlinedButton(onClick = feedbackClick(model::claimDraw), enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("申请和棋 · ${ChessRules.drawClaim(state.game.moves)}") }
                                if (state.game.finished && state.game.ratingChange == null)
                                    Text("${model.resultChinese(state.game)} · ${state.game.ending}", color = Accent, fontSize = 14.sp)
                                if (!state.game.finished) Text("${if (state.analyzing) "棋谱正在后台整理" else "棋谱自动保存"} · 完整分析在复盘查看", color = Muted,
                                    fontSize = 11.sp, modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
        if (newDialog) NewGameDialog(state.settings, state.profile.rating, onTestToken = model::testStockfishConnection,
            onSave = { settings -> model.saveSettings(settings); newDialog = false }, onDismiss = { newDialog = false }) { settings ->
            model.configureAndStart(settings); newDialog = false
        }
        if (promotion.isNotEmpty()) AlertDialog(onDismissRequest = { promotion = emptyList() }, title = { Text("选择升变棋子") }, text = {
            Column {
                listOf('q' to "后 ♛", 'r' to "车 ♜", 'b' to "象 ♝", 'n' to "马 ♞").forEach { (piece, title) ->
                    TextButton(onClick = feedbackClick { promotion.find { it.last() == piece }?.let(model::play); promotion = emptyList() }, modifier = Modifier.fillMaxWidth()) { Text(title) }
                }
            }
        }, confirmButton = {})
        if (resignDialog) AlertDialog(onDismissRequest = { resignDialog = false }, title = { Text("结束这盘对局？") }, text = { Text("棋谱和分析会保留。${if (state.game.rated) "本局按负局结算个人 Elo。" else "本局不改变个人 Elo。"}") },
            confirmButton = { TextButton(onClick = feedbackClick { model.resign(); resignDialog = false }) { Text("认输") } }, dismissButton = { TextButton(onClick = feedbackClick { resignDialog = false }) { Text("继续对弈") } })
        if (aboutDialog) AlertDialog(onDismissRequest = { aboutDialog = false }, title = { Text("关于弈步 · ${BuildConfig.VERSION_NAME}") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = feedbackClick { context.startActivity(model.shareAnalysisTimings()) }) { Text("导出分析耗时") }
                Text("先复评几步，再导出记录。本次打开 App 期间保留最近120次分析，用于区分接口等待、结果处理与保存耗时。重启 App 会清空这份计时记录。", color = Muted, fontSize = 12.sp)
                Text("拟人对手：Maia-3 5M\n用人类棋谱训练，按走法概率选择，近期重复开局会适度减权。Maia 模型离线内置。最强挑战对手与深度复盘使用云端 Stockfish 19 服务（需在对局设置中配置 Access Token）。", fontSize = 13.sp)
                Text("新局默认随机白黑，点击即开始。可在对局设置里主动选择，选择会记住。对弈时只提示经过深度验证的 !!，附上弃子原因和参考变化；完整评级与推荐走法在复盘查看。", fontSize = 13.sp)
                Text("音效随 APK 离线提供，始终开启并跟随手机媒体音量。落子、吃子、易位、升变、将军、终局、王碎裂、复盘和界面操作均有声音。", fontSize = 13.sp)
                Text("将杀和认输后，落败方的王会播放碎裂特效。全局复盘会挑选几个关键节点，手动查看实战与推荐思路，点“下一步”继续，可返回逐步复盘。", fontSize = 13.sp)
                Text("对手落子前默认思考约1–2秒，搜索时间计入等待。复盘点击“讲解这一步”，棋盘与原因、后续思路在同一屏查看，每着参考变化都有说明，通过“上一步”“下一步”手动跟走。深度分析使用云端 Stockfish 19 服务。讲解基于引擎变化和局面事实，不是联网聊天模型。", fontSize = 13.sp)
                Text("个人 Elo 从500开始，与 Chess.com 分数独立。匹配局的胜负与和棋按 Elo 公式结算；最强局不计分。前10盘调整较快。删除棋谱不会撤销分数；旧版对局不补计分。", fontSize = 13.sp)
                Text("采用 Chess.com 公开的预期得分损失阈值：\n最佳：引擎最佳或等值走法\n小于2个百分点：优秀\n2–5：不错 · 5–10：?!\n10–20：? · 20以上：??\n! 是关键好棋，!! 是深入验证的合理弃子。", fontSize = 13.sp)
                Text("Chess.com 完整算法未公开。这里用引擎分值和棋力估算预期得分。Maia 使用另一套棋谱分数范围，个人 Elo 与模型强度的对应仍是近似值，不能等同平台真人分数。", color = Muted, fontSize = 12.sp)
                Text("Maia-3 / 弈步：AGPLv3\n云端 Stockfish：GPLv3-or-later\nONNX Runtime：MIT · chesslib：Apache-2.0\n完整许可和模型版本记录包含在源码及 APK 内。", fontSize = 12.sp)
                TextButton(onClick = feedbackClick { context.startActivity(model.share(true)) }) { Text("导出对局诊断 JSON") }
                TextButton(onClick = feedbackClick { context.startActivity(model.shareRuntimeDiagnostics()) }) { Text("导出运行诊断") }
                TextButton(onClick = feedbackClick { context.startActivity(model.shareLicenses()) }) { Text("查看／导出开源许可证") }
            }
        }, confirmButton = { TextButton(onClick = feedbackClick { aboutDialog = false }) { Text("知道了") } })
    }
}

@Composable
private fun AppHeader(state: AppState, model: GameViewModel, onAbout: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp).testTag("app-header"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(Accent, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            LineIcon(ChessIcon.KNIGHT, Modifier.size(25.dp), Color.White)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("弈步", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text("${state.profile.rating} ELO · ${state.profile.ratedGames} 盘计分", color = Muted,
                fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconAction(ChessIcon.INFO, "说明", onAbout)
        PrimaryAction("新局", { model.newGame() }, enabled = state.ready && !state.transitioning)
    }
}

@Composable
private fun OpponentRow(state: AppState) {
    state.game.source?.let { source ->
        Text("Chess.com · ${source.opponent(state.game.humanWhite)} · Elo ${state.game.opponentElo ?: "未提供"}",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        return
    }
    val opponentName = if (state.game.opponentEngine.startsWith("Maia")) "Maia" else "Stockfish"
    if (state.page == 1) {
        Text(if (state.game.mode == Difficulty.STRONG) "Stockfish 对局 · 最强"
            else "$opponentName 对局 · 对手 Elo ${state.game.opponentElo ?: 500}",
            color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).background(Soft, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            LineIcon(ChessIcon.KNIGHT, color = Accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (state.game.mode == Difficulty.STRONG) "Stockfish · 最强"
                else if (opponentName == "Maia") "Maia · 拟人对手" else "Stockfish · 练习对手",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (state.game.mode == Difficulty.STRONG) "全棋力 · 不计 Elo"
                else "Elo ${state.game.opponentElo ?: 500} · 执${if (state.game.humanWhite) "黑" else "白"}", color = Muted, fontSize = 11.sp)
        }
        StatusPill(if (!state.ready) "准备中" else if (state.page == 0 && state.busy && !state.humanTurn) "思考中" else if (state.game.mode == Difficulty.STRONG) "云端" else "离线", dot = true)
    }
}

@Composable
private fun PlayerRow(state: AppState, onFlip: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(if (state.game.humanWhite) Color.White else Ink, RoundedCornerShape(3.dp))
            .border(1.dp, Line, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(state.game.source?.let { "${it.username} · ${it.playerRating(state.game.humanWhite) ?: "未提供"}" }
                ?: "你 · ${state.profile.rating}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("执${if (state.game.humanWhite) "白" else "黑"}", color = Muted, fontSize = 11.sp)
        }
        Text(when {
            state.variation.isNotEmpty() -> "变化 · 第 ${state.variationStep} 步"
            state.page == 1 -> "第 ${state.cursor} / ${state.game.moves.size} 步"
            state.game.finished -> "对局结束"
            !state.ready -> "引擎准备中"
            state.humanTurn -> "轮到你走棋"
            else -> "对手的回合"
        }, color = if (state.page == 0 && state.ready && state.humanTurn && !state.game.finished) Accent else Muted,
            fontSize = 12.sp, fontWeight = FontWeight.Medium)
        IconAction(ChessIcon.FLIP, "翻转", onFlip)
    }
}

@Composable
private fun ReviewNavigation(state: AppState, model: GameViewModel) {
    Surface(color = Soft, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            listOf(Triple("起始", ChessIcon.FIRST, { model.cursor(0) }),
                Triple("上一步", ChessIcon.PREVIOUS, { model.step(-1) }),
                Triple("下一步", ChessIcon.NEXT, { model.step(1) }),
                Triple("末尾", ChessIcon.LAST, { model.cursor(state.game.moves.size) })).forEach { (name, icon, click) ->
                Column(Modifier.weight(1f).clickable(onClick = feedbackClick(click)).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LineIcon(icon, Modifier.size(22.dp), Accent)
                    Spacer(Modifier.height(4.dp))
                    Text(name, fontSize = 11.sp, color = Accent)
                }
            }

        }
    }
}

@Composable
internal fun BrilliantCards(state: AppState) {
    state.brilliantNotices.filter { it.grade == Grade.BRILLIANT && !it.provisional && it.brilliantReason != null && it.brilliantPlan != null }.forEach { review ->
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFFE5F3F0))) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${if (review.moverWhite == state.game.humanWhite) "你" else "对手"} · ${review.san} !!", color = gradeColor(Grade.BRILLIANT), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(review.brilliantReason!!, fontSize = 13.sp)
                Text(review.brilliantPlan!!, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun RatingCard(state: AppState, model: GameViewModel) {
    val review = state.chosenReview
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (review == null) {
                Text(if (state.game.moves.isEmpty()) "从第一步开始" else "本步尚未完成分析", fontWeight = FontWeight.SemiBold)
                Text("选择一步棋，点击深度复评即可分析。", color = Muted, fontSize = 13.sp)
                if (state.page == 1 && state.cursor > 0) TextButton(onClick = feedbackClick(model::analyzeSelected), enabled = state.ready && !state.busy) { Text("分析本步") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(review.grade.symbol.ifEmpty { "•" }, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = gradeColor(review.grade))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${review.san} · ${review.grade.chinese}", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("${if (review.algorithmVersion < 3) "旧版评级 · 建议复评" else if (review.provisional && review.deeplySearched) "深度复评 · 待确认" else if (review.provisional) "初评" else "深度复评"} · 深度 ${minOf(review.best.depth, review.played.depth)}", color = Muted, fontSize = 11.sp)
                    }
                    Text(review.played.display(whitePerspective = true, moverWhite = review.moverWhite), fontSize = 17.sp, color = Accent)
                }
                val root = state.game.moves.take(review.ply - 1)
                val bestSan = remember(review, root) { ChessRules.san(root, review.bestMove) }
                Text("推荐 $bestSan  ·  得分损失 ${String.format(Locale.ROOT, "%.1f", review.pointsLost * 100)} 个百分点", color = Accent, fontSize = 13.sp)
                Text(review.explanation, color = Ink, fontSize = 13.sp, lineHeight = 21.sp)
                if (state.page == 1) {
                    Text("推荐：${ChessRules.variationSan(root, review.best.pv.take(6)).joinToString("  ")}", color = Muted, fontSize = 12.sp)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = feedbackClick { model.showVariation(true) }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp)) { Text("跟走推荐", fontSize = 12.sp) }
                        OutlinedButton(onClick = feedbackClick { model.showVariation(false) }, modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp)) { Text("实战变化", fontSize = 12.sp) }
                    }
                    TextButton(onClick = feedbackClick(model::analyzeSelected), enabled = !state.busy && state.ready) { Text("复评本步") }
                }
            }
            if (state.cursor > 0) {
                val lesson = state.chosenLesson
                if (lesson == null) {
                    FilledTonalButton(onClick = feedbackClick(model::explainSelected), enabled = state.ready && !state.busy,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text(if (state.explainingPly == state.cursor) "正在讲解第 ${state.cursor} 步…" else "讲解这一步")
                    }
                    Text("只生成当前一步的原因与后续思路，完成后自动保存。", fontSize = 11.sp, color = Muted)
                } else {
                    HorizontalDivider(color = Line)
                    Text("为什么这样走", color = Accent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("离线讲解 · 搜索深度 ${lesson.depth} · 已保存", fontSize = 11.sp, color = Muted)
                    FilledTonalButton(onClick = feedbackClick(model::explainSelected), enabled = state.ready && !state.busy,
                        modifier = Modifier.fillMaxWidth()) { Text("打开讲解与棋盘演示") }
                }
            }
        }
    }
}

@Composable
private fun MoveStrip(state: AppState, model: GameViewModel) {
    val sans = remember(state.game.moves) { ChessRules.sanMoves(state.game.moves) }
    val scroll = rememberLazyListState()
    LaunchedEffect(state.game.moves.size, state.cursor, state.page) {
        // Every move is a frequent interaction: keep scrolling immediate, not animated.
        if (sans.isNotEmpty()) scroll.scrollToItem((if (state.page == 1) state.cursor - 1 else sans.lastIndex).coerceIn(0, sans.lastIndex))
    }
    LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(sans) { index, san ->
            val review = state.game.reviews.find { it.ply == index + 1 }
            val active = (if (state.page == 1) state.cursor else state.game.moves.size) == index + 1
            Surface(color = if (active) Accent else Panel, shape = RoundedCornerShape(10.dp), modifier = Modifier.clickable(onClick = feedbackClick { if (state.page != 1) model.page(1); model.cursor(index + 1) })) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("${index / 2 + 1}${if (index % 2 == 0) "." else "…"} $san", fontSize = 13.sp, color = if (active) Color.White else Ink)
                    if (state.page == 1 || (review?.grade == Grade.BRILLIANT && !review.provisional))
                        Text(review?.grade?.symbol?.ifEmpty { "·" } ?: "…", color = if (active) Color.White else review?.grade?.let(::gradeColor) ?: Muted, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun EvaluationChart(game: GameRecord, cursor: Int, onSelect: (Int) -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text("局势变化", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("白方视角 · 点击跳转", fontSize = 11.sp, color = Muted)
            }
            val points = game.reviews.associate { it.ply to tanh(it.played.whiteScore(it.moverWhite) / 3.0).toFloat() }
            Canvas(Modifier.fillMaxWidth().padding(top = 10.dp).height(76.dp).pointerInput(game.moves.size) {
                detectTapGestures { onSelect((it.x / size.width * game.moves.size).toInt().coerceIn(0, game.moves.size)) }
            }) {
                val middle = size.height / 2
                drawLine(Line, Offset(0f, middle), Offset(size.width, middle), 1.dp.toPx())
                val count = game.moves.size.coerceAtLeast(1)
                val path = Path()
                var previousPly = -2
                (listOf(0 to 0f) + points.toList().sortedBy { it.first }).forEach { (ply, value) ->
                    val x = size.width * ply / count
                    val y = middle - value * (middle - 6.dp.toPx())
                    if (ply != previousPly + 1) path.moveTo(x, y) else path.lineTo(x, y)
                    drawCircle(Accent, 2.dp.toPx(), Offset(x, y))
                    previousPly = ply
                }
                drawPath(path, Accent, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                val x = size.width * cursor / count
                drawLine(Gold, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
            }
        }
    }
}

@Composable
private fun Library(state: AppState, model: GameViewModel) {
    val formatter = remember { SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA) }
    var pendingDelete by remember { mutableStateOf<GameRecord?>(null) }
    var filter by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    val visible = state.games.filter { when (filter) { 1 -> it.source != null; 2 -> it.source == null && it.openingTraining == null; 3 -> it.openingTraining != null; else -> true } }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("我的棋谱", style = MaterialTheme.typography.titleLarge)
                    Text("${state.games.size} 盘 · 保存在这台手机", color = Muted, fontSize = 12.sp)
                }
                ChessComImportButton(state, model::importChessCom)
            }
        }
        item { ChessComImportStatus(state, model::cancelImport, model::clearImportStatus) }
        if (state.games.any { it.moves.isNotEmpty() }) item { AutoReviewStatus(state.autoReview, model::pauseAutoReview, model::resumeAutoReview) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(listOf("全部", "Chess.com", "本地对弈", "开局陪练")) { index, label ->
                    FilterChip(selected = filter == index, onClick = feedbackClick { filter = index }, label = { Text(label, fontSize = 12.sp) })
                }
            }
        }
        if (state.error != null) item { Text(state.error, color = Danger, fontSize = 13.sp) }
        if (state.games.isEmpty()) item {
            Column(Modifier.fillMaxWidth().background(Soft, RoundedCornerShape(20.dp)).padding(horizontal = 24.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LineIcon(ChessIcon.LIBRARY, Modifier.size(36.dp), Accent)
                Text("每一盘，都值得回看", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("开始对弈后，棋谱会自动保存在这里。", color = Muted, fontSize = 12.sp)
                PrimaryAction("开始一盘", { model.newGame() }, enabled = state.ready && !state.transitioning)
            }
        }
        if (visible.isEmpty() && state.games.isNotEmpty()) item { Text("这里还没有棋谱。", color = Muted, modifier = Modifier.padding(16.dp)) }
        items(visible, key = { it.id }) { game ->
            Card(Modifier.fillMaxWidth().clickable(onClick = feedbackClick { model.load(game) }), shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(Soft, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                            LineIcon(ChessIcon.KNIGHT, Modifier.size(22.dp), Accent)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(game.source?.let { "Chess.com · ${it.opponent(game.humanWhite)}" }
                                ?: if (game.openingTraining != null) "开局陪练" else if (game.mode == Difficulty.STRONG) "挑战最强" else "匹配对局",
                                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(formatter.format(Date(game.startedAt)), color = Muted, fontSize = 11.sp)
                        }
                        StatusPill(model.resultChinese(game), color = if (game.result == "*") Muted else Accent)
                    }
                    val opponent = when {
                        game.source != null -> "对手 ${game.opponentElo ?: "未知"} Elo"
                        game.openingTraining != null -> "Maia 陪练"
                        game.mode == Difficulty.STRONG -> "Stockfish"
                        else -> "Maia ${game.opponentElo ?: 500} Elo"
                    }
                    Text("你执${if (game.humanWhite) "白" else "黑"} · ${game.moves.size} 步 · $opponent", color = Muted, fontSize = 12.sp)
                    Text("已分析 ${game.reviews.size}/${game.moves.size} 步", color = Muted, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val change = game.ratingChange
                        Text(if (change != null) "Elo ${change.before} → ${change.after}（${if (change.delta > 0) "+" else ""}${change.delta}）"
                            else if (game.rated) "${if (game.finished) "正在结算" else "结束后结算"} Elo" else "本局不计分",
                            color = Accent, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = feedbackClick { pendingDelete = game }, enabled = !state.transitioning,
                            contentPadding = PaddingValues(horizontal = 8.dp)) {
                            LineIcon(ChessIcon.TRASH, Modifier.size(16.dp), Danger)
                            Spacer(Modifier.width(5.dp)); Text("删除", color = Danger, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { game ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除这盘棋谱？") },
            text = { Text("棋谱和复盘分析将从手机中删除，无法恢复。已经结算的个人 Elo 会保留。") },
            confirmButton = { TextButton(onClick = feedbackClick { model.delete(game); pendingDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = feedbackClick { pendingDelete = null }) { Text("取消") } })
    }
}

@Composable
internal fun NewGameDialog(
    initial: PlaySettings,
    rating: Int,
    onTestToken: suspend (String) -> Result<String>,
    onSave: (PlaySettings) -> Unit,
    onDismiss: () -> Unit,
    onStart: (PlaySettings) -> Unit
) {
    var settings by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("对局设置") },
        text = { NewGameSettingsEditor(settings, rating, onChange = { settings = it }, onTestToken = onTestToken) },
        confirmButton = {
            Button(onClick = feedbackClick { onStart(settings.copy(stockfishToken = settings.stockfishToken.trim())) }) {
                Text("开始对弈")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = feedbackClick { onSave(settings.copy(stockfishToken = settings.stockfishToken.trim())) }) {
                    Text("保存设置")
                }
                TextButton(onClick = feedbackClick(onDismiss)) { Text("取消") }
            }
        })
}

@Composable
internal fun NewGameSettingsEditor(
    settings: PlaySettings,
    rating: Int,
    onChange: (PlaySettings) -> Unit,
    onTestToken: suspend (String) -> Result<String>
) {
    val difficulty = settings.mode
    val color = settings.color
    val stockfishToken = settings.stockfishToken
    val latestToken by rememberUpdatedState(stockfishToken.trim())
    var tokenVisible by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
        Text("你的个人 Elo：$rating", color = Accent, modifier = Modifier.padding(bottom = 8.dp))
        Difficulty.choices.forEach { option ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = feedbackClick { onChange(settings.copy(mode = option)) })
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = difficulty == option, onClick = feedbackClick { onChange(settings.copy(mode = option)) })
                Column {
                    Text(if (option == Difficulty.MATCHED) "${option.chinese} · $rating" else option.chinese, fontSize = 15.sp)
                    Text(option.description, fontSize = 11.sp, color = Muted)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("执棋颜色", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ColorPreference.entries.forEach { option ->
                FilterChip(
                    selected = color == option,
                    onClick = feedbackClick { onChange(settings.copy(color = option)) },
                    modifier = Modifier.weight(1f),
                    label = { Text(option.chinese, fontSize = 12.sp) }
                )
            }
        }
        HorizontalDivider(color = Line.copy(alpha = .6f), modifier = Modifier.padding(vertical = 12.dp))
        Text("云端算力配置 (Stockfish 19)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("用于复盘分析与最强挑战对手。Maia 拟人对局仍为本地离线运行。", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
        OutlinedTextField(
            value = stockfishToken,
            onValueChange = {
                onChange(settings.copy(stockfishToken = it))
                testStatus = null
            },
            label = { Text("Access Token") },
            placeholder = { Text("请输入云端访问口令") },
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val text = clipboardManager.getText()?.text
                        if (!text.isNullOrBlank()) {
                            onChange(settings.copy(stockfishToken = text.trim()))
                            testStatus = null
                        }
                    }) {
                        Text("粘贴", fontSize = 12.sp)
                    }
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(if (tokenVisible) "隐藏" else "显示", fontSize = 11.sp, color = Muted)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    if (stockfishToken.isBlank()) {
                        testStatus = "请先输入口令"
                        return@TextButton
                    }
                    testing = true
                    testStatus = "测试中…"
                    val testedToken = stockfishToken.trim()
                    scope.launch {
                        val res = onTestToken(testedToken)
                        testing = false
                        if (latestToken == testedToken) testStatus = res.fold(
                            onSuccess = { "连接成功：$it" },
                            onFailure = { "连接失败：${it.message}" }
                        )
                    }
                },
                enabled = !testing
            ) {
                Text(if (testing) "测试中…" else "测试连接", fontSize = 12.sp)
            }
            testStatus?.let { status ->
                Text(
                    status,
                    fontSize = 11.sp,
                    color = if (status.startsWith("连接成功")) Accent else Danger,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("自动分析棋谱", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text("使用极速分析补齐所有棋谱，切到后台后继续，结果逐步保存。手动复盘和最强对手优先处理；可在棋谱页暂停。",
            fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
        Text("选择与口令会保存在本机；当前对局会保留在棋谱中。", fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 8.dp))
    }
}
