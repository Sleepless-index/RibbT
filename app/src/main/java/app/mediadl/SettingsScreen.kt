package app.mediadl

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val LAYOUT_CHOICES = MediaLayout.entries.map { Choice(it.key, it.label) }
private val CHANNEL_CHOICES = listOf(Choice("stable", "Stable"), Choice("nightly", "Nightly"))
private val SERVER_CHOICES = listOf(Choice("builtin", "Built-in"), Choice("custom", "Custom"))
private val SECTION_ORDER = listOf("Video", "Images", "Audio", "X / Twitter", "Downloads", "Server")

private const val PAGE_APPEARANCE = "Appearance"
internal const val PAGE_SERVER = "Server"
private const val PAGE_DOWNLOADING = "Downloads"

/** What the server calls this section; older servers still send it, so it is mapped to [PAGE_DOWNLOADING]. */
private const val SERVER_SAVING_SECTION = "Saving"
private const val PAGE_SERVICES = "Supported services"

// ---------------------------------------------------------------------------------------------
// Settings: a list of sections; each opens as its own subpage (state lives in AppViewModel.settingsPage).
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    LaunchedEffect(Unit) { if (vm.server == null) vm.refresh() }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseFolder(uri)
    }
    val saveChoices = listOf(
        Choice("downloads", "Internal storage"),
        if (vm.sdAvailable) Choice("sdcard", "SD card") else Choice("sdcard", "SD card", enabled = false, note = "Not detected"),
        Choice("folder", "Custom folder"),
    )
    // The list keeps its scroll position while a subpage is open on top of it.
    val rootScroll = rememberScrollState()

    AnimatedContent(
        targetState = vm.settingsPage,
        transitionSpec = {
            val push = targetState != null
            val enter = slideInHorizontally(tween(300, easing = WebEase)) { if (push) it / 4 else -it / 5 } +
                fadeIn(tween(240))
            val exit = slideOutHorizontally(tween(300, easing = WebEase)) { if (push) -it / 5 else it / 4 } +
                fadeOut(tween(160))
            enter togetherWith exit
        },
        modifier = Modifier.fillMaxSize(),
        label = "settings-page",
    ) { page ->
        val scroll = if (page == null) rootScroll else rememberScrollState()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (page == null) SettingsHome(vm) else SettingsPage(vm, page, saveChoices) { pickFolder.launch(null) }
            }
        }
    }
}

private class GroupedSettings(
    val choices: Map<String, List<Choice>>,
    val rows: Map<String, List<SettingRow>>,
    /** Section titles in display order ("Downloads" and "Server" always exist, the rest come from the server). */
    val sections: List<String>,
)

private fun groupSettings(server: ServerSettings?): GroupedSettings {
    val choices = server?.choices.orEmpty()
    val rows = server?.schema
        ?.filter { row -> if (row.type == "choice") !choices[row.path].isNullOrEmpty() else true }
        ?.groupBy { if (it.section == SERVER_SAVING_SECTION) PAGE_DOWNLOADING else it.section }
        ?: emptyMap()
    val sections = SECTION_ORDER.filter { it == PAGE_DOWNLOADING || it == PAGE_SERVER || rows.containsKey(it) } +
        rows.keys.filter { it !in SECTION_ORDER }
    return GroupedSettings(choices, rows, sections)
}

private fun iconFor(title: String): VIcon = when (title) {
    PAGE_APPEARANCE -> VIcons.Palette
    "Video" -> VIcons.Video
    "Images" -> VIcons.Photo
    "Audio" -> VIcons.Audio
    "X / Twitter" -> VIcons.Link
    PAGE_DOWNLOADING -> VIcons.Download
    PAGE_SERVER -> VIcons.Server
    PAGE_SERVICES -> VIcons.Services
    else -> VIcons.Settings
}

