package cn.yibu.chess.engine

import cn.yibu.chess.core.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

class BatchReviewClientTest {
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var client: RemoteStockfishClient
    private val moves = listOf("e2e4", "e7e5", "g1f3", "b8c6")
    @Before fun setup() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = BatchApiFixture.response(request) }
            start()
        }
        http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        client = RemoteStockfishClient({ " token " }, server.url("/").toString(), http)
    }
    @After fun close() { client.stop(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdownNow(); server.shutdown() }
    @Test fun batchUsesCanonicalEndpointAndDetailsMatchEveryServerCacheKey() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/sf/v1/review") return MockResponse().setBody(BatchApiFixture.summary(moves, listOf(4, 2, 3, 1)))
            return BatchApiFixture.response(request)
        } }
        val results = mutableListOf<Pair<Int, RemoteMoveAnalysis>>()
        client.reviewGame(moves) { ply, analysis -> results += ply to analysis }
        assertEquals(listOf(1, 2, 3, 4), results.map { it.first })
        assertEquals(moves, results.map { it.second.played.pv.first() })
        assertEquals(-.2, results[1].second.played.whiteScore(false), .0001)
        assertEquals(5, server.requestCount)
        val bulk = server.takeRequest()
        assertEquals("/sf/v1/review", bulk.path); assertEquals("token", bulk.getHeader("X-Access-Token"))
        val body = Json.parseToJsonElement(bulk.body.readUtf8()).jsonObject
        assertEquals(moves, body.getValue("moves").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(body.containsKey("limits")); assertEquals(JsonNull, body["concurrency"])
        val id = body.getValue("requestId").jsonPrimitive.content
        assertTrue(id.isNotBlank())
        val histories = mutableListOf<List<String>>()
        repeat(4) {
            val req = server.takeRequest(); assertEquals("token", req.getHeader("X-Access-Token"))
            val data = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            val position = data.getValue("position").jsonObject
            assertEquals(JsonNull, position["initialFen"]); assertFalse(data.containsKey("limits"))
            assertEquals(1, data.getValue("multiPv").jsonPrimitive.int)
            assertEquals(10, data.getValue("maxPvPlies").jsonPrimitive.int)
            val history = position.getValue("moves").jsonArray.map { it.jsonPrimitive.content }
            histories += history
            assertEquals("$id-${history.size + 1}", data.getValue("requestId").jsonPrimitive.content)
        }
        assertEquals(moves.indices.map { moves.take(it) }, histories.sortedBy { it.size })
    }
    @Test fun fourCacheReadsRunConcurrentlyRatherThanWaitingForEachRoundTrip() = runBlocking {
        val entered = CountDownLatch(4)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path != "/sf/v1/review") {
                entered.countDown()
                if (!entered.await(2, TimeUnit.SECONDS)) return MockResponse().setResponseCode(504)
            }
            return BatchApiFixture.response(request)
        } }
        val saved = mutableListOf<Int>()
        withTimeout(5000) { client.reviewGame(moves) { ply, _ -> saved += ply } }
        assertEquals(listOf(1, 2, 3, 4), saved)
    }
    @Test fun resumingFetchesOnlyNeededDetailsButKeepsCompleteHistoryInBatch() = runBlocking {
        val saved = mutableListOf<Int>()
        client.reviewGame(moves, requestedPlies = setOf(2, 4)) { ply, _ -> saved += ply }
        assertEquals(listOf(2, 4), saved); assertEquals(3, server.requestCount)
        assertEquals(moves, Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["moves"]!!.jsonArray.map { it.jsonPrimitive.content })
        val sizes = (1..2).map { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["position"]!!.jsonObject["moves"]!!.jsonArray.size }
        assertEquals(listOf(1, 3), sizes.sorted())
    }
    @Test fun missingSummaryRowsPreserveOtherResultsAndReportIncompleteReview() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse =
            if (request.path == "/sf/v1/review") MockResponse().setBody(BatchApiFixture.summary(moves, listOf(1, 3, 4)))
            else BatchApiFixture.response(request)
        }
        val saved = mutableListOf<Int>()
        val error = runCatching { client.reviewGame(moves) { ply, _ -> saved += ply } }.exceptionOrNull()
        assertNotNull(error); assertEquals(listOf(1, 3, 4), saved); assertEquals(4, server.requestCount)
    }
    @Test fun oneFailedDetailDoesNotDiscardOtherCompletedMoves() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path != "/sf/v1/review" && Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["playedMove"]!!.jsonPrimitive.content == "e7e5")
                return MockResponse().setResponseCode(503)
            return BatchApiFixture.response(request)
        } }
        val saved = mutableListOf<Int>()
        assertTrue(runCatching { client.reviewGame(moves) { ply, _ -> saved += ply } }.isFailure)
        assertEquals(listOf(1, 3, 4), saved)
    }
    @Test fun mismatchedOrDuplicateSummaryIdentityCannotBeAssignedToAnotherMove() = runBlocking {
        for (rows in listOf(listOf(1, 1, 3, 4), listOf(1, 2, 3, 4))) {
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) =
                MockResponse().setBody(BatchApiFixture.summary(moves, rows).let { if (rows.distinct().size == rows.size) it.replace("\"move\":\"e7e5\"", "\"move\":\"c7c5\"") else it }) }
            assertTrue(runCatching { client.reviewGame(moves) { _, _ -> fail("Wrong identity must never reach storage") } }.isFailure)
        }
        assertEquals(2, server.requestCount)
    }
    @Test fun summaryJudgmentIsIgnoredAndFullLegalComparisonsDrivePhoneRating() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/sf/v1/review") return BatchApiFixture.response(request)
            val ev = """{"depth":22,"score":{"type":"cp","value":50},"pv":["d2d4","d7d5"]}"""
            val played = """{"depth":22,"score":{"type":"cp","value":-300},"pv":["e2e4","c7c5"]}"""
            return MockResponse().setBody("""{"best":$ev,"played":$played,"comparison":{"canCompare":true,"commonDepth":22,"diffCp":-350}}""")
        } }
        var analysis: RemoteMoveAnalysis? = null
        client.reviewGame(listOf("e2e4")) { _, a -> analysis = a }
        val rating = MoveAnalyzer(client).fromAnalysis(emptyList(), "e2e4", analysis!!, true, 500, "lightning")
        assertEquals(Grade.BLUNDER, rating.grade); assertEquals(-300, rating.played.cp)
        assertEquals(listOf("e2e4", "c7c5"), rating.played.pv)
    }
    @Test fun malformedDetailStillRejectsIllegalVariationAndUnmatchedDepthIsProvisional() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/sf/v1/review") return BatchApiFixture.response(request)
            return MockResponse().setBody("""{"best":{"depth":22,"score":{"type":"cp","value":0},"pv":["e2e4"]},"played":{"depth":21,"score":{"type":"cp","value":0},"pv":["e2e4"]},"comparison":{"canCompare":true,"commonDepth":22}}""")
        } }
        var rating: MoveReview? = null
        client.reviewGame(listOf("e2e4")) { _, a -> rating = MoveAnalyzer(client).fromAnalysis(emptyList(), "e2e4", a, true) }
        assertEquals(Grade.UNSTABLE, rating!!.grade)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse =
            if (request.path == "/sf/v1/review") BatchApiFixture.response(request)
            else MockResponse().setBody("""{"best":{"depth":22,"score":{"type":"cp","value":0},"pv":["e2e5"]},"played":{"depth":22,"score":{"type":"cp","value":0},"pv":["e2e4"]},"comparison":{"canCompare":true,"commonDepth":22}}""")
        }
        assertTrue(runCatching { client.reviewGame(listOf("e2e4")) { _, _ -> fail("Illegal PV must not be saved") } }.isFailure)
    }
    @Test fun batchHasLongTimeoutButCancellationStillWorksAndLeavesNextRequestUsable() = runBlocking {
        val quickHttp = http.newBuilder().callTimeout(50, TimeUnit.MILLISECONDS).readTimeout(50, TimeUnit.MILLISECONDS).build()
        val longBatch = RemoteStockfishClient({ "token" }, server.url("/").toString(), quickHttp)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse =
            BatchApiFixture.response(request).setBodyDelay(120, TimeUnit.MILLISECONDS) }
        longBatch.reviewGame(listOf("e2e4")) { _, _ -> }
        repeat(2) { server.takeRequest() }
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE) }
        val job = async { longBatch.reviewGame(moves) { _, _ -> } }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        withTimeout(1000) { job.cancelAndJoin() }; assertTrue(job.isCancelled)
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = BatchApiFixture.response(request) }
        client.reviewGame(listOf("e2e4")) { _, _ -> }
    }
}
