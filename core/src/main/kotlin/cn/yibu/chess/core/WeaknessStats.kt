package cn.yibu.chess.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

enum class WeaknessType(val title: String, val goal: String) {
    ALLOWED_MATE("漏防将杀", "接下来三局，落子前检查对手能否将军，以及王还有哪些逃跑格。"),
    HANGING_PIECE("漏看直接丢子", "接下来三局，落子前检查对手能吃哪些子，并确认自己能否回吃。"),
    MISSED_MATE("错过将杀", "接下来三局，有进攻机会时先检查将军，再计算对手的所有合法解将。"),
    MISSED_FORK("错过双攻赢子", "接下来三局，留意一着同时攻击王、后或车的机会，并算清对手应对。"),
    MISSED_CAPTURE("错过直接赢子", "接下来三局，先检查能吃的棋子，再把对手的回吃算进去。"),
}

data class WeaknessExample(val gameId: Long, val ply: Int, val san: String, val evidence: String)
data class WeaknessGroup(val type: WeaknessType, val examples: List<WeaknessExample>) {
    val count: Int get() = examples.size
    val games: Int get() = examples.map { it.gameId }.distinct().size
}
data class WeaknessReport(
    val games: Int = 0,
    val analyzedGames: Int = 0,
    val playerMoves: Int = 0,
    val confirmedMoves: Int = 0,
    val groups: List<WeaknessGroup> = emptyList(),
)

/** Conservative tactical labels, derived locally from the player's saved, confirmed analysis. */
object WeaknessStats {
    const val MAX_GAMES = 20

    /** Reuse unchanged games when another background analysis or lesson save arrives. */
    class Tracker {
        private val cache = mutableMapOf<Long, Pair<GameRecord, WeaknessReport>>()
        fun build(records: List<GameRecord>): WeaknessReport {
            val games = records.distinctBy { it.id }.filter { it.finished }
                .sortedWith(compareByDescending<GameRecord> { it.startedAt }.thenByDescending { it.id }).take(MAX_GAMES)
            cache.keys.retainAll(games.map { it.id }.toSet())
            val reports = games.map { game ->
                val saved = cache[game.id]
                val report = saved?.takeIf { (old, _) -> old.humanWhite == game.humanWhite &&
                    old.moves == game.moves && old.reviews == game.reviews && old.openingTraining == game.openingTraining }?.second ?: WeaknessStats.build(listOf(game))
                cache[game.id] = game to report
                report
            }
            val groups = reports.flatMap { it.groups }.groupBy { it.type }.map { (type, matches) ->
                WeaknessGroup(type, matches.flatMap { it.examples })
            }.sortedWith(compareByDescending<WeaknessGroup> { it.count }.thenBy { it.type.ordinal })
            return WeaknessReport(games.size, reports.sumOf { it.analyzedGames }, reports.sumOf { it.playerMoves },
                reports.sumOf { it.confirmedMoves }, groups)
        }
    }

    fun confirmed(game: GameRecord, review: MoveReview): Boolean =
        review.ply in 1..game.moves.size && review.uci == game.moves[review.ply - 1] &&
            game.isPlayerMove(review.ply) && !review.provisional &&
            review.grade != Grade.UNSTABLE && review.algorithmVersion == 3 &&
            review.best.depth >= 12 && review.best.depth == review.played.depth &&
            review.best.pv.firstOrNull() in ChessRules.legal(game.moves.take(review.ply - 1)) &&
            review.played.pv.firstOrNull() == review.uci

    fun build(records: List<GameRecord>): WeaknessReport {
        val games = records.distinctBy { it.id }.filter { it.finished }
            .sortedWith(compareByDescending<GameRecord> { it.startedAt }.thenByDescending { it.id }).take(MAX_GAMES)
        var analyzedGames = 0
        var confirmedMoves = 0
        val findings = mutableListOf<Pair<WeaknessType, WeaknessExample>>()
        for (game in games) {
            val reviews = game.reviews.associateBy { it.ply }.values.filter { review ->
                runCatching { confirmed(game, review) }.getOrDefault(false)
            }
            if (reviews.isNotEmpty()) analyzedGames++
            confirmedMoves += reviews.size
            reviews.sortedByDescending { it.ply }.forEach { review ->
                runCatching { classify(game, review) }.getOrNull()?.let { findings += it }
            }
        }
        val groups = findings.groupBy({ it.first }, { it.second }).map { (type, examples) -> WeaknessGroup(type, examples) }
            .sortedWith(compareByDescending<WeaknessGroup> { it.count }.thenBy { it.type.ordinal })
        return WeaknessReport(games.size, analyzedGames,
            games.sumOf { game -> (1..game.moves.size).count(game::isPlayerMove) },
            confirmedMoves, groups)
    }