@Composable
private fun SettingsHome(vm: AppViewModel) {
    val data = remember(vm.server) { groupSettings(vm.server) }
    ScreenTitle("Settings") { SavedLabel(vm) }

    // Looks for a new RibbT once per session when Settings opens, quietly.
    LaunchedEffect(Unit) { if (vm.appRelease == null && !vm.appChecking) vm.checkAppUpdate(silent = true) }

    StatusCard(vm)

    // The media sections (Video, Images, ...) live on the Downloads page together with the save options.
    val mediaSections = data.sections.filter { it != PAGE_SERVER && it != PAGE_DOWNLOADING }
    val themeLabel = when (vm.theme) {
        "light" -> "Light"
        "dark" -> "Dark"
        else -> "System"
    }
    val serviceNames = vm.platforms.map { it.label }
    Column {
        SectionTitle("Preferences")
        CardBox(Modifier.fillMaxWidth()) {
            Column {
                NavRow(
                    iconFor(PAGE_DOWNLOADING),
                    PAGE_DOWNLOADING,
                    (listOf("Location") + mediaSections + "Filenames").joinToString(", "),
                ) { vm.settingsPage = PAGE_DOWNLOADING }
                Separator()
                NavRow(
                    iconFor(PAGE_APPEARANCE),
                    PAGE_APPEARANCE,
                    "Theme, accent color, media layout",
                    value = themeLabel,
                ) { vm.settingsPage = PAGE_APPEARANCE }
            }
        }
    }
    Column {
        SectionTitle("Engine")
        CardBox(Modifier.fillMaxWidth()) {
            Column {
                NavRow(
                    iconFor(PAGE_SERVER),
                    PAGE_SERVER,
                    "Built-in, yt-dlp, sign-in cookies, debug info",
                ) { vm.settingsPage = PAGE_SERVER }
                Separator()
                NavRow(
                    iconFor(PAGE_SERVICES),
                    PAGE_SERVICES,
                    if (serviceNames.isEmpty()) null else serviceNames.take(3).joinToString(", ") + " and more",
                    value = when {
                        vm.platforms.isNotEmpty() -> "${vm.platforms.size} sites"
                        vm.online == false -> "Offline"
                        else -> "Loading…"
                    },
                ) { vm.settingsPage = PAGE_SERVICES }
            }
        }
    }

    if (vm.server == null) OfflineHint(vm)
}

