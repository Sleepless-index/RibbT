package app.mediadl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

class VIcon(val paths: List<String>, val stroke: Float, val viewW: Float = 24f, val viewH: Float = 24f)

private fun circle(cx: Float, cy: Float, r: Float): String =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${r * 2} 0a$r $r 0 1 0 ${-r * 2} 0z"

private fun square(x: Float, y: Float, s: Float, r: Float): String {
    val side = s - 2 * r
    return "M${x + r} ${y}h${side}a$r $r 0 0 1 $r ${r}v${side}a$r $r 0 0 1 ${-r} ${r}h${-side}a$r $r 0 0 1 ${-r} ${-r}v${-side}a$r $r 0 0 1 $r ${-r}z"
}

object VIcons {
    val Home = VIcon(
        listOf(
            "M3 10.5 12 3l9 7.5",
            "M5 9.5V20a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V9.5",
        ),
        1.8f,
    )

    val Settings = VIcon(
        listOf(
            circle(12f, 12f, 3f),
            "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z",
        ),
        1.8f,
    )

    val Services = VIcon(
        listOf(
            square(4f, 4f, 6.5f, 1.5f),
            square(13.5f, 4f, 6.5f, 1.5f),
            square(4f, 13.5f, 6.5f, 1.5f),
            square(13.5f, 13.5f, 6.5f, 1.5f),
        ),
        1.8f,
    )

    val History = VIcon(listOf(circle(12f, 12f, 9f), "M12 7v5l3 2"), 1.8f)

    val Link = VIcon(
        listOf(
            "M10 13a5 5 0 0 0 7.07 0l3-3a5 5 0 0 0-7.07-7.07l-1.5 1.5",
            "M14 11a5 5 0 0 0-7.07 0l-3 3a5 5 0 0 0 7.07 7.07l1.5-1.5",
        ),
        2f,
    )

    val Paste = VIcon(
        listOf(
            "M9 3h6a1 1 0 0 1 1 1v2a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1z",
            "M16 5h2a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2h2",
        ),
        2f,
    )

    val Clear = VIcon(listOf("M18 6 6 18", "m6 6 12 12"), 2f)
    val CloseSmall = VIcon(listOf("M6 6l12 12M18 6 6 18"), 2.4f)
    val Download = VIcon(listOf("M12 4v11", "m7 11 5 5 5-5", "M5 20h14"), 2.2f)
    val Check = VIcon(listOf("m5 12 5 5 9-11"), 2.6f)
    val Audio = VIcon(listOf("M9 18V5l12-2v13", circle(6f, 18f, 3f), circle(18f, 16f, 3f)), 2.2f)
    val Error = VIcon(listOf(circle(12f, 12f, 9f), "M12 8v5", "M12 16.5h.01"), 2f)
    val ChevronDown = VIcon(listOf("M1 1l4 4 4-4"), 1.6f, 10f, 6f)
    val ChevronRight = VIcon(listOf("M1 1l4 4-4 4"), 1.6f, 6f, 10f)
    val Back = VIcon(listOf("m15 18-6-6 6-6"), 2.2f)
    val Server = VIcon(
        listOf(
            "M5 3h14a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
            "M5 13h14a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z",
            "M7 7h.01",
            "M7 17h.01",
        ),
        1.8f,
    )
    val Play = VIcon(
        listOf("M8 5.14v13.72a1 1 0 0 0 1.54.84l10.29-6.86a1 1 0 0 0 0-1.68L9.54 4.3A1 1 0 0 0 8 5.14z"),
        0f,
    )
    val Trash = VIcon(
        listOf("M3 6h18", "M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2", "M6 6l1 14a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-14"),
        1.8f,
    )
    val Cloud = VIcon(
        listOf("M7 18a4 4 0 0 1-.5-7.97A5.5 5.5 0 0 1 17 8.5a4.75 4.75 0 0 1 0 9.5z", "M3 3l18 18"),
        1.8f,
    )
    val Refresh = VIcon(listOf("M20 12a8 8 0 1 1-2.34-5.66", "M20 4v5h-5"), 2.2f)
    val Video = VIcon(
        listOf("M3 7a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", "m15 10 6-3v10l-6-3"),
        1.8f,
    )
    val Photo = VIcon(
        listOf(square(3f, 3f, 18f, 2.5f), circle(9f, 9f, 1.6f), "m21 16-5-5L5 21"),
        1.8f,
    )
}

@Composable
fun VIconView(icon: VIcon, size: Dp, tint: Color, modifier: Modifier = Modifier) {
    val parsed = remember(icon) { icon.paths.map { PathParser().parsePathString(it).toPath() } }
    val height = size * (icon.viewH / icon.viewW)
    Canvas(modifier.size(size, height)) {
        val factor = this.size.width / icon.viewW
        scale(factor, Offset.Zero) {
            parsed.forEach { path ->
                if (icon.stroke > 0f) {
                    drawPath(path, tint, style = Stroke(width = icon.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                } else {
                    drawPath(path, tint, style = Fill)
                }
            }
        }
    }
}
