package cn.yibu.chess.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import com.github.bhlangonijr.chesslib.move.MoveList

data class PracticeExplanation(val mistake: String, val improvement: String, val continuation: List<LessonStep>)

/** Explain the answered route using legal board facts, without another engine call or generic praise. */
object PracticeCoach {
    private data class Step(val uci: String, val san: String, val move: Move, val before: Board, val after: Board,
        val captured: Piece, val capturedAt: Square) {
        val side: Side get() = before.sideToMove
        val piece: Piece get() = before.getPiece(move.from)
    }

    fun wrongMove(question: PracticeQuestion): String {
        val root = ChessRules.board(question.history)
        return line(root, listOf(question.review.uci)).first().let { "${it.san}（${action(it)}）" }
    }

    fun explain(question: PracticeQuestion, answer: String = question.bestMove): PracticeExplanation {
        require(answer in question.answers)
        val root = ChessRules.board(question.history)
        val side = root.sideToMove
        val evaluation = if (answer == question.bestMove) question.review.best
            else requireNotNull(question.review.second).also { require(it.pv.firstOrNull() == answer) }
        val chosen = line(root, evaluation.pv.take(MoveCoach.MAX_VARIATION_PLIES))
        require(chosen.firstOrNull()?.uci == answer)
        val actual = line(root, question.review.played.pv.take(MoveCoach.MAX_VARIATION_PLIES))
            .takeIf { it.firstOrNull()?.uci == question.review.uci }
            ?: line(root, listOf(question.review.uci))
        val first = chosen.first()
        val why = mutableListOf<String>()

        // A legal immediate capture is a stronger fact than a score delta or a geometric attack.
        actual.getOrNull(1)?.takeIf { it.captured != Piece.NONE && it.captured.pieceSide == side &&
            it.after.legalMoves().none { move -> move.to == it.move.to } }?.let { reply ->
            val origin = if (actual.first().move.to == reply.capturedAt) actual.first().move.from else reply.capturedAt
            val destination = when {
                first.move.from == origin -> first.move.to
                first.piece.pieceType == PieceType.KING && kotlin.math.abs(first.move.to.ordinal - first.move.from.ordinal) == 2 &&
                    reply.captured.pieceType == PieceType.ROOK && origin.ordinal == (first.move.from.ordinal / 8 * 8 +
                    if (first.move.to.ordinal % 8 == 6) 7 else 0) ->
                    Square.squareAt(first.move.from.ordinal / 8 * 8 + if (first.move.to.ordinal % 8 == 6) 5 else 3)
                else -> origin
            }
            if (root.getPiece(origin) == reply.captured && first.after.getPiece(destination) == reply.captured) {
                val canTake = first.after.legalMoves().any { capturedAt(first.after, it) == destination }
                if (!canTake) {
                    val changed = if (first.move.from == origin) "把${ChessRules.pieceChinese(reply.captured)}从${name(origin)}移到${name(destination)}"
                        else "保住${name(destination)}的${ChessRules.pieceChinese(reply.captured)}"
                    why += "${first.san} $changed。相比实战让对手用 ${reply.san} 吃子，这一着之后，对手没有合法的一步吃掉${name(destination)}这枚${ChessRules.pieceChinese(reply.captured)}的走法。"
                } else {
                    val recapture = sameReply(first.after, reply.uci)?.let { after -> after.legalMoves().firstOrNull { it.to == reply.move.to }?.let { after to it } }
                    recapture?.let { (after, move) ->
                        why += "${first.san} 后，若对手仍走 ${san(first.after, Move(reply.uci, first.after.sideToMove))} 吃子，本方现在可以 ${san(after, move)} 回吃；实战参考线中没有这次立即回吃。"
                    }
                }
            }
        }

        val targets = targets(first.after, first.move.to, side).filter {
            value(first.after.getPiece(it)) >= 3 || first.after.getPiece(it).pieceType == PieceType.KING
        }
        val forkCapture = chosen.drop(2).firstOrNull { step -> step.side == side && step.move.from == first.move.to &&
            step.capturedAt in targets && value(step.captured) >= 3 }
        if (targets.size >= 2 && forkCapture != null) {
            why += "${first.san} 用${ChessRules.pieceChinese(first.after.getPiece(first.move.to))}同时攻击${targets.joinToString("、") { label(first.after, it) }}。参考线中，对手 ${chosen[1].san} 后，本方 ${forkCapture.san} 吃掉${name(forkCapture.capturedAt)}的${ChessRules.pieceChinese(forkCapture.captured)}，双攻的收益在这里兑现。"
        } else if (first.captured != Piece.NONE) {
            val recaptures = first.after.legalMoves().filter { capturedAt(first.after, it) == first.move.to }
            why += "${first.san} 直接吃掉${name(first.capturedAt)}的${ChessRules.pieceChinese(first.captured)}（${value(first.captured)} 点子力）。" +
                if (recaptures.isEmpty()) "对手没有合法的立即回吃；后续能否保住收益，见下方参考应对。"
                else "对手有立即回吃的走法，不能只算这一口；要把下面双方的交换合起来看。"
        }

        val end = chosen.last().after
        if (end.isMated && end.sideToMove != side) {
            val checked = chosen.filter { it.side == side && it.after.isKingAttacked }.map { it.san }
            why += "这条答案变化的将军包括 ${checked.joinToString(" → ")}，最后 ${chosen.last().san} 将杀${name(end.getKingSquare(end.sideToMove))}的王：对方没有合法解将。"
        }
        if (question.type == WeaknessType.ALLOWED_MATE) {
            actual.getOrNull(1)?.let { reply ->
                val same = sameReply(first.after, reply.uci)
                if (same != null && !same.isMated) {
                    val replySan = san(first.after, Move(reply.uci, first.after.sideToMove))
                    if (same.isKingAttacked) {
                        val defenses = same.legalMoves().take(3).map { "${san(same, it)}（${action(same, it)}）" }
                        why += "${first.san} 后，即使对手仍走 $replySan，本方可以用${defenses.joinToString("、")}解将，已经不是实战那种无路可逃的将杀。"
                    } else why += "${first.san} 后，对手原来的 $replySan 在此局面没有将到本方王，实战参考线的这次直接将杀已被避开。"
                } else if (same == null) why += "${first.san} 改变了局面，对手实战参考线的 ${reply.san} 在这里已不是合法走法，不能照原来的路线直接将杀。"
            }
        }

        val length = minOf(chosen.size, actual.size, 6)
        if (length >= 2) {
            val chosenGain = balance(chosen[length - 1].after, side) - balance(root, side)
            val actualGain = balance(actual[length - 1].after, side) - balance(root, side)
            if (chosenGain > actualGain) why += "两条参考线各走 $length 着并计入双方吃子与升变：实战${net(actualGain)}，答案${net(chosenGain)}，保留的净子力相差 ${chosenGain - actualGain} 点（兵1、马/象3、车5、后9）。这是所示变化的结果。"
        }
        if (why.isEmpty()) {
            why += "已保存的答案为 ${first.san}（${action(first)}），参考应对见下方。这段变化没有展示出可验证的赢子或将杀原因，暂不把评分接近说成相同的战术收益。"
        }

        val mistake = if (question.type == WeaknessType.HANGING_PIECE && actual.size >= 2) {
            val reply = actual[1]
            val lost = balance(root, side) - balance(reply.after, side)
            if (reply.captured.pieceSide == side && value(reply.captured) >= 3 &&
                reply.after.legalMoves().none { it.to == reply.move.to } && lost > 0) buildString {
                append("${actual.first().san}（${action(actual.first())}）")
                if (actual.first().captured != Piece.NONE) append("只吃到 ${value(actual.first().captured)} 点子力；") else append("之后，")
                append("对手 ${reply.san}（${action(reply)}）吃掉${name(reply.capturedAt)}的${ChessRules.pieceChinese(reply.captured)}（${value(reply.captured)} 点）。本方没有合法的立即回吃，两着后净少 $lost 点子力。")
            } else question.evidence
        } else question.evidence

        val continuation = chosen.take(8).map { step ->
            val notes = mutableListOf<String>()
            if (step.captured != Piece.NONE) notes += "吃掉${name(step.capturedAt)}的${ChessRules.pieceChinese(step.captured)}（${value(step.captured)} 点）"
            if (step.move.promotion != Piece.NONE) notes += "兵升变为${ChessRules.pieceChinese(step.move.promotion)}"
            if (step.after.isMated) notes += "王被将军且没有合法解将，形成将杀"
            else if (step.after.isKingAttacked) notes += "将军，对方下一着必须解将"
            val attacked = targets(step.after, step.move.to, step.side).filter { value(step.after.getPiece(it)) >= 3 }
            if (attacked.isNotEmpty()) notes += "这枚子现在攻击${attacked.joinToString("、") { label(step.after, it) }}（攻击不等于必然能吃到）"
            LessonStep(step.uci, "${if (step.side == Side.WHITE) "白方" else "黑方"} ${step.san}（${action(step)}）",
                notes.joinToString("；").ifEmpty { "${action(step)}。这是保存的参考应对。" })
        }
        return PracticeExplanation(mistake, why.distinct().joinToString("\n\n"), continuation)
    }

