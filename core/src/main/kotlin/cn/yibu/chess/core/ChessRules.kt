package cn.yibu.chess.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList

object ChessRules {
    const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
    fun board(history: List<String>): Board = Board().apply {
        for (uci in history) {
            require(uci.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) { "无效走法：$uci" }
            val move = Move(uci, sideToMove)
            require(legalMoves().contains(move) && doMove(move, true)) { "非法走法：$uci" }
        }
    }
    fun legal(history: List<String>): List<String> = board(history).legalMoves().map { it.toString().lowercase() }
    fun squareName(index: Int): String = "${('a'.code + index % 8).toChar()}${index / 8 + 1}"
    fun squareIndex(name: String): Int = (name[1] - '1') * 8 + (name[0] - 'a')
    fun fenPieces(fen: String): List<Char> {
        val result = MutableList(64) { ' ' }
        fen.substringBefore(' ').split('/').forEachIndexed { row, rank ->
            var file = 0
            rank.forEach { c -> if (c.isDigit()) file += c.digitToInt() else result[(7 - row) * 8 + file++] = c }
        }
        return result
    }
    fun san(history: List<String>, uci: String): String {
        val b = board(history)
        return MoveList(b.fen).apply { add(Move(uci, b.sideToMove)) }.toSanArray().first()
    }
    fun sanMoves(history: List<String>): List<String> {
        if (history.isEmpty()) return emptyList()
        return MoveList().apply { loadFromText(history.joinToString(" ")) }.toSanArray().toList()
    }
    fun variationSan(history: List<String>, variation: List<String>): List<String> {
        val safe = legalVariation(history, variation)
        if (safe.isEmpty()) return emptyList()
        return MoveList(board(history).fen).apply { loadFromText(safe.joinToString(" ")) }.toSanArray().toList()
    }
    fun legalVariation(history: List<String>, variation: List<String>): List<String> {
        val b = board(history)
        val result = mutableListOf<String>()
        for (uci in variation) {
            val move = runCatching { Move(uci, b.sideToMove) }.getOrNull() ?: break
            if (!b.legalMoves().contains(move) || !b.doMove(move, true)) break
            result += uci
        }
        return result
    }
    fun outcome(history: List<String>): Pair<String, String>? {
        val b = board(history)
        return when {
            b.isMated -> (if (b.sideToMove == Side.WHITE) "0-1" else "1-0") to "将杀"
            b.isStaleMate -> "1/2-1/2" to "逼和"
            deadMaterial(b) -> "1/2-1/2" to "子力不足以将杀"
            b.isRepetition(5) -> "1/2-1/2" to "五次重复局面"
            b.halfMoveCounter >= 150 -> "1/2-1/2" to "七十五回合规则"
            else -> null
        }
    }
    // Do not equate "cannot force mate" with "mate is impossible". KNN versus K,
    // or a minor piece on each side, can still reach a legal mating position.
    fun deadMaterial(b: Board): Boolean {
        val nonKings = Square.entries.filter { it != Square.NONE }.mapNotNull { sq ->
            val piece = b.getPiece(sq)
            if (piece == Piece.NONE || piece.pieceType == PieceType.KING) null else sq to piece
        }
        if (nonKings.any { it.second.pieceType !in listOf(PieceType.BISHOP, PieceType.KNIGHT) }) return false
        if (nonKings.size <= 1) return true
        return nonKings.all { it.second.pieceType == PieceType.BISHOP } && nonKings.map { (sq, _) ->
            (sq.ordinal % 8 + sq.ordinal / 8) % 2
        }.distinct().size == 1
    }
    fun drawClaim(history: List<String>): String? {
        if (outcome(history) != null) return null
        val b = board(history)
        return when {
            b.isRepetition -> "三次重复局面"
            b.halfMoveCounter >= 100 -> "五十回合规则"
            else -> null
        }
    }
    private fun value(piece: Piece): Int = when (piece.pieceType) {
        PieceType.PAWN -> 100; PieceType.KNIGHT -> 320; PieceType.BISHOP -> 330
        PieceType.ROOK -> 500; PieceType.QUEEN -> 900; else -> 0
    }
    private fun balance(b: Board, side: Side): Int = Square.entries.filter { it != Square.NONE }.sumOf { sq ->
        val p = b.getPiece(sq)
        if (p == Piece.NONE) 0 else value(p) * if (p.pieceSide == side) 1 else -1
    }
    // Conservative sacrifice evidence: the best defensive PV captures real material,
    // and the player remains at least two pawns down after the short sequence.
    fun substantialSacrifice(history: List<String>, pv: List<String>): Boolean {
        val b = board(history)
        val side = b.sideToMove
        val initial = balance(b, side)
        val safe = legalVariation(history, pv.take(8))
        if (safe.size < 4) return false
        var earlyLoss = false
        safe.forEachIndexed { index, uci ->
            b.doMove(Move(uci, b.sideToMove), true)
            if (index in 1..3 && initial - balance(b, side) >= 200) earlyLoss = true
        }
        return earlyLoss && initial - balance(b, side) >= 200
    }
    fun pieceChinese(piece: Piece): String = when (piece.pieceType) {
        PieceType.PAWN -> "兵"; PieceType.KNIGHT -> "马"; PieceType.BISHOP -> "象"
        PieceType.ROOK -> "车"; PieceType.QUEEN -> "后"; PieceType.KING -> "王"; else -> "棋子"
    }
    data class BrilliantNote(val reason: String, val plan: String)
    fun brilliantNote(history: List<String>, played: Evaluation): BrilliantNote {
        val safe = legalVariation(history, played.pv.take(8))
        require(substantialSacrifice(history, safe))
        val b = board(history)
        val side = b.sideToMove
        val offered = mutableListOf<Piece>()
        safe.forEachIndexed { index, uci ->
            val move = Move(uci, b.sideToMove)
            val captured = b.getPiece(move.to)
            if (index < 4 && captured != Piece.NONE && captured.pieceSide == side && b.sideToMove != side)
                offered += captured
            b.doMove(move, true)
        }
        val piece = offered.maxByOrNull(::value)?.let(::pieceChinese) ?: "子力"
        val sans = variationSan(history, safe)
        val laterChecks = sans.filterIndexed { i, _ -> i % 2 == 0 }.drop(1).any { it.endsWith("+") || it.endsWith("#") }
        val reason = if (played.mate != null && played.mate > 0) "弃${piece}后，在引擎最佳应对下仍有强制将杀，因此是合理弃子。"
            else "弃${piece}后${if (laterChecks) "可用后续将军保持进攻，" else "的具体变化提供了补偿，"}在引擎最佳应对下基本保持了局面质量。"
        val purpose = if (played.mate != null && played.mate > 0) "，沿这条路线争取将杀" else
            if (laterChecks) "，继续保持进攻" else "，延续这条补偿路线"
        val reply = moveChinese(history + safe.take(1), safe[1])
        val follow = moveChinese(history + safe.take(2), safe[2])
        val plan = "对手 $reply 后，可${follow}$purpose。参考：${sans.joinToString("  ")}"
        return BrilliantNote(reason, plan)
    }
    private fun moveChinese(history: List<String>, uci: String): String {
        val b = board(history)
        val move = Move(uci, b.sideToMove)
        val notation = san(history, uci)
        if (notation.startsWith("O-O")) return "王车易位"
        return pieceChinese(b.getPiece(move.from)) + (if (notation.contains('x')) "吃 " else "到 ") + uci.substring(2, 4) +
            (if (uci.length == 5) "，升变为${pieceChinese(move.promotion)}" else "") +
            (if (notation.endsWith('#')) "并将杀" else if (notation.endsWith('+')) "并将军" else "")
    }
    fun replyExplanation(history: List<String>, played: Evaluation): String? {
        if (played.mate != null && played.mate < 0) return "对手有强制将杀路线，点击实战变化查看。"
        if (played.pv.size < 2) return null
        val b = board(history)
        val first = Move(played.pv[0], b.sideToMove)
        if (!b.legalMoves().contains(first)) return null
        b.doMove(first, true)
        val reply = Move(played.pv[1], b.sideToMove)
        if (!b.legalMoves().contains(reply)) return null
        val captured = b.getPiece(reply.to)
        return if (captured != Piece.NONE && captured.pieceSide != b.sideToMove)
            "对手可走 ${san(history + played.pv[0], played.pv[1])}，吃掉你的${pieceChinese(captured)}。点击变化查看后续。" else null
    }
    fun pgn(game: GameRecord): String {
        game.source?.pgn?.takeIf { it.isNotBlank() }?.let { return it }
        val date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.ROOT).format(java.util.Date(game.startedAt))
        val human = "Player"
        val engine = "${game.opponentEngine} (${game.difficulty.name})"
        val moves = sanMoves(game.moves)
        return buildString {
            append("[Event \"YiBu offline training\"]\n[Date \"$date\"]\n")
            append("[White \"${if (game.humanWhite) human else engine}\"]\n")
            append("[Black \"${if (game.humanWhite) engine else human}\"]\n[Result \"${game.result}\"]\n\n")
            moves.forEachIndexed { index, san ->
                if (index % 2 == 0) append("${index / 2 + 1}. ")
                append(san)
                game.reviews.find { it.ply == index + 1 }?.let { review ->
                    append(" {${review.grade.chinese}; best ${review.bestMove}; loss ${String.format(java.util.Locale.ROOT, "%.1f", review.pointsLost * 100)} pp}")
                }
                append(' ')
            }
            append(game.result)
        }
    }
}
