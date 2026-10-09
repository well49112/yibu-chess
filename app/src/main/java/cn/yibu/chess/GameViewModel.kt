package cn.yibu.chess

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cn.yibu.chess.core.*
import cn.yibu.chess.audio.ChessSounds
import cn.yibu.chess.background.*
import cn.yibu.chess.data.GameRepository
import cn.yibu.chess.data.PlayPreferences
import cn.yibu.chess.data.PracticePreferences
import cn.yibu.chess.data.ImportPreferences
import cn.yibu.chess.data.ChessComClient
import cn.yibu.chess.data.OpeningPreferences
import cn.yibu.chess.diagnostics.RuntimeDiagnostics
import cn.yibu.chess.diagnostics.AnalysisTimings
import cn.yibu.chess.diagnostics.AnalysisTiming
import cn.yibu.chess.engine.MaiaModel
import cn.yibu.chess.engine.RemoteStockfishClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

data class AppState(
    val game: GameRecord = GameRecord(),
    val profile: PlayerProfile = PlayerProfile(),
    val games: List<GameRecord> = emptyList(),
    val page: Int = 0,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val analyzing: Boolean = false,
    val transitioning: Boolean = false,
    val status: String = "正在初始化引擎…",
    val error: String? = null,
    val cursor: Int = 0,
    val variation: List<String> = emptyList(),
    val variationBase: Int = 0,
    val variationStep: Int = 0,
    val lessonPlayed: Boolean = false,
    val weaknesses: WeaknessReport = WeaknessReport(),
    val practiceQuestions: List<PracticeQuestion> = emptyList(),
    val practiceProgress: Map<String, PracticeProgress> = emptyMap(),
    val practice: PracticeSession? = null,
    val reviewDone: Int = 0,
    val settings: PlaySettings = PlaySettings(),
    val brilliantNotices: List<MoveReview> = emptyList(),
    val explainingPly: Int? = null,
    val lessonOpen: Boolean = false,
    val kingBreak: KingBreak? = null,
    val highlightsOpen: Boolean = false,
    val highlights: List<ReviewHighlight> = emptyList(),
    val lastAnalysisTimingId: String? = null,
    val autoReview: AutoReviewState = AutoReviewState(),
    val chessComUsername: String = "",
    val importing: Boolean = false,
    val importStatus: String = "",
    val importError: String? = null,
    val importCount: Int = 0,
    val importDuplicates: Int = 0,
    val importSkipped: Int = 0,
    val opening: OpeningSession? = null,
    val openingThinking: Boolean = false,
    val openingProgress: Set<String> = emptySet(),
    val trainingSection: Int = 0,
) {
    val boardHistory: List<String> get() = when {
        page != 1 -> game.moves
        variation.isNotEmpty() -> game.moves.take(variationBase) + variation.take(variationStep)
        lessonOpen -> game.moves.take((cursor - 1).coerceAtLeast(0))
        else -> game.moves.take(cursor)
    }
    val chosenReview: MoveReview? get() = game.reviews.find { it.ply == if (page == 1) cursor else game.moves.size }
    val chosenLesson: MoveLesson? get() = if (page == 1) game.lessons.find { it.ply == cursor } else null
    val humanTurn: Boolean get() = (game.moves.size % 2 == 0) == game.humanWhite
}

