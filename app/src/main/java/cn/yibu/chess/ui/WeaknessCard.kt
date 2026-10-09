package cn.yibu.chess.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.yibu.chess.core.*

@Composable
internal fun WeaknessCard(report: WeaknessReport, onExample: (Long, Int) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf<WeaknessType?>(null) }
    Surface(color = Soft, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().testTag("weakness-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("个人弱点 · 最近 ${report.games} 盘", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text("已确认分析你的 ${report.confirmedMoves} / ${report.playerMoves} 着 · ${report.analyzedGames} 盘有分析",
                color = Muted, fontSize = 12.sp, modifier = Modifier.testTag("weakness-coverage"))
            if (report.groups.isEmpty()) {
                Text(if (report.confirmedMoves == 0) "完成全局复盘后，会从已保存的分析中整理你的战术问题。"
                    else "目前没有足够证据归类战术问题，不能据此判断没有失误。", fontSize = 13.sp, lineHeight = 20.sp)
            } else {
                report.groups.forEach { group ->
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(group.type.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text("${group.count} 次 · ${group.games} 盘${if (group.games < 2) " · 样本有限" else ""}", color = Muted, fontSize = 11.sp)
                        }
                        TextButton(onClick = feedbackClick { expanded = if (expanded == group.type) null else group.type },
                            contentPadding = PaddingValues(horizontal = 6.dp)) { Text(if (expanded == group.type) "收起" else "看例子", fontSize = 12.sp) }
                    }
                    if (expanded == group.type) group.examples.take(3).forEach { example ->
                        OutlinedButton(onClick = feedbackClick { onExample(example.gameId, example.ply) }, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().testTag("weakness-example-${example.gameId}-${example.ply}"),
                            contentPadding = PaddingValues(10.dp)) {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("第 ${example.ply} 步 · ${example.san} · 打开对照复盘", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                Text(example.evidence, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                    }
                }
                HorizontalDivider(color = Line)
                Text("下一阶段的小目标", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(report.groups.first().type.goal, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.testTag("weakness-goal"))
            }
            Text("仅统计保留棋谱中最近 20 盘已结束对局。未分析、待复评和只有分差的棋步不贴标签；每着最多归入一类。删除棋谱后同步移除。",
                color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
        }
    }
}
