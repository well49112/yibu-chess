package cn.yibu.chess.core

import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move

/** Offline coaching: engine supplies the line; board facts supply the explanation. */
object MoveCoach {
    const val ALGORITHM_VERSION = 5
    const val MAX_VARIATION_PLIES = 16

    fun canReuse(lesson: MoveLesson, review: MoveReview?): Boolean =
        lesson.algorithmVersion == ALGORITHM_VERSION && (review == null ||
            lesson.recommendedMove == review.bestMove && lesson.depth == review.best.depth &&
                lesson.variation == review.best.pv.take(MAX_VARIATION_PLIES) &&
                lesson.playedVariation == review.played.pv.take(MAX_VARIATION_PLIES))

    /** Never substitute the recommended continuation for missing actual-move evidence. */
    fun playedLine(history: List<String>, review: MoveReview): List<String> =
        ChessRules.legalVariation(history, review.played.pv.take(MAX_VARIATION_PLIES))
            .takeIf { it.firstOrNull() == review.uci }
            ?: ChessRules.legalVariation(history, listOf(review.uci))

    fun explain(history: List<String>, review: MoveReview): MoveLesson {
        require(review.ply == history.size + 1)
        val line = ChessRules.legalVariation(history, review.best.pv.take(MAX_VARIATION_PLIES))
        require(line.isNotEmpty()) { "引擎没有返回合法推荐，请重试本步讲解" }
        val actor = if (history.size % 2 == 0) "白方" else "黑方"
        val san = ChessRules.san(history, line.first())
        val reasons = facts(history, line.first())
        val playedExplanation = comparison(history, review, line)
        val scoreNote = when {
            review.best.mate != null && review.best.mate > 0 -> "引擎在这条最佳防守变化中找到将杀路线，优先保持连续的威胁。"
            review.best.mate != null && review.best.mate < 0 -> "引擎仍判断本方会被将杀；这是当前防守候选，不能据此认为已经脱险。"
            (review.best.cp ?: 0) < -200 -> "本方当前仍处劣势；推荐走法是在劣势中寻找更好的抵抗，不代表已经扳回局面。"
            else -> ""
        }
        val why = buildString {
            append("建议${actor}走 $san。")
            append(reasons.joinToString("；").ifEmpty { "通过${action(history, line.first())}调整站位，衔接下方参考变化" })
            append("。")
            if (scoreNote.isNotBlank()) append("\n").append(scoreNote)
            append("\n\n").append(playedExplanation)
        }
        val steps = annotatedSteps(history, line)
        val playedLine = playedLine(history, review)
        val plan = buildString {
            steps.forEachIndexed { index, step ->
                append("${index + 1}. ${step.title}。${step.explanation}\n")
            }
            val ending = ChessRules.outcome(history + line)
            if (ending != null) append("这条变化到此${ending.second}。\n")
            else if (line.size == 1) append("本次搜索没有给出更长的可靠变化，暂不推测对手的下一着。\n")
            append("这是引擎主变化的参考路线。对手若改走，先重新检查将军、吃子与直接威胁，不能机械照走。")
        }
        return MoveLesson(review.ply, line.first(), why, plan, line, review.best.depth,
            algorithmVersion = ALGORITHM_VERSION, steps = steps, playedExplanation = playedExplanation,
            playedVariation = playedLine, playedSteps = annotatedSteps(history, playedLine))
    }

