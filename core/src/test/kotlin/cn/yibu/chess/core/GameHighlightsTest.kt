package cn.yibu.chess.core

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GameHighlightsTest {
    private val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6", "b5a4", "g8f6", "e1g1", "f8e7", "d2d3", "b7b5")
    private fun review(ply: Int, loss: Double = 0.0): MoveReview {
        val ev = Evaluation(22, cp = 30, pv = moves.drop(ply - 1).take(4))
        return MoveReview(ply, moves[ply - 1], ChessRules.san(moves.take(ply - 1), moves[ply - 1]), ev, ev,
            grade = if (loss > 0) Grade.BLUNDER else Grade.GOOD, explanation = "", provisional = false,
            algorithmVersion = 3, deeplySearched = true, bestExpectedPoints = .8, playedExpectedPoints = .8 - loss)
    }

    @Test fun eitherColorShattersOnlyAtTheTransitionToCheckmateOrResignation() {
        val mates = listOf(listOf("f2f3", "e7e5", "g2g4", "d8h4"),
            listOf("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6", "h5f7"))
        mates.forEachIndexed { i, line ->
            val before = GameRecord(id = i.toLong(), moves = line.dropLast(1))
            val (result, ending) = ChessRules.outcome(line)!!
            val after = before.copy(moves = line, result = result, ending = ending, finished = true)
            val event = KingBreak.between(before, after)!!
            assertEquals(i == 0, event.white)
            assertEquals(if (event.white) 'K' else 'k', ChessRules.fenPieces(ChessRules.board(line).fen)[event.square])
            assertNull(KingBreak.between(after, after))
            assertNull(KingBreak.between(before.copy(id = 999), after))
        }
        listOf("1-0", "0-1").forEach { result ->
            val before = GameRecord()
            assertEquals(result == "0-1", KingBreak.between(before, before.copy(finished = true, result = result, ending = "认输"))!!.white)
            assertNull(KingBreak.between(before, before.copy(finished = true, result = "1/2-1/2", ending = "逼和")))
        }
    }

    private fun concreteReview(history: List<String>, best: List<String>, played: List<String>,
        loss: Double = .5, grade: Grade = Grade.BLUNDER, mate: Int? = null): MoveReview {
        val evaluation = Evaluation(22, cp = 30, mate = mate, pv = played)
        return MoveReview(history.size + 1, played.first(), ChessRules.san(history, played.first()),
            Evaluation(22, cp = 30, mate = mate, pv = best), evaluation, grade = grade, explanation = "",
            provisional = false, algorithmVersion = 3, deeplySearched = true,
            bestExpectedPoints = .8, playedExpectedPoints = .8 - loss)
    }

    @Test fun playerMomentsAreSpacedChronologicalAndNeverIncludeTheOpponentsMoves() {
        val line = listOf("e2e4", "e7e5", "d1h5", "b8c6", "h5e5", "c6e5")
        val white = concreteReview(line.take(4), listOf("h5f3", "g8f6"), line.drop(4))
        val black = concreteReview(line.take(5), line.drop(5), line.drop(5), 0.0, Grade.BEST)
        val opening = concreteReview(emptyList(), listOf("d2d4", "d7d5"), line.take(2), .10)
        val game = GameRecord(moves = line, reviews = listOf(black, white, opening), finished = true, ending = "认输", result = "0-1")
        val points = GameHighlights.build(game)
        assertEquals(listOf(1, 5), points.map { it.ply })
        assertTrue(points.all { it.ply % 2 == 1 && it.reason.startsWith("你走了") })
        assertTrue(points[1].reason.contains("e5的白后"))
        assertTrue(points.none { it.title == "对局如何结束" || it.title == "阶段回顾" })
        points.forEach { point ->
            val lesson = point.lesson!!
            assertEquals(lesson.variation, ChessRules.legalVariation(line.take(point.ply - 1), lesson.variation))
            assertEquals(lesson.variation, lesson.steps.map { it.uci })
        }
        assertEquals(line, game.moves)
        assertTrue(game.lessons.isEmpty())
        val blackPoints = GameHighlights.build(game.copy(humanWhite = false))
        assertEquals(listOf(6), blackPoints.map { it.ply })
        assertTrue(blackPoints.single().reason.contains("净增加 9 点子力"))
    }

    @Test fun playerQuietGamesHaveNoPaddingAndUnconfirmedOrStaleReviewsAreExcluded() {
        val reviews = (1..12).map { review(it) }
        assertTrue(GameHighlights.build(GameRecord(moves = moves, reviews = reviews)).isEmpty())
        val root = listOf("e2e4", "e7e5", "d1h5", "b8c6")
        val valid = concreteReview(root, listOf("h5f3", "g8f6"), listOf("h5e5", "c6e5"))
        for (unsafe in listOf(valid.copy(provisional = true), valid.copy(grade = Grade.UNSTABLE),
            valid.copy(uci = "a2a3"), valid.copy(algorithmVersion = 1),
            valid.copy(played = valid.played.copy(depth = 10)))) {
            assertTrue(GameHighlights.build(GameRecord(moves = root + "h5e5", reviews = listOf(unsafe))).isEmpty())
        }
        assertTrue(GameHighlights.build(GameRecord()).isEmpty())
    }

    @Test fun playerOrdinaryPawnMinorAndQueenTradesAreExcludedEvenIfGradedGreat() {
        val cases = listOf(
            listOf("e2e4", "d7d5") to listOf("e4d5", "d8d5"),
            listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1b5", "a7a6") to listOf("b5c6", "b7c6"),
            listOf("e2e4", "d7d5", "e4d5", "d8d5", "b1c3", "d5e5", "d1e2") to listOf("e5e2", "f1e2")
        )
        for ((history, exchange) in cases) {
            val gameMoves = history + exchange
            for (index in 0..1) {
                val root = history + exchange.take(index)
                val pv = exchange.drop(index)
                val review = concreteReview(root, pv, pv, 0.0, Grade.GREAT)
                val game = GameRecord(moves = gameMoves, humanWhite = root.size % 2 == 0, reviews = listOf(review))
                assertTrue("Routine exchange ${review.san} was highlighted", GameHighlights.build(game).isEmpty())
            }
        }
    }

    @Test fun playerForkWithAnActualFollowUpAndDirectMateRemainMeaningfulGoodMoves() {
        val history = listOf("e2e4", "e7e5", "b1c3", "b8c6", "c3b5", "d7d5", "e4d5", "d8d5", "d2d3", "g8f6")
        val fork = listOf("b5c7", "e8d7", "c7a8")
        val review = concreteReview(history, fork, fork, 0.0, Grade.BEST)
        val points = GameHighlights.build(GameRecord(moves = history + fork.first(), reviews = listOf(review)))
        assertEquals(1, points.size)
        assertTrue(points.single().reason.contains("双攻"))
        assertTrue(points.single().reason.contains("a8的黑车"))
        val mateRoot = listOf("e2e4", "e7e5", "f1c4", "b8c6", "d1h5", "g8f6")
        val mate = concreteReview(mateRoot, listOf("h5f7"), listOf("h5f7"), 0.0, Grade.BEST, 1)
        val matePoints = GameHighlights.build(GameRecord(moves = mateRoot + "h5f7", reviews = listOf(mate)))
        assertEquals(7, matePoints.single().ply)
        assertTrue(matePoints.single().reason.contains("直接将杀"))
    }

    @Test fun playerTourDoesNotAddTheOpponentsMateOrAnUnanalyzedFinalPosition() {
        val line = listOf("f2f3", "e7e5", "g2g4", "d8h4")
        val mate = concreteReview(line.take(3), listOf("d8h4"), listOf("d8h4"), 0.0, Grade.BEST, 1)
        val game = GameRecord(moves = line, finished = true, result = "0-1", ending = "将杀", reviews = listOf(mate))
        assertTrue(GameHighlights.build(game).isEmpty())
        assertTrue(GameHighlights.build(game.copy(reviews = emptyList())).isEmpty())
        assertEquals(4, GameHighlights.build(game.copy(humanWhite = false)).single().ply)
    }

    @Test fun playerRealQueenSacrificeIsKeptAndTheMateContinuationRemainsLegal() {
        val history = listOf("e2e4", "e7e5", "g1f3", "d7d6", "f1c4", "c8g4", "b1c3", "g7g6")
        val line = listOf("f3e5", "g4d1", "c4f7", "e8e7", "c3d5")
        val review = concreteReview(history, line, line, 0.0, Grade.BRILLIANT, 3)
        assertTrue(ChessRules.substantialSacrifice(history, line))
        val points = GameHighlights.build(GameRecord(moves = history + line.first(), reviews = listOf(review)))
        assertEquals("精彩弃子 !!", points.single().title)
        assertTrue(points.single().reason.contains("后"))
        assertEquals(line, points.single().lesson!!.variation)
        assertEquals("将杀", ChessRules.outcome(history + line)!!.second)
    }
}
