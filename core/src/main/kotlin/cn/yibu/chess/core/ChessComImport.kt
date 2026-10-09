package cn.yibu.chess.core

import com.github.bhlangonijr.chesslib.move.MoveList
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale

/** Import only completed standard chess, preserving its actual owner and stable source identity. */
object ChessComImport {
    private val header = Regex("(?m)^\\s*\\[(\\w+)\\s+\"((?:\\\\.|[^\"\\\\])*)\"\\]\\s*$")
    fun username(value: String): String = value.trim().also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,50}"))) { "请输入 Chess.com 用户名，不能填写昵称或网址" }
    }.lowercase(Locale.ROOT)

    fun parse(pgn: String, username: String, url: String, endedAt: Long, whiteRating: Int? = null,
        blackRating: Int? = null, timeClass: String = "", timeControl: String = ""): GameRecord {
        require(endedAt > 0 && endedAt <= Long.MAX_VALUE / 1000) { "棋局结束时间无效" }
        require(pgn.length <= 200_000) { "棋谱过大" }
        val owner = username(username)
        val tags = header.findAll(pgn).associate { it.groupValues[1] to it.groupValues[2].replace("\\\"", "\"").replace("\\\\", "\\") }
        require(tags["Variant"].let { it == null || it.equals("standard", true) || it.equals("chess", true) }) { "暂不支持变体棋谱" }
        require(tags["FEN"].let { it == null || it == ChessRules.START_FEN }) { "暂不支持非标准起始局面" }
        val white = requireNotNull(tags["White"]) { "缺少白方用户名" }
        val black = requireNotNull(tags["Black"]) { "缺少黑方用户名" }
        require(white.equals(owner, true) || black.equals(owner, true)) { "棋谱不属于该用户名" }
        val result = tags["Result"]
        require(result in listOf("1-0", "0-1", "1/2-1/2")) { "棋局尚未结束或结果无效" }
        require(url.matches(Regex("https://(?:www\\.)?chess\\.com/game/(?:live|daily)/[0-9]+"))) { "棋局链接无效" }
        val text = mainline(header.replace(pgn, " "))
        val rawTokens = text.split(Regex("\\s+")).map { it.replace(Regex("^\\d+\\.(?:\\.\\.)?"), "").trimEnd('!', '?') }
        val footer = rawTokens.filter { it in listOf("1-0", "0-1", "1/2-1/2", "*") }
        require(footer.all { it == result }) { "棋谱结果与棋步末尾结果不一致" }
        val tokens = rawTokens.filter { it.isNotBlank() && it !in listOf("1-0", "0-1", "1/2-1/2", "*", "e.p.", "ep", "...") && !it.startsWith('$') }
            .map { if (it.startsWith("0-0")) it.replace('0', 'O') else it }
        require(tokens.size in 1..2000 && tokens.none { it.equals("Z0", true) || it.equals("ZO", true) || it == "--" }) { "棋谱走法为空或无效" }
        val moves = MoveList().apply { loadFromSan(tokens.joinToString(" ")) }.map { it.toString().lowercase(Locale.ROOT) }
        // SAN decoding is not a substitute for complete legality checking.
        require(moves.size == tokens.size && ChessRules.legalVariation(emptyList(), moves) == moves) { "棋谱包含非法走法，未导入该局" }
        val ownWhite = white.equals(owner, true)
        val wr = whiteRating ?: tags["WhiteElo"]?.toIntOrNull()
        val br = blackRating ?: tags["BlackElo"]?.toIntOrNull()
        val source = ChessComSource(owner, url, white, black, wr, br, timeClass,
            timeControl.ifBlank { tags["TimeControl"].orEmpty() }, pgn)
        return GameRecord(id = sourceId(url), startedAt = endedAt * 1000, humanWhite = ownWhite, moves = moves,
            result = requireNotNull(result), ending = "Chess.com 已结束棋局", finished = true, rated = false,
            playerEloAtStart = source.playerRating(ownWhite), opponentElo = if (ownWhite) br else wr,
            opponentEngine = source.opponent(ownWhite), source = source)
    }

    fun sourceId(url: String): Long {
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.removePrefix("https://www.").removePrefix("https://").toByteArray(Charsets.UTF_8))
        return -(ByteBuffer.wrap(bytes).long and Long.MAX_VALUE).coerceAtLeast(1)
    }
    private fun mainline(text: String): String {
        val out = StringBuilder()
        var comment = false
        var lineComment = false
        var variation = 0
        text.forEach { char ->
            when {
                lineComment -> if (char == '\n') { lineComment = false; out.append(' ') }
                comment -> if (char == '}') { comment = false; out.append(' ') }
                char == '{' -> { comment = true; out.append(' ') }
                char == ';' -> { lineComment = true; out.append(' ') }
                char == '(' -> { variation++; out.append(' ') }
                char == ')' -> { require(variation > 0) { "棋谱变化括号不匹配" }; variation--; out.append(' ') }
                variation == 0 -> out.append(char)
            }
        }
        require(!comment && variation == 0) { "棋谱注释或变化不完整" }
        return out.toString()
    }
}
