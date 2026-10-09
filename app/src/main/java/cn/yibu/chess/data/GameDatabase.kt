package cn.yibu.chess.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cn.yibu.chess.core.*
import cn.yibu.chess.diagnostics.AnalysisTimings
import android.os.SystemClock
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "games")
data class StoredGame(@PrimaryKey val id: Long, val startedAt: Long, val payload: String)

@Entity(tableName = "player_profile")
data class StoredProfile(@PrimaryKey val id: Int = 1, val rating: Int = 500, val ratedGames: Int = 0) {
    fun value() = PlayerProfile(rating, ratedGames)
}

@Entity(tableName = "rating_history")
data class StoredRating(@PrimaryKey val gameId: Long, val before: Int, val after: Int, val opponent: Int,
    val score: Double, val expected: Double, val k: Int) {
    fun value() = RatingChange(before, after, opponent, score, expected, k)
}

@Entity(tableName = "deleted_games")
data class DeletedGame(@PrimaryKey val id: Long)

@Dao
interface GameDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(game: StoredGame)
    @Query("SELECT * FROM games ORDER BY startedAt DESC, id DESC") fun observe(): kotlinx.coroutines.flow.Flow<List<StoredGame>>
    @Query("SELECT * FROM games ORDER BY startedAt DESC, id DESC LIMIT 1") suspend fun latest(): StoredGame?
    @Query("SELECT * FROM games WHERE id = :id") suspend fun find(id: Long): StoredGame?
    @Query("SELECT * FROM games ORDER BY startedAt DESC, id DESC") suspend fun all(): List<StoredGame>
    @Query("SELECT id FROM games ORDER BY startedAt DESC, id DESC") fun ids(): kotlinx.coroutines.flow.Flow<List<Long>>
    @Query("DELETE FROM games WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface RatingDao {
    @Query("SELECT * FROM player_profile WHERE id = 1") fun observeProfile(): kotlinx.coroutines.flow.Flow<StoredProfile?>
    @Query("SELECT * FROM player_profile WHERE id = 1") suspend fun profile(): StoredProfile?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(profile: StoredProfile)
    @Query("SELECT * FROM rating_history WHERE gameId = :id") suspend fun rating(id: Long): StoredRating?
    @Insert suspend fun record(rating: StoredRating)
    @Query("SELECT * FROM deleted_games WHERE id = :id") suspend fun deleted(id: Long): DeletedGame?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markDeleted(game: DeletedGame)
}

