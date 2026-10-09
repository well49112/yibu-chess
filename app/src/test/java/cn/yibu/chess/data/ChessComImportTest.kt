package cn.yibu.chess.data

import android.app.Application
import androidx.room.Room
import cn.yibu.chess.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChessComImportTest {
    private val pgn = """[White "Alice"]
[Black "Bob"]
[Result "1-0"]
[WhiteElo "500"]
[BlackElo "600"]

1. e4 e5 2. Nf3 Nc6 1-0"""
    private fun month(vararg ids: Int, wrongRules: Boolean = false) = buildJsonObject {
        putJsonArray("games") { ids.forEach { id -> add(buildJsonObject {
            put("pgn", pgn); put("url", "https://www.chess.com/game/live/$id"); put("end_time", 1700000000L + id)
            put("rules", if (wrongRules) "chess960" else "chess"); put("time_class", "rapid"); put("time_control", "600")
        }) } }
    }.toString()
    private fun archives(vararg months: String) = buildJsonObject { putJsonArray("archives") {
        months.forEach { add("https://api.chess.com/pub/player/alice/games/$it") }
    } }.toString()
    @Test fun newestFirstLimitedImportSkipsVariantsAndUsesOnlyPublicHeaders() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(archives("2026/08", "2026/09", "2026/10")))
            server.enqueue(MockResponse().setBody(month(4, wrongRules = true)))
            server.enqueue(MockResponse().setBody(month(2, 3)))
            val batches = mutableListOf<ChessComBatch>()
            ChessComClient(server.url("/").toString()).import(" ALICE ", 1, {}) { batches += it }
            assertEquals(listOf("2026/10", "2026/09"), batches.map { it.month })
            assertEquals(1, batches.first().skipped)
            val imported = batches.last().games.single()
            assertEquals("https://www.chess.com/game/live/3", imported.source!!.url)
            assertTrue(imported.humanWhite)
            assertEquals(3, server.requestCount)
            listOf("archives", "2026/10", "2026/09").forEach { suffix ->
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/pub/player/alice/games/$suffix", request.path)
                assertNull(request.getHeader("Authorization")); assertNull(request.getHeader("Access-Token"))
                assertTrue(request.getHeader("User-Agent")!!.startsWith("YibuChess/"))
            }
        } finally { server.shutdown() }
    }
    @Test fun fullImportSavesEachMonthBeforeContinuingAndPreservesPartialResultsOnHttpFailure() = runBlocking {
        val server = MockWebServer().apply { start() }
        val batches = mutableListOf<ChessComBatch>()
        try {
            server.enqueue(MockResponse().setBody(archives("2026/09", "2026/10")))
            server.enqueue(MockResponse().setBody(month(3)))
            server.enqueue(MockResponse().setResponseCode(429))
            try {
                ChessComClient(server.url("/").toString()).import("alice", null, {}) { batch ->
                    assertEquals(2, server.requestCount) // Older month has not started before saving this one.
                    batches += batch
                }
                fail("Should report HTTP failure")
            } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("稍后")) }
            assertEquals(1, batches.single().games.size)
        } finally { server.shutdown() }
    }
    @Test fun cancellationStopsFurtherMonthsAfterTheSavedBatch() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(archives("2026/09", "2026/10")))
            server.enqueue(MockResponse().setBody(month(3)))
            var saved = 0
            val job = launch { ChessComClient(server.url("/").toString()).import("alice", null, {}) {
                saved += it.games.size; currentCoroutineContext().cancel()
            } }
            job.join()
            assertTrue(job.isCancelled); assertEquals(1, saved); assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }
    @Test fun duplicatesKeepAnalysisDeletedGamesStayDeletedAndImportedResultsNeverChangeElo() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(app, GameDatabase::class.java).build()
        try {
            val repo = GameRepository(app, db)
            val game = ChessComImport.parse(pgn, "alice", "https://www.chess.com/game/live/12", 1700000000)
            val other = ChessComImport.parse(pgn, "alice", "https://www.chess.com/game/live/13", 1700000001)
            assertEquals(ImportSaveResult(2, 1, 0), repo.importGames(listOf(game, other, game)))
            val eval = Evaluation(22, cp = 20, pv = listOf("e2e4"))
            val reviewed = game.copy(reviews = listOf(MoveReview(1, "e2e4", "e4", eval, eval, grade = Grade.BEST, explanation = "保存过的讲解")))
            repo.save(reviewed)
            assertEquals(ImportSaveResult(0, 1, 0), repo.importGames(listOf(game)))
            assertEquals(reviewed.reviews, repo.games.first().first { it.id == game.id }.reviews)
            repo.delete(game.id)
            assertEquals(ImportSaveResult(0, 0, 1), repo.importGames(listOf(game)))
            assertEquals(listOf(other.id), repo.games.first().map { it.id })
            assertEquals(PlayerProfile(), repo.profile())
        } finally { db.close() }
    }
    @Test fun usernameAndLearningProgressSurvivePreferenceRecreation() {
        val app = RuntimeEnvironment.getApplication()
        ImportPreferences(app).saveUsername(" Alice ")
        OpeningPreferences(app).save(setOf("italian:main:learn", "caro-kann:branch:quiz"))
        assertEquals("alice", ImportPreferences(app).username())
        assertEquals(2, OpeningPreferences(app).progress().size)
    }
}
