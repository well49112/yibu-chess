package cn.yibu.chess.background

import android.app.Application
import androidx.room.Room
import cn.yibu.chess.core.*
import cn.yibu.chess.data.*
import cn.yibu.chess.engine.RemoteStockfishClient
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoReviewRunnerTest {
    private lateinit var app: Application
    private lateinit var db: GameDatabase
    private lateinit var repo: GameRepository
    private lateinit var server: MockWebServer
    private val states = CopyOnWriteArrayList<AutoReviewState>()
    private var now = 1000L
    private var revision = 0L
    private var online = true
    private var interactive = false
    private var importing = false
    private var auth = false
    private var waiting: (suspend (Long) -> Unit)? = null
    private val waits = mutableListOf<Long>()
    private val control = object : AutoReviewControl {
        override val revision get() = this@AutoReviewRunnerTest.revision
        override fun settings() = PlaySettings(stockfishToken = "token")
        override fun online() = online
        override fun paused() = false
        override fun interactive() = interactive
        override fun importing() = importing
        override fun activeGameId(): Long? = null
        override fun authFailed(token: String) { auth = true }
        override fun searching(value: Boolean) {}
        override fun publish(state: AutoReviewState) { states += state }
        override suspend fun awaitWake(ms: Long) { waits += ms; waiting?.invoke(ms) ?: run { now += ms; yield() } }
    }
    @Before fun setup() {
        app = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(app, GameDatabase::class.java).build(); repo = GameRepository(app, db)
        server = MockWebServer().apply { dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = response(request) }; start() }
        AutoReviewRetries(app).clear()
    }
    @After fun close() { server.shutdown(); db.close(); AutoReview.resetForTests() }
    private fun client() = RemoteStockfishClient({ "token" }, server.url("/").toString())
    private fun runner() = AutoReviewRunner(app, repo, client(), AutoReviewRetries(app), control, { now }, idleGraceMs = 0)
    private fun game(plies: Int = 3) = GameRecord(moves = listOf("e2e4", "e7e5", "g1f3").take(plies), finished = true)
    private fun response(request: RecordedRequest, comparable: Boolean = true): MockResponse {
        val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
        assertEquals("lightning", body["profile"]!!.jsonPrimitive.content)
        assertEquals(22, body["limits"]!!.jsonObject["depth"]!!.jsonPrimitive.int)
        assertEquals(500, body["limits"]!!.jsonObject["maxTimeMs"]!!.jsonPrimitive.int)
        val move = body["playedMove"]!!.jsonPrimitive.content
        val ev = """{"depth":22,"score":{"type":"cp","value":20,"bound":"exact"},"pv":["$move"]}"""
        return MockResponse().setBody("""{"best":$ev,"played":$ev,"comparison":{"canCompare":$comparable,"commonDepth":22},"engine":{"name":"Stockfish","version":"19"}}""")
    }
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(10_000) { while (!predicate()) delay(10) }
    @Test fun importedLocalUnfinishedAndPresetGamesAreCompletedAndRestartReusesTheCache() = runBlocking {
        val imported = ChessComImport.parse("""[White "Me"]
[Black "Other"]
[Result "1-0"]

1.e4 e5 1-0""", "me", "https://www.chess.com/game/live/20", 1700000000)
        repo.importGames(listOf(imported)); val local = game(); val pending = game(2).copy(finished = false)
        val course = game(2).copy(openingTraining = OpeningTraining("italian", "main", 2))
        repo.save(local); repo.save(pending); repo.save(course)
        runner().run()
        assertEquals(9, server.requestCount)
        assertTrue(repo.all().all { AutoAnalysis.missing(it).isEmpty() })
        assertEquals(PlayerProfile(), repo.profile())
        val saved = repo.all().associate { it.id to it.reviews }
        runner().run()
        assertEquals(9, server.requestCount); assertEquals(saved, repo.all().associate { it.id to it.reviews })
        assertEquals(0, states.last().pendingSteps)
    }
    @Test fun cancellingTheWorkerKeepsTheFirstResultAndANewWorkerResumesOnlyMissingSteps() = runBlocking {
        val calls = AtomicInteger(); val blocked = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            if (calls.incrementAndGet() == 2) { blocked.countDown(); release.await(10, TimeUnit.SECONDS) }
            return response(request)
        } }
        val game = game(); repo.save(game)
        val job = launch(Dispatchers.IO) { runner().run() }
        try {
            until { repo.find(game.id)!!.reviews.size == 1 }
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            val first = repo.find(game.id)!!.reviews.single()
            job.cancelAndJoin(); release.countDown()
            runner().run()
            assertEquals(first, repo.find(game.id)!!.reviews.first { it.ply == 1 })
            assertEquals(3, repo.find(game.id)!!.reviews.size); assertEquals(4, server.requestCount)
        } finally { job.cancelAndJoin(); release.countDown() }
    }
    @Test fun unstableResultsAreDeferredWithoutBlockingOtherStepsAndLaterRetried() = runBlocking {
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = response(request, calls.incrementAndGet() != 1) }
        val game = game(); repo.save(game)
        runner().run()
        assertEquals(4, server.requestCount)
        assertEquals(listOf(30_000L), waits)
        assertTrue(AutoAnalysis.missing(repo.find(game.id)!!).isEmpty())
        assertTrue(states.any { it.message.contains("等待重试") })
    }
    @Test fun noRequestsWhileOfflineOrInteractiveAndNewImportsJoinTheRunningQueue() = runBlocking {
        repo.save(game(2)); online = false
        waiting = { online = true; interactive = true; waiting = {
            interactive = false; repo.save(game(1)); revision++; waiting = null
        } }
        runner().run()
        assertEquals(3, server.requestCount)
        assertTrue(states.any { it.message.contains("网络") }); assertTrue(states.any { it.message.contains("手动复盘") })
    }
    @Test fun invalidTokenStopsWithoutLosingAnySavedGameOrSendingFurtherRequests() = runBlocking {
        repo.save(game()); repo.save(game(2))
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401) }
        runner().run()
        assertEquals(1, server.requestCount); assertTrue(auth); assertTrue(states.last().needsToken)
        assertEquals(2, repo.all().size); assertTrue(repo.all().all { it.reviews.isEmpty() })
    }
    @Test fun deletingTheGameWhileAnAnalysisIsInFlightCannotRestoreIt() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            entered.countDown(); release.await(10, TimeUnit.SECONDS); return response(request)
        } }
        val game = game(1); repo.save(game)
        val job = launch(Dispatchers.IO) { runner().run() }
        try { assertTrue(entered.await(3, TimeUnit.SECONDS)); repo.delete(game.id); release.countDown(); job.join()
            assertNull(repo.find(game.id)); assertEquals(PlayerProfile(), repo.profile())
        } finally { release.countDown(); job.cancelAndJoin() }
    }
    @Test fun concurrentOldSnapshotsAndNewMovesKeepSavedAnalysisLessonsAndSettledElo() = runBlocking {
        val game = EloRules.newGame(PlayerProfile(), humanWhite = true).copy(moves = game(2).moves, finished = true, result = "1-0")
        repo.save(game); runner().run()
        val saved = repo.find(game.id)!!
        val lesson = MoveCoach.explain(emptyList(), saved.reviews.first())
        repo.save(saved.copy(lessons = listOf(lesson)))
        coroutineScope { List(6) { async(Dispatchers.IO) { repo.save(game.copy(finished = false)) } }.awaitAll() }
        val kept = repo.find(game.id)!!
        assertTrue(kept.finished); assertEquals(saved.reviews, kept.reviews); assertEquals(listOf(lesson), kept.lessons)
        assertEquals(PlayerProfile(532, 1), repo.profile())
        assertEquals(saved.ratingChange, kept.ratingChange)
    }
}