/** The top card of Settings: RibbT's own update, and the server and yt-dlp at a glance. */
@Composable
private fun StatusCard(vm: AppViewModel) {
    val palette = LocalPalette.current
    val release = vm.appRelease
    val busy = vm.appUpdateBusy
    val available = release?.available == true
    val good = if (palette.dark) Color(0xFF5BD68A) else Color(0xFF1E8E4E)
    CardBox(Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Accent),
                    contentAlignment = Alignment.Center,
                ) { VIconView(VIcons.Download, 22.dp, AccentOn) }
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        T("RibbT", size = 15.2.sp, weight = FontWeight.SemiBold, maxLines = 1)
                        if (release != null && !busy) {
                            StatusPill(
                                if (available) "Update available" else "Up to date",
                                if (available) PillTone.Accent else PillTone.Good,
                            )
                        }
                    }
                    T(
                        buildString {
                            append("Version ${vm.appVersion}")
                            if (available && !release?.version.isNullOrBlank()) append(" · ${release?.version} is ready")
                        },
                        size = 12.16.sp, color = palette.muted, lineHeight = 15.8.sp, modifier = Modifier.padding(top = 2.dp),
                    )
                }
                SmallButton(
                    text = when {
                        busy -> vm.appUpdatePercent?.let { "$it%" } ?: vm.appUpdateStatus ?: "Updating…"
                        vm.appChecking -> "Checking…"
                        available -> "Update"
                        else -> "Check"
                    },
                    enabled = !busy && !vm.appChecking,
                    tonal = !available && !busy,
                    minWidth = 76.dp,
                    onClick = { if (available) vm.installAppUpdate() else vm.checkAppUpdate() },
                )
            }
            Separator()
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val serverText = when {
                    vm.starting -> "Starting…"
                    vm.online == true -> if (vm.serverMode == "custom") "Connected" else "Running"
                    vm.online == false -> "Offline"
                    else -> "Checking…"
                }
                StatusCell("Server", Modifier.weight(1f), onClick = { vm.settingsPage = PAGE_SERVER }) {
                    Box(
                        Modifier.size(8.dp).clip(CircleShape).background(
                            when (vm.online) {
                                true -> good
                                false -> palette.err
                                null -> palette.muted
                            },
                        ),
                    )
                    T(serverText, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
                }
                val version = vm.ytdlpVersion
                StatusCell("yt-dlp", Modifier.weight(1f), onClick = { vm.settingsPage = PAGE_SERVER }) {
                    T(
                        when (version) {
                            null -> "Checking…"
                            "" -> "Not installed"
                            else -> version.split('.').take(3).joinToString(".")
                        },
                        size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1,
                    )
                    if (!version.isNullOrEmpty() && vm.ytdlpAvailable != null) {
                        StatusPill(
                            if (vm.ytdlpAvailable == true) "Update" else "Latest",
                            if (vm.ytdlpAvailable == true) PillTone.Accent else PillTone.Good,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCell(label: String, modifier: Modifier, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val palette = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.soft)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        T(label, size = 11.5.sp, color = palette.muted, weight = FontWeight.SemiBold)
        Row(
            Modifier.padding(top = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsPage(vm: AppViewModel, page: String, saveChoices: List<Choice>, pickFolder: () -> Unit) {
    val data = remember(vm.server) { groupSettings(vm.server) }
    if (page == PAGE_DOWNLOADING) StorageWatcher(vm)
    PageHeader(page, onBack = { vm.settingsPage = null }) { SavedLabel(vm) }
    when (page) {
        PAGE_APPEARANCE -> AppearanceCards(vm)
        PAGE_SERVICES -> ServicesList(vm)
        PAGE_SERVER -> ServerPage(vm, data)
        PAGE_DOWNLOADING -> {
            Column {
                SectionTitle("Where files go")
                DownloadLocationCard(vm, saveChoices, pickFolder)
            }
            data.sections.filter { it != PAGE_SERVER && it != PAGE_DOWNLOADING }.forEach { title ->
                Column {
                    IconSectionTitle(iconFor(title), title)
                    SectionCard(vm, title, data)
                }
            }
            if (data.rows[PAGE_DOWNLOADING].orEmpty().isNotEmpty()) {
                Column {
                    SectionTitle("Filenames")
                    CardBox(Modifier.fillMaxWidth()) {
                        Column {
                            SettingRows(vm, data.rows[PAGE_DOWNLOADING].orEmpty(), data)
                            Separator()
                            FilenamePreview(vm.settingValue("metadata.filename_style") as? String ?: "classic")
                        }
                    }
                }
            }
            if (vm.server == null) OfflineHint(vm)
        }
        else -> {
            SectionCard(vm, page, data)
            if (vm.server == null) OfflineHint(vm)
        }
    }
}

/** Re-checks the SD card and custom folder when the page opens and whenever a card is inserted or pulled out. */
@Composable
private fun StorageWatcher(vm: AppViewModel) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        vm.refreshStorage()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = vm.refreshStorage()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
}

/** The line under "Download location": where files really go, or why they won't go where it points. */
private fun locationHint(vm: AppViewModel): String = when (vm.saveTo) {
    "sdcard" -> if (vm.sdAvailable) "SD card/Download/RibbT" else "SD card not detected. Saving to your phone."
    "folder" -> when {
        vm.folderName.isBlank() -> "No folder picked. Saving to your phone."
        !vm.folderOk -> "Folder unavailable. Saving to your phone."
        else -> vm.folderName
    }
    else -> "Downloads/RibbT"
}

@Composable
private fun OfflineHint(vm: AppViewModel) {
    T(
        if (vm.online == false) "Start the server to change download options." else "Loading download options…",
        modifier = Modifier.padding(start = 6.dp),
        size = 13.44.sp,
        color = LocalPalette.current.muted,
    )
}

@Composable
private fun AppearanceCards(vm: AppViewModel) {
    CardBox(Modifier.fillMaxWidth()) { ThemeSegments(vm.theme) { vm.updateTheme(it) } }
    CardBox(Modifier.fillMaxWidth()) { AccentPicker(vm) }
    CardBox(Modifier.fillMaxWidth()) { LogoPicker(vm) }
    CardBox(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            T("Media layout", size = 14.4.sp, lineHeight = 18.7.sp)
            T(
                "How results are shown. One result always fills the width; two or more follow the layout.",
                size = 12.16.sp,
                color = LocalPalette.current.muted,
                modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                lineHeight = 15.8.sp,
            )
            LayoutPicker(vm.layout, onSelect = { vm.updateLayout(it) })
        }
    }
}

/** A section title with a small icon before it (Video, Audio, ...). */
@Composable
private fun IconSectionTitle(icon: VIcon, title: String) {
    val palette = LocalPalette.current
    Row(Modifier.padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        VIconView(icon, 14.dp, palette.muted)
        Spacer(Modifier.width(6.dp))
        T(title, size = 13.sp, color = palette.muted, weight = FontWeight.Bold)
    }
}

/** Where downloads are saved, how they are sorted, and whether a copied link is fetched on open. */
@Composable
private fun DownloadLocationCard(vm: AppViewModel, saveChoices: List<Choice>, pickFolder: () -> Unit) {
    CardBox(Modifier.fillMaxWidth()) {
        Column {
            ChoiceRow("Download location", locationHint(vm), saveChoices, vm.saveTo) { value ->
                // A custom folder has to be picked once; after that it is just switched to.
                if (value == "folder" && (vm.folderName.isBlank() || !vm.folderOk)) pickFolder() else vm.updateSaveTo(value)
            }
            if (vm.saveTo == "folder") {
                Separator()
                RowShell("Folder", if (vm.folderName.isBlank()) "None picked yet" else vm.folderName) {
                    SmallButton(text = if (vm.folderName.isBlank()) "Pick" else "Change", enabled = true, tonal = true, onClick = pickFolder)
                }
            }
            Separator()
            ToggleRow("Sort into folders by type", "Videos, Images and Audio subfolders.", vm.sortByType) { vm.updateSortByType(it) }
            Separator()
            ToggleRow("Paste and go", "Fetch a copied link when you open RibbT.", vm.pasteAndGo) { vm.updatePasteAndGo(it) }
        }
    }
}

/** The server's own settings rows (toggles and choices), with separators between them. */
@Composable
private fun SettingRows(vm: AppViewModel, rows: List<SettingRow>, data: GroupedSettings) {
    rows.forEachIndexed { index, row ->
        if (index > 0) Separator()
        if (row.type == "toggle") {
            ToggleRow(row.label, row.hint, vm.settingValue(row.path) as? Boolean ?: false) {
                vm.updateServerSetting(row.path, it)
            }
        } else {
            ChoiceRow(
                row.label, row.hint, data.choices[row.path].orEmpty(),
                vm.settingValue(row.path) as? String ?: "",
            ) { vm.updateServerSetting(row.path, it) }
        }
    }
}

/** One server-driven section (Video, Images, Audio, ...) as a card. A section the server doesn't send shows nothing. */
@Composable
private fun SectionCard(vm: AppViewModel, title: String, data: GroupedSettings) {
    val rows = data.rows[title].orEmpty()
    if (rows.isEmpty()) return
    CardBox(Modifier.fillMaxWidth()) { Column { SettingRows(vm, rows, data) } }
}

/** Connection first (built-in or a PC), then the engine behind it, then troubleshooting. */
@Composable
private fun ServerPage(vm: AppViewModel, data: GroupedSettings) {
    CardBox(Modifier.fillMaxWidth()) {
        Column {
            ServerHero(vm)
            Separator()
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Segments(SERVER_CHOICES, vm.serverMode, { vm.updateServerMode(it) })
                T(
                    "Run the downloader inside RibbT, or connect to Termux or a PC.",
                    size = 12.16.sp, color = LocalPalette.current.muted, lineHeight = 15.8.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            if (vm.serverMode == "custom") {
                Separator()
                AddressRow(vm)
            }
        }
    }
    Column {
        SectionTitle("Engine")
        CardBox(Modifier.fillMaxWidth()) {
            Column {
                YtdlpRow(vm)
                Separator()
                CookiesRow(vm)
            }
        }
    }
    val extra = data.rows[PAGE_SERVER].orEmpty()
    if (extra.isNotEmpty()) CardBox(Modifier.fillMaxWidth()) { Column { SettingRows(vm, extra, data) } }
    Column {
        SectionTitle("Troubleshooting")
        CardBox(Modifier.fillMaxWidth()) {
            ToggleRow(
                "Debug info",
                "Adds Copy to failed downloads, with details for bug reports.",
                vm.debugInfo,
                icon = VIcons.Copy,
            ) { vm.updateDebugInfo(it) }
        }
    }
    if (vm.server == null) OfflineHint(vm)
}

@Composable
private fun ServerHero(vm: AppViewModel) {
    val palette = LocalPalette.current
    val builtIn = vm.serverMode == "builtin"
    val good = if (palette.dark) Color(0xFF5BD68A) else Color(0xFF1E8E4E)
    val status = when {
        vm.starting -> "Starting…"
        !builtIn -> vm.baseUrl.ifBlank { "No address yet" }
        vm.online == true -> "Running on this phone"
        vm.builtInFailure != null -> "Couldn’t start: ${vm.builtInFailure}"
        vm.online == false -> "Not answering. Tap Retry."
        else -> "Checking…"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp)) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).background(Accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) { VIconView(VIcons.Server, 22.dp, Accent) }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(palette.card)
                    .padding(3.dp)
                    .clip(CircleShape)
                    .background(
                        when (vm.online) {
                            true -> good
                            false -> palette.err
                            null -> palette.muted
                        },
                    ),
            )
        }
        Column(Modifier.weight(1f)) {
            T(if (builtIn) "Built-in server" else "Custom server", size = 15.2.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T(status, size = 12.16.sp, color = palette.muted, lineHeight = 15.8.sp, modifier = Modifier.padding(top = 2.dp))
        }
        when {
            vm.starting -> StatusPill("Starting", PillTone.Accent)
            vm.online == true -> StatusPill(if (builtIn) "Running" else "Connected", PillTone.Good)
            vm.online == false && builtIn -> SmallButton(text = "Retry", enabled = true, onClick = { vm.refresh() })
        }
    }
}

/** yt-dlp's version, with Check / Update and its own settings. */
@Composable
private fun YtdlpRow(vm: AppViewModel) {
    var showYtdlpSettings by rememberSaveable { mutableStateOf(false) }
    // "Version X" with a pill beside it once we know whether a newer one exists.
    val version = vm.ytdlpVersion
    val showPill = vm.ytdlpStatus == null && !version.isNullOrEmpty() && vm.ytdlpAvailable != null
    RowShell(
        "yt-dlp",
        vm.ytdlpStatus ?: when (version) {
            null -> "Checking version…"
            "" -> "Not installed on the server"
            else -> "Version $version"
        },
        badge = if (showPill) {
            if (vm.ytdlpAvailable == true) "Update available" else "Up to date"
        } else {
            null
        },
        badgeTone = if (vm.ytdlpAvailable == true) PillTone.Accent else PillTone.Good,
        icon = VIcons.Refresh,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // "Check" looks for a newer yt-dlp; once one is found the button turns into "Update".
            SmallButton(
                text = when {
                    vm.ytdlpBusy -> "Updating…"
                    vm.ytdlpChecking -> "Checking…"
                    vm.ytdlpAvailable == true -> "Update"
                    else -> "Check"
                },
                enabled = !vm.ytdlpBusy && !vm.ytdlpChecking && vm.online == true,
                tonal = vm.ytdlpAvailable != true,
                minWidth = 64.dp,
                onClick = { if (vm.ytdlpAvailable == true) vm.updateYtdlp() else vm.checkYtdlp(manual = true) },
            )
            IconSquareButton(VIcons.Settings, "yt-dlp settings") { showYtdlpSettings = true }
        }
    }
    if (showYtdlpSettings) YtdlpSettingsDialog(vm) { showYtdlpSettings = false }
}

