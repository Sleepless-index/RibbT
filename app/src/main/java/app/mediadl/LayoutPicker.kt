package app.mediadl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Picks how results are laid out by showing it: each choice is a small drawing of the real layout,
 * so nobody has to guess what "Masonry" means.
 */
@Composable
fun LayoutPicker(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val hints = mapOf(
        MediaLayout.Carousel to "Swipe through photos",
        MediaLayout.Stacked to "One after another",
        MediaLayout.Masonry to "Tiles side by side",
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MediaLayout.entries.forEach { layout ->
            LayoutTile(
                layout = layout,
                hint = hints[layout].orEmpty(),
                isSelected = layout.key == selected,
                onClick = { if (layout.key != selected) onSelect(layout.key) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LayoutTile(
    layout: MediaLayout,
    hint: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    val source = remember { MutableInteractionSource() }
    Column(
        modifier
            .clip(shape)
            .background(if (isSelected) Accent.copy(alpha = 0.08f) else palette.bg)
            .border(1.5.dp, if (isSelected) Accent else Color.Transparent, shape)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val block = if (isSelected) Accent.copy(alpha = 0.55f) else palette.line
            val ink = if (isSelected) Accent else palette.muted.copy(alpha = 0.6f)
            when (layout) {
                MediaLayout.Carousel -> drawCarousel(block, ink)
                MediaLayout.Stacked -> drawStacked(block)
                MediaLayout.Masonry -> drawMasonry(block)
            }
        }
        T(
            layout.label,
            modifier = Modifier.padding(top = 8.dp),
            size = 13.sp,
            weight = FontWeight.SemiBold,
            color = if (isSelected) Accent else palette.text,
            maxLines = 1,
        )
        T(
            hint,
            modifier = Modifier.padding(top = 2.dp),
            size = 11.sp,
            color = palette.muted,
            lineHeight = 14.sp,
            maxLines = 2,
        )
    }
}

private fun DrawScope.rounded(color: Color, x: Float, y: Float, w: Float, h: Float, r: Float = 5.dp.toPx()) {
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(r))
}

/** One big card in the middle with the neighbours peeking in, and page dots under it. */
private fun DrawScope.drawCarousel(block: Color, ink: Color) {
    val dots = 8.dp.toPx()
    val h = size.height - dots - 4.dp.toPx()
    val side = size.width * 0.13f
    val gap = 4.dp.toPx()
    rounded(block.copy(alpha = block.alpha * 0.6f), -side + 2.dp.toPx(), 4.dp.toPx(), side + 2.dp.toPx(), h - 8.dp.toPx())
    rounded(block.copy(alpha = block.alpha * 0.6f), size.width - 4.dp.toPx(), 4.dp.toPx(), side + 2.dp.toPx(), h - 8.dp.toPx())
    rounded(block, side + gap, 0f, size.width - 2 * (side + gap), h)
    val r = 2.dp.toPx()
    val cx = size.width / 2
    val y = size.height - r
    for (i in -1..1) {
        drawCircle(if (i == 0) ink else ink.copy(alpha = 0.35f), r, Offset(cx + i * 3 * r, y))
    }
}

/** Full-width cards one under the other. */
private fun DrawScope.drawStacked(block: Color) {
    val gap = 5.dp.toPx()
    val h = (size.height - gap * 2) / 3
    for (i in 0 until 3) rounded(block, 0f, i * (h + gap), size.width, h)
}

/** Two columns of tiles with different heights. */
private fun DrawScope.drawMasonry(block: Color) {
    val gap = 5.dp.toPx()
    val w = (size.width - gap) / 2
    val left = floatArrayOf(0.42f, 0.5f)
    val right = floatArrayOf(0.6f, 0.32f)
    var y = 0f
    for (f in left) {
        val h = (size.height - gap) * f
        rounded(block, 0f, y, w, h); y += h + gap
    }
    y = 0f
    for (f in right) {
        val h = (size.height - gap) * f
        rounded(block, w + gap, y, w, h); y += h + gap
    }
}