class GameViewModel @JvmOverloads constructor(application: Application, remoteClient: RemoteStockfishClient? = null,
    chessCom: ChessComClient? = null) : AndroidViewModel(application) {
    private val repository = GameRepository(application)
    private val preferences = PlayPreferences(application)
    private val practicePreferences = PracticePreferences(application)
    private val importPreferences = ImportPreferences(application)
    private val chessComClient = chessCom ?: ChessComClient()
    private val openingPreferences = OpeningPreferences(application)
    private val sounds = ChessSounds(application)
    private val stockfishClient = remoteClient ?: RemoteStockfishClient({ mutable.value.settings.stockfishToken })
    private val maia = MaiaModel(application)
    private val analyzer = MoveAnalyzer(stockfishClient)
    private val reviewProfile = AnalysisBudget.LIGHTNING.profileName
    private val opponent = Opponent(stockfishClient)
    private val humanOpponent = HumanOpponent(maia)
    private val mutable = MutableStateFlow(AppState(settings = preferences.read(), chessComUsername = importPreferences.username(),
        openingProgress = openingPreferences.progress()))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var importWork: Job? = null
    private var openingWork: Job? = null
    private var openingGeneration = 0
    private var generation = 0
    private val persistence = Mutex()
    private val weaknessTracker = WeaknessStats.Tracker()

    init {
        viewModelScope.launch { AutoReview.state.collect { auto -> mutable.update { it.copy(autoReview = auto,
            analyzing = auto.running && auto.gameId == it.game.id && auto.ply > 0) } } }
        viewModelScope.launch { mutable.collect { state -> AutoReview.interactive(state.busy &&
            (state.page == 1 || state.game.mode == Difficulty.STRONG), state.game.id) } }
        viewModelScope.launch { repository.games.collect { games ->
            val (report, questions) = withContext(Dispatchers.Default) {
                val report = weaknessTracker.build(games)
                report to MistakePractice.questions(games, report)
            }
            val progress = practicePreferences.retainGames(games.map { it.id }.toSet())
            mutable.update { state ->
                val current = state.practice?.current
                val stale = current != null && questions.none { it.key == current.key && it.bestMove == current.bestMove }
                val saved = games.find { it.id == state.game.id }
                val merged = GameSnapshots.merge(state.game, saved)
                val fresh = merged.reviews.filter { review -> review.ply >= state.game.moves.size - 1 && review.grade == Grade.BRILLIANT && !review.provisional &&
                    state.game.reviews.none { it.ply == review.ply && it.grade == Grade.BRILLIANT && !it.provisional } }
                if (state.page == 0 && fresh.isNotEmpty()) playFeedback(SoundCue.BRILLIANT)
                state.copy(game = merged, games = games, weaknesses = report, practiceQuestions = questions, practiceProgress = progress,
                    brilliantNotices = if (state.page == 0) (state.brilliantNotices + fresh).distinctBy { it.ply }.takeLast(2) else state.brilliantNotices,
                    practice = if (stale) null else state.practice,
                    error = if (stale) "这道错题已被删除或分析已更新，请重新开始练习。" else state.error)
            }
        } }
        viewModelScope.launch { repository.profiles.collect { profile ->
            mutable.update { if (profile.ratedGames >= it.profile.ratedGames) it.copy(profile = profile) else it }
        } }
        viewModelScope.launch {
            try {
                val profile = repository.profile()
                val restored = repository.latest()
                val settings = mutable.value.settings
                val resumed = restored?.takeUnless { it.finished }
                val game = resumed?.let { HumanOpponent.prepare(it) } ?: EloRules.newGame(profile, settings.mode, settings.color.humanWhite)
                mutable.update { it.copy(profile = profile, game = game, cursor = game.moves.size) }
                maia.initialize()
                mutable.update { it.copy(ready = true, status = "对弈引擎已就绪") }
                if (resumed == null) playFeedback(SoundCue.START)
                if (!mutable.value.humanTurn) advance() else scheduleLiveAnalysis()
            } catch (e: Exception) { mutable.update { it.copy(error = "引擎启动失败：${e.message}", status = "启动失败") } }
        }
    }
    private fun cancelWork(saveSnapshot: Boolean = true) {
        val snapshot = mutable.value.game
        generation++
        work?.cancel()
        stockfishClient.stop()
        sounds.stop()
        cancelOpeningWork()
        mutable.update { it.copy(busy = false, analyzing = false, explainingPly = null, kingBreak = null) }
        if (saveSnapshot && snapshot.moves.isNotEmpty()) viewModelScope.launch { persist(snapshot) }
    }
    fun playFeedback(cue: SoundCue) { sounds.play(listOf(SoundBeat(cue))) }
    fun reviewSound(before: List<String>, after: List<String>) { sounds.play(SoundEvents.preview(before, after)) }
    fun kingBreakStarted(gameId: Long) {
        if (mutable.value.page == 0 && mutable.value.kingBreak?.gameId == gameId) playFeedback(SoundCue.SHATTER)
    }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun importChessCom(username: String, limit: Int? = 30) {
        if (mutable.value.importing || mutable.value.transitioning) return
        val owner = try { ChessComImport.username(username) }
            catch (e: Exception) { mutable.update { it.copy(importError = e.message) }; return }
        importPreferences.saveUsername(owner)
        AutoReview.importing(getApplication(), true)
        mutable.update { it.copy(chessComUsername = owner, importing = true, importError = null, importCount = 0,
            importDuplicates = 0, importSkipped = 0, importStatus = "开始导入…") }
        importWork = viewModelScope.launch {
            try {
                chessComClient.import(owner, limit, { message -> mutable.update { it.copy(importStatus = message) } }) { batch ->
                    val result = withContext(Dispatchers.IO) { repository.importGames(batch.games) }
                    AutoReview.request(getApplication())
                    mutable.update { it.copy(importCount = it.importCount + result.imported,
                        importDuplicates = it.importDuplicates + result.duplicates,
                        importSkipped = it.importSkipped + batch.skipped + result.deleted,
                        importStatus = "${batch.month} 已保存 · 新增 ${it.importCount + result.imported} 盘",
                        importError = if (batch.notes.isNotEmpty()) "部分棋局跳过：${batch.notes.joinToString("；")}" else it.importError) }
                }
                mutable.update { it.copy(importStatus = "导入完成 · 新增 ${it.importCount} 盘 · 重复 ${it.importDuplicates} 盘 · 跳过 ${it.importSkipped} 盘") }
                playFeedback(SoundCue.CONFIRM)
            } catch (e: CancellationException) {
                mutable.update { it.copy(importStatus = "已停止导入，已保存 ${it.importCount} 盘，下次导入会跳过已有棋局") }
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(importError = e.message ?: "网络连接失败，请稍后重试",
                    importStatus = "导入未完成，已保存 ${it.importCount} 盘，可重试") }
            } finally { mutable.update { it.copy(importing = false) }; AutoReview.importing(getApplication(), false) }
        }
    }
    fun cancelImport() { importWork?.cancel() }
    fun clearImportStatus() { if (!mutable.value.importing) mutable.update { it.copy(importStatus = "", importError = null) } }
    private fun cancelOpeningWork() {
        openingGeneration++
        openingWork?.cancel()
        mutable.update { it.copy(openingThinking = false, opening = if (it.opening?.message == "Maia 正在思考…") it.opening.copy(message = "") else it.opening) }
    }
    fun trainingSection(index: Int) { mutable.update { it.copy(trainingSection = index.coerceIn(0, 1)) } }
    fun openCourse(id: String, routeIndex: Int = 0) {
        if (mutable.value.transitioning || OpeningCourses.all.none { it.id == id && routeIndex in it.routes.indices }) return
        cancelWork()
        mutable.update { it.copy(page = 3, trainingSection = 0, opening = OpeningSession(id, routeIndex), practice = null,
            lessonOpen = false, highlightsOpen = false, variation = emptyList(), error = null) }
    }
    fun closeCourse() { cancelOpeningWork(); mutable.update { it.copy(opening = null) } }
    fun courseRoute(index: Int) {
        val current = mutable.value.opening ?: return
        cancelOpeningWork()
        mutable.update { it.copy(opening = current.changeRoute(index)) }
    }
    fun courseSeek(ply: Int) {
        val current = mutable.value.opening ?: return
        cancelOpeningWork()
        val next = current.seek(ply)
        mutable.update { it.copy(opening = next) }
        if (next.cursor == next.route.moves.size) markOpening("${next.key}:learn")
        reviewSound(current.history, next.history)
    }
    private fun markOpening(key: String) {
        val progress = mutable.value.openingProgress + key
        openingPreferences.save(progress)
        mutable.update { it.copy(openingProgress = progress) }
    }
    fun courseQuiz() { cancelOpeningWork(); mutable.update { it.copy(opening = it.opening?.quiz()) } }
    fun courseHint() { mutable.update { it.copy(opening = it.opening?.lessonHint()) } }
    fun courseGuided() { cancelOpeningWork(); mutable.update { it.copy(opening = it.opening?.guided()) } }
    fun courseContinue() {
        val current = mutable.value.opening ?: return
        cancelOpeningWork()
        val next = current.continueLesson()
        mutable.update { it.copy(opening = next) }
        reviewSound(current.history, next.history)
        if (next.lessonPhase == OpeningLessonPhase.DONE && current.lessonPhase != OpeningLessonPhase.DONE) {
            markOpening("${next.key}:learn")
            markOpening("${next.key}:guided")
            if (next.firstTry == next.lessonPlies.size && next.assisted == 0) markOpening("${next.key}:quiz")
            playFeedback(SoundCue.CONFIRM)
        }
    }
    fun courseShowMove() {
        val current = mutable.value.opening ?: return
        val next = current.showLessonMove()
        mutable.update { it.copy(opening = next) }
        reviewSound(current.history, next.history)
    }
    fun courseExplore() { cancelOpeningWork(); mutable.update { it.copy(opening = it.opening?.explore()) } }
    fun courseUndo() {
        val current = mutable.value.opening ?: return
        if (current.mode != OpeningMode.FREE || current.freeMoves.size <= current.cursor) return
        cancelOpeningWork()
        val next = current.copy(freeMoves = current.freeMoves.dropLast(1), message = "")
        mutable.update { it.copy(opening = next) }
        reviewSound(current.history, next.history)
    }
    fun courseAnswer(uci: String) {
        val current = mutable.value.opening ?: return
        if (mutable.value.openingThinking) return
        val next = current.answer(uci)
        mutable.update { it.copy(opening = next) }
        if (next.history != current.history) reviewSound(current.history, next.history)
        else if (next.message.isNotBlank()) playFeedback(SoundCue.ILLEGAL)
        if ((current.mode == OpeningMode.QUIZ || current.mode == OpeningMode.GUIDE) && current.solvedMove == null && next.solvedMove != null) {
            if (current.mode == OpeningMode.QUIZ) markOpening("${next.key}:quiz")
            playFeedback(SoundCue.CONFIRM)
        }
    }
    fun courseReply() {
        val current = mutable.value.opening ?: return
        if (!mutable.value.ready || mutable.value.openingThinking || current.mode != OpeningMode.FREE ||
            (current.history.size % 2 == 0) == current.course.humanWhite || ChessRules.outcome(current.history) != null) return
        val token = ++openingGeneration
        val history = current.history
        val game = EloRules.newGame(mutable.value.profile, Difficulty.MATCHED, current.course.humanWhite)
            .copy(moves = history, rated = false)
        mutable.update { it.copy(openingThinking = true, opening = current.copy(message = "Maia 正在思考…")) }
        openingWork = viewModelScope.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val target = OpponentPacing.targetMs()
                val move = humanOpponent.move(game, mutable.value.games)
                delay(OpponentPacing.remainingMs(target, SystemClock.elapsedRealtime() - started))
                currentCoroutineContext().ensureActive()
                if (token != openingGeneration || mutable.value.page != 3 || mutable.value.opening?.history != history) return@launch
                mutable.update { it.copy(opening = it.opening?.answer(move)) }
                reviewSound(history, history + move)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == openingGeneration) mutable.update { it.copy(opening = it.opening?.copy(message = "本次回应失败：${e.message}，可重试。")) } }
            finally { if (token == openingGeneration) mutable.update { it.copy(openingThinking = false) } }
        }
    }
    fun trainOpening() {
        val current = mutable.value.opening ?: return
        if (!mutable.value.ready || mutable.value.transitioning || current.history.isEmpty() || ChessRules.outcome(current.history) != null) return
        val previous = mutable.value.game
        val game = current.training(mutable.value.profile)
        cancelWork(false)
        mutable.update { it.copy(busy = true, transitioning = true, error = null) }
        viewModelScope.launch {
            try {
                if (previous.moves.isNotEmpty() || previous.finished) persist(previous)
                mutable.update { it.copy(game = game, page = 0, cursor = game.moves.size, opening = null, practice = null,
                    variation = emptyList(), lessonOpen = false, highlightsOpen = false, highlights = emptyList(),
                    brilliantNotices = emptyList(), reviewDone = 0, status = "${current.course.title} · 从当前位置陪练，本局不计 Elo") }
                persist(game)
                mutable.update { it.copy(busy = false, transitioning = false) }
                playFeedback(SoundCue.START)
                if (!mutable.value.humanTurn) advance()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(busy = false, transitioning = false, error = "陪练启动失败：${e.message}") } }
        }
    }
    fun saveSettings(settings: PlaySettings) {
        if (!mutable.value.ready || mutable.value.transitioning) return
        val saved = settings.copy(stockfishToken = settings.stockfishToken.trim())
        if (saved.mode == Difficulty.STRONG && saved.stockfishToken.isBlank()) {
            mutable.update { it.copy(error = "最强对手需要云端 Access Token；匹配对手可离线对弈") }
            return
        }
        val connectionChanged = saved.stockfishToken != mutable.value.settings.stockfishToken
        if (connectionChanged) cancelWork()
        preferences.save(saved)
        AutoReview.request(getApplication())
        mutable.update { it.copy(settings = saved, error = null, status = "设置已保存，当前棋局继续保留") }
        if (connectionChanged && mutable.value.page == 0) {
            if (!mutable.value.game.finished && !mutable.value.humanTurn && !mutable.value.busy) advance()
            scheduleLiveAnalysis()
        }
    }
    fun configureAndStart(settings: PlaySettings) {
        if (!mutable.value.ready || mutable.value.transitioning) return
        if (settings.mode == Difficulty.STRONG && settings.stockfishToken.isBlank()) {
            mutable.update { it.copy(error = "最强对手需要云端 Access Token；匹配对手可离线对弈") }
            return
        }
        val saved = settings.copy(stockfishToken = settings.stockfishToken.trim())
        preferences.save(saved)
        mutable.update { it.copy(settings = saved) }
        newGame()
    }
    fun newGame(difficulty: Difficulty = mutable.value.settings.mode, humanWhite: Boolean? = mutable.value.settings.color.humanWhite) {
        if (!mutable.value.ready || mutable.value.transitioning || difficulty !in Difficulty.choices) return
        if (difficulty == Difficulty.STRONG && mutable.value.settings.stockfishToken.isBlank()) {
            mutable.update { it.copy(error = "最强对手需要云端 Access Token；匹配对手可离线对弈") }
            return
        }
        val previous = mutable.value.game
        cancelWork(false)
        mutable.update { it.copy(busy = true, transitioning = true, error = null) }
        viewModelScope.launch {
            try {
                if (previous.moves.isNotEmpty() || previous.finished) persist(previous)
                val profile = repository.profile()
                val game = EloRules.newGame(profile, difficulty, humanWhite)
                mutable.update { it.copy(profile = profile, game = game, page = 0, cursor = 0, variation = emptyList(), lessonOpen = false, kingBreak = null, highlightsOpen = false, highlights = emptyList(),
                    reviewDone = 0, brilliantNotices = emptyList(), opening = null, practice = null, status = "新对局已开始") }
                persist(game)
                mutable.update { it.copy(busy = false, transitioning = false) }
                playFeedback(SoundCue.START)
                if (!game.humanWhite) advance()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(busy = false, transitioning = false, error = "新局保存失败：${e.message}") } }
        }
    }
    fun play(uci: String) {
        val state = mutable.value
        if (!state.ready || state.busy || state.page != 0 || state.game.finished || !state.humanTurn) return
        try {
            if (uci !in ChessRules.legal(state.game.moves)) { playFeedback(SoundCue.ILLEGAL); return }
            val current = state.game
            val game = finishIfNecessary(current.copy(moves = current.moves + uci))
            mutable.update { it.copy(game = game, cursor = game.moves.size, brilliantNotices = emptyList(), kingBreak = KingBreak.between(current, game)) }
            sounds.play(SoundEvents.transition(current, game))
            AutoReview.request(getApplication())
            advance()
            scheduleLiveAnalysis()
        } catch (e: Exception) { mutable.update { it.copy(error = e.message) } }
    }
    private fun finishIfNecessary(game: GameRecord): GameRecord = ChessRules.outcome(game.moves)?.let { (result, ending) ->
        game.copy(result = result, ending = ending, finished = true)
    } ?: game
    private suspend fun persist(game: GameRecord) = persistence.withLock {
        AutoReview.saving(getApplication(), true)
        try {
            // Concurrent cancellation/navigation must not overwrite a newer snapshot.
            val current = mutable.value.game
            val latest = if (current.id == game.id) current else game
            val timing = currentCoroutineContext()[AnalysisTiming]
            val saved = withContext(Dispatchers.IO) {
                val started = timing?.now()
                try { repository.save(latest) } finally { started?.let { timing?.duration("database_ms", it) } }
            }
            mutable.update { state ->
                val profile = if (saved.profile.ratedGames >= state.profile.ratedGames) saved.profile else state.profile
                state.copy(profile = profile, game = if (state.game.id == saved.game?.id) GameSnapshots.merge(state.game, saved.game) else state.game)
            }
        } finally { AutoReview.saving(getApplication(), false) }
    }
    private fun advance() {
        if (!mutable.value.ready || mutable.value.busy || mutable.value.transitioning) return
        val token = generation
        mutable.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try {
                persist(mutable.value.game)
                currentCoroutineContext().ensureActive()
                if (token == generation && !mutable.value.game.finished && !mutable.value.humanTurn && mutable.value.page == 0) {
                    mutable.update { it.copy(status = "对手正在思考…") }
                    val before = mutable.value.game
                    val started = SystemClock.elapsedRealtime()
                    val thinkingTime = OpponentPacing.targetMs()
                    val move = if (before.mode == Difficulty.MATCHED) humanOpponent.move(before, mutable.value.games)
                        else CloudSearchGate.mutex.withLock { opponent.move(before.moves) }
                    delay(OpponentPacing.remainingMs(thinkingTime, SystemClock.elapsedRealtime() - started))
                    currentCoroutineContext().ensureActive()
                    if (token != generation || mutable.value.game.id != before.id || mutable.value.page != 0 || mutable.value.game.finished) return@launch
                    val moved = withContext(Dispatchers.Default) { finishIfNecessary(before.copy(moves = before.moves + move)) }
                    currentCoroutineContext().ensureActive()
                    if (token != generation || mutable.value.game.id != before.id || mutable.value.page != 0 || mutable.value.game.finished) return@launch
                    mutable.update { state ->
                        // An analysis can finish while outcome checking runs on Default.
                        val game = state.game.copy(moves = moved.moves, result = moved.result, ending = moved.ending, finished = moved.finished)
                        state.copy(game = game, cursor = game.moves.size, kingBreak = KingBreak.between(state.game, game))
                    }
                    sounds.play(SoundEvents.transition(before, mutable.value.game))
                    persist(mutable.value.game)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(error = "本次计算失败：${e.message}，可点击继续重试。") } }
            finally {
                if (token == generation) {
                    mutable.update { it.copy(busy = false, status = if (it.game.finished) "${it.game.ending} · ${resultChinese(it.game)}" else "轮到${if (it.humanTurn) "你" else "对手"}走棋") }
                    scheduleLiveAnalysis()
                }
            }
        }
    }
    private fun scheduleLiveAnalysis() { AutoReview.request(getApplication()) }
    fun pauseAutoReview() { AutoReview.pause(getApplication()) }
    fun resumeAutoReview() { AutoReviewRetries(getApplication()).clear(); AutoReview.resume(getApplication()) }
    private suspend fun analyzePly(ply: Int, deep: Boolean, token: Int, profileOverride: String? = null) {
        if (token != generation) return
        check(mutable.value.settings.stockfishToken.isNotBlank()) { "请在对局设置中填写朋友提供的 Access Token" }
        val game = mutable.value.game
        if (ply <= 0 || ply > game.moves.size) return
        mutable.update {
            val status = when {
                it.page == 1 -> if (profileOverride == reviewProfile) "极速复评 $ply / ${game.moves.size}"
                    else if (deep) "深度复评 $ply / ${game.moves.size}" else "正在分析第 ${(ply + 1) / 2} 回合…"
                it.busy && !it.humanTurn -> "对手正在思考…"
                it.game.finished -> "后台深度分析第 $ply 步"
                else -> "后台深度分析第 $ply 步 · 可继续走棋"
            }
            it.copy(status = status)
        }
        persist(game)
        val scoringElo = scoringElo(game, ply)
        val timing = AnalysisTimings.begin(getApplication(), game.id, ply, deep, profileOverride)
        timing.put("scoring_elo", scoringElo)
        val review = try {
            withContext(timing) {
                val analysisStarted = timing.now()
                val result = try {
                    CloudSearchGate.mutex.withLock { withContext(Dispatchers.Default) { analyzer.analyze(game.moves.take(ply - 1), game.moves[ply - 1], deep, scoringElo, profileOverride) } }
                } finally { timing.duration("analysis_ms", analysisStarted) }
                currentCoroutineContext().ensureActive()
                if (token != generation || game.id != mutable.value.game.id) return@withContext null
                val savedReview = withContext(Dispatchers.IO) { repository.saveReview(game, result, onlyIfUnchanged = false) } ?: return@withContext null
                val updated = GameSnapshots.merge(mutable.value.game, savedReview)
                val announceBrilliant = mutable.value.page == 0 && result.ply >= mutable.value.game.moves.size - 1 &&
                    result.grade == Grade.BRILLIANT && !result.provisional && mutable.value.brilliantNotices.none { it.ply == ply }
                mutable.update { state ->
                    val notices = state.brilliantNotices.filterNot { it.ply == ply }
                    state.copy(game = updated, brilliantNotices = if (state.page == 0 && result.ply >= state.game.moves.size - 1 && result.grade == Grade.BRILLIANT && !result.provisional)
                        (notices + result).sortedBy { it.ply }.takeLast(2) else notices)
                }
                if (announceBrilliant) playFeedback(SoundCue.BRILLIANT)
                val saveStarted = timing.now()
                try { persist(updated) } finally { timing.duration("save_ms", saveStarted) }
                mutable.update { it.copy(lastAnalysisTimingId = timing.id) }
                result
            }
        } catch (e: CancellationException) {
            timing.finish("cancelled"); throw e
        } catch (e: Exception) {
            timing.finish("failed", e.javaClass.simpleName); throw e
        }
        timing.finish(if (review == null) "discarded" else "success")
        if (review == null) return
        // Only a promising sacrifice merits live verification; ordinary grades stay in review.
        if (!deep && review.pointsLost < 0.02 && ChessRules.legal(game.moves.take(ply - 1)).size > 1) {
            val sacrifice = (review.playedExpectedPoints ?: 0.5) >= 0.5 && ChessRules.substantialSacrifice(game.moves.take(ply - 1), review.played.pv)
            if (sacrifice) analyzePly(ply, true, token)
        }
    }
    private fun scoringElo(game: GameRecord, ply: Int): Int =
        AutoAnalysis.scoringElo(game, ply)

    fun page(page: Int) {
        if (mutable.value.transitioning) return
        if (page == mutable.value.page) return
        cancelWork()
        mutable.update { it.copy(page = page, cursor = it.game.moves.size, variation = emptyList(), lessonOpen = false,
            practice = null, opening = null, kingBreak = null, highlightsOpen = false, status = "引擎已就绪") }
        if (page == 0) {
            if (!mutable.value.game.finished && !mutable.value.humanTurn) advance()
            scheduleLiveAnalysis()
        }
    }
    fun load(game: GameRecord) {
        if (mutable.value.transitioning) return
        cancelWork()
        mutable.update { it.copy(game = HumanOpponent.prepare(game), page = 1, cursor = game.moves.size, variation = emptyList(), lessonOpen = false,
            practice = null, opening = null, kingBreak = null, highlightsOpen = false, highlights = emptyList(), brilliantNotices = emptyList(), error = null) }
    }
    fun cursor(ply: Int) {
        val before = mutable.value.boardHistory
        mutable.update { it.copy(cursor = ply.coerceIn(0, it.game.moves.size), variation = emptyList(), lessonOpen = false) }
        reviewSound(before, mutable.value.boardHistory)
    }
    fun step(delta: Int) {
        val state = mutable.value
        if (state.variation.isNotEmpty()) {
            mutable.update { it.copy(variationStep = (it.variationStep + delta).coerceIn(0, it.variation.size)) }
            reviewSound(state.boardHistory, mutable.value.boardHistory)
        }
        else if (!state.lessonOpen) cursor(state.cursor + delta)
    }
    fun showVariation(best: Boolean) {
        val state = mutable.value
        val review = state.chosenReview ?: return
        val base = review.ply - 1
        val pv = if (best) review.best.pv else review.played.pv
        val safe = ChessRules.legalVariation(state.game.moves.take(base), pv)
        mutable.update { it.copy(variation = safe, variationBase = base, variationStep = 0) }
    }
    fun retry() { if (mutable.value.page == 0) advance() else analyzeSelected() }
    fun explainSelected() {
        val state = mutable.value
        if (state.page != 1 || state.cursor == 0) return
        if (state.chosenLesson?.let { MoveCoach.canReuse(it, state.chosenReview) } == true ||
            state.chosenLesson != null && state.chosenReview == null) { showLessonVariation(); return }
        if (!state.ready || state.busy) return
        val token = generation
        val ply = state.cursor
        val gameId = state.game.id
        mutable.update { it.copy(busy = true, explainingPly = ply, lessonOpen = true, variation = emptyList(),
            variationStep = 0, lessonPlayed = false, error = null, status = "深入讲解第 $ply 步…") }
        work = viewModelScope.launch {
            try {
                // Updating an old explanation uses its saved engine evidence, without another cloud request.
                if (state.chosenLesson == null && state.chosenReview?.canReuseDeep(scoringElo(state.game, ply), stockfishClient.engineName) != true)
                    analyzePly(ply, true, token, profileOverride = reviewProfile)
                currentCoroutineContext().ensureActive()
                if (token != generation || mutable.value.game.id != gameId) return@launch
                val game = mutable.value.game
                val review = game.reviews.first { it.ply == ply }
                val lesson = withContext(Dispatchers.Default) { MoveCoach.explain(game.moves.take(ply - 1), review) }
                currentCoroutineContext().ensureActive()
                if (token != generation || mutable.value.game.id != gameId) return@launch
                mutable.update {
                    val open = it.lessonOpen && it.page == 1 && it.cursor == ply
                    it.copy(game = it.game.copy(lessons = (it.game.lessons.filterNot { note -> note.ply == ply } + lesson).sortedBy { note -> note.ply }),
                        variation = if (open) lesson.variation else it.variation,
                        variationBase = if (open) ply - 1 else it.variationBase,
                        variationStep = if (open) 0 else it.variationStep)
                }
                persist(mutable.value.game)
                playFeedback(SoundCue.CONFIRM)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(error = "本步讲解失败：${e.message}，可再次点击讲解。") } }
            finally { if (token == generation) mutable.update { it.copy(busy = false, explainingPly = null,
                status = if (it.game.lessons.any { note -> note.ply == ply }) "第 $ply 步讲解已保存" else "本步讲解未完成，可重试") } }
        }
    }
    fun showLessonVariation() {
        val state = mutable.value
        val lesson = state.chosenLesson ?: return
        val base = lesson.ply - 1
        val safe = ChessRules.legalVariation(state.game.moves.take(base), lesson.variation)
        mutable.update { it.copy(lessonOpen = true, variation = safe, variationBase = base, variationStep = 0, lessonPlayed = false) }
    }
    fun lessonRoute(played: Boolean) {
        val state = mutable.value
        if (!state.lessonOpen) return
        val lesson = state.chosenLesson ?: return
        val root = state.game.moves.take(state.cursor - 1)
        val line = if (played) lesson.playedVariation.ifEmpty {
            state.chosenReview?.let { MoveCoach.playedLine(root, it) }
                ?: listOf(state.game.moves[state.cursor - 1])
        } else lesson.variation
        val safe = ChessRules.legalVariation(root, line)
        mutable.update { it.copy(lessonPlayed = played, variation = safe, variationBase = state.cursor - 1, variationStep = 0) }
        reviewSound(state.boardHistory, mutable.value.boardHistory)
    }
    fun openWeakness(gameId: Long, ply: Int) {
        val game = mutable.value.games.find { it.id == gameId } ?: return
        if (mutable.value.transitioning) return
        val review = game.reviews.find { it.ply == ply } ?: return
        load(game)
        cursor(ply)
        if (mutable.value.chosenLesson?.let { MoveCoach.canReuse(it, review) } == true) {
            showLessonVariation()
            return
        }
        val token = generation
        mutable.update { it.copy(busy = true, lessonOpen = true, explainingPly = ply, lessonPlayed = false, status = "打开已保存的战术讲解…") }
        work = viewModelScope.launch {
            try {
                val lesson = withContext(Dispatchers.Default) { MoveCoach.explain(game.moves.take(ply - 1), review) }
                currentCoroutineContext().ensureActive()
                if (token != generation || mutable.value.game.id != gameId) return@launch
                mutable.update {
                    val open = it.lessonOpen && it.page == 1 && it.cursor == ply
                    it.copy(game = it.game.copy(lessons = (it.game.lessons.filterNot { note -> note.ply == ply } + lesson).sortedBy { note -> note.ply }),
                        variation = if (open) lesson.variation else it.variation,
                        variationBase = if (open) ply - 1 else it.variationBase,
                        variationStep = if (open) 0 else it.variationStep)
                }
                persist(mutable.value.game)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (token == generation) mutable.update { it.copy(error = "已保存讲解暂时无法打开：${e.message}") } }
            finally { if (token == generation) mutable.update { it.copy(busy = false, explainingPly = null) } }
        }
    }
    fun closeLesson() { mutable.update { it.copy(lessonOpen = false, variation = emptyList(), variationStep = 0,
        page = if (it.practice != null) 3 else it.page) } }
    fun startPractice() {
        val state = mutable.value
        if (!state.ready || state.busy || state.transitioning || state.practiceQuestions.isEmpty()) return
        val questions = MistakePractice.queue(state.practiceQuestions, state.practiceProgress, System.currentTimeMillis())
        cancelWork()
        mutable.update { it.copy(page = 3, trainingSection = 1, opening = null, practice = PracticeSession(questions), error = null,
            lessonOpen = false, highlightsOpen = false, variation = emptyList()) }
    }
    fun closePractice() { mutable.update { it.copy(practice = null) } }
    fun practiceHint() {
        val session = mutable.value.practice ?: return
        if (session.current == null || session.finished) return
        val hints = (session.hints + 1).coerceAtMost(2)
        mutable.update { it.copy(practice = session.copy(hints = hints, message = "")) }
    }
    fun practiceAnswer(uci: String) {
        val session = mutable.value.practice ?: return
        val question = session.current ?: return
        if (session.finished) return
        if (uci !in ChessRules.legal(question.history)) { playFeedback(SoundCue.ILLEGAL); return }
        if (uci in question.answers) {
            val independent = session.hints == 0 && session.wrongAttempts == 0
            finishPractice(session.copy(solvedMove = uci, message = if (independent) "独立答对了，明天再复习。" else "找到了。用过提示或尝试过其他走法，稍后再练一次。"), independent)
            reviewSound(question.history, question.history + uci)
            playFeedback(SoundCue.CONFIRM)
        } else {
            val message = if (uci == question.review.uci) "这正是实战走过的失误。${MistakePractice.hint(question, 1)}"
                else "这着没有匹配已保存的答案，请再找一种走法。其他候选没有重新搜索，不能据此断言这着一定错误。"
            mutable.update { it.copy(practice = session.copy(wrongAttempts = session.wrongAttempts + 1, message = message)) }
            playFeedback(SoundCue.ERROR)
        }
    }
    fun practiceReveal() {
        val session = mutable.value.practice ?: return
        if (session.current == null || session.finished) return
        finishPractice(session.copy(revealed = true, message = "已查看答案，本次记为辅助完成，十分钟后再复习。"), independent = false)
    }
    private fun finishPractice(session: PracticeSession, independent: Boolean) {
        val question = session.current ?: return
        val current = mutable.value
        val record = MistakePractice.record(question, MistakePractice.effective(question, current.practiceProgress), independent, System.currentTimeMillis())
        val records = current.practiceProgress + (question.key to record)
        practicePreferences.save(records)
        mutable.update { it.copy(practiceProgress = records, practice = session.copy(
            independent = session.independent + if (independent) 1 else 0,
            assisted = session.assisted + if (independent) 0 else 1)) }
    }
    fun practiceNext() {
        val session = mutable.value.practice ?: return
        if (!session.finished) return
        mutable.update { it.copy(practice = session.next()) }
    }
    fun practiceExplain() {
        val session = mutable.value.practice ?: return
        if (!session.finished) return
        val question = session.current ?: return
        openWeakness(question.gameId, question.ply)
        mutable.update { it.copy(practice = session) }
    }
    fun lessonSeek(step: Int) {
        if (!mutable.value.lessonOpen) return
        val before = mutable.value.boardHistory
        mutable.update { it.copy(variationStep = step.coerceIn(0, it.variation.size)) }
        reviewSound(before, mutable.value.boardHistory)
    }
    fun analyzeSelected() {
        val state = mutable.value
        if (!state.ready || state.busy || state.page != 1 || state.cursor == 0) return
        if (state.settings.stockfishToken.isBlank()) {
            mutable.update { it.copy(error = "未配置云端 Access Token，请在对局设置中配置") }
            return
        }
        val token = generation
        mutable.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try { analyzePly(state.cursor, true, token, profileOverride = reviewProfile) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message) } }
            finally { if (token == generation) mutable.update { it.copy(busy = false, status = "本步复评完成") } }
        }
    }
    fun reviewAll() = reviewGame(guided = false)
    fun reviewHighlights() {
        if (mutable.value.busy || mutable.value.game.moves.isEmpty()) return
        if (mutable.value.page != 1) page(1)
        reviewGame(guided = true)
    }
    fun closeHighlights() { mutable.update { it.copy(highlightsOpen = false) } }
    fun finishKingBreak(gameId: Long) {
        mutable.update { if (it.kingBreak?.gameId == gameId) it.copy(kingBreak = null) else it }
    }
    private fun reviewGame(guided: Boolean) {
        if (!mutable.value.ready || mutable.value.busy || mutable.value.page != 1 || mutable.value.game.moves.isEmpty()) return
        val current = mutable.value.game
        val plies = (1..current.moves.size).filter { !guided || current.isPlayerMove(it) }
        val needsSearch = !guided || plies.any { ply ->
            current.reviews.find { it.ply == ply }?.canReuseDeep(scoringElo(current, ply), stockfishClient.engineName) != true
        }
        if (needsSearch && mutable.value.settings.stockfishToken.isBlank()) {
            mutable.update { it.copy(error = "未配置云端 Access Token，请在对局设置中配置") }
            return
        }
        val token = generation
        mutable.update { it.copy(busy = true, reviewDone = 0, error = null, lessonOpen = false, highlightsOpen = false, variation = emptyList(),
            status = if (guided) "正在寻找本局关键点…" else "正在用 lightning 逐步复评整盘棋…") }
        work = viewModelScope.launch {
            try {
                val game = mutable.value.game
                for ((index, ply) in plies.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val review = mutable.value.game.reviews.find { it.ply == ply }
                    if (!guided || review?.canReuseDeep(scoringElo(game, ply), stockfishClient.engineName) != true)
                        analyzePly(ply, true, token, profileOverride = reviewProfile)
                    mutable.update { it.copy(reviewDone = index + 1, status = if (guided)
                        "分析你的棋步 ${index + 1} / ${plies.size}" else "极速复盘 ${index + 1} / ${plies.size}") }
                }
                currentCoroutineContext().ensureActive()
                if (guided && token == generation && mutable.value.game.id == game.id) {
                    val highlights = withContext(Dispatchers.Default) { GameHighlights.build(mutable.value.game) }
                    currentCoroutineContext().ensureActive()
                    if (token == generation && mutable.value.page == 1 && mutable.value.game.id == game.id)
                        mutable.update { it.copy(highlights = highlights, highlightsOpen = true) }
                    playFeedback(SoundCue.CONFIRM)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = "复评失败：${e.message}") } }
            finally { if (token == generation) mutable.update { it.copy(busy = false,
                status = if (it.error != null) "复评未完成，已完成的结果已保存，可重试。" else "复评已保存") } }
        }
    }
    fun pauseReview() { cancelWork(); mutable.update { it.copy(status = "复评已暂停，已完成的结果已保存") } }
    fun resign() {
        if (!mutable.value.ready || mutable.value.transitioning || mutable.value.game.finished) return
        cancelWork()
        val before = mutable.value.game
        val game = before.copy(finished = true, result = if (before.humanWhite) "0-1" else "1-0", ending = "认输")
        mutable.update { it.copy(game = game, status = "对局已结束", kingBreak = KingBreak.between(it.game, game)) }
        sounds.play(SoundEvents.transition(before, game))
        viewModelScope.launch { persist(game); scheduleLiveAnalysis() }
    }
    fun claimDraw() {
        val state = mutable.value
        if (state.busy || !state.humanTurn || state.game.finished) return
        val reason = ChessRules.drawClaim(state.game.moves) ?: return
        cancelWork()
        val game = state.game.copy(finished = true, result = "1/2-1/2", ending = reason)
        mutable.update { it.copy(game = game, status = "和棋 · $reason") }
        sounds.play(SoundEvents.transition(state.game, game))
        viewModelScope.launch { persist(game); scheduleLiveAnalysis() }
    }
    fun delete(game: GameRecord) {
        if (mutable.value.transitioning) return
        val active = mutable.value.game.id == game.id
        val snapshot = mutable.value.game
        if (active) cancelWork(false)
        mutable.update { it.copy(transitioning = true) }
        viewModelScope.launch {
            try {
                if (active && snapshot.finished) persist(snapshot)
                persistence.withLock { repository.delete(game.id) }
                mutable.update { state ->
                    if (state.game.id == game.id) state.copy(game = EloRules.newGame(state.profile, state.settings.mode, state.settings.color.humanWhite),
                        page = 2, cursor = 0, variation = emptyList(), lessonOpen = false, kingBreak = null, highlightsOpen = false, highlights = emptyList(), busy = false, reviewDone = 0,
                        brilliantNotices = emptyList(), transitioning = false, status = "棋谱已删除")
                    else state.copy(transitioning = false)
                }
                playFeedback(SoundCue.DELETE)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(transitioning = false, error = "删除失败：${e.message}") } }
        }
    }
    fun share(diagnostics: Boolean): Intent {
        val game = mutable.value.game
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "yibu-${game.id}.${if (diagnostics) "json" else "pgn"}")
        file.writeText(if (diagnostics) repository.diagnostics(game) else ChessRules.pgn(game))
        val uri = FileProvider.getUriForFile(getApplication(), "cn.yibu.chess.files", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = if (diagnostics) "application/json" else "application/x-chess-pgn"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, if (diagnostics) "导出对局诊断" else "分享棋谱")
    }
    fun shareLicenses(): Intent {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "yibu-open-source.txt")
        getApplication<Application>().assets.open("licenses/OPEN_SOURCE.txt").use { input -> file.outputStream().use { input.copyTo(it) } }
        val uri = FileProvider.getUriForFile(getApplication(), "cn.yibu.chess.files", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "开源许可证")
    }
    fun shareRuntimeDiagnostics(): Intent {
        val application = getApplication<Application>()
        val dir = File(application.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "yibu-runtime-diagnostics.json")
        file.writeText(RuntimeDiagnostics.collect(application))
        val uri = FileProvider.getUriForFile(application, "cn.yibu.chess.files", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "导出运行诊断")
    }
    fun shareAnalysisTimings(): Intent {
        val application = getApplication<Application>()
        val dir = File(application.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "yibu-analysis-timings.json")
        file.writeText(AnalysisTimings.export(application))
        val uri = FileProvider.getUriForFile(application, "cn.yibu.chess.files", file)
        return Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "导出分析耗时")
    }
    fun pauseForBackground() {
        sounds.pause()
        cancelOpeningWork()
        mutable.update { it.copy(kingBreak = null) }
        if ((mutable.value.busy || mutable.value.analyzing) && !mutable.value.transitioning) { cancelWork(); mutable.update { it.copy(status = "已暂停计算，棋谱已保存") } }
    }
    fun resumeForeground() {
        sounds.resume()
        val state = mutable.value
        if (state.ready && state.page == 0 && !state.busy) {
            if (!state.game.finished && !state.humanTurn) advance()
            scheduleLiveAnalysis()
        }
    }
    fun resultChinese(game: GameRecord): String = when (game.result) {
        "1-0" -> "白方获胜"; "0-1" -> "黑方获胜"; "1/2-1/2" -> "和棋"; else -> "进行中"
    }
    suspend fun testStockfishConnection(token: String): Result<String> = stockfishClient.checkHealth(token)
    override fun onCleared() { work?.cancel(); importWork?.cancel(); openingWork?.cancel(); stockfishClient.stop(); AutoReview.interactive(false, null); sounds.release(); super.onCleared() }
}