/** The small "Saved" confirmation next to a title. Only reacts to saves made while it is on screen. */
@Composable
private fun SavedLabel(vm: AppViewModel) {
    val palette = LocalPalette.current
    val baseline = remember { vm.savedPulse }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(vm.savedPulse) {
        if (vm.savedPulse > baseline) {
            show = true
            delay(1400)
            show = false
        }
    }
    val alpha by animateFloatAsState(if (show) 1f else 0f, tween(200), label = "saved")
    T("Saved", size = 12.sp, color = palette.muted, modifier = Modifier.widthIn(min = 42.dp).alpha(alpha))
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit, trailing: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(palette.card)
                .border(1.dp, palette.line, CircleShape)
                .clickable(onClickLabel = "Back", role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { VIconView(VIcons.Back, 20.dp, palette.text) }
        Spacer(Modifier.width(12.dp))
        T(
            title, size = 26.sp, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f),
            fontFamily = DisplayFont, letterSpacing = (-0.6).sp,
        )
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun NavRow(icon: VIcon, title: String, summary: String?, value: String? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { VIconView(icon, 20.dp, Accent) }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
            T(title, size = 14.4.sp, lineHeight = 18.7.sp)
            if (!summary.isNullOrBlank()) {
                T(summary, size = 12.16.sp, color = palette.muted, maxLines = 1, lineHeight = 15.8.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (!value.isNullOrBlank()) {
            T(value, size = 13.5.sp, color = palette.muted, maxLines = 1, modifier = Modifier.widthIn(max = 150.dp).padding(end = 12.dp))
        }
        VIconView(VIcons.ChevronRight, 7.dp, palette.muted)
    }
}

@Composable
private fun RowShell(
    label: String,
    hint: String?,
    /** Text of a small pill shown right after the label (e.g. "Up to date"); none when null. */
    badge: String? = null,
    badgeTone: PillTone = PillTone.Good,
    /** An icon tile before the label, like the navigation rows have; none when null. */
    icon: VIcon? = null,
    control: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = if (icon != null) 64.dp else 52.dp)
            .padding(horizontal = 16.dp, vertical = if (icon != null) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (icon != null) 14.dp else 12.dp),
    ) {
        if (icon != null) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(Accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) { VIconView(icon, 20.dp, Accent) }
        }
        Column(Modifier.weight(1f)) {
            T(label, size = 14.4.sp, lineHeight = 18.7.sp)
            if (badge != null) {
                // Under the label, so a narrow row never squeezes the title into one letter per line.
                Box(Modifier.padding(top = 4.dp)) { StatusPill(badge, badgeTone) }
            }
            if (!hint.isNullOrBlank()) {
                T(hint, size = 12.16.sp, color = palette.muted, modifier = Modifier.padding(top = 2.dp), lineHeight = 15.8.sp)
            }
        }
        control()
    }
}

