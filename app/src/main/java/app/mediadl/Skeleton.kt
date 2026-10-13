package app.mediadl

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shimmering placeholder fill. With animations turned off in system settings it stays a still tint. */
@Composable
fun Modifier.shimmer(): Modifier {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val still = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val base = palette.text.copy(alpha = 0.09f).compositeOver(palette.bg)
    val highlight = palette.text.copy(alpha = 0.04f).compositeOver(palette.bg)
    val phase by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "skeleton-phase",
    )
    return if (still) {
        this.background(base)
    } else {
        this.drawBehind {
            val width = size.width
            val x = -width + 2f * width * phase
            drawRect(
                Brush.linearGradient(
                    colors = listOf(base, highlight, base),
                    start = Offset(x, 0f),
                    end = Offset(x + width, 0f),
                ),
            )
        }
    }
}

@Composable
private fun Bar(modifier: Modifier, height: Dp) {
    Box(modifier.height(height).clip(RoundedCornerShape(8.dp)).shimmer())
}

/** What the result area looks like while a link is being parsed: byline, title, a card and its buttons. */
@Composable
fun ParseSkeleton(modifier: Modifier = Modifier) {
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val limit = minOf(screenHeight * 0.65f, 540f).dp
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(
            Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 6.dp),
        ) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).shimmer())
                Column(Modifier.padding(start = 10.dp)) {
                    Bar(Modifier.width(120.dp), 11.dp)
                    Spacer(Modifier.height(7.dp))
                    Bar(Modifier.width(60.dp), 9.dp)
                }
            }
            Bar(Modifier.fillMaxWidth(), 15.dp)
            Spacer(Modifier.height(9.dp))
            Bar(Modifier.fillMaxWidth(0.62f), 15.dp)
        }
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val cardWidth = minOf(maxWidth, limit * 0.75f)
            Column(Modifier.width(cardWidth)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.75f)
                        .clip(RoundedCornerShape(14.dp))
                        .shimmer(),
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).shimmer())
                    Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).shimmer())
                }
            }
        }
    }
}