    private fun line(root: Board, moves: List<String>): List<Step> {
        val board = root.clone()
        val steps = mutableListOf<Step>()
        for (uci in moves) {
            val move = runCatching { Move(uci, board.sideToMove) }.getOrNull() ?: break
            if (move !in board.legalMoves()) break
            val before = board.clone()
            val capture = capturedAt(before, move)
            val san = san(before, move)
            require(board.doMove(move, true))
            steps += Step(uci, san, move, before, board.clone(), before.getPiece(capture), capture)
        }
        return steps
    }
    private fun sameReply(root: Board, uci: String): Board? {
        val move = Move(uci, root.sideToMove)
        return if (move in root.legalMoves()) root.clone().apply { require(doMove(move, true)) } else null
    }
    private fun san(board: Board, move: Move): String = MoveList(board.fen).apply { add(move) }.toSanArray().first()
    private fun action(step: Step): String = action(step.before, step.move)
    private fun action(board: Board, move: Move): String {
        val piece = board.getPiece(move.from)
        if (piece.pieceType == PieceType.KING && kotlin.math.abs(move.to.ordinal - move.from.ordinal) == 2)
            return if (move.to.ordinal % 8 == 6) "王翼易位" else "后翼易位"
        return "${ChessRules.pieceChinese(piece)}从${name(move.from)}到${name(move.to)}" +
            if (move.promotion != Piece.NONE) "，升变为${ChessRules.pieceChinese(move.promotion)}" else ""
    }
    private fun capturedAt(board: Board, move: Move): Square =
        if (board.getPiece(move.from).pieceType == PieceType.PAWN && move.from.ordinal % 8 != move.to.ordinal % 8 &&
            board.getPiece(move.to) == Piece.NONE) Square.squareAt(move.to.ordinal + if (board.sideToMove == Side.WHITE) -8 else 8)
        else move.to
    private fun name(square: Square): String = square.toString().lowercase()
    private fun label(board: Board, square: Square): String = "${name(square)}的${if (board.getPiece(square).pieceSide == Side.WHITE) "白" else "黑"}${ChessRules.pieceChinese(board.getPiece(square))}"
    private fun targets(board: Board, square: Square, side: Side): List<Square> = Square.entries.filter {
        it != Square.NONE && board.getPiece(it) != Piece.NONE && board.getPiece(it).pieceSide != side &&
            board.squareAttackedBy(it, side) and square.bitboard != 0L
    }
    private fun value(piece: Piece): Int = when (piece.pieceType) {
        PieceType.PAWN -> 1; PieceType.KNIGHT, PieceType.BISHOP -> 3; PieceType.ROOK -> 5; PieceType.QUEEN -> 9; else -> 0
    }
    private fun balance(board: Board, side: Side): Int = Square.entries.filter { it != Square.NONE }.sumOf {
        val piece = board.getPiece(it)
        value(piece) * if (piece.pieceSide == side) 1 else -1
    }
    private fun net(gain: Int): String = when {
        gain > 0 -> "净多 $gain 点"; gain < 0 -> "净少 ${-gain} 点"; else -> "子力不变"
    }
}
