package cn.yibu.chess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yibu.chess.AppState

@Composable
internal fun ChessComImportButton(state: AppState, onImport: (String, Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var username by rememberSaveable(state.chessComUsername) { mutableStateOf(state.chessComUsername) }
    var range by rememberSaveable { mutableIntStateOf(0) }
    FilledTonalButton(onClick = feedbackClick { open = true }, enabled = !state.importing && !state.transitioning,
        modifier = Modifier.testTag("chesscom-import-button")) { Text("导入", fontSize = 13.sp) }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("导入 Chess.com 棋局") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(username, { username = it }, label = { Text("Chess.com 用户名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("chesscom-username"))
            Text("不需要账号密码。用户名会记住，可随时修改。", color = Muted, fontSize = 12.sp)
            listOf("最近 30 盘" to 0, "最近 100 盘" to 1, "全部历史棋局" to 2).forEach { (label, index) ->
                FilterChip(selected = range == index, onClick = feedbackClick { range = index }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
            }
            Text("只导入公开、已结束的标准国际象棋。自动识别你的执棋颜色，保留原分数；不改变弈步 Elo。全部历史按月保存，途中可停止。", fontSize = 12.sp, lineHeight = 19.sp)
        }
    }, confirmButton = {
        TextButton(onClick = feedbackClick { onImport(username, when (range) { 0 -> 30; 1 -> 100; else -> null }); open = false },
            enabled = username.isNotBlank()) { Text("开始导入") }
    }, dismissButton = { TextButton(onClick = feedbackClick { open = false }) { Text("取消") } })
}

@Composable
internal fun ChessComImportStatus(state: AppState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    if (!state.importing && state.importStatus.isBlank() && state.importError == null) return
    Surface(color = Soft, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().testTag("chesscom-import-status")) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (state.importing) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text(state.importStatus, Modifier.weight(1f), color = Accent, fontSize = 12.sp, lineHeight = 18.sp)
                TextButton(onClick = feedbackClick(if (state.importing) onCancel else onDismiss), contentPadding = PaddingValues(6.dp)) {
                    Text(if (state.importing) "停止" else "收起", fontSize = 12.sp)
                }
            }
            state.importError?.let { Text(it, color = Danger, fontSize = 12.sp, lineHeight = 19.sp) }
        }
    }
}
