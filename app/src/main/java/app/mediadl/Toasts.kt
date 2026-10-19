package app.mediadl

import androidx.compose.foundation.layout.size

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ToastHost(
    toasts: List<DlToast>,
    onDismiss: (Long) -> Unit,
    modifier: Modifier = Modifier,
    onCancel: (Long) -> Unit = {},
    onRetry: (Long) -> Unit = {},
) {
    val kept = remember { mutableStateListOf<DlToast>() }
    val leaving = remember { mutableStateListOf<Long>() }
    val current = toasts.toList()
    LaunchedEffect(current) {
        val liveIds = current.map { it.id }.toSet()
        current.forEach { toast ->
            val at = kept.indexOfFirst { it.id == toast.id }
            if (at >= 0) kept[at] = toast else kept.add(toast)
        }
        kept.forEach { if (it.id !in liveIds && it.id !in leaving) leaving.add(it.id) }
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        kept.toList().forEach { toast ->
            key(toast.id) {
                ToastCard(
                    toast,
                    leaving = toast.id in leaving,
                    onCancel = { onCancel(toast.id) },
                    onRetry = { onRetry(toast.id) },
                    onGone = {
                        kept.removeAll { it.id == toast.id }
                        leaving.remove(toast.id)
                    },
                ) { onDismiss(toast.id) }
            }
        }
    }
}

@Composable
private fun ToastCard(
    toast: DlToast,
    leaving: Boolean,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onGone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalPalette.current
    val visibility = remember { MutableTransitionState(false) }
    LaunchedEffect(leaving) { visibility.targetState = !leaving }
    LaunchedEffect(leaving, visibility.currentState, visibility.isIdle) {
        if (leaving && visibility.isIdle && !visibility.currentState) onGone()
    }
    AnimatedVisibility(
        visibleState = visibility,
        enter = fadeIn(tween(280, easing = WebEase)) + slideInVertically(tween(280, easing = WebEase)) { -it / 2 },
        exit = fadeOut(tween(240, easing = WebEase)) +
            slideOutVertically(tween(240, easing = WebEase)) { -it / 2 } +
            shrinkVertically(tween(240, easing = WebEase), shrinkTowards = Alignment.Top),
    ) {
        val shape = RoundedCornerShape(20.dp)
        var dragX by remember { mutableFloatStateOf(0f) }
        Box(
            Modifier
                .padding(bottom = 8.dp)
                .offset { IntOffset(dragX.roundToInt(), 0) }
                .graphicsLayer { alpha = 1f - (abs(dragX) / 500f).coerceIn(0f, 0.7f) }
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .shadow(14.dp, shape)
                .clip(shape)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(dragX) > 120f) onDismiss() else dragX = 0f
                        },
                        onDragCancel = { dragX = 0f },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            dragX += amount
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
                    ToastPhase.Cancelled -> palette.sep
                }
                val iconColor = when (toast.phase) {
                    ToastPhase.Active -> Accent
                    ToastPhase.Ok -> AccentOn
                    ToastPhase.Bad -> palette.err
                    ToastPhase.Cancelled -> palette.muted
                }
                val icon = when (toast.phase) {
                    ToastPhase.Active -> VIcons.Download
                    ToastPhase.Ok -> VIcons.Check
                    ToastPhase.Bad -> VIcons.Error
                    ToastPhase.Cancelled -> VIcons.CloseSmall
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
                // While a download runs, Cancel is the only button: a close X next to it is what made them easy
                // to mix up. The X comes back once the download is over.
                val cancelling = toast.canCancel && toast.phase == ToastPhase.Active
                if (cancelling) {
                    ToastAction("Cancel", palette.text, palette.sep, palette.line, onCancel)
                }
                if (toast.canRetry && toast.phase == ToastPhase.Bad) {
                    ToastIconAction(VIcons.Refresh, "Retry", Accent, Accent.copy(alpha = 0.14f), Color.Transparent, onRetry)
                }
                val debug = toast.debug
                if (debug != null && toast.phase == ToastPhase.Bad) {
                    val context = LocalContext.current
                    var copied by remember(toast.id) { mutableStateOf(false) }
                    ToastIconAction(
                        if (copied) VIcons.Check else VIcons.Copy,
                        "Copy debug info",
                        palette.text, palette.sep, palette.line,
                    ) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("RibbT debug info", debug))
                        copied = true
                    }
                }
                if (!cancelling) {
                    Box(
                        Modifier
                            .padding(start = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onDismiss)
                            .padding(4.dp),
                    ) {
                        VIconView(VIcons.CloseSmall, 14.dp, palette.muted)
                    }
                }
            }
            if (toast.phase == ToastPhase.Active) {
                ProgressBar(toast.progress, Modifier.align(Alignment.BottomStart))
            }
        }
    }
}

/** A real button (filled, with an edge) so it can't be taken for plain text or for the close X. */
@Composable
private fun ToastAction(label: String, textColor: Color, background: Color, edge: Color, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(background)
            .border(1.dp, edge, shape)
            .clickable(role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        T(label, size = 12.8.sp, color = textColor, weight = FontWeight.SemiBold, maxLines = 1)
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

/** Icon-only version of [ToastAction]; the label is only for screen readers. */
@Composable
private fun ToastIconAction(icon: VIcon, label: String, tint: Color, background: Color, edge: Color, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(11.dp)
    Box(
        Modifier
            .padding(start = 6.dp)
            .size(34.dp)
            .clip(shape)
            .background(background)
            .border(1.dp, edge, shape)
            .clickable(onClickLabel = label, role = Role.Button) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { VIconView(icon, 17.dp, tint) }
}
