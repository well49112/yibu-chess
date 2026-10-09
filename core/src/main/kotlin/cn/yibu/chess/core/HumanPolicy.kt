package cn.yibu.chess.core

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlin.math.exp
import kotlin.random.Random

interface HumanPolicy {
    suspend fun logits(history: List<String>, selfElo: Int, opponentElo: Int): FloatArray
}

object HumanSkill {
    // A monotone practice-scale mapping, not a claim of Chess.com/Lichess equivalence.
    fun modelElo(practiceElo: Int): Int = (practiceElo + 500).coerceIn(600, 2600)
}

object MaiaEncoding {
    const val HISTORY = 8
    const val FEATURES = HISTORY * 12
    const val VOCABULARY = 4352
    fun tokens(history: List<String>): FloatArray {
        val board = Board()
        val frames = ArrayDeque<FloatArray>()
        fun append() { if (frames.size == HISTORY) frames.removeFirst(); frames.addLast(frame(board)) }
        append()
        history.forEach { uci ->
            val move = Move(uci, board.sideToMove)
            require(board.legalMoves().contains(move) && board.doMove(move, true)) { "非法棋谱走法：$uci" }
            append()
        }
        val result = FloatArray(64 * FEATURES)
        val padded = List(HISTORY - frames.size) { frames.first() } + frames.toList()
        padded.forEachIndexed { time, frame ->
            for (square in 0..63) for (piece in 0..11)
                result[square * FEATURES + time * 12 + piece] = frame[square * 12 + piece]
        }
        return result
    }
    private fun frame(board: Board): FloatArray {
        val result = FloatArray(64 * 12)
        val black = board.sideToMove == Side.BLACK
        for (index in 0..63) {
            val piece = board.getPiece(Square.entries[index])
            if (piece == Piece.NONE) continue
            val kind = when (piece.pieceType) {
                PieceType.PAWN -> 0; PieceType.KNIGHT -> 1; PieceType.BISHOP -> 2
                PieceType.ROOK -> 3; PieceType.QUEEN -> 4; PieceType.KING -> 5
                else -> error("无效棋子")
            }
            val square = if (black) index xor 56 else index
            val own = piece.pieceSide == board.sideToMove
            result[square * 12 + kind + if (own) 0 else 6] = 1f
        }
        return result
    }
    fun index(uci: String, moverWhite: Boolean): Int {
        val from = ChessRules.squareIndex(uci.take(2)).let { if (moverWhite) it else it xor 56 }
        val to = ChessRules.squareIndex(uci.substring(2, 4)).let { if (moverWhite) it else it xor 56 }
        if (uci.length == 4) return from * 64 + to
        require(from / 8 == 6 && to / 8 == 7)
        val piece = "qrbn".indexOf(uci.last()); require(piece >= 0)
        return 4096 + ((from % 8) * 8 + to % 8) * 4 + piece
    }
}

data class HumanCandidate(val move: String, val probability: Double)

object HumanSampling {
    fun candidates(legal: List<String>, logits: FloatArray, moverWhite: Boolean,
        temperature: Double = 1.0, topP: Double = 0.97, repeats: Map<String, Int> = emptyMap()): List<HumanCandidate> {
        require(legal.isNotEmpty() && logits.size == MaiaEncoding.VOCABULARY)
        require(temperature in 0.7..1.2 && topP in 0.5..1.0)
        val scores = legal.map { logits[MaiaEncoding.index(it, moverWhite)].toDouble() / temperature }
        check(scores.all { it.isFinite() }) { "拟人模型返回无效概率，请重试" }
        val max = scores.max()
        val weights = scores.map { exp(it - max) }
        val total = weights.sum()
        val ranked = legal.indices.sortedByDescending { weights[it] }
        val kept = mutableListOf<Int>()
        var cumulative = 0.0
        for (i in ranked) {
            kept += i
            cumulative += weights[i] / total
            if (cumulative >= topP) break
        }
        // Novelty only reweights the plausible human pool; it never adds arbitrary moves.
        val adjusted = kept.map { i -> weights[i] / (1.0 + 0.25 * (repeats[legal[i]] ?: 0).coerceAtMost(12)) }
        val sum = adjusted.sum()
        return kept.mapIndexed { j, i -> HumanCandidate(legal[i], adjusted[j] / sum) }
    }
    fun choose(candidates: List<HumanCandidate>, random: Random): String {
        require(candidates.isNotEmpty())
        val roll = random.nextDouble()
        var sum = 0.0
        for (candidate in candidates) { sum += candidate.probability; if (roll < sum) return candidate.move }
        return candidates.last().move
    }
}

class HumanOpponent(private val policy: HumanPolicy) {
    companion object {
        fun prepare(game: GameRecord): GameRecord {
            if (game.source != null || game.finished || game.mode != Difficulty.MATCHED || game.opponentEngine == "Maia-3 5M") return game
            return game.copy(opponentEngine = "Maia-3 5M", modelElo = game.modelElo ?: HumanSkill.modelElo(game.opponentElo ?: 500),
                policySeed = if (game.opponentEngine.startsWith("Maia")) game.policySeed else Random.nextLong())
        }
    }
    suspend fun move(game: GameRecord, recent: List<GameRecord> = emptyList()): String {
        val legal = ChessRules.legal(game.moves)
        require(legal.isNotEmpty())
        if (legal.size == 1) return legal.single()
        val elo = game.modelElo ?: HumanSkill.modelElo(game.opponentElo ?: 500)
        val logits = policy.logits(game.moves, elo, HumanSkill.modelElo(game.playerEloAtStart ?: 500))
        val repeats = if (game.moves.size < 12) recent.asSequence().filter {
            it.id != game.id && it.source == null && it.mode == Difficulty.MATCHED && (game.moves.size % 2 == 0) != it.humanWhite
        }
            .take(12).filter { it.moves.take(game.moves.size) == game.moves }
            .mapNotNull { it.moves.getOrNull(game.moves.size) }.groupingBy { it }.eachCount() else emptyMap()
        val temperature = 0.95 + Random(game.policySeed xor 0x6A09E667F3BCC909L).nextDouble() * 0.10
        val candidates = HumanSampling.candidates(legal, logits, game.moves.size % 2 == 0, temperature, repeats = repeats)
        val random = Random(game.policySeed xor (game.moves.joinToString(" ").hashCode().toLong() * 0x9E3779B9L))
        return HumanSampling.choose(candidates, random)
    }
}
