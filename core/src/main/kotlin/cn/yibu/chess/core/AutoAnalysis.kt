package cn.yibu.chess.core

object AutoAnalysis {
    const val PROFILE = "lightning"
    fun scoringElo(game: GameRecord, ply: Int): Int = if ((ply % 2 == 1) == game.humanWhite)
        game.playerEloAtStart ?: 500 else game.opponentElo ?: if (game.mode == Difficulty.STRONG) 2800 else 500
    fun complete(game: GameRecord, ply: Int, engine: String = "Stockfish 19"): Boolean =
        game.reviews.any { it.ply == ply && it.uci == game.moves.getOrNull(ply - 1) &&
            it.canReuseDeep(scoringElo(game, ply), engine, PROFILE) }
    fun missing(game: GameRecord, engine: String = "Stockfish 19"): List<Int> {
        val reviews = game.reviews.associateBy { it.ply }
        return (1..game.moves.size).filter { ply -> reviews[ply]?.let { it.uci == game.moves[ply - 1] &&
            it.canReuseDeep(scoringElo(game, ply), engine, PROFILE) } != true }
    }
    fun retryDelay(attempt: Int): Long = when (attempt) { 1 -> 30_000; 2 -> 60_000; 3 -> 120_000; 4 -> 300_000; else -> 900_000 }
}

/** Merge delayed UI snapshots without losing completed work saved by the service. */
object GameSnapshots {
    fun merge(incoming: GameRecord, stored: GameRecord?): GameRecord {
        if (stored == null) return incoming
        require(stored.id == incoming.id)
        val base = if (stored.finished && !incoming.finished || stored.moves.size > incoming.moves.size) stored else incoming
        val candidates = listOf(stored, incoming).flatMap { game -> game.reviews.filter { review ->
            review.ply in 1..base.moves.size && review.uci == base.moves[review.ply - 1] &&
                game.moves.take(review.ply) == base.moves.take(review.ply)
        } }
        val reviews = candidates.groupBy { it.ply }.values.map { samePly ->
            samePly.withIndex().maxWith(compareBy<IndexedValue<MoveReview>> { it.value.analyzedAt }.thenBy { it.index }).value
        }.sortedBy { it.ply }
        val lessons = listOf(stored, incoming).flatMap { game -> game.lessons.filter { lesson ->
            lesson.ply in 1..base.moves.size && game.moves.take(lesson.ply) == base.moves.take(lesson.ply) &&
                (reviews.find { it.ply == lesson.ply }?.let { it == game.reviews.find { review -> review.ply == lesson.ply } || MoveCoach.canReuse(lesson, it) } ?: true)
        } }.associateBy { it.ply }.values.sortedBy { it.ply }
        return base.copy(reviews = reviews, lessons = lessons, ratingChange = stored.ratingChange ?: base.ratingChange)
    }
}
