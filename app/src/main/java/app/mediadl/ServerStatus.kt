package app.mediadl

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * What the home screen shows when there is no server to talk to. Starting up and being offline are
 * deliberately different pictures: starting is calm and accent-coloured with a moving bar and no
 * buttons; offline is red, says what is wrong and what to do, and offers the way out.
 */
@Composable
fun ServerStatusCard(vm: AppViewModel, modifier: Modifier = Modifier) {
    if (vm.starting) StartingCard(modifier) else OfflineCard(vm, modifier)
}

@Composable
private fun StartingCard(modifier: Modifier) {
    val palette = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "starting")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "sweep",
    )
    CardBox(modifier.fillMaxWidth()) {
        Column {
            // Indeterminate bar along the top edge.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Accent.copy(alpha = 0.14f))
                    .drawBehind {
                        val bar = size.width * 0.35f
                        val x = -bar + (size.width + bar) * sweep
                        drawRoundRect(
                            Accent,
                            topLeft = Offset(x, 0f),
                            size = Size(bar, size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2),
                        )
                    },
            )
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    VIconView(VIcons.Server, 20.dp, Accent)
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    T("Starting the downloader", size = 15.2.sp, weight = FontWeight.SemiBold)
                    T(
                        "Getting things ready inside RibbT. This takes a few seconds, a little longer right after installing.",
                        modifier = Modifier.padding(top = 4.dp),
                        size = 13.sp,
                        color = palette.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineCard(vm: AppViewModel, modifier: Modifier) {
    val palette = LocalPalette.current
    val builtIn = vm.serverMode == "builtin"
    val title = if (builtIn) "Built-in server stopped" else "Can’t reach your server"
    CardBox(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.errBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        VIconView(VIcons.Server, 20.dp, palette.err)
                    }
                    // Status dot, bottom right.
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(palette.card)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(palette.err),
                    )
                }
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    T(title, size = 15.2.sp, weight = FontWeight.SemiBold)
                    T("Offline", size = 12.sp, color = palette.err, weight = FontWeight.Medium)
                }
            }

            if (builtIn) {
                T(
                    vm.builtInFailure?.let { "It couldn’t start: $it" }
                        ?: "The downloader inside RibbT isn’t answering. Retry, or restart the app if it keeps happening.",
                    modifier = Modifier.padding(top = 12.dp),
                    size = 13.sp,
                    color = palette.muted,
                )
            } else {
                T("Nothing is answering at", modifier = Modifier.padding(top = 12.dp), size = 13.sp, color = palette.muted)
                T(
                    vm.baseUrl,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.bg)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    size = 12.8.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                )
                T(
                    "Start the server on that device, then retry.",
                    modifier = Modifier.padding(top = 8.dp),
                    size = 13.sp,
                    color = palette.muted,
                )
            }

            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(text = "Retry", icon = VIcons.Refresh, onClick = { vm.refresh() })
                PillButton(
                    text = if (builtIn) "Server settings" else "Change address",
                    icon = VIcons.Settings,
                    onClick = {
                        vm.settingsPage = PAGE_SERVER
                        vm.tab = Tab.Settings
                    },
                    style = PillStyle.Tonal,
                )
            }
        }
    }
}
