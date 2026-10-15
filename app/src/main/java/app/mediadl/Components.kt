package app.mediadl

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

val WebEase = CubicBezierEasing(0.22f, 0.61f, 0.36f, 1f)

@Composable
fun T(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 16.sp,
    color: Color = LocalPalette.current.text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    lineHeight: TextUnit = TextUnit.Unspecified,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    fontFamily: FontFamily? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = weight,
        maxLines = maxLines,
        lineHeight = if (lineHeight == TextUnit.Unspecified) size * 1.4f else lineHeight,
        letterSpacing = letterSpacing,
        fontFamily = fontFamily,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
fun CardBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(LocalPalette.current.card),
    ) { content() }
}

@Composable
fun ScreenTitle(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        T(title, size = 20.8.sp, weight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
fun SectionTitle(title: String) {
    T(
        title,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        size = 12.48.sp,
        color = LocalPalette.current.muted,
        weight = FontWeight.SemiBold,
    )
}

@Composable
fun Separator() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalPalette.current.sep),
    )
}

/** Colour of a [StatusPill]: green for good news, the accent for something to act on. */
enum class PillTone { Good, Accent }

/** A small rounded label that sits next to text, e.g. "Up to date". */
@Composable
fun StatusPill(text: String, tone: PillTone, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val color = when (tone) {
        PillTone.Good -> if (palette.dark) Color(0xFF5BD68A) else Color(0xFF1E8E4E)
        PillTone.Accent -> Accent
    }
    Box(
        modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        T(text, size = 11.sp, color = color, weight = FontWeight.SemiBold, maxLines = 1, lineHeight = 14.sp)
    }
}

enum class PillStyle { Primary, Tonal, Done }

@Composable
fun PillButton(
    text: String,
    icon: VIcon?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.Primary,
    busy: Boolean = false,
    height: Dp = 36.dp,
    spinner: Boolean = false,
    /** 0..1 progress of an attention ring around the button; null for none. */
    pulse: Float? = null,
    /** Overrides the text and icon color (for a red "Delete", say). */
    contentColor: Color? = null,
) {
    val palette = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    val background = when (style) {
        PillStyle.Primary -> if (pressed) AccentDark else Accent
        PillStyle.Tonal -> if (pressed) palette.line else palette.sep
        PillStyle.Done -> palette.card
    }
    val foreground = contentColor ?: when (style) {
        PillStyle.Primary -> Color.White
        PillStyle.Tonal -> if (pulse != null) Accent else palette.text
        PillStyle.Done -> Accent
    }
    Row(
        modifier = modifier
            .height(height)
            .alpha(if (busy) 0.6f else 1f)
            .drawBehind {
                if (pulse != null) {
                    val progress = (pulse / 0.7f).coerceAtMost(1f)
                    val grow = 8.dp.toPx() * progress
                    drawRoundRect(
                        Accent.copy(alpha = 0.45f * (1f - progress)),
                        topLeft = androidx.compose.ui.geometry.Offset(-grow, -grow),
                        size = androidx.compose.ui.geometry.Size(size.width + grow * 2, size.height + grow * 2),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx() + grow),
                    )
                }
            }
            .clip(shape)
            .background(background)
            .then(if (style == PillStyle.Done) Modifier.border(1.dp, Accent, shape) else Modifier)
            .clickable(interactionSource = source, indication = null, enabled = !busy, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (spinner) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = foreground,
                strokeWidth = 2.dp,
                trackColor = foreground.copy(alpha = 0.35f),
            )
            Spacer(Modifier.width(6.dp))
        } else if (icon != null) {
            VIconView(icon, 16.dp, foreground)
            Spacer(Modifier.width(6.dp))
        }
        T(text, size = if (height > 36.dp) 14.sp else 12.8.sp, color = foreground, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

fun initialOf(name: String): String {
    val text = name.trim()
    if (text.isEmpty()) return "?"
    val latin = text.firstOrNull { it in 'A'..'Z' || it in 'a'..'z' }
    return (latin ?: text[0]).toString().uppercase()
}

@Composable
fun CircleLetter(name: String, size: Dp = 34.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Accent),
        contentAlignment = Alignment.Center,
    ) {
        T(initialOf(name), size = 13.12.sp, color = Color.White, weight = FontWeight.Bold, lineHeight = 13.12.sp)
    }
}

@Composable
fun Modifier.appear(delayMs: Int = 0): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs.toLong())
        shown = true
    }
    val progress by animateFloatAsState(if (shown) 1f else 0f, tween(400, easing = WebEase), label = "appear")
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * 10.dp.toPx()
    }
}
