package cn.yibu.chess.data

import android.content.Context
import cn.yibu.chess.core.ChessComImport

class ImportPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("chesscom-import", Context.MODE_PRIVATE)
    fun username(): String = preferences.getString("username", "").orEmpty()
    fun saveUsername(value: String) { preferences.edit().putString("username", ChessComImport.username(value)).apply() }
}
