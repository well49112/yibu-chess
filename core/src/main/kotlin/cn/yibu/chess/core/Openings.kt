package cn.yibu.chess.core

data class OpeningCheckpoint(val atPly: Int, val prompt: String, val hint: String, val answers: Map<String, String>)
data class OpeningRoute(val id: String, val title: String, val moves: List<String>, val notes: List<String>,
    val checkpoint: OpeningCheckpoint, val identityPlies: Int)
data class OpeningCourse(val id: String, val title: String, val humanWhite: Boolean, val goal: String,
    val plan: String, val watchOut: String, val routes: List<OpeningRoute>)
data class OpeningMatch(val course: OpeningCourse, val routeIndex: Int, val sharedPlies: Int, val deviationPly: Int?)
enum class OpeningMode { GUIDE, LEARN, QUIZ, FREE }
enum class OpeningLessonPhase { INTRO, TASK, FEEDBACK, DONE }

data class OpeningSession(val courseId: String, val routeIndex: Int = 0, val cursor: Int = 0,
    val mode: OpeningMode = OpeningMode.GUIDE, val freeMoves: List<String> = emptyList(),
    val solvedMove: String? = null, val hint: Boolean = false, val message: String = "",
    val lessonPhase: OpeningLessonPhase = OpeningLessonPhase.INTRO, val lessonStep: Int = 0,
    val lessonOrder: List<Int> = emptyList(), val retryPlies: Set<Int> = emptySet(), val retrying: Boolean = false,
    val hintLevel: Int = 0, val attempts: Int = 0, val firstTry: Int = 0, val assisted: Int = 0) {
    val course: OpeningCourse get() = OpeningCourses.all.first { it.id == courseId }
    val route: OpeningRoute get() = course.routes[routeIndex]
    val history: List<String> get() = when (mode) {
        OpeningMode.GUIDE -> when (lessonPhase) {
            OpeningLessonPhase.INTRO -> emptyList()
            OpeningLessonPhase.TASK -> route.moves.take(lessonPly)
            OpeningLessonPhase.FEEDBACK -> route.moves.take(lessonPly) + listOfNotNull(solvedMove)
            OpeningLessonPhase.DONE -> route.moves
        }
        OpeningMode.LEARN -> route.moves.take(cursor)
        OpeningMode.QUIZ -> route.moves.take(route.checkpoint.atPly) + listOfNotNull(solvedMove)
        OpeningMode.FREE -> freeMoves
    }
    val key: String get() = "$courseId:${route.id}"
    val lessonPlies: List<Int> get() = route.moves.indices.filter { (it % 2 == 0) == course.humanWhite }
    val lessonDeck: List<Int> get() = lessonOrder.ifEmpty { lessonPlies }
    val lessonPly: Int get() = lessonDeck.getOrElse(lessonStep) { lessonPlies.last() }
    val lessonTask: OpeningCheckpoint get() {
        if (route.checkpoint.atPly == lessonPly) return route.checkpoint
        val move = route.moves[lessonPly]
        val note = route.notes[lessonPly]
        return OpeningCheckpoint(lessonPly, "把这个目标走出来：${note.substringBefore('；').substringBefore('。')}。",
            "先考虑 ${move.take(2)} 的棋子。", mapOf(move to note))
    }
    val lessonDone: Int get() = if (lessonPhase == OpeningLessonPhase.DONE || retrying) lessonPlies.size
        else if (lessonPhase == OpeningLessonPhase.INTRO) 0 else lessonStep + if (lessonPhase == OpeningLessonPhase.FEEDBACK) 1 else 0
    fun guided(): OpeningSession = OpeningSession(courseId, routeIndex)
    /** Opponent replies and new exercises advance only when the learner taps Continue. */
    fun continueLesson(): OpeningSession {
        if (mode != OpeningMode.GUIDE) return guided()
        if (lessonPhase == OpeningLessonPhase.INTRO) return copy(lessonPhase = OpeningLessonPhase.TASK,
            lessonOrder = lessonPlies, cursor = lessonPlies.first())
        if (lessonPhase != OpeningLessonPhase.FEEDBACK) return this
        if (lessonStep + 1 < lessonDeck.size) return copy(lessonPhase = OpeningLessonPhase.TASK, lessonStep = lessonStep + 1,
            cursor = lessonDeck[lessonStep + 1], solvedMove = null, message = "", hintLevel = 0, attempts = 0)
        if (!retrying && retryPlies.isNotEmpty()) return copy(lessonPhase = OpeningLessonPhase.TASK, lessonOrder = retryPlies.sorted(),
            lessonStep = 0, retrying = true, cursor = retryPlies.min(), solvedMove = null, message = "", hintLevel = 0, attempts = 0)
        return copy(lessonPhase = OpeningLessonPhase.DONE, cursor = route.moves.size, solvedMove = null, message = "")
    }
    fun lessonHint(): OpeningSession = if (mode == OpeningMode.GUIDE && lessonPhase == OpeningLessonPhase.TASK)
        copy(hintLevel = (hintLevel + 1).coerceAtMost(2), retryPlies = retryPlies + lessonPly) else copy(hint = true)
    fun showLessonMove(): OpeningSession = if (mode == OpeningMode.GUIDE && lessonPhase == OpeningLessonPhase.TASK)
        copy(hintLevel = 2, retryPlies = retryPlies + lessonPly).answer(route.moves[lessonPly]) else this
    fun seek(ply: Int): OpeningSession = copy(cursor = ply.coerceIn(0, route.moves.size), mode = OpeningMode.LEARN,
        solvedMove = null, hint = false, message = "")
    fun changeRoute(index: Int): OpeningSession {
        require(index in course.routes.indices)
        return OpeningSession(courseId, index)
    }
    fun quiz(): OpeningSession = copy(mode = OpeningMode.QUIZ, solvedMove = null, hint = false, message = "")
    fun explore(): OpeningSession = copy(freeMoves = history, cursor = if (mode == OpeningMode.LEARN) cursor else history.size,
        mode = OpeningMode.FREE, solvedMove = null, message = "")
    fun answer(uci: String): OpeningSession {
        if (mode == OpeningMode.GUIDE) {
            if (lessonPhase != OpeningLessonPhase.TASK) return this
            if (uci !in ChessRules.legal(history)) return copy(message = "这着不合法，请重新选择。")
            val why = lessonTask.answers[uci]
            if (why == null) return copy(attempts = attempts + 1, retryPlies = retryPlies + lessonPly,
                message = "你走了 ${ChessRules.san(history, uci)}。这着合法，但本关要完成的是：${lessonTask.prompt} 换个走法试试；这里练习课程路线，不把其他走法判成坏棋。")
            return copy(lessonPhase = OpeningLessonPhase.FEEDBACK, solvedMove = uci, message = why,
                firstTry = firstTry + if (!retrying && attempts == 0 && hintLevel == 0) 1 else 0,
                assisted = assisted + if (!retrying && hintLevel > 0) 1 else 0)
        }
        if (mode == OpeningMode.LEARN || mode == OpeningMode.QUIZ && solvedMove != null) return this
        if (uci !in ChessRules.legal(history)) return copy(message = "这着不合法，请重新选择。")
        if (mode == OpeningMode.FREE) return copy(freeMoves = freeMoves + uci, message = "")
        val answer = route.checkpoint.answers[uci]
        return if (answer != null) copy(solvedMove = uci, message = answer)
        else copy(message = "${ChessRules.san(history, uci)} 是合法走法。本题练习的是：${route.checkpoint.prompt}。它没有匹配课程答案，不代表这着一定不好；可在自由试走中比较。")
    }
    fun training(profile: PlayerProfile): GameRecord {
        require(history.isNotEmpty() && ChessRules.outcome(history) == null)
        require(ChessRules.legalVariation(emptyList(), history) == history)
        return EloRules.newGame(profile, Difficulty.MATCHED, course.humanWhite).copy(moves = history, rated = false,
            openingTraining = OpeningTraining(course.id, route.id, history.size))
    }
}