    private fun comparison(history: List<String>, review: MoveReview, recommended: List<String>): String {
        val confirmed = !review.provisional && review.grade != Grade.UNSTABLE && review.algorithmVersion == 3 &&
            review.best.depth >= 12 && review.best.depth == review.played.depth
        val worse = confirmed && recommended.first() != review.uci &&
            (review.pointsLost >= .04 || review.grade in listOf(Grade.INACCURACY, Grade.MISTAKE, Grade.BLUNDER))
        val played = ChessRules.legalVariation(history, review.played.pv.take(8))
            .takeIf { it.firstOrNull() == review.uci }.orEmpty()
        val side = ChessRules.board(history).sideToMove
        return buildString {
            val same = recommended.first() == review.uci
            append("实战走的是 ${review.san}。")
            if (!confirmed) append("本步评价尚未确认；先比较两条参考变化，暂不把分值差认定为失误。")
            else if (same) append("这步与推荐一致。${goodReason(history, review) ?: facts(history, review.uci).take(2).joinToString("；") }。")
            else if (!worse) append("与推荐有差别，但当前证据不足以把它说成明显失误。")
            else {
                append("\n为什么这步有问题：")
                when {
                    review.played.mate != null && review.played.mate < 0 && (review.best.mate == null || review.best.mate >= 0) ->
                        append("这步给对手留下了强制将杀路线；即使本方接下来尽力防守，当前搜索仍判断王无法逃脱。")
                    review.best.mate != null && review.best.mate > 0 && (review.played.mate ?: 0) <= 0 ->
                        append("原本存在将杀路线，这步没有保留它，让对手获得了防守机会。")
                    else -> append(differences(history, recommended, played).take(2).joinToString("\n")
                        .ifEmpty { "当前合法变化没有展示出能解释差距的具体后果；可深入复评，暂不推测战术原因。" })
                }
            }
            if (played.size >= 2) {
                val replyRoot = history + played.first()
                val replyBoard = ChessRules.board(replyRoot)
                val replyMove = Move(played[1], replyBoard.sideToMove)
                val victim = capturedPiece(replyBoard, replyMove)
                if (worse && victim != Piece.NONE) {
                    val square = capturedSquare(replyBoard, replyMove).toString().lowercase()
                    append("\n具体交换：")
                    if (review.uci.substring(2, 4) == square)
                        append("把${ChessRules.pieceChinese(victim)}走到 $square 后，对手可以直接吃它。")
                    else append("这步没有保住 $square 的${ChessRules.pieceChinese(victim)}，对手可以直接吃它。")
                    val recapture = ChessRules.board(replyRoot + played[1]).legalMoves().firstOrNull { it.to == replyMove.to }
                    if (recapture == null) {
                        append("本方随后没有合法的立即回吃。")
                        if (material(ChessRules.board(replyRoot + played[1]), side) < material(ChessRules.board(history), side))
                            append("到这里子力已经净减少，不能只看第一着吃到了什么。")
                        else append("但双方可能已经交换了等值子力，不能仅因被回吃就断言亏子。")
                    }
                    else append("本方虽可用 ${ChessRules.san(replyRoot + played[1], recapture.toString().lowercase())} 回吃，但需要把后续交换和王的安全一起算完。")
                }
                append("\n对手如何应对：${ChessRules.san(replyRoot, played[1])}（${action(replyRoot, played[1])}）。")
                append(facts(replyRoot, played[1]).take(3).joinToString("；").ifEmpty { "调整站位，准备下一次接触" }).append("。")
                val tactical = played.drop(2).mapIndexedNotNull { i, uci ->
                    val root = history + played.take(i + 2)
                    val board = ChessRules.board(root)
                    val move = Move(uci, board.sideToMove)
                    val captured = capturedPiece(board, move)
                    if (board.sideToMove != side && captured != Piece.NONE && pieceValue(captured) >= 3)
                        "${ChessRules.san(root, uci)} 吃掉${move.to.toString().lowercase()}的${ChessRules.pieceChinese(captured)}"
                    else null
                }.firstOrNull()
                if (tactical != null) append("后续还要防范 $tactical。")
                val delta = material(ChessRules.board(history + played), side) - material(ChessRules.board(history), side)
                if (delta <= -1 && worse) {
                    append("\n实战参考变化走完 ${played.size} 着后，本方相对本步之前净少了 ${-delta} 点子力（兵1、马/象3、车5、后9，已计入双方吃子和升变）。")
                    append("这是这条路线的结果，后续仍可能回吃，不能当作所有应对都必然如此。")
                }
                append("\n实战后续参考：${ChessRules.variationSan(history, played).joinToString(" → ")}。")
            } else append("\n引擎没有给出合法的实战后续，暂不能具体断言对手怎样惩罚这步；可重新复评本步。")
            append(if (same) "\n这步的作用：" else "\n推荐的改进：")
            append("${ChessRules.san(history, recommended.first())}，")
            append(facts(history, recommended.first()).take(3).joinToString("；").ifEmpty { action(history, recommended.first()) }).append("。")
            val length = minOf(played.size, recommended.size)
            if (worse && length >= 2) {
                val advantage = material(ChessRules.board(history + recommended.take(length)), side) -
                    material(ChessRules.board(history + played.take(length)), side)
                if (advantage >= 1) append("比较两条路线各前 $length 着，推荐路线的净子力多 $advantage 点；也要继续看王的安全和能否回吃。")
            }
            if (worse && review.best.cp != null && review.played.cp != null) {
                append("\n引擎参考评价：推荐 ${review.best.display()}，实战 ${review.played.display()}（行棋方视角）。")
                append("这是局面评价，不是确定会损失这么多兵。")
            }
        }
    }

