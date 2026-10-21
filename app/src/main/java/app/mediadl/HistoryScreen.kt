package app.mediadl

import androidx.compose.foundation.horizontalScroll

import androidx.compose.ui.res.painterResource

import androidx.compose.foundation.Image

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset

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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween

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

private val TYPE_CHOICES = listOf(
    Choice("", "All"), Choice("video", "Video"), Choice("image", "Photos"), Choice("audio", "Audio"),
)
private val SORT_CHOICES = listOf(
    Choice("newest", "Newest"), Choice("oldest", "Oldest"), Choice("largest", "Largest"),
)

/** Service chips (with their logos) on the page; type and sort live behind the filter button. */
@Composable
private fun FilterRow(vm: AppViewModel) {
    val palette = LocalPalette.current
    var showFilters by remember { mutableStateOf(false) }
    val active = vm.historyType.isNotEmpty() || vm.historySort != "newest"
    val present = remember(vm.platforms, vm.history.size) {
        vm.history.map { it.platform }.distinct().map { name ->
            vm.platforms.firstOrNull { it.name == name } ?: Platform(name, name, emptyList(), emptyList())
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ServiceChip("All", null, vm.historyPlatform.isEmpty()) { vm.historyPlatform = "" }
            present.forEach { platform ->
                ServiceChip(platform.label, platform.name, vm.historyPlatform == platform.name) { vm.historyPlatform = platform.name }
            }
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(if (active) Accent.copy(alpha = 0.14f) else palette.card)
                .border(1.dp, if (active) Accent else palette.line, CircleShape)
                .clickable(onClickLabel = "Filter and sort", role = Role.Button) { showFilters = true },
            contentAlignment = Alignment.Center,
        ) {
            VIconView(VIcons.Filter, 18.dp, if (active) Accent else palette.text)
        }
    }
    if (showFilters) FilterDialog(vm) { showFilters = false }
}

/** "All" or one service: a pill with the service's logo, filled dark while selected. */
@Composable
private fun ServiceChip(label: String, platform: String?, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val logo = remember(platform) {
        if (platform == null) 0 else context.resources.getIdentifier("logo_$platform", "drawable", context.packageName)
    }
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier
            .height(44.dp)
            .clip(shape)
            .background(if (selected) palette.text else palette.card)
            .border(1.dp, if (selected) palette.text else palette.line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (logo != 0) {
            Image(
                painter = painterResource(logo),
                contentDescription = null,
                modifier = Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)),
            )
        }
        T(label, size = 14.sp, color = if (selected) palette.bg else palette.text, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Type and sort, tucked behind the filter button. */
@Composable
private fun FilterDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val changed = vm.historyType.isNotEmpty() || vm.historySort != "newest"
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(palette.card)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            T("Filter and sort", size = 18.sp, weight = FontWeight.Bold)
            T("Type", size = 12.48.sp, color = palette.muted, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            ChipGrid(TYPE_CHOICES, vm.historyType) { vm.historyType = it }
            T("Sort by", size = 12.48.sp, color = palette.muted, weight = FontWeight.SemiBold, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            ChipGrid(SORT_CHOICES, vm.historySort) { vm.historySort = it }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changed) {
                    PillButton(
                        text = "Reset",
                        icon = null,
                        onClick = { vm.historyType = ""; vm.historySort = "newest" },
                        modifier = Modifier.weight(1f),
                        style = PillStyle.Tonal,
                        height = 42.dp,
                    )
                }
                PillButton(text = "Done", icon = null, onClick = onDismiss, modifier = Modifier.weight(1f), height = 42.dp)
            }
        }
    }
}

@Composable
private fun ChipGrid(choices: List<Choice>, selected: String, onSelect: (String) -> Unit) {
    val palette = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.chunked(3).forEach { rowChoices ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowChoices.forEach { choice ->
                    OptionChip(choice.label, selected == choice.value, Modifier.weight(1f), palette.bg) { onSelect(choice.value) }
                }
                repeat(3 - rowChoices.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun OptionChip(label: String, selected: Boolean, modifier: Modifier, idle: Color, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val background by animateColorAsState(if (selected) Accent.copy(alpha = 0.14f) else idle, tween(160), label = "chip-bg")
    val textColor by animateColorAsState(if (selected) Accent else palette.text, tween(160), label = "chip-text")
    Box(
        modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        T(label, size = 12.8.sp, color = textColor, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
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
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The file's own preview, with the service's logo as a badge on the corner.
        Box(Modifier.size(56.dp)) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(palette.soft),
                contentAlignment = Alignment.Center,
            ) {
                if (thumb != null) {
                    AsyncImage(model = thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    VIconView(if (entry.kind == "video") VIcons.Video else VIcons.Photo, 22.dp, palette.muted)
                }
            }
            PlatformBadge(entry.platform, Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp))
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
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Details", role = Role.Button, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) { VIconView(VIcons.More, 18.dp, palette.muted) }
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
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(palette.card)
            .border(1.dp, palette.line, RoundedCornerShape(22.dp))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIconView(VIcons.Search, 18.dp, palette.muted)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (vm.historyQuery.isEmpty()) T("Search title or author", color = palette.muted, size = 14.4.sp, maxLines = 1)
            BasicTextField(
                value = vm.historyQuery,
                onValueChange = { vm.historyQuery = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 14.4.sp, fontFamily = AppFont),
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

            val actions = buildList {
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
                PillButton("Download again", VIcons.Download, { vm.openFromHistory(entry); onDismiss() }, Modifier.fillMaxWidth(), height = 46.dp)
                actions.chunked(2).forEach { pair ->
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
