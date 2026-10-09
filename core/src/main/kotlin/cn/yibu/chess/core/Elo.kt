package cn.yibu.chess.core

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

@Serializable
data class PlayerProfile(val rating: Int = 500, val ratedGames: Int = 0)

@Serializable
data class RatingChange(
    val before: Int,
    val after: Int,
    val opponent: Int,
    val score: Double,
    val expected: Double,
    val k: Int,
) { val delta: Int get() = after - before }

object EloRules {
    const val MIN_RATING = 100
    const val MAX_RATING = 2800
    fun newGame(profile: PlayerProfile, mode: Difficulty = Difficulty.MATCHED, humanWhite: Boolean? = null,
        random: Random = Random.Default): GameRecord {
        require(mode in Difficulty.choices)
        return GameRecord(humanWhite = humanWhite ?: random.nextBoolean(), difficulty = mode, rated = mode == Difficulty.MATCHED,
            playerEloAtStart = profile.rating, opponentElo = if (mode == Difficulty.MATCHED) profile.rating else null,
            opponentEngine = if (mode == Difficulty.MATCHED) "Maia-3 5M" else "Stockfish 19",
            modelElo = if (mode == Difficulty.MATCHED) HumanSkill.modelElo(profile.rating) else null,
            policySeed = random.nextLong())
    }
    fun score(game: GameRecord): Double? = when (game.result) {
        "1-0" -> if (game.humanWhite) 1.0 else 0.0
        "0-1" -> if (game.humanWhite) 0.0 else 1.0
        "1/2-1/2" -> 0.5
        else -> null
    }
    fun eligible(game: GameRecord): Boolean = game.source == null && game.openingTraining == null &&
        game.finished && game.rated && game.difficulty == Difficulty.MATCHED &&
        game.opponentElo != null && score(game) != null
    fun calculate(profile: PlayerProfile, opponent: Int, score: Double): RatingChange {
        require(score in listOf(0.0, 0.5, 1.0))
        val expected = 1.0 / (1.0 + 10.0.pow((opponent - profile.rating) / 400.0))
        val k = when { profile.ratedGames < 10 -> 64; profile.ratedGames < 30 -> 32; else -> 24 }
        val after = (profile.rating + (k * (score - expected)).roundToInt()).coerceIn(MIN_RATING, MAX_RATING)
        return RatingChange(profile.rating, after, opponent, score, expected, k)
    }
}
