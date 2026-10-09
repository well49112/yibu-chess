package cn.yibu.chess

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.activity.viewModels
import cn.yibu.chess.ui.ChessApp
import cn.yibu.chess.background.AutoReview

class MainActivity : ComponentActivity() {
    private val model: GameViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.rgb(247, 246, 242), Color.rgb(247, 246, 242)))
        setContent { ChessApp(model) }
    }
    override fun onStart() { super.onStart(); AutoReview.visible(this, true); model.resumeForeground() }
    override fun onStop() { AutoReview.visible(this, false); model.pauseForBackground(); super.onStop() }
}
