package cn.yibu.chess.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yibu.chess.background.AutoReviewState

@Composable
internal fun AutoReviewStatus(state: AutoReviewState, onPause: () -> Unit, onResume: () -> Unit) {
    if (state.message.isBlank()) return
    val context = LocalContext.current
    var notifications by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    Surface(color = Soft, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("auto-review-status")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(state.message, fontSize = 12.sp, color = Accent, lineHeight = 19.sp)
            if (state.pendingSteps > 0) Text("剩余 ${state.pendingGames} 盘 · ${state.pendingSteps} 步 · 支持后台继续", fontSize = 11.sp, color = Muted)
            if (state.running || state.paused || state.pendingSteps > 0) Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = feedbackClick(if (state.paused || !state.running) onResume else onPause), contentPadding = PaddingValues(4.dp)) {
                    Text(if (state.paused || !state.running) "继续分析" else "暂停", fontSize = 11.sp)
                }
                if (!notifications && Build.VERSION.SDK_INT >= 33) TextButton(onClick = feedbackClick { request.launch(Manifest.permission.POST_NOTIFICATIONS) }, contentPadding = PaddingValues(4.dp)) {
                    Text("显示进度通知", fontSize = 11.sp)
                }
                TextButton(onClick = feedbackClick { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }, contentPadding = PaddingValues(4.dp)) {
                    Text("后台运行设置", fontSize = 11.sp)
                }
            }
            if (state.needsToken) Text("请在对局设置中填写或更新朋友提供的 Access Token。", color = Muted, fontSize = 11.sp, lineHeight = 18.sp)
        }
    }
}