    /** A tour needs a concrete lesson, not merely a high or low numeric score. */
    fun keyReason(history: List<String>, review: MoveReview): String? {
        val best = ChessRules.legalVariation(history, review.best.pv.take(8))
        val played = ChessRules.legalVariation(history, review.played.pv.take(8))
            .takeIf { it.firstOrNull() == review.uci }.orEmpty()
        if (best.isEmpty() || played.isEmpty()) return null
        if (review.bestMove != review.uci && (review.pointsLost >= .04 ||
                review.grade in listOf(Grade.INACCURACY, Grade.MISTAKE, Grade.BLUNDER))) {
            if (review.played.mate != null && review.played.mate < 0 && (review.best.mate ?: 0) >= 0)
                return "这步允许对手沿 ${ChessRules.variationSan(history, played).take(4).joinToString(" → ")} 发动将杀攻势。"
            if (review.best.mate != null && review.best.mate > 0 && (review.played.mate ?: 0) <= 0)
                return "错过将杀路线：${ChessRules.variationSan(history, best).take(4).joinToString(" → ")}。"
            return differences(history, best, played).take(2).joinToString("\n").takeIf { it.isNotBlank() }
        }
        return if (review.pointsLost < .02) goodReason(history, review) else null
    }

    private fun label(board: Board, square: Square): String =
        "${square.toString().lowercase()}的${if (board.getPiece(square).pieceSide == Side.WHITE) "白" else "黑"}${ChessRules.pieceChinese(board.getPiece(square))}"

    private val centers = listOf(Square.D4, Square.E4, Square.D5, Square.E5)

    private fun targets(board: Board, square: Square, side: Side): List<Square> = Square.entries.filter {
        it != Square.NONE && board.getPiece(it) != Piece.NONE && board.getPiece(it).pieceSide != side &&
            board.squareAttackedBy(it, side) and square.bitboard != 0L
    }

