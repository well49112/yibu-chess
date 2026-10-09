package cn.yibu.chess.core

import org.junit.Assert.*
import org.junit.Test

class OpeningLessonTest {
    @Test fun allFortyLessonsRequireTheLearnersMovesAndOnlyAdvanceOnContinue() {
        var targets = 0
        OpeningCourses.all.forEach { course -> course.routes.forEachIndexed { index, route ->
            var session = OpeningSession(course.id, index)
            assertEquals(OpeningMode.GUIDE, session.mode)
            assertTrue(session.history.isEmpty())
            assertEquals(session, session.answer("e2e4"))
            session = session.continueLesson()
            while (session.lessonPhase != OpeningLessonPhase.DONE) {
                assertEquals(course.humanWhite, session.history.size % 2 == 0)
                assertEquals(route.moves.take(session.lessonPly), session.history)
                val task = session.lessonTask
                assertTrue("${course.id}/${route.id}: ${task.prompt}", task.prompt.isNotBlank())
                task.answers.keys.forEach { assertTrue(it in ChessRules.legal(session.history)) }
                assertEquals(session, session.continueLesson())
                val move = route.moves[session.lessonPly]
                val answered = session.answer(move)
                assertEquals(OpeningLessonPhase.FEEDBACK, answered.lessonPhase)
                assertEquals(session.history + move, answered.history)
                assertTrue(answered.message.length >= 15)
                assertEquals(answered, answered.answer(move))
                session = answered.continueLesson()
                targets++
            }
            assertEquals(route.moves, session.history)
            assertEquals(session.lessonPlies.size, session.firstTry)
            assertEquals(0, session.assisted)
            assertFalse(session.retrying)
            assertEquals(course.humanWhite, session.training(PlayerProfile()).humanWhite)
        } }
        assertTrue(targets >= 240)
    }
    @Test fun unmatchedLegalMovesHintsAndDemonstrationRepeatOnlyThoseTargetsAtTheEnd() {
        var s = OpeningSession("italian").continueLesson()
        val failed = s.answer("d2d4")
        assertEquals(s.history, failed.history)
        assertEquals(OpeningLessonPhase.TASK, failed.lessonPhase)
        assertTrue(failed.message.contains("不把其他走法判成坏棋"))
        assertEquals(setOf(0), failed.retryPlies)
        s = failed.answer("e2e4").continueLesson()
        assertEquals(2, s.lessonPly)
        s = s.lessonHint().lessonHint().showLessonMove()
        assertEquals(1, s.assisted)
        assertEquals(setOf(0, 2), s.retryPlies)
        s = s.continueLesson()
        while (!s.retrying) s = s.answer(s.route.moves[s.lessonPly]).continueLesson()
        assertEquals(listOf(0, 2), s.lessonDeck)
        assertEquals(0, s.lessonPly)
        s = s.answer("e2e4").continueLesson()
        assertEquals(2, s.lessonPly)
        s = s.answer("g1f3").continueLesson()
        assertEquals(OpeningLessonPhase.DONE, s.lessonPhase)
        assertEquals(6, s.firstTry)
        assertEquals(1, s.assisted)
        assertEquals(OpeningLessonPhase.INTRO, s.guided().lessonPhase)
        assertFalse(s.changeRoute(3).retrying)
    }
    @Test fun blackFirstMoveReferenceAndExplorationPreserveCorrectPositionAndNoRatings() {
        val s = OpeningSession("caro-kann", 2).continueLesson()
        assertEquals(listOf("e2e4"), s.history)
        val answered = s.answer("c7c6")
        val free = answered.explore()
        assertEquals(answered.history, free.history)
        assertEquals(free.history, free.training(PlayerProfile(436, 2)).moves)
        assertFalse(free.training(PlayerProfile()).rated)
        assertEquals(answered.history, answered.seek(answered.history.size).history)
        assertEquals(OpeningMode.LEARN, answered.seek(answered.history.size).mode)
        assertEquals("caro-kann:exchange", s.key)
        assertEquals("caro-kann:main", s.changeRoute(0).key)
    }
}
