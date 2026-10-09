package cn.yibu.chess.core

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChessComImportTest {
    private fun pgn(body: String = "1.e4 {[%clk 0:04:59]} 1...e5 2.Nf3 (2.Bc4 Nc6 (2...Nf6)) Nc6 3.Bc4 Bc5 4.0-0 Nf6 1-0",
        tags: String = ""): String = """[White "Learner"]
[Black "Rival"]
[Result "1-0"]
[WhiteElo "500"]
[BlackElo "520"]
$tags

$body"""
    private fun parse(pgn: String = pgn(), username: String = "learner", url: String = "https://www.chess.com/game/live/123") =
        ChessComImport.parse(pgn, username, url, 1_700_000_000, timeClass = "blitz", timeControl = "300")

    @Test fun multipleCommentsVariationsMoveNumbersAndCastlingDecodeTheEntireMainline() {
        val game = parse()
        assertEquals(listOf("e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5", "e1g1", "g8f6"), game.moves)
        assertTrue(game.humanWhite)
        assertTrue(game.finished)
        assertFalse(game.rated)
        assertEquals(500, game.playerEloAtStart)
        assertEquals(520, game.opponentElo)
        assertEquals("blitz", game.source!!.timeClass)
        assertEquals(pgn(), ChessRules.pgn(game))
        assertFalse(EloRules.eligible(game.copy(rated = true)))
        assertEquals(game.opponentEngine, HumanOpponent.prepare(game).opponentEngine)
    }
    @Test fun promotionsEnPassantAndAnnotationsPreserveSpecialMoveSemantics() {
        val promoted = parse(pgn("1.a4 h5 2.a5 h4 3.a6 h3 4.axb7 hxg2 5.bxa8=Q gxh1=Q 1-0"))
        assertEquals(listOf("b7a8q", "g2h1q"), promoted.moves.takeLast(2))
        val promotedBoard = ChessRules.fenPieces(ChessRules.board(promoted.moves).fen)
        assertEquals('Q', promotedBoard[ChessRules.squareIndex("a8")])
        assertEquals('q', promotedBoard[ChessRules.squareIndex("h1")])
        val ep = parse(pgn("1.e4 $1 a6 2.e5 d5 3.exd6 e.p. 1-0"))
        assertEquals("e5d6", ep.moves.last())
        val epBoard = ChessRules.fenPieces(ChessRules.board(ep.moves).fen)
        assertEquals(' ', epBoard[ChessRules.squareIndex("d5")])
        assertEquals('P', epBoard[ChessRules.squareIndex("d6")])
    }
    @Test fun usernamesAreRememberableCaseInsensitiveAndSourceIdsAreStableAcrossImports() {
        val white = parse(username = " Learner ")
        val black = parse(username = "RIVAL")
        assertFalse(black.humanWhite)
        assertEquals(520, black.playerEloAtStart)
        assertEquals(500, black.opponentElo)
        assertEquals(white.id, black.id)
        assertTrue(white.id < 0)
        assertEquals(white.id, parse(url = "https://chess.com/game/live/123").id)
        assertNotEquals(white.id, parse(url = "https://www.chess.com/game/live/124").id)
    }
    @Test fun actualChessComArchivePgnWithClocksAndDisambiguationLoadsAsBlack() {
        val pgn = javaClass.getResourceAsStream("/chesscom-import-example.pgn")!!.bufferedReader().use { it.readText() }
        val game = ChessComImport.parse(pgn, "erik", "https://www.chess.com/game/daily/1033338800", 1_790_845_181)
        assertFalse(game.humanWhite)
        assertEquals(55, game.moves.size)
        assertEquals("1-0", game.result)
        assertEquals("Maia-3 5M", EloRules.newGame(PlayerProfile()).opponentEngine)
        assertEquals(game.moves, ChessRules.legalVariation(emptyList(), game.moves))
    }
    @Test fun illegalIncompleteVariantAndWrongOwnerGamesAreRejectedWithoutPartialScores() {
        val invalid = listOf(pgn("1.e4 e5 2.Ke5 1-0"), pgn(tags = "[FEN \"8/8/8/8/8/8/8/8 w - - 0 1\"]"),
            pgn(tags = "[Variant \"Chess960\"]"), pgn().replace("[Result \"1-0\"]", "[Result \"*\"]"), pgn("1.e4 {unterminated"), pgn("1.e4 e5 0-1"))
        invalid.forEach { assertTrue(runCatching { parse(it) }.isFailure) }
        assertTrue(runCatching { parse(username = "unknown") }.isFailure)
        assertTrue(runCatching { ChessComImport.username("https://chess.com/player/test") }.isFailure)
    }
    @Test fun oldSavedGamesDecodeWithNoImportOrOpeningMetadata() {
        val old = Json.decodeFromString<GameRecord>("""{"id":1,"moves":["e2e4"],"humanWhite":true}""")
        assertNull(old.source)
        assertNull(old.openingTraining)
        assertTrue(old.isPlayerMove(1))
    }
}