    private fun differences(history: List<String>, best: List<String>, played: List<String>): List<String> {
        if (best.isEmpty() || played.isEmpty() || best.first() == played.first()) return emptyList()
        val before = ChessRules.board(history)
        val side = before.sideToMove
        val choice = Move(best.first(), side)
        val actual = Move(played.first(), side)
        val better = ChessRules.board(history + best.first())
        val worse = ChessRules.board(history + played.first())
        val notes = mutableListOf<String>()
        val bestSan = ChessRules.san(history, best.first())
        val actualSan = ChessRules.san(history, played.first())
        if (played.size >= 2) {
            val replyRoot = history + played.first()
            val reply = Move(played[1], side.flip())
            val victim = capturedPiece(worse, reply)
            val afterReply = ChessRules.board(replyRoot + played[1])
            val loss = material(before, side) - material(afterReply, side)
            val recapture = afterReply.legalMoves().any { it.to == reply.to }
            if (victim != Piece.NONE && loss > 0 && !recapture)
                notes += "$actualSan 之后，对手可走 ${ChessRules.san(replyRoot, played[1])}，吃掉${label(worse, capturedSquare(worse, reply))}；本方没有合法的立即回吃，两着交换后净少 $loss 点子力。"
        }
        val pressure = targets(better, choice.to, side).filter { pieceValue(better.getPiece(it)) >= 3 &&
            it !in targets(worse, actual.to, side) && before.getPiece(it) == better.getPiece(it) }
        if (pressure.isNotEmpty()) {
            var note = "$bestSan 用${ChessRules.pieceChinese(better.getPiece(choice.to))}攻击${pressure.joinToString("、") { label(better, it) }}；$actualSan 没有制造这层压力。"
            best.getOrNull(1)?.let { uci ->
                val reply = Move(uci, side.flip())
                if (reply.from in pressure && better.getPiece(reply.from) != Piece.NONE)
                    note += "推荐线中，对手随后用 ${ChessRules.san(history + best.first(), uci)} 把这枚子从${reply.from.toString().lowercase()}撤到${reply.to.toString().lowercase()}。"
            }
            notes += note
        }
        if (played.size >= 2) {
            val reply = Move(played[1], side.flip())
            val bestReply = best.getOrNull(1)?.let { Move(it, side.flip()) }
            if (worse.getPiece(reply.from).pieceType == PieceType.PAWN && reply.to in centers && bestReply?.to != reply.to) {
                val after = ChessRules.board(history + played.take(2))
                val attacked = targets(after, reply.to, side.flip()).filter { after.getPiece(it).pieceType != PieceType.KING }
                notes += "实战线中，对手有时间走 ${ChessRules.san(history + played.first(), played[1])}，把兵从${reply.from.toString().lowercase()}推进中心${reply.to.toString().lowercase()}" +
                    if (attacked.isEmpty()) "，建立新的中心据点。" else "，同时攻击${attacked.joinToString("、") { label(after, it) }}。"
            }
        }
        val escaped = Square.entries.filter { sq -> sq != Square.NONE && before.getPiece(sq) != Piece.NONE &&
            before.getPiece(sq).pieceSide == side && pieceValue(before.getPiece(sq)) >= 3 &&
            worse.getPiece(sq) == before.getPiece(sq) && worse.legalMoves().any { it.to == sq } &&
            (better.getPiece(sq) != before.getPiece(sq) || better.legalMoves().none { it.to == sq }) }
        if (escaped.isNotEmpty()) notes += "实战之后，${escaped.joinToString("、") { label(worse, it) }}仍可被对手合法吃掉；$bestSan 避开了同样的直接吃子。"
        val controlled = centers.filter { better.squareAttackedBy(it, side) and choice.to.bitboard != 0L &&
            worse.squareAttackedBy(it, side) and actual.to.bitboard == 0L }
        if (controlled.isNotEmpty()) notes += "$bestSan 让${ChessRules.pieceChinese(better.getPiece(choice.to))}直接控制${controlled.joinToString("、") { it.toString().lowercase() }}；$actualSan 的新站位没有这层中心控制。"
        val exchangeLength = minOf(played.size, best.size, 6)
        if (exchangeLength >= 2) {
            val advantage = material(ChessRules.board(history + best.take(exchangeLength)), side) -
                material(ChessRules.board(history + played.take(exchangeLength)), side)
            if (advantage > 0) notes += "两条参考线各走 $exchangeLength 着后，推荐线保留的净子力比实战线多 $advantage 点（已计入双方吃子）；可跟棋盘检查损失发生在哪一着。"
        }
        return notes.distinct()
    }