private class MenuPositioner(
    private val pad: Int,
    private val gap: Int,
    /** Where the menu should grow from (the anchor), reported once the final position is known. */
    private val onPlaced: (TransformOrigin) -> Unit,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width + pad)
            .coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width))
        var y = anchorBounds.bottom - pad + gap
        if (y + popupContentSize.height > windowSize.height) {
            val above = anchorBounds.top + pad - gap - popupContentSize.height
            y = if (above >= 0) above else maxOf(0, windowSize.height - popupContentSize.height)
        }
        val width = maxOf(1, popupContentSize.width).toFloat()
        val height = maxOf(1, popupContentSize.height).toFloat()
        val opensDownward = y + popupContentSize.height / 2 > anchorBounds.center.y
        onPlaced(
            TransformOrigin(
                pivotFractionX = ((anchorBounds.center.x - x) / width).coerceIn(0f, 1f),
                pivotFractionY = if (opensDownward) pad / height else 1f - pad / height,
            ),
        )
        return IntOffset(x, y)
    }
}

@Composable
internal fun Dropdown(
    options: List<Choice>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Stretch the button to the width it is given (used for a row of filters). */
    fill: Boolean = false,
) {
    val palette = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.value == selected }?.label ?: selected
    val buttonShape = RoundedCornerShape(10.dp)
    val menuShape = RoundedCornerShape(14.dp)
    val density = LocalDensity.current

    // The popup has to outlive `expanded` so the close animation can play before it leaves the window.
    val menuState = remember { MutableTransitionState(false) }
    menuState.targetState = expanded
    val origin = remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    var anchorWidth by remember { mutableStateOf(0.dp) }

    val chevron by animateFloatAsState(if (expanded) 180f else 0f, tween(200, easing = WebEase), label = "dd-chevron")
    val outline by animateColorAsState(
        if (expanded) Accent else Accent.copy(alpha = 0f),
        tween(160),
        label = "dd-outline",
    )

    Box(modifier) {
        Row(
            (if (fill) Modifier.fillMaxWidth() else Modifier.widthIn(min = 120.dp, max = 220.dp))
                .onSizeChanged { anchorWidth = with(density) { it.width.toDp() } }
                .clip(buttonShape)
                .background(palette.bg)
                .border(1.dp, outline, buttonShape)
                .clickable { expanded = true }
                .padding(start = 10.dp, end = 9.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            T(current, size = 13.6.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false), lineHeight = 19.sp)
            Spacer(Modifier.width(11.dp))
            VIconView(VIcons.ChevronDown, 10.dp, palette.muted, Modifier.graphicsLayer { rotationZ = chevron })
        }
        if (menuState.currentState || menuState.targetState) {
            val pad = with(density) { 12.dp.roundToPx() }
            val gap = with(density) { 6.dp.roundToPx() }
            Popup(
                popupPositionProvider = remember(pad, gap) { MenuPositioner(pad, gap) { origin.value = it } },
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                val transition = rememberTransition(menuState, label = "dd-menu")
                val menuScale by transition.animateFloat(
                    transitionSpec = {
                        if (false isTransitioningTo true) tween(200, easing = WebEase) else tween(120, easing = WebEase)
                    },
                    label = "dd-menu-scale",
                ) { open -> if (open) 1f else 0.9f }
                val menuAlpha by transition.animateFloat(
                    transitionSpec = { if (false isTransitioningTo true) tween(140) else tween(100) },
                    label = "dd-menu-alpha",
                ) { open -> if (open) 1f else 0f }

                Box(
                    Modifier
                        .graphicsLayer {
                            scaleX = menuScale
                            scaleY = menuScale
                            alpha = menuAlpha
                            transformOrigin = origin.value
                        }
                        .padding(12.dp),
                ) {
                    Column(
                        Modifier
                            // As wide as the button it opened from (or as its longest option), never wider.
                            .widthIn(min = anchorWidth, max = maxOf(280.dp, anchorWidth))
                            .width(IntrinsicSize.Max)
                            .heightIn(max = 320.dp)
                            .shadow(14.dp, menuShape)
                            .clip(menuShape)
                            .background(palette.card)
                            .border(1.dp, palette.sep, menuShape)
                            .verticalScroll(rememberScrollState())
                            .padding(5.dp),
                    ) {
                        options.forEach { option ->
                            val isSelected = option.value == selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) palette.bg else Color.Transparent)
                                    .clickable(enabled = option.enabled) {
                                        expanded = false
                                        if (!isSelected) onSelect(option.value)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                T(
                                    option.label,
                                    size = 13.6.sp,
                                    color = when {
                                        !option.enabled -> palette.muted.copy(alpha = 0.7f)
                                        isSelected -> Accent
                                        else -> palette.text
                                    },
                                    weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    lineHeight = 19.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (option.note != null) {
                                    T(
                                        option.note,
                                        size = 11.5.sp,
                                        color = palette.muted,
                                        maxLines = 1,
                                        modifier = Modifier.padding(start = 12.dp),
                                    )
                                }
                                if (isSelected) VIconView(VIcons.Check, 13.dp, Accent, Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceRow(label: String, hint: String?, options: List<Choice>, selected: String, onSelect: (String) -> Unit) {
    val short = options.size in 2..3 && options.all { it.enabled && it.note == null && it.label.length <= 12 }
    if (short) {
        val palette = LocalPalette.current
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            T(label, size = 14.4.sp, lineHeight = 18.7.sp)
            if (!hint.isNullOrBlank()) {
                T(hint, size = 12.16.sp, color = palette.muted, modifier = Modifier.padding(top = 2.dp), lineHeight = 15.8.sp)
            }
            Segments(options, selected, onSelect, Modifier.padding(top = 12.dp))
        }
    } else {
        RowShell(label, hint) { Dropdown(options, selected, onSelect) }
    }
}

@Composable
private fun WebToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    val track by animateColorAsState(if (checked) Accent else palette.line, tween(180), label = "toggle-track")
    val offset by animateDpAsState(if (checked) 21.dp else 3.dp, tween(180, easing = WebEase), label = "toggle-thumb")
    Box(
        Modifier
            .size(44.dp, 26.dp)
            .clip(CircleShape)
            .background(track)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onChange,
            ),
    ) {
        Box(
            Modifier
                .offset(x = offset, y = 3.dp)
                .size(20.dp)
                .shadow(2.dp, CircleShape)
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
private fun ToggleRow(label: String, hint: String?, checked: Boolean, icon: VIcon? = null, onChange: (Boolean) -> Unit) {
    RowShell(label, hint, icon = icon) { WebToggle(checked, onChange) }
}

@Composable
private fun AddressRow(vm: AppViewModel) {
    val palette = LocalPalette.current
    var text by remember(vm.baseUrl) { mutableStateOf(vm.baseUrl) }
    val status = when (vm.online) {
        true -> "Connected."
        false -> "Offline. Start the server on that device."
        null -> "Checking…"
    }
    RowShell("Server address", status) {
        Box(
            Modifier
                .widthIn(min = 150.dp, max = 190.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(palette.bg)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 13.6.sp, fontFamily = AppFont),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.saveBaseUrl(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val SAMPLE_ID = "0000000000000000000"

private fun previewName(style: String, kind: String): String {
    val title = "Sample Video Title"
    val author = "Creator Name"
    val suffix = when (kind) {
        "image" -> "image 1"
        "audio" -> "1080p h264 audio"
        else -> "1080p h264"
    }
    return when (style) {
        "title" -> "$title ($suffix)"
        "author_title" -> "$author - $title ($suffix)"
        else -> when (kind) {
            "image" -> "example_${SAMPLE_ID}_p1"
            "audio" -> "example_${SAMPLE_ID}_1080p_h264_audio"
            else -> "example_${SAMPLE_ID}_1080p_h264"
        }
    }
}

@Composable
private fun FilenamePreview(style: String) {
    val palette = LocalPalette.current
    var open by rememberSaveable { mutableStateOf(false) }
    val chevron by animateFloatAsState(if (open) 180f else 0f, tween(180), label = "preview-chevron")
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(palette.bg)
            .animateContentSize(tween(200)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            T(
                "FILENAME PREVIEW",
                size = 10.88.sp,
                color = palette.muted,
                weight = FontWeight.SemiBold,
                letterSpacing = 0.65.sp,
                modifier = Modifier.weight(1f),
            )
            VIconView(VIcons.ChevronDown, 10.dp, palette.muted, Modifier.rotate(chevron))
        }
        if (open) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                listOf("Video" to ".mp4", "Image" to ".jpg", "Audio" to ".mp3").forEachIndexed { index, (label, ext) ->
                    Row(Modifier.padding(top = if (index == 0) 0.dp else 5.dp), verticalAlignment = Alignment.Top) {
                        T(
                            label.uppercase(),
                            size = 10.56.sp,
                            color = palette.muted,
                            weight = FontWeight.SemiBold,
                            letterSpacing = 0.42.sp,
                            modifier = Modifier.width(42.dp).padding(top = 3.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        androidx.compose.material3.Text(
                            text = buildAnnotatedString {
                                append(previewName(style, label.lowercase()))
                                withStyle(SpanStyle(color = Accent, fontWeight = FontWeight.SemiBold)) { append(ext) }
                            },
                            color = palette.text,
                            fontSize = 12.48.sp,
                            lineHeight = 18.7.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** yt-dlp's own settings: which build the update button installs. */
@Composable
private fun YtdlpSettingsDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(palette.card)
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp, bottom = 16.dp),
        ) {
            T("yt-dlp settings", size = 18.sp, weight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
            T(
                "Update channel",
                size = 12.48.sp,
                color = palette.muted,
                weight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
            )
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CHANNEL_CHOICES.forEach { choice ->
                    val selected = vm.ytdlpChannel == choice.value
                    val shape = RoundedCornerShape(12.dp)
                    Row(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(shape)
                            .background(if (selected) Accent.copy(alpha = 0.10f) else palette.bg)
                            .border(1.5.dp, if (selected) Accent else Color.Transparent, shape)
                            .clickable(role = Role.RadioButton) { if (!selected) vm.updateYtdlpChannel(choice.value) }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        T(
                            choice.label,
                            size = 14.4.sp,
                            color = if (selected) Accent else palette.text,
                            weight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                        if (selected) VIconView(VIcons.Check, 14.dp, Accent)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            PillButton(
                text = "Done",
                icon = null,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                height = 40.dp,
            )
        }
    }
}

/** Import / replace / remove sign-in cookies (a cookies.txt exported from a browser). */
@Composable
private fun CookiesRow(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCookies(uri)
    }
    val info = vm.cookies
    val present = info?.present == true
    val status = when {
        vm.cookiesBusy -> "Working…"
        info == null -> if (vm.online == true) "Checking…" else "Start the server to set this up."
        info.present -> "${info.count} cookies for ${info.domains.take(3).joinToString(", ")}" +
            if (info.domains.size > 3) " and more" else ""
        else -> "For sites that ask you to log in, like Instagram, and age-restricted YouTube videos."
    }
    RowShell("Sign-in cookies", status, icon = VIcons.Lock) {
        val enabled = !vm.cookiesBusy && vm.online == true && info != null
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (present) SmallButton(text = "Remove", enabled = enabled, tonal = true, minWidth = 68.dp, onClick = { vm.removeCookies() })
            SmallButton(
                text = if (present) "Replace" else "Import",
                enabled = enabled,
                minWidth = 68.dp,
                // Many file managers label a cookies.txt as plain text or as unknown, so every type is offered.
                onClick = { picker.launch(arrayOf("*/*")) },
            )
        }
    }
}

/** A square button with just an icon, styled like the tonal [SmallButton]. */
@Composable
private fun IconSquareButton(icon: VIcon, label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (pressed) palette.line else palette.sep)
            .clickable(interactionSource = source, indication = null, onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { VIconView(icon, 18.dp, palette.text) }
}

@Composable
internal fun SmallButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tonal: Boolean = false,
    minWidth: androidx.compose.ui.unit.Dp = 84.dp,
) {
    val palette = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val shape = RoundedCornerShape(10.dp)
    val background = when {
        tonal -> if (pressed) palette.line else palette.sep
        else -> if (pressed) AccentDark else Accent
    }
    Box(
        Modifier
            .widthIn(min = minWidth)
            .height(34.dp)
            .alpha(if (enabled) 1f else 0.6f)
            .clip(shape)
            .background(background)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        T(text, size = 12.8.sp, color = if (tonal) palette.text else AccentOn, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Light / Dark / System as one segmented control, like the mockup. */
@Composable
private fun ThemeSegments(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(Choice("light", "Light"), Choice("dark", "Dark"), Choice("system", "System"))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        T("Theme", size = 14.4.sp, lineHeight = 18.7.sp)
        Segments(options, selected, onSelect, Modifier.padding(top = 12.dp))
    }
}

/** A row of 2-3 short options as one pill-shaped control. */
@Composable
private fun Segments(options: List<Choice>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.soft)
            .padding(3.dp),
    ) {
        options.forEach { option ->
            val on = option.value == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) palette.card else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(option.value) },
                contentAlignment = Alignment.Center,
            ) {
                T(option.label, size = 14.sp, color = if (on) palette.text else palette.muted, weight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