    private fun classify(game: GameRecord, review: MoveReview): Pair<WeaknessType, WeaknessExample>? {
        if (review.grade !in listOf(Grade.INACCURACY, Grade.MISTAKE, Grade.BLUNDER) ||
            review.pointsLost < .04 || review.bestMove == review.uci) return null
        val history = game.moves.take(review.ply - 1)
        val best = ChessRules.legalVariation(history, review.best.pv.take(MoveCoach.MAX_VARIATION_PLIES))
        val played = ChessRules.legalVariation(history, review.played.pv.take(MoveCoach.MAX_VARIATION_PLIES))
        if (best.isEmpty() || played.firstOrNull() != review.uci) return null
        val before = ChessRules.board(history)
        val side = before.sideToMove
        fun finding(type: WeaknessType, text: String) = type to WeaknessExample(game.id, review.ply, review.san, text)
        fun shownMate(line: List<String>, winner: Side): Boolean {
            val end = replay(before, line)
            return end.isMated && end.sideToMove != winner
        }
        if ((review.played.mate ?: 0) < 0 && (review.best.mate ?: 0) >= 0 && shownMate(played, side.flip()))
            return finding(WeaknessType.ALLOWED_MATE,
                "${review.san} 后，参考线 ${ChessRules.variationSan(history, played).joinToString(" → ")} 到达对手将杀；推荐线没有这条将杀结果。")
        if ((review.best.mate ?: 0) > 0 && (review.played.mate ?: 0) <= 0 && shownMate(best, side))
            return finding(WeaknessType.MISSED_MATE,
                "推荐 ${ChessRules.san(history, best.first())}，参考线 ${ChessRules.variationSan(history, best).joinToString(" → ")} 到达将杀；实战未保留这条机会。")
        if (played.size < 2 || best.size < 2) return null
        val afterPlayed = replay(before, played.take(1))
        val reply = Move(played[1], afterPlayed.sideToMove)
        val victim = afterPlayed.getPiece(reply.to)
        val afterReply = replay(before, played.take(2))
        val bestPair = replay(before, best.take(2))
        if (value(victim) >= 3 && victim.pieceSide == side &&
            afterReply.legalMoves().none { it.to == reply.to } &&
            balance(afterReply, side) < balance(before, side) && balance(bestPair, side) > balance(afterReply, side))
            return finding(WeaknessType.HANGING_PIECE,
                "${review.san} 后，对手 ${ChessRules.san(history + played.first(), played[1])} 吃掉 ${reply.to.toString().lowercase()} 的${ChessRules.pieceChinese(victim)}；本方没有合法的立即回吃。")

        // Compare equal-length prefixes, account for both players' captures, and require actual follow-through.
        val length = minOf(6, best.size, played.size)
        val gain = balance(replay(before, best.take(length)), side) - balance(before, side)
        val actualGain = balance(replay(before, played.take(length)), side) - balance(before, side)
        if (gain < 2 || gain - actualGain < 2) return null
        val first = Move(best.first(), side)
        val afterBest = replay(before, best.take(1))
        val targets = Square.entries.filter { square ->
            square != Square.NONE && afterBest.getPiece(square) != Piece.NONE && afterBest.getPiece(square).pieceSide != side &&
                (value(afterBest.getPiece(square)) >= 3 || afterBest.getPiece(square).pieceType == PieceType.KING) &&
                afterBest.squareAttackedBy(square, side) and first.to.bitboard != 0L
        }
        val followsThrough = best.take(length).withIndex().any { (index, uci) ->
            if (index < 2 || index % 2 != 0) false else {
                val board = replay(before, best.take(index))
                val move = Move(uci, board.sideToMove)
                move.from == first.to && move.to in targets && value(board.getPiece(move.to)) >= 3
            }
        }
        if (targets.size >= 2 && followsThrough)
            return finding(WeaknessType.MISSED_FORK,
                "推荐 ${ChessRules.san(history, best.first())} 同时攻击${targets.joinToString("、") { "${it.toString().lowercase()} 的${ChessRules.pieceChinese(afterBest.getPiece(it))}" }}；计入双方交换后，这段参考线净赢 $gain 点子力。")
        val captured = before.getPiece(first.to)
        if (value(captured) >= 3 && captured.pieceSide != side && gain >= 3)
            return finding(WeaknessType.MISSED_CAPTURE,
                "推荐 ${ChessRules.san(history, best.first())} 吃掉 ${first.to.toString().lowercase()} 的${ChessRules.pieceChinese(captured)}；计入双方交换后，这段参考线净赢 $gain 点子力，实战未取得这项收益。")
        return null
    }

    private fun replay(root: Board, line: List<String>): Board = root.clone().apply {
        line.forEach { uci -> require(doMove(Move(uci, sideToMove), true)) }
    }
    private fun value(piece: Piece): Int = when (piece.pieceType) {
        PieceType.PAWN -> 1; PieceType.KNIGHT, PieceType.BISHOP -> 3; PieceType.ROOK -> 5; PieceType.QUEEN -> 9; else -> 0
    }
    private fun balance(board: Board, side: Side): Int = Square.entries.filter { it != Square.NONE }.sumOf { square ->
        val piece = board.getPiece(square)
        value(piece) * if (piece.pieceSide == side) 1 else -1
    }
}