    private fun goodReason(history: List<String>, review: MoveReview): String? {
        val line = ChessRules.legalVariation(history, review.played.pv.take(8)).takeIf { it.firstOrNull() == review.uci } ?: return null
        val before = ChessRules.board(history)
        val side = before.sideToMove
        val move = Move(line.first(), side)
        val after = ChessRules.board(history + line.first())
        if (after.isMated) return "${review.san}直接将杀；对方已没有合法的解将走法。"
        if (review.grade == Grade.BRILLIANT && ChessRules.substantialSacrifice(history, line))
            return review.brilliantReason ?: ChessRules.brilliantNote(history, review.played).reason
        val attacked = targets(after, move.to, side).filter { pieceValue(after.getPiece(it)) >= 3 || after.getPiece(it).pieceType == PieceType.KING }
        if (attacked.size >= 2 && line.size >= 3) {
            val follow = Move(line[2], side)
            val continuation = ChessRules.board(history + line.take(2))
            if (follow.from == move.to && follow.to in attacked && capturedPiece(continuation, follow) != Piece.NONE)
                return "这步形成双攻：${ChessRules.pieceChinese(after.getPiece(move.to))}同时攻击${attacked.joinToString("、") { label(after, it) }}。参考线中对手应对后，本方用 ${ChessRules.san(history + line.take(2), line[2])} 吃掉其中一枚子。"
        }
        if (review.played.mate != null && review.played.mate > 0 && after.isKingAttacked)
            return "${review.san}以将军开始将杀路线：${ChessRules.variationSan(history, line).take(4).joinToString(" → ")}。"
        if (routineExchange(history, line)) return null
        val gain = material(ChessRules.board(history + line), side) - material(before, side)
        val victim = capturedPiece(before, move)
        if (pieceValue(victim) >= 3 && gain >= 1 && (line.size >= 2 || after.legalMoves().none { it.to == move.to }))
            return "${review.san}吃掉${label(before, capturedSquare(before, move))}；参考线走完 ${line.size} 着并计入双方吃子后，本方净增加 $gain 点子力。"
        if (review.grade == Grade.GREAT && review.second != null) {
            val alternative = ChessRules.legalVariation(history, review.second.pv.take(8))
            return differences(history, line, alternative).firstOrNull()?.let { "关键在于：$it" }
        }
        return null
    }

    /** Equal capture/recapture is routine unless the caller found mate, sacrifice or a fork. */
    private fun routineExchange(history: List<String>, line: List<String>): Boolean {
        if (line.isEmpty()) return false
        val before = ChessRules.board(history)
        val move = Move(line.first(), before.sideToMove)
        val victim = capturedPiece(before, move)
        if (victim == Piece.NONE || move.promotion != Piece.NONE) return false
        history.lastOrNull()?.let { uci ->
            val earlier = ChessRules.board(history.dropLast(1))
            val previous = Move(uci, earlier.sideToMove)
            if (previous.to == move.to && previous.promotion == Piece.NONE &&
                pieceValue(capturedPiece(earlier, previous)) == pieceValue(victim) &&
                capturedPiece(earlier, previous) != Piece.NONE) return true
        }
        var root = history + line.first()
        for (uci in line.drop(1).take(3)) {
            val board = ChessRules.board(root)
            val reply = Move(uci, board.sideToMove)
            if (reply.to != move.to || capturedPiece(board, reply) == Piece.NONE || reply.promotion != Piece.NONE) break
            root = root + uci
            if (material(ChessRules.board(root), before.sideToMove) == material(before, before.sideToMove)) return true
        }
        return false
    }

    private fun pieceValue(piece: Piece): Int = when (piece.pieceType) {
        PieceType.PAWN -> 1; PieceType.KNIGHT, PieceType.BISHOP -> 3
        PieceType.ROOK -> 5; PieceType.QUEEN -> 9; else -> 0
    }
    private fun material(board: Board, side: Side): Int = Square.entries.filter { it != Square.NONE }.sumOf {
        val piece = board.getPiece(it)
        pieceValue(piece) * if (piece.pieceSide == side) 1 else -1
    }
    private fun capturedPiece(board: Board, move: Move): Piece {
        return board.getPiece(capturedSquare(board, move))
    }
    private fun capturedSquare(board: Board, move: Move): Square {
        val piece = board.getPiece(move.from)
        return if (piece.pieceType == PieceType.PAWN && move.from.ordinal % 8 != move.to.ordinal % 8 && board.getPiece(move.to) == Piece.NONE)
            Square.squareAt(move.to.ordinal + if (board.sideToMove == Side.WHITE) -8 else 8)
        else move.to
    }

