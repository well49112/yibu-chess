package cn.yibu.chess.core

import org.junit.Assert.*
import org.junit.Test

class OpeningCoursesTest {
    @Test fun tenBalancedCoursesHaveLegalRoutesAuthoredNotesAndPlayableCheckpoints() {
        assertEquals(10, OpeningCourses.all.size)
        assertEquals(5, OpeningCourses.all.count { it.humanWhite })
        assertEquals(10, OpeningCourses.all.map { it.id }.distinct().size)
        OpeningCourses.all.forEach { course ->
            assertEquals(2, course.routes.size)
            assertEquals(16, course.routes.first().moves.size)
            course.routes.forEach { route ->
                assertEquals("${course.id}/${route.title}", route.moves, ChessRules.legalVariation(emptyList(), route.moves))
                assertEquals("${course.id}/${route.title}", route.moves.size, route.notes.size)
                assertTrue("${course.id}/${route.title}: ${route.notes.filter { it.length < 15 }}", route.notes.all { it.length >= 15 })
                val check = route.checkpoint
                assertTrue(check.atPly in 0 until route.moves.size)
                assertEquals(course.humanWhite, check.atPly % 2 == 0)
                assertTrue(check.answers.isNotEmpty())
                check.answers.forEach { (move, reason) ->
                    assertTrue("${course.id} checkpoint $move", move in ChessRules.legal(route.moves.take(check.atPly)))
                    assertTrue(reason.length >= 20)
                }
            }
        }
    }
    @Test fun earlyQueenLessonActuallyPreventsBothShownMatingThreats() {
        val line = OpeningCourses.all.first { it.id == "double-pawn" }.routes[1].moves
        val firstThreat = line.take(5) + listOf("h7h6", "h5f7")
        assertEquals(firstThreat, ChessRules.legalVariation(emptyList(), firstThreat))
        assertTrue(ChessRules.board(firstThreat).isMated)
        val secondThreat = line.take(7) + listOf("a7a6", "f3f7")
        assertTrue(ChessRules.board(secondThreat).isMated)
        assertFalse("f3f7" in ChessRules.legal(line.take(8)))
        assertFalse(ChessRules.board(line.take(8)).isMated)
    }
    @Test fun checkpointsExplainCorrectAnswersWithoutCallingUnmatchedLegalMovesBlunders() {
        val session = OpeningSession("italian").quiz()
        assertEquals(8, session.history.size)
        val other = session.answer("b1c3")
        assertNull(other.solvedMove)
        assertEquals(session.history, other.history)
        assertTrue(other.message.contains("不代表这着一定不好"))
        val solved = other.answer("e1g1")
        assertEquals("e1g1", solved.solvedMove)
        assertTrue(solved.message.contains("王放到g1"))
        assertEquals(session.history + "e1g1", solved.history)
        assertEquals(solved, solved.answer("a2a3"))
    }
    @Test fun explorationAndRouteChangesKeepTheOriginalTeachingLineIntact() {
        val course = OpeningCourses.all.first { it.id == "italian" }
        val session = OpeningSession(course.id).seek(8)
        val free = session.explore().answer("b1c3")
        assertEquals(session.history + "b1c3", free.history)
        assertEquals(OpeningMode.FREE, free.mode)
        assertEquals(course.routes[0].moves.take(8), free.seek(8).history)
        assertEquals(OpeningMode.LEARN, free.changeRoute(1).mode)
        assertTrue(free.changeRoute(1).history.isEmpty())
        assertEquals(course.routes[0].moves, OpeningSession(course.id).seek(99).history)
    }
    @Test fun courseTrainingPreservesTheRootAndColorAndNeverChangesPracticeElo() {
        OpeningCourses.all.forEach { course ->
            val session = OpeningSession(course.id).seek(16)
            val game = session.training(PlayerProfile(436, 2))
            assertEquals(session.history, game.moves)
            assertEquals(course.humanWhite, game.humanWhite)
            assertEquals("Maia-3 5M", game.opponentEngine)
            assertFalse(game.rated)
            assertEquals(16, game.openingTraining!!.startPly)
            assertFalse(game.isPlayerMove(15))
            assertEquals(course.humanWhite, game.isPlayerMove(17))
            assertEquals(!course.humanWhite, game.isPlayerMove(18))
            assertFalse(EloRules.eligible(game.copy(rated = true, finished = true, result = "1-0")))
        }
    }
    @Test fun matchingUsesThePlayersColorAndMarksDeviationWithoutCallingItAnError() {
        val moves = OpeningCourses.all.first { it.id == "italian" }.routes[0].moves.take(8)
        val white = OpeningCourses.match(GameRecord(moves = moves + "b1c3", humanWhite = true))!!
        assertEquals("italian", white.course.id)
        assertEquals(8, white.sharedPlies)
        assertEquals(9, white.deviationPly)
        assertEquals("double-pawn", OpeningCourses.match(GameRecord(moves = moves, humanWhite = false))!!.course.id)
        assertNull(OpeningCourses.match(GameRecord(moves = listOf("e2e4", "c7c5"), humanWhite = true)))
        assertEquals("sicilian", OpeningCourses.match(GameRecord(moves = listOf("e2e4", "c7c5"), humanWhite = false))!!.course.id)
    }
    @Test fun teacherSuppliedMovesDoNotBecomePersonalWeaknessesOrHighlights() {
        val root = listOf("e2e4", "e7e5", "d1h5", "b8c6")
        val review = MoveReview(5, "h5e5", "Qxe5+", Evaluation(22, cp = 30, pv = listOf("h5f3", "g8f6")),
            Evaluation(22, cp = -800, pv = listOf("h5e5", "c6e5")), grade = Grade.BLUNDER, explanation = "",
            provisional = false, algorithmVersion = 3, bestExpectedPoints = .8, playedExpectedPoints = .2)
        val game = GameRecord(moves = root + review.uci, reviews = listOf(review), finished = true)
        val tracker = WeaknessStats.Tracker()
        assertEquals(1, tracker.build(listOf(game)).groups.single().count)
        val training = game.copy(openingTraining = OpeningTraining("italian", "main", 5))
        assertTrue(tracker.build(listOf(training)).groups.isEmpty())
        assertEquals(0, WeaknessStats.build(listOf(training)).playerMoves)
        assertTrue(GameHighlights.build(training).isEmpty())
    }
}
