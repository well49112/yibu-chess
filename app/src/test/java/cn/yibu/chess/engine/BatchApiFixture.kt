package cn.yibu.chess.engine

import cn.yibu.chess.core.ChessRules
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*

/** Models the public UniChessServer review summary and its cached analyze-move response. */
internal object BatchApiFixture {
    fun summary(moves: List<String>, plies: List<Int> = (1..moves.size).toList()): String {
        val rows = plies.joinToString(",") { ply ->
            """{"ply":$ply,"move":"${moves[ply-1]}","turn":"${if (ply%2==1) "white" else "black"}","san":"${ChessRules.san(moves.take(ply-1), moves[ply-1])}","bestMove":"${moves[ply-1]}","diffCp":0,"judgment":"best","accuracy":100,"depth":22,"score":{"type":"cp","value":20},"elapsedMs":600,"cached":false}"""
        }
        return """{"totalPlies":${moves.size},"analyzedPlies":${plies.size},"cacheHits":0,"elapsedMs":600,"effectivePliesPerSecond":5,"summary":{"whiteAccuracy":100,"blackAccuracy":100,"whiteAcpl":0,"blackAcpl":0,"whiteJudgments":{},"blackJudgments":{}},"moves":[$rows]}"""
    }
    fun response(request: RecordedRequest, comparable: Boolean = true): MockResponse {
        val body = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject
        assertEquals("lightning", body.getValue("profile").jsonPrimitive.content)
        if (request.path == "/sf/v1/review") {
            assertEquals(JsonNull, body["initialFen"]); assertEquals(JsonNull, body["concurrency"])
            assertFalse(body.containsKey("limits"))
            return MockResponse().setBody(summary(body.getValue("moves").jsonArray.map { it.jsonPrimitive.content }))
        }
        assertEquals("/sf/v1/analyze-move", request.path)
        if (body.containsKey("maxPvPlies")) {
            assertEquals(JsonNull, body.getValue("position").jsonObject["initialFen"])
            assertEquals(1, body.getValue("multiPv").jsonPrimitive.int)
            assertEquals(10, body.getValue("maxPvPlies").jsonPrimitive.int)
            assertFalse(body.containsKey("limits"))
        } else {
            assertEquals(22, body.getValue("limits").jsonObject.getValue("depth").jsonPrimitive.int)
            assertEquals(500, body.getValue("limits").jsonObject.getValue("maxTimeMs").jsonPrimitive.int)
        }
        val move = body.getValue("playedMove").jsonPrimitive.content
        val ev = """{"depth":22,"score":{"type":"cp","value":20,"bound":"exact"},"pv":["$move"]}"""
        return MockResponse().setBody("""{"best":$ev,"played":$ev,"comparison":{"canCompare":$comparable,"commonDepth":22},"engine":{"name":"Stockfish","version":"19"},"stats":{"cached":true}}""")
    }
}
