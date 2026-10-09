package cn.yibu.chess.core

import kotlinx.serialization.Serializable
import com.github.bhlangonijr.chesslib.move.Move

data class PracticeQuestion(val gameId: Long, val ply: Int, val history: List<String>,
    val review: MoveReview, val type: WeaknessType, val evidence: String, val answers: Set<String>) {
    val key: String get() = "$gameId:$ply"
    val bestMove: String get() = review.bestMove
    val humanWhite: Boolean get() = history.size % 2 == 0
}

@Serializable
data class PracticeProgress(val key: String, val bestMove: String, val completed: Int = 0,
    val independentCorrect: Int = 0, val streak: Int = 0, val nextDueAt: Long = 0, val lastReviewedAt: Long = 0)

data class PracticeSession(val questions: List<PracticeQuestion>, val index: Int = 0,
    val hints: Int = 0, val wrongAttempts: Int = 0, val solvedMove: String? = null, val revealed: Boolean = false,
    val message: String = "", val independent: Int = 0, val assisted: Int = 0) {
    val current: PracticeQuestion? get() = questions.getOrNull(index)
    val finished: Boolean get() = solvedMove != null || revealed
    fun next(): PracticeSession = copy(index = index + 1, hints = 0, wrongAttempts = 0,
        solvedMove = null, revealed = false, message = "")
}

object MistakePractice {
    const val MAX_SESSION = 10
    private const val DAY = 86_400_000L

    /** Reuse the same confirmed tactical evidence as the weakness report; never search the engine. */
    fun questions(games: List<GameRecord>, report: WeaknessReport): List<PracticeQuestion> {
        val records = games.associateBy { it.id }
        return report.groups.flatMap { group -> group.examples.mapNotNull { example ->
            val game = records[example.gameId] ?: return@mapNotNull null
            val review = game.reviews.find { it.ply == example.ply } ?: return@mapNotNull null
            if (!WeaknessStats.confirmed(game, review)) return@mapNotNull null
            val history = game.moves.take(review.ply - 1)
            val legal = ChessRules.legal(history)
            val answers = mutableSetOf(review.bestMove)
            review.second?.takeIf { second -> second.depth == review.best.depth && second.pv.firstOrNull() in legal &&
                second.pv.firstOrNull() != review.uci &&
                RatingRules.expectedPoints(review.best, review.scoringElo) - RatingRules.expectedPoints(second, review.scoringElo) < .02
            }?.pv?.firstOrNull()?.let(answers::add)
            PracticeQuestion(game.id, review.ply, history, review, group.type, example.evidence, answers)
        } }.distinctBy { it.key }
    }

    fun effective(question: PracticeQuestion, records: Map<String, PracticeProgress>): PracticeProgress =
        records[question.key]?.takeIf { it.bestMove == question.bestMove } ?: PracticeProgress(question.key, question.bestMove)

    fun queue(questions: List<PracticeQuestion>, records: Map<String, PracticeProgress>, now: Long): List<PracticeQuestion> {
        val sorted = questions.sortedWith(compareBy<PracticeQuestion> { effective(it, records).nextDueAt }
            .thenByDescending { it.review.pointsLost }.thenBy { it.key })
        val due = sorted.filter { effective(it, records).nextDueAt <= now }
        return due.ifEmpty { sorted }.take(MAX_SESSION)
    }

    fun record(question: PracticeQuestion, previous: PracticeProgress, independent: Boolean, now: Long): PracticeProgress {
        val old = previous.takeIf { it.key == question.key && it.bestMove == question.bestMove }
            ?: PracticeProgress(question.key, question.bestMove)
        val streak = if (independent) old.streak + 1 else 0
        val interval = if (!independent) 600_000L else when (streak) { 1 -> DAY; 2 -> 3 * DAY; 3 -> 7 * DAY; else -> 14 * DAY }
        return old.copy(completed = old.completed + 1, independentCorrect = old.independentCorrect + if (independent) 1 else 0,
            streak = streak, nextDueAt = now + interval, lastReviewedAt = now)
    }

    fun hint(question: PracticeQuestion, level: Int): String = if (level <= 1) when (question.type) {
        WeaknessType.HANGING_PIECE -> "先检查对手能直接吃哪些子，自己能不能回吃。"
        WeaknessType.ALLOWED_MATE -> "先找对手的将军，检查王的逃跑格与能否挡住将军。"
        WeaknessType.MISSED_MATE -> "先找你的将军，再计算对手所有合法的解将。"
        WeaknessType.MISSED_FORK -> "留意一着同时攻击两个重要目标的机会。"
        WeaknessType.MISSED_CAPTURE -> "先看能吃哪些子，把对手的回吃也算进去。"
    } else {
        val board = ChessRules.board(question.history)
        val move = Move(question.bestMove, board.sideToMove)
        "重点考虑 ${move.from.toString().lowercase()} 的${ChessRules.pieceChinese(board.getPiece(move.from))}能走到哪里。"
    }
}
