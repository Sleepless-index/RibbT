package app.mediadl

import androidx.compose.foundation.layout.Spacer

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.material3.rememberModalBottomSheetState

import androidx.compose.material3.ModalBottomSheet

import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.foundation.layout.offset

import androidx.compose.ui.layout.ContentScale

import coil.compose.AsyncImage


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

/** The download list, as a bottom sheet. Opened from the Queue slot in the bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val all = vm.queue.toList()
    val items = all.filter { it.parent == null }.asReversed().flatMap { head -> listOf(head) + all.filter { it.parent == head.id } }
    val finished = items.count { it.phase != ToastPhase.Active && it.parent == null }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.card,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .size(width = 40.dp, height = 5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(palette.line),
            )
        },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    T("Downloads", size = 24.sp, weight = FontWeight.Bold, fontFamily = DisplayFont, letterSpacing = (-0.5).sp)
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
                        QueueRow(vm, item)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun QueueRow(vm: AppViewModel, item: DlToast) {
    val palette = LocalPalette.current
    val child = item.parent != null
    val bad = item.phase == ToastPhase.Bad
    val statusColor = when (item.phase) {
        ToastPhase.Bad -> palette.err
        ToastPhase.Ok -> Accent
        else -> palette.muted
    }
    val thumbSize = if (child) 40.dp else 52.dp
    Box(Modifier.padding(start = if (child) 44.dp else 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(if (bad) palette.errBg else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The file's preview, with the service's logo on the corner.
                Box(Modifier.size(thumbSize)) {
                    Box(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(13.dp)).background(palette.soft),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (item.thumb != null) {
                            AsyncImage(model = item.thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            VIconView(
                                when (item.phase) {
                                    ToastPhase.Ok -> VIcons.Check
                                    ToastPhase.Bad -> VIcons.Error
                                    ToastPhase.Cancelled -> VIcons.CloseSmall
                                    else -> VIcons.Download
                                },
                                20.dp,
                                statusColor,
                            )
                        }
                    }
                    if (item.platform != null) {
                        PlatformBadge(item.platform, Modifier.align(Alignment.BottomEnd).offset(x = 5.dp, y = 5.dp), size = 20.dp)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    T(item.name, size = 13.6.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    T(
                        item.status.ifBlank { " " }, size = 12.sp, color = statusColor,
                        maxLines = if (bad) 3 else 1, modifier = Modifier.padding(top = 1.dp),
                    )
                }
                when {
                    item.phase == ToastPhase.Active && item.canCancel ->
                        QueueIconAction(VIcons.CloseSmall, "Cancel", palette.muted, Color.Transparent) { vm.cancelDownload(item.id) }
                    bad && item.canRetry ->
                        QueueIconAction(VIcons.Refresh, "Retry", AccentOn, Accent) { vm.retryFromQueue(item.id) }
                }
                val debug = item.debug
                if (bad && debug != null) {
                    val context = LocalContext.current
                    var copied by remember(item.id) { mutableStateOf(false) }
                    Box(Modifier.padding(start = 6.dp)) {
                        QueueIconAction(if (copied) VIcons.Check else VIcons.Copy, "Copy debug info", palette.text, palette.card) {
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
                        .padding(top = 10.dp, start = thumbSize + 12.dp, end = 44.dp)
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
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
