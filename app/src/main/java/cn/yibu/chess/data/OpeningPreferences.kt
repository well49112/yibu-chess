package cn.yibu.chess.data

import android.content.Context

class OpeningPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("opening-learning", Context.MODE_PRIVATE)
    fun progress(): Set<String> = preferences.getStringSet("progress", emptySet()).orEmpty().toSet()
    fun save(progress: Set<String>) { preferences.edit().putStringSet("progress", progress.toSet()).apply() }
}