    /** Old saved lessons get annotations locally, without repeating an engine search. */
    fun annotatedSteps(history: List<String>, line: List<String>): List<LessonStep> {
        val safe = ChessRules.legalVariation(history, line)
        return safe.mapIndexed { index, uci ->
            val root = history + safe.take(index)
            val side = if (root.size % 2 == 0) "白方" else "黑方"
            val label = when (index) {
                0 -> "先手计划"
                1 -> "对手关键应对"
                else -> if (index % 2 == 0) "继续思路" else "对手防守"
            }
            val note = buildString {
                append(facts(root, uci).take(4).joinToString("；").ifEmpty { "通过${action(root, uci)}调整站位，衔接下一步变化" }).append("。")
                safe.getOrNull(index + 1)?.let { reply ->
                    append("\n下一着参考：${ChessRules.san(root + uci, reply)}，")
                    append(facts(root + uci, reply).take(2).joinToString("；").ifEmpty { action(root + uci, reply) }).append("。")
                }
                if (index == safe.lastIndex && ChessRules.outcome(history + safe) == null)
                    append("搜索展示到这里，接下来重新检查将军、吃子和未保护的棋子，不代表计划已经完成。")
            }
            LessonStep(uci, "$label：$side ${ChessRules.san(root, uci)}（${action(root, uci)}）", note)
        }
    }

    private fun action(history: List<String>, uci: String): String {
        val board = ChessRules.board(history)
        val move = Move(uci, board.sideToMove)
        val piece = board.getPiece(move.from)
        if (piece.pieceType == PieceType.KING && kotlin.math.abs(move.to.ordinal - move.from.ordinal) == 2)
            return if (move.to.ordinal % 8 == 6) "王翼易位" else "后翼易位"
        return "${ChessRules.pieceChinese(piece)}从${move.from.toString().lowercase()}到${move.to.toString().lowercase()}"
    }

