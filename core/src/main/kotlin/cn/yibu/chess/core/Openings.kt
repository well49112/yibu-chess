package cn.yibu.chess.core

data class OpeningCheckpoint(val atPly: Int, val prompt: String, val hint: String, val answers: Map<String, String>)
data class OpeningRoute(val id: String, val title: String, val moves: List<String>, val notes: List<String>,
    val checkpoint: OpeningCheckpoint, val identityPlies: Int)
data class OpeningCourse(val id: String, val title: String, val humanWhite: Boolean, val goal: String,
    val plan: String, val watchOut: String, val routes: List<OpeningRoute>)
data class OpeningMatch(val course: OpeningCourse, val routeIndex: Int, val sharedPlies: Int, val deviationPly: Int?)
enum class OpeningMode { LEARN, QUIZ, FREE }

data class OpeningSession(val courseId: String, val routeIndex: Int = 0, val cursor: Int = 0,
    val mode: OpeningMode = OpeningMode.LEARN, val freeMoves: List<String> = emptyList(),
    val solvedMove: String? = null, val hint: Boolean = false, val message: String = "") {
    val course: OpeningCourse get() = OpeningCourses.all.first { it.id == courseId }
    val route: OpeningRoute get() = course.routes[routeIndex]
    val history: List<String> get() = when (mode) {
        OpeningMode.LEARN -> route.moves.take(cursor)
        OpeningMode.QUIZ -> route.moves.take(route.checkpoint.atPly) + listOfNotNull(solvedMove)
        OpeningMode.FREE -> freeMoves
    }
    val key: String get() = "$courseId:${route.id}"
    fun seek(ply: Int): OpeningSession = copy(cursor = ply.coerceIn(0, route.moves.size), mode = OpeningMode.LEARN,
        solvedMove = null, hint = false, message = "")
    fun changeRoute(index: Int): OpeningSession {
        require(index in course.routes.indices)
        return copy(routeIndex = index, cursor = 0, mode = OpeningMode.LEARN, freeMoves = emptyList(), solvedMove = null, hint = false, message = "")
    }
    fun quiz(): OpeningSession = copy(mode = OpeningMode.QUIZ, solvedMove = null, hint = false, message = "")
    fun explore(): OpeningSession = copy(freeMoves = history, cursor = if (mode == OpeningMode.LEARN) cursor else history.size,
        mode = OpeningMode.FREE, solvedMove = null, message = "")
    fun answer(uci: String): OpeningSession {
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
