package app.mediadl

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val palette = LocalPalette.current
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }

    LaunchedEffect(Unit) { vm.loadHistory() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
        ) {
            ScreenTitle("History") {
                if (vm.history.isNotEmpty()) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { confirmClear = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        T("Clear", color = Accent, weight = FontWeight.SemiBold, size = 13.6.sp)
                    }
                }
            }

            if (vm.history.isNotEmpty()) {
                StorageCard(vm.history)
                Spacer(Modifier.height(12.dp))
                SearchField(vm)
                Spacer(Modifier.height(8.dp))
                FilterRow(vm)
                Spacer(Modifier.height(14.dp))
            }

            if (vm.history.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().padding(top = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    VIconView(VIcons.History, 40.dp, palette.muted)
                    T("Nothing here yet", modifier = Modifier.padding(top = 12.dp), weight = FontWeight.SemiBold, size = 15.2.sp)
                    T(
                        "Posts you download will show up here so you can grab them again.",
                        modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp),
                        size = 12.8.sp,
                        color = palette.muted,
                    )
                }
            } else if (vm.visibleHistory.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 36.dp), contentAlignment = Alignment.Center) {
                    T("Nothing matches your search or filters.", size = 13.6.sp, color = palette.muted)
                }
            } else {
                val visible = vm.visibleHistory
                if (vm.historySort == "largest") {
                    // Size order has no days to group by, so it stays one list.
                    CardBox(Modifier.fillMaxWidth()) {
                        Column {
                            visible.forEachIndexed { index, entry ->
                                if (index > 0) Separator()
                                HistoryRow(vm, entry) { selected = entry }
                            }
                        }
                    }
                } else {
                    // Newest or oldest first: one card per day group (Today, Yesterday, This week, Earlier).
                    visible.groupBy { dayBucket(it.time) }.entries.forEachIndexed { groupIndex, (bucket, items) ->
                        if (groupIndex > 0) Spacer(Modifier.height(16.dp))
                        SectionTitle(DAY_LABELS[bucket])
                        CardBox(Modifier.fillMaxWidth()) {
                            Column {
                                items.forEachIndexed { index, entry ->
                                    if (index > 0) Separator()
                                    HistoryRow(vm, entry) { selected = entry }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { entry ->
        HistoryDetail(vm, entry) { selected = null }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { T("Clear history?", weight = FontWeight.SemiBold, size = 18.sp) },
            text = { T("This removes the list only. Your saved files stay where they are.", size = 14.sp, color = palette.muted) },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { T("Clear", color = Accent, weight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { T("Cancel", color = palette.muted) }
            },
        )
    }
}

private val VideoColor = Accent
private val PhotoColor = Color(0xFFFFB020)
private val AudioColor = Color(0xFF5B8DEF)

/** What the saved downloads take up, split by type like a phone's storage screen. */
@Composable
private fun StorageCard(history: List<HistoryEntry>) {
    val palette = LocalPalette.current
    val video = history.sumOf { it.videoBytes }
    val photos = history.sumOf { it.imageBytes }
    val audio = history.sumOf { it.audioBytes }
    val total = video + photos + audio
    CardBox(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                T(if (total > 0) formatBytes(total) else "—", size = 22.sp, weight = FontWeight.Bold)
                T(
                    "  ·  ${history.size} ${if (history.size == 1) "item" else "items"}",
                    size = 13.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            T(
                "Saved with RibbT",
                size = 12.sp,
                color = palette.muted,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(palette.sep),
            ) {
                listOf(video to VideoColor, photos to PhotoColor, audio to AudioColor).forEach { (bytes, color) ->
                    if (bytes > 0 && total > 0) {
                        Box(Modifier.weight(bytes.toFloat() / total).fillMaxHeight().background(color))
                    }
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LegendItem("Video", video, VideoColor)
                LegendItem("Photos", photos, PhotoColor)
                LegendItem("Audio", audio, AudioColor)
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, bytes: Long, color: Color) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Column(Modifier.padding(start = 6.dp)) {
            T(label, size = 12.sp, weight = FontWeight.SemiBold)
            T(if (bytes > 0) formatBytes(bytes) else "0 B", size = 11.sp, color = palette.muted)
        }
    }
}

private val TYPE_CHOICES = listOf(
    Choice("", "All types"), Choice("video", "Videos"), Choice("image", "Photos"), Choice("audio", "Audio"),
)
private val SORT_CHOICES = listOf(
    Choice("newest", "Newest"), Choice("oldest", "Oldest"), Choice("largest", "Largest"),
)

/** Platform, type and sort each get their own dropdown. */
@Composable
private fun FilterRow(vm: AppViewModel) {
    val platformChoices = remember(vm.platforms, vm.history.size) {
        val present = vm.history.map { it.platform }.toSet()
        listOf(Choice("", "All platforms")) +
            vm.platforms.filter { it.name in present }.map { Choice(it.name, it.label) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Dropdown(platformChoices, vm.historyPlatform, { vm.historyPlatform = it }, Modifier.weight(1.2f), fill = true)
        Dropdown(TYPE_CHOICES, vm.historyType, { vm.historyType = it }, Modifier.weight(1f), fill = true)
        Dropdown(SORT_CHOICES, vm.historySort, { vm.historySort = it }, Modifier.weight(1f), fill = true)
    }
}

@Composable
private fun HistoryRow(vm: AppViewModel, entry: HistoryEntry, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val thumb = vm.absolute(entry.thumb.ifBlank { null })
    val platformLabel = vm.platforms.firstOrNull { it.name == entry.platform }?.label ?: entry.platform
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(palette.sep),
            contentAlignment = Alignment.Center,
        ) {
            if (thumb != null) {
                AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp))
            } else {
                VIconView(if (entry.kind == "video") VIcons.Video else VIcons.Photo, 22.dp, palette.muted)
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            T(
                entry.title.ifBlank { entry.author.ifBlank { "Untitled" } },
                weight = FontWeight.SemiBold, size = 15.2.sp, maxLines = 2, lineHeight = 20.sp,
            )
            val sub = listOf(platformLabel, entry.author).filter { it.isNotBlank() }.joinToString(" · ")
            T(sub, size = 12.16.sp, color = palette.muted, maxLines = 1, lineHeight = 16.4.sp)
            val ago = DateUtils.getRelativeTimeSpanString(
                entry.time * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            ).toString()
            val files = if (entry.files > 1) "${entry.files} files" else "1 file"
            val size = if (entry.totalBytes > 0) " · ${formatBytes(entry.totalBytes)}" else ""
            T("$ago · $files$size", size = 12.16.sp, color = palette.muted, maxLines = 1, lineHeight = 16.4.sp)
        }
    }
}


private val DAY_LABELS = listOf("Today", "Yesterday", "This week", "Earlier")

/** 0 = today, 1 = yesterday, 2 = the last week, 3 = older. [epochSeconds] is when the download was saved. */
private fun dayBucket(epochSeconds: Long): Int {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(day, LocalDate.now(zone))
    return when {
        days <= 0L -> 0
        days == 1L -> 1
        days < 7L -> 2
        else -> 3
    }
}

@Composable
private fun SearchField(vm: AppViewModel) {
    val palette = LocalPalette.current
    val focus = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(palette.card)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIconView(VIcons.Search, 18.dp, palette.muted)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (vm.historyQuery.isEmpty()) T("Search title or author", color = palette.muted, size = 14.4.sp, maxLines = 1)
            BasicTextField(
                value = vm.historyQuery,
                onValueChange = { vm.historyQuery = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 14.4.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (vm.historyQuery.isNotEmpty()) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Clear search", role = Role.Button) { vm.historyQuery = "" },
                contentAlignment = Alignment.Center,
            ) { VIconView(VIcons.Clear, 16.dp, palette.muted) }
        }
    }
}

/** What opens when a history row is tapped: the whole picture, the details, and what you can do with it. */
@Composable
private fun HistoryDetail(vm: AppViewModel, entry: HistoryEntry, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    // The files from this post that are still on the phone; empty for entries saved before this was tracked.
    var files by remember { mutableStateOf<List<SavedRef>>(emptyList()) }
    LaunchedEffect(entry.link) { files = vm.existingFiles(entry.link) }

    val thumb = vm.absolute(entry.thumb.ifBlank { null })
    val platformLabel = vm.platforms.firstOrNull { it.name == entry.platform }?.label ?: entry.platform
    val ago = DateUtils.getRelativeTimeSpanString(
        entry.time * 1000, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
    ).toString()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.card)
                .verticalScroll(rememberScrollState()),
        ) {
            // The picture is shown whole (never cropped); what it doesn't fill stays the dark backdrop.
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.bg),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 380.dp),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        VIconView(if (entry.kind == "video") VIcons.Video else VIcons.Photo, 36.dp, palette.muted)
                    }
                }
                if (platformLabel.isNotBlank()) {
                    T(
                        platformLabel,
                        size = 11.5.sp,
                        color = Color.White,
                        weight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable(onClickLabel = "Close", role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { VIconView(VIcons.CloseSmall, 14.dp, Color.White) }
            }

            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp)) {
                T(
                    entry.title.ifBlank { entry.author.ifBlank { "Untitled" } },
                    weight = FontWeight.SemiBold, size = 17.sp, maxLines = 3, lineHeight = 22.sp,
                )
                if (entry.author.isNotBlank() && entry.title.isNotBlank()) {
                    T(entry.author, size = 13.sp, color = palette.muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoChip(ago)
                    InfoChip(if (entry.files > 1) "${entry.files} files" else "1 file")
                    if (entry.totalBytes > 0) InfoChip(formatBytes(entry.totalBytes))
                }
                Row(
                    Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.bg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VIconView(VIcons.Link, 15.dp, palette.muted)
                    T(entry.link, size = 12.5.sp, color = palette.muted, maxLines = 2, lineHeight = 17.sp, modifier = Modifier.padding(start = 8.dp))
                }
                if (entry.savedTo.isNotBlank()) {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        VIconView(VIcons.Folder, 15.dp, palette.muted)
                        T("Saved to ${entry.savedTo}", size = 12.16.sp, color = palette.muted, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            // One main action, then the rest in pairs. Open and Share only show when the files are still on the phone.
            val actions = buildList {
                if (files.size == 1) add(DetailAction("Share", VIcons.Share) { FileActions.share(context, files) })
                if (files.isNotEmpty()) add(DetailAction("Download again", VIcons.Download) { vm.openFromHistory(entry); onDismiss() })
                add(
                    DetailAction(if (copied) "Copied" else "Copy link", if (copied) VIcons.Check else VIcons.Link) {
                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        manager?.setPrimaryClip(ClipData.newPlainText("RibbT link", entry.link))
                        copied = true
                    },
                )
                add(DetailAction("Delete", VIcons.Trash, destructive = true) { vm.deleteHistory(entry); onDismiss() })
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    files.size == 1 -> PillButton("Open", VIcons.Open, { FileActions.open(context, files[0]) }, Modifier.fillMaxWidth(), height = 46.dp)
                    files.size > 1 -> PillButton("Share ${files.size} files", VIcons.Share, { FileActions.share(context, files) }, Modifier.fillMaxWidth(), height = 46.dp)
                    else -> PillButton("Download again", VIcons.Download, { vm.openFromHistory(entry); onDismiss() }, Modifier.fillMaxWidth(), height = 46.dp)
                }
                // With no files on the phone "Download again" is already the main button above.
                val rest = if (files.isEmpty()) actions.filter { it.label != "Download again" } else actions
                rest.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { action ->
                            PillButton(
                                text = action.label,
                                icon = action.icon,
                                onClick = action.onClick,
                                modifier = Modifier.weight(1f),
                                style = PillStyle.Tonal,
                                height = 42.dp,
                                contentColor = if (action.destructive) palette.err else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

private class DetailAction(val label: String, val icon: VIcon, val destructive: Boolean = false, val onClick: () -> Unit)

@Composable
private fun InfoChip(text: String) {
    val palette = LocalPalette.current
    T(
        text,
        size = 12.sp,
        color = palette.muted,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(palette.bg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
