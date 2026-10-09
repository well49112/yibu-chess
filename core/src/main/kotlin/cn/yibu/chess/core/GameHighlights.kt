package cn.yibu.chess.core

import kotlin.math.abs

/** Transient end event. Loading a finished record never creates a celebration. */
data class KingBreak(val gameId: Long, val square: Int, val white: Boolean, val checkmate: Boolean) {
    companion object {
        fun between(before: GameRecord, after: GameRecord): KingBreak? {
            if (before.id != after.id || before.finished || !after.finished ||
                after.ending !in listOf("将杀", "认输") || after.result !in listOf("1-0", "0-1")) return null
            val white = after.result == "0-1"
            val square = ChessRules.fenPieces(ChessRules.board(after.moves).fen).indexOf(if (white) 'K' else 'k')
            return square.takeIf { it >= 0 }?.let { KingBreak(after.id, it, white, after.ending == "将杀") }
        }
    }
}

data class ReviewHighlight(val ply: Int, val title: String, val reason: String, val lesson: MoveLesson?)

/** Select the player's concrete teaching moments; never pad a tour with ordinary moves. */
object GameHighlights {
    fun build(game: GameRecord): List<ReviewHighlight> {
        if (game.moves.isEmpty()) return emptyList()
        val reviews = game.reviews.filter { it.ply in 1..game.moves.size && it.uci == game.moves[it.ply - 1] &&
            it.moverWhite == game.humanWhite && !it.provisional && it.grade !in listOf(Grade.UNSTABLE, Grade.FORCED) &&
            it.algorithmVersion == 3 && it.best.depth >= 12 && it.best.depth == it.played.depth }.distinctBy { it.ply }
        fun weight(review: MoveReview): Double = review.pointsLost * 100 + when {
            review.grade == Grade.BRILLIANT -> 100.0
            review.played.mate != null && review.played.mate > 0 -> 40.0
            review.best.mate != null && review.best.mate > 0 && (review.played.mate ?: 0) <= 0 -> 40.0
            review.grade == Grade.GREAT -> 24.0
            else -> 0.0
        }
        val reasons = reviews.mapNotNull { review ->
            runCatching { MoveCoach.keyReason(game.moves.take(review.ply - 1), review) }.getOrNull()
                ?.let { review to it }
        }
        val selected = mutableListOf<Pair<MoveReview, String>>()
        reasons.sortedByDescending { weight(it.first) }.forEach { candidate ->
            if (selected.size < 5 && selected.none { abs(it.first.ply - candidate.first.ply) < 3 }) selected += candidate
        }
        return selected.sortedBy { it.first.ply }.mapNotNull { (review, reason) ->
            val history = game.moves.take(review.ply - 1)
            val lesson = runCatching { MoveCoach.explain(history, review) }.getOrNull() ?: return@mapNotNull null
            val missedMate = review.best.mate != null && review.best.mate > 0 && (review.played.mate ?: 0) <= 0
            val title = when {
                review.grade == Grade.BRILLIANT -> "精彩弃子 !!"
                missedMate -> "错过将杀机会"
                review.pointsLost >= .04 -> "值得改进的一步"
                review.played.mate != null && review.played.mate > 0 -> "将杀的关键一步"
                else -> "有价值的好棋"
            }
            ReviewHighlight(review.ply, title, "你走了 ${review.san}。\n$reason", lesson)
        }
    }
}
