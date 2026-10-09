package cn.yibu.chess.data

import android.content.Context
import cn.yibu.chess.core.PracticeProgress
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Separate from Elo and game storage; practice never settles or rewrites a game. */
class PracticePreferences(context: Context) {
    private val preferences = context.getSharedPreferences("mistake-practice", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun read(): Map<String, PracticeProgress> = runCatching {
        json.decodeFromString<List<PracticeProgress>>(preferences.getString("progress", "[]") ?: "[]").associateBy { it.key }
    }.getOrDefault(emptyMap())
    fun save(records: Map<String, PracticeProgress>) {
        preferences.edit().putString("progress", json.encodeToString(records.values.toList())).apply()
    }
    fun retainGames(gameIds: Set<Long>): Map<String, PracticeProgress> {
        val old = read()
        val retained = old.filterKeys { it.substringBefore(':').toLongOrNull() in gameIds }
        if (retained.size != old.size) save(retained)
        return retained
    }
}
