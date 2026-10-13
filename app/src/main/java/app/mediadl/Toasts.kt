package app.mediadl

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ToastHost(toasts: List<DlToast>, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        toasts.forEach { toast ->
            key(toast.id) { ToastCard(toast) { onDismiss(toast.id) } }
        }
    }
}

@Composable
private fun ToastCard(toast: DlToast, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(280, easing = WebEase)) + slideInVertically(tween(280, easing = WebEase)) { -it / 2 },
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .shadow(14.dp, shape)
                .clip(shape)
                .pointerInput(Unit) {
                    // Swipe up to dismiss.
                    var travelled = 0f
                    detectVerticalDragGestures(
                        onDragStart = { travelled = 0f },
                        onVerticalDrag = { change, amount ->
                            travelled += amount
                            if (travelled < -48f) {
                                change.consume()
                                onDismiss()
                            }
                        },
                    )
                }
                .background(palette.card)
                .border(1.dp, palette.line, shape),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconBg = when (toast.phase) {
                    ToastPhase.Active -> Accent.copy(alpha = 0.16f)
                    ToastPhase.Ok -> Accent
                    ToastPhase.Bad -> palette.errBg
                }
                val iconColor = when (toast.phase) {
                    ToastPhase.Active -> Accent
                    ToastPhase.Ok -> Color.White
                    ToastPhase.Bad -> palette.err
                }
                val icon = when (toast.phase) {
                    ToastPhase.Active -> VIcons.Download
                    ToastPhase.Ok -> VIcons.Check
                    ToastPhase.Bad -> VIcons.Error
                }
                Box(
                    Modifier
                        .width(28.dp)
                        .height(28.dp)
                        .clip(CircleShape)
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    VIconView(icon, 16.dp, iconColor)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp),
                ) {
                    T(toast.name, size = 13.44.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    T(
                        toast.status, size = 11.84.sp, color = palette.muted,
                        maxLines = if (toast.phase == ToastPhase.Bad) 2 else 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDismiss)
                        .padding(4.dp),
                ) {
                    VIconView(VIcons.CloseSmall, 14.dp, palette.muted)
                }
            }
            if (toast.phase == ToastPhase.Active) {
                ProgressBar(toast.progress, Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

@Composable
private fun ProgressBar(progress: Float?, modifier: Modifier) {
    val palette = LocalPalette.current
    if (progress != null) {
        val animated by animateFloatAsState(progress.coerceIn(0f, 1f), tween(150, easing = LinearEasing), label = "toast-progress")
        Box(
            modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(palette.line),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .background(Accent),
            )
        }
    } else {
        val transition = rememberInfiniteTransition(label = "toast-indeterminate")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1250, easing = CubicBezierEasing(0.45f, 0.05f, 0.55f, 0.95f)),
                RepeatMode.Restart,
            ),
            label = "toast-phase",
        )
        BoxWithConstraints(
            modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(palette.line)
                .clipToBounds(),
        ) {
            val barWidth = maxWidth * 0.38f
            Box(
                Modifier
                    .width(barWidth)
                    .fillMaxHeight()
                    .offset(x = barWidth * (-1f + 3.8f * phase))
                    .background(Accent),
            )
        }
    }
}
