package cn.yibu.chess.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class ChessIcon { KNIGHT, REVIEW, LIBRARY, TRAIN, INFO, SETTINGS, FLIP, PLUS, FIRST, PREVIOUS, NEXT, LAST, TRASH, SHARE, FLAG }

/** Original, consistent 24-unit line icons; decorative icons have no duplicate spoken label. */
@Composable
internal fun LineIcon(icon: ChessIcon, modifier: Modifier = Modifier.size(22.dp), color: Color = LocalContentColor.current) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1, y1), Offset(x2, y2), 1.7f, StrokeCap.Round)
            fun path(vararg points: Pair<Float, Float>, close: Boolean = false) {
                drawPath(Path().apply {
                    points.forEachIndexed { index, point -> if (index == 0) moveTo(point.first, point.second) else lineTo(point.first, point.second) }
                    if (close) close()
                }, color, style = stroke)
            }
            when (icon) {
                ChessIcon.KNIGHT -> {
                    path(5f to 21f, 19f to 21f, 18f to 17f, 16f to 14f, 17f to 10f, 16f to 6f, 12f to 3f, 11f to 6f, 7f to 8f, 4f to 12f, 7f to 14f, 11f to 11f, 9f to 16f, 6f to 18f, close = true)
                    drawCircle(color, .8f, Offset(12.6f, 8f))
                    line(6f, 18f, 18f, 18f)
                }
                ChessIcon.REVIEW -> {
                    line(4f, 4f, 4f, 20f); line(4f, 20f, 21f, 20f)
                    path(7f to 15f, 11f to 11f, 15f to 13f, 21f to 6f)
                }
                ChessIcon.LIBRARY -> {
                    drawRoundRect(color, Offset(5f, 3f), Size(15f, 18f), androidx.compose.ui.geometry.CornerRadius(2f), style = stroke)
                    line(9f, 3f, 9f, 21f); line(12f, 8f, 17f, 8f); line(12f, 12f, 17f, 12f)
                }
                ChessIcon.TRAIN -> {
                    path(12f to 6f, 8f to 4f, 3f to 4f, 3f to 19f, 8f to 19f, 12f to 21f, 16f to 19f, 21f to 19f, 21f to 4f, 16f to 4f, 12f to 6f)
                    line(12f, 6f, 12f, 21f); line(6f, 9f, 9f, 10f); line(15f, 10f, 18f, 9f)
                }
                ChessIcon.INFO -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = stroke)
                    drawCircle(color, .9f, Offset(12f, 8f)); line(12f, 11f, 12f, 17f)
                }
                ChessIcon.SETTINGS -> {
                    listOf(6f to 8f, 12f to 16f, 18f to 10f).forEach { (x, y) ->
                        line(x, 4f, x, y - 2f); line(x, y + 2f, x, 20f)
                        drawCircle(color, 2f, Offset(x, y), style = stroke)
                    }
                }
                ChessIcon.FLIP -> {
                    path(4f to 9f, 4f to 5f, 19f to 5f, 16f to 2f)
                    path(20f to 15f, 20f to 19f, 5f to 19f, 8f to 22f)
                }
                ChessIcon.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
                ChessIcon.FIRST, ChessIcon.PREVIOUS -> {
                    path(15f to 6f, 9f to 12f, 15f to 18f)
                    if (icon == ChessIcon.FIRST) line(5f, 6f, 5f, 18f)
                }
                ChessIcon.LAST, ChessIcon.NEXT -> {
                    path(9f to 6f, 15f to 12f, 9f to 18f)
                    if (icon == ChessIcon.LAST) line(19f, 6f, 19f, 18f)
                }
                ChessIcon.TRASH -> {
                    line(4f, 6f, 20f, 6f); path(9f to 6f, 9f to 3f, 15f to 3f, 15f to 6f)
                    path(6f to 6f, 7f to 21f, 17f to 21f, 18f to 6f)
                    line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
                }
                ChessIcon.SHARE -> {
                    path(12f to 15f, 12f to 3f, 8f to 7f); path(12f to 3f, 16f to 7f)
                    path(5f to 11f, 5f to 21f, 19f to 21f, 19f to 11f)
                }
                ChessIcon.FLAG -> { line(5f, 3f, 5f, 21f); path(5f to 4f, 19f to 4f, 16f to 9f, 19f to 14f, 5f to 14f) }
            }
        }
    }
}

@Composable
internal fun StatusPill(text: String, color: Color = Accent, dot: Boolean = false) {
    Row(Modifier.background(color.copy(alpha = .08f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        if (dot) Box(Modifier.size(5.dp).background(color, CircleShape))
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ChessIcon? = null) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    // Occasional actions only. Compose respects the system animator duration scale, including zero.
    val scale by animateFloatAsState(if (pressed && enabled) .97f else 1f,
        tween(if (pressed) 120 else 80, easing = CubicBezierEasing(.23f, 1f, .32f, 1f)), label = "action press")
    Button(feedbackClick(onClick), modifier.graphicsLayer { scaleX = scale; scaleY = scale }, enabled = enabled,
        shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 13.dp), interactionSource = interactions) {
        if (icon != null) { LineIcon(icon, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)) }
        Text(text)
    }
}

@Composable
internal fun IconAction(icon: ChessIcon, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(feedbackClick(onClick), enabled = enabled, modifier = Modifier.size(48.dp).semantics { contentDescription = label }) {
        LineIcon(icon, color = if (enabled) Muted else Muted.copy(alpha = .4f))
    }
}