    private fun facts(history: List<String>, uci: String): List<String> {
        val before = ChessRules.board(history)
        val side = before.sideToMove
        val move = Move(uci, side)
        val piece = before.getPiece(move.from)
        val from = move.from.ordinal
        val to = move.to.ordinal
        val isPawn = piece.pieceType == PieceType.PAWN
        val enPassant = isPawn && from % 8 != to % 8 && before.getPiece(move.to) == Piece.NONE
        val captured = capturedPiece(before, move)
        val after = ChessRules.board(history + uci)
        val moved = after.getPiece(move.to)
        val result = mutableListOf<String>()
        if (after.isMated) return listOf("这着直接将杀，对方已没有合法的解将方式")
        if (before.isKingAttacked) result += "先解除本方王受到的将军，避免忽略眼前最紧急的威胁"
        if (piece.pieceType == PieceType.KING && kotlin.math.abs(to - from) == 2)
            result += "通过易位把王移出中路，同时让车进入${if (to % 8 == 6) "f" else "d"}线"
        if (move.promotion != Piece.NONE) result += "兵到底线升变为${ChessRules.pieceChinese(move.promotion)}，增加可用子力"
        if (captured != Piece.NONE) result += "${if (enPassant) "吃掉相邻线的兵（吃过路兵）" else "直接吃掉${move.to.toString().lowercase()}的${ChessRules.pieceChinese(captured)}"}；是否能保持子力收益还要看对方回吃"
        if (after.isKingAttacked) result += "将军迫使对手先处理王的威胁，为后续变化争取节奏"
        val recapture = if (captured != Piece.NONE) after.legalMoves().firstOrNull { it.to == move.to } else null
        if (recapture != null) result += "对方可以用 ${ChessRules.san(history + uci, recapture.toString().lowercase())} 回吃，先算清交换，不能只数眼前吃到的子"
        if (before.squareAttackedBy(move.from, side.flip()) != 0L && after.squareAttackedBy(move.to, side.flip()) == 0L)
            result += "把${ChessRules.pieceChinese(piece)}移出对方的攻击范围，减少它被直接吃掉的风险"
        val enemies = Square.entries.filter { it != Square.NONE && after.getPiece(it) != Piece.NONE && after.getPiece(it).pieceSide != side }
        val targets = enemies.filter { after.squareAttackedBy(it, side) and move.to.bitboard != 0L }
        val valuable = targets.filter { after.getPiece(it).pieceType in listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.KING) }
        if (valuable.size >= 2) result += "${ChessRules.pieceChinese(moved)}同时瞄准${valuable.joinToString("和") { "${it.toString().lowercase()}的${ChessRules.pieceChinese(after.getPiece(it))}" }}；对方仍可能解围，需核对后续应对"
        else targets.firstOrNull { after.getPiece(it).pieceType != PieceType.PAWN && after.getPiece(it).pieceType != PieceType.KING }?.let {
            result += "新站位瞄准${it.toString().lowercase()}的${ChessRules.pieceChinese(after.getPiece(it))}，给对方增加需要处理的威胁"
        }
        if (isPawn && targets.isNotEmpty()) result += "这枚兵从新格同时攻击${targets.joinToString("、") { label(after, it) }}，要结合对手的移子或交换判断是否能赢子"
        val homeRank = if (side == Side.WHITE) 0 else 7
        if (piece.pieceType in listOf(PieceType.KNIGHT, PieceType.BISHOP) && from / 8 == homeRank && to / 8 != homeRank && history.size < 24)
            result += "把${ChessRules.pieceChinese(piece)}从底线发展出来，让它参与争夺局面"
        val centers = listOf(Square.D4, Square.E4, Square.D5, Square.E5)
        val controlled = centers.filter { after.squareAttackedBy(it, side) and move.to.bitboard != 0L }
        if (isPawn && move.to in centers) result += "兵占据${move.to.toString().lowercase()}中心格，为子力活动建立据点"
        if (controlled.isNotEmpty()) result += "${ChessRules.pieceChinese(moved)}控制${controlled.joinToString("、") { it.toString().lowercase() }}，增加对中心的影响"
        val defended = Square.entries.firstOrNull { sq ->
            sq != Square.NONE && sq != move.to && after.getPiece(sq) != Piece.NONE && after.getPiece(sq).pieceSide == side &&
                after.getPiece(sq).pieceType != PieceType.KING && before.squareAttackedBy(sq, side.flip()) != 0L &&
                after.squareAttackedBy(sq, side) and move.to.bitboard != 0L && before.squareAttackedBy(sq, side) and move.from.bitboard == 0L
        }
        if (defended != null) result += "新增对${defended.toString().lowercase()}${ChessRules.pieceChinese(after.getPiece(defended))}的保护，应对它原先受到的攻击"
        if (moved.pieceType == PieceType.ROOK) {
            val pawns = Square.entries.filter { it != Square.NONE && it.ordinal % 8 == to % 8 && after.getPiece(it).pieceType == PieceType.PAWN }
            if (pawns.none { after.getPiece(it).pieceSide == side }) result += if (pawns.isEmpty()) "车来到没有兵遮挡的开放线，便于沿线活动" else "车来到没有己方兵遮挡的半开放线，可沿线关注对方兵的弱点"
        }
        if (isPawn && move.promotion == Piece.NONE) {
            val blockers = enemies.any { sq -> after.getPiece(sq).pieceType == PieceType.PAWN && kotlin.math.abs(sq.ordinal % 8 - to % 8) <= 1 &&
                if (side == Side.WHITE) sq.ordinal / 8 > to / 8 else sq.ordinal / 8 < to / 8 }
            if (!blockers && (to / 8 in 3..6 && side == Side.WHITE || to / 8 in 1..4 && side == Side.BLACK))
                result += "向前推进通路兵；同线和相邻线前方没有敌兵，后续仍需保护它免被其他棋子吃掉"
        }
        if (after.isStaleMate) result += "这着让对方无合法走法但王未被将军，形成逼和"
        if (ChessRules.deadMaterial(after)) result += "变化后子力已不足以将杀，形成和棋"
        return result.distinct().take(4)
    }
}