@Database(entities = [StoredGame::class, StoredProfile::class, StoredRating::class, DeletedGame::class], version = 2, exportSchema = false)
abstract class GameDatabase : RoomDatabase() {
    abstract fun games(): GameDao
    abstract fun ratings(): RatingDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS player_profile (id INTEGER NOT NULL, rating INTEGER NOT NULL, ratedGames INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS rating_history (gameId INTEGER NOT NULL, `before` INTEGER NOT NULL, `after` INTEGER NOT NULL, opponent INTEGER NOT NULL, score REAL NOT NULL, expected REAL NOT NULL, k INTEGER NOT NULL, PRIMARY KEY(gameId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS deleted_games (id INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("INSERT OR IGNORE INTO player_profile (id, rating, ratedGames) VALUES (1, 500, 0)")
            }
        }
        @Volatile private var instance: GameDatabase? = null
        // Room opens lazily. isOpen=false does not mean a fresh database is unusable.
        fun get(context: Context): GameDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, GameDatabase::class.java, "yibu-chess.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
        fun resetForTests() = synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}

data class SaveResult(val game: GameRecord?, val profile: PlayerProfile)
data class ImportSaveResult(val imported: Int, val duplicates: Int, val deleted: Int)

class GameRepository(context: Context, private val database: GameDatabase = GameDatabase.get(context)) {
    private val dao = database.games()
    private val ratings = database.ratings()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val games = dao.observe().map { rows ->
        val start = SystemClock.elapsedRealtimeNanos()
        val decoded = rows.mapNotNull { runCatching { json.decodeFromString<GameRecord>(it.payload) }.getOrNull() }
        AnalysisTimings.reload(start, rows.size, rows.sumOf { it.payload.length.toLong() })
        decoded
    }
    val ids = dao.ids()
    suspend fun all(): List<GameRecord> = dao.all().mapNotNull { runCatching { json.decodeFromString<GameRecord>(it.payload) }.getOrNull() }
    suspend fun find(id: Long): GameRecord? = dao.find(id)?.let { json.decodeFromString<GameRecord>(it.payload) }
    val profiles = ratings.observeProfile().map { it?.value() ?: PlayerProfile() }
    suspend fun profile(): PlayerProfile = ratings.profile()?.value() ?: PlayerProfile()
    suspend fun latest(): GameRecord? = dao.latest()?.let { runCatching { json.decodeFromString<GameRecord>(it.payload) }.getOrNull() }
    suspend fun importGames(games: List<GameRecord>): ImportSaveResult = database.withTransaction {
        var imported = 0
        var duplicates = 0
        var deleted = 0
        for (game in games) {
            val source = requireNotNull(game.source)
            require(game.finished && !game.rated && game.ratingChange == null)
            require(game.id == ChessComImport.sourceId(source.url))
            if (ratings.deleted(game.id) != null) { deleted++; continue }
            val previous = dao.find(game.id)
            if (previous != null) {
                require(json.decodeFromString<GameRecord>(previous.payload).source?.url?.replace("https://www.", "https://") ==
                    source.url.replace("https://www.", "https://")) { "棋谱编号冲突，该批次未写入" }
                duplicates++
                continue // Keep existing reviews and lessons intact.
            }
            dao.save(StoredGame(game.id, game.startedAt, json.encodeToString(game)))
            imported++
        }
        ImportSaveResult(imported, duplicates, deleted)
    }
    suspend fun save(game: GameRecord): SaveResult = database.withTransaction {
        var profile = profile()
        // Cancelled analysis may finish after a deletion; it must never restore the record.
        if (ratings.deleted(game.id) != null) return@withTransaction SaveResult(null, profile)
        val previous = dao.find(game.id)?.let { runCatching { json.decodeFromString<GameRecord>(it.payload) }.getOrNull() }
        // A navigation save queued before the last move must not undo a finished game.
        val snapshot = GameSnapshots.merge(game, previous)
        var change = ratings.rating(game.id)?.value()
        if (change == null && EloRules.eligible(snapshot)) {
            change = EloRules.calculate(profile, requireNotNull(snapshot.opponentElo), requireNotNull(EloRules.score(snapshot)))
            ratings.record(StoredRating(game.id, change.before, change.after, change.opponent, change.score, change.expected, change.k))
            profile = PlayerProfile(change.after, profile.ratedGames + 1)
            ratings.save(StoredProfile(rating = profile.rating, ratedGames = profile.ratedGames))
        }
        val saved = snapshot.copy(ratingChange = change)
        dao.save(StoredGame(saved.id, saved.startedAt, json.encodeToString(saved)))
        SaveResult(saved, profile)
    }
    suspend fun saveReview(expected: GameRecord, review: MoveReview, onlyIfUnchanged: Boolean = true): GameRecord? = database.withTransaction {
        if (ratings.deleted(expected.id) != null) return@withTransaction null
        val current = find(expected.id) ?: return@withTransaction null
        val ply = review.ply
        if (ply !in 1..current.moves.size || review.uci != current.moves[ply - 1] ||
            current.moves.take(ply) != expected.moves.take(ply)) return@withTransaction null
        val previous = current.reviews.find { it.ply == ply }
        if (onlyIfUnchanged && previous != expected.reviews.find { it.ply == ply }) return@withTransaction current
        val updated = current.copy(reviews = (current.reviews.filterNot { it.ply == ply } + review).sortedBy { it.ply },
            lessons = current.lessons.filterNot { it.ply == ply && previous != review })
        dao.save(StoredGame(updated.id, updated.startedAt, json.encodeToString(updated)))
        updated
    }
    suspend fun delete(id: Long) = database.withTransaction {
        ratings.markDeleted(DeletedGame(id))
        dao.delete(id)
        // Rating history remains authoritative, even when its chess score is removed.
    }
    fun diagnostics(game: GameRecord): String = json.encodeToString(game)
}
