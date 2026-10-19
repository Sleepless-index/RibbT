package app.mediadl


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** The download list. Opened from the Queue slot in the bottom bar. */
@Composable
fun QueueDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val all = vm.queue.toList()
    val items = all.filter { it.parent == null }.asReversed().flatMap { head -> listOf(head) + all.filter { it.parent == head.id } }
    val finished = items.count { it.phase != ToastPhase.Active && it.parent == null }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(palette.card),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    T("Download queue", size = 18.sp, weight = FontWeight.Bold)
                    val running = items.count { it.phase == ToastPhase.Active && it.parent == null }
                    T(
                        when {
                            items.isEmpty() -> "Nothing downloading"
                            running > 0 -> "$running running" + if (finished > 0) " · $finished finished" else ""
                            else -> "$finished finished"
                        },
                        size = 12.sp, color = palette.muted, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (finished > 0) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { vm.clearFinishedQueue() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) { T("Clear finished", color = Accent, weight = FontWeight.SemiBold, size = 13.sp) }
                }
            }
            if (items.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    VIconView(VIcons.Queue, 34.dp, palette.muted)
                    T("Downloads you start show up here.", size = 13.sp, color = palette.muted, modifier = Modifier.padding(top = 10.dp))
                }
            } else {
                Column(
                    Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                ) {
                    items.forEachIndexed { index, item ->
                        if (index > 0) Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(1.dp).background(palette.sep))
                        QueueRow(vm, item)
                    }
                }
            }
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)) {
                PillButton(text = "Done", icon = null, onClick = onDismiss, modifier = Modifier.fillMaxWidth(), height = 42.dp)
            }
        }
    }
}

@Composable
private fun QueueRow(vm: AppViewModel, item: DlToast) {
    val palette = LocalPalette.current
    val phaseIcon = when (item.phase) {
        ToastPhase.Active -> VIcons.Download
        ToastPhase.Ok -> VIcons.Check
        ToastPhase.Bad -> VIcons.Error
        ToastPhase.Cancelled -> VIcons.CloseSmall
    }
    val iconBg = when (item.phase) {
        ToastPhase.Active -> Accent.copy(alpha = 0.16f)
        ToastPhase.Ok -> Accent
        ToastPhase.Bad -> palette.errBg
        ToastPhase.Cancelled -> palette.sep
    }
    val iconColor = when (item.phase) {
        ToastPhase.Active -> Accent
        ToastPhase.Ok -> AccentOn
        ToastPhase.Bad -> palette.err
        ToastPhase.Cancelled -> palette.muted
    }
    val child = item.parent != null
    Column(Modifier.fillMaxWidth().padding(start = if (child) 44.dp else 20.dp, end = 20.dp, top = if (child) 8.dp else 12.dp, bottom = if (child) 8.dp else 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (child) 24.dp else 30.dp).clip(CircleShape).background(iconBg), contentAlignment = Alignment.Center) {
                VIconView(phaseIcon, 16.dp, iconColor)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                T(item.name, size = 13.6.sp, weight = FontWeight.SemiBold, maxLines = 1)
                T(
                    item.status.ifBlank { " " }, size = 12.sp, color = palette.muted,
                    maxLines = if (item.phase == ToastPhase.Bad) 3 else 1, modifier = Modifier.padding(top = 1.dp),
                )
            }
            when {
                item.phase == ToastPhase.Active && item.canCancel ->
                    QueueAction("Cancel", palette.text, palette.sep) { vm.cancelDownload(item.id) }
                item.phase == ToastPhase.Bad && item.canRetry ->
                    QueueIconAction(VIcons.Refresh, "Retry", AccentOn, Accent) { vm.retryFromQueue(item.id) }
            }
            val debug = item.debug
            if (item.phase == ToastPhase.Bad && debug != null) {
                val context = LocalContext.current
                var copied by remember(item.id) { mutableStateOf(false) }
                Box(Modifier.padding(start = 6.dp)) {
                    QueueIconAction(if (copied) VIcons.Check else VIcons.Copy, "Copy debug info", palette.text, palette.sep) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("RibbT debug info", debug))
                        copied = true
                    }
                }
            }
        }
        if (item.phase == ToastPhase.Active) {
            Box(
                Modifier
                    .padding(top = 10.dp)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(palette.line),
            ) {
                val progress = item.progress
                if (progress != null) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Accent))
                } else {
                    Box(Modifier.fillMaxWidth(0.3f).fillMaxHeight().background(Accent.copy(alpha = 0.55f)))
                }
            }
        }
    }
}

@Composable
private fun QueueAction(label: String, textColor: androidx.compose.ui.graphics.Color, background: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { T(label, size = 12.5.sp, color = textColor, weight = FontWeight.SemiBold, maxLines = 1) }
}

/** A small icon-only button (Retry, Copy debug info). The label is only for screen readers. */
@Composable
private fun QueueIconAction(
    icon: VIcon,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    background: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(background)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { VIconView(icon, 17.dp, tint) }
}
