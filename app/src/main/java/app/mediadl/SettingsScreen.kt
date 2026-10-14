package app.mediadl

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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

private val THEME_CHOICES = listOf(Choice("system", "Auto"), Choice("light", "Light"), Choice("dark", "Dark"))
private val MOTION_CHOICES = listOf(Choice("system", "Auto"), Choice("on", "Reduced"), Choice("off", "Full"))
private val LAYOUT_CHOICES = MediaLayout.entries.map { Choice(it.key, it.label) }
private val CHANNEL_CHOICES = listOf(Choice("stable", "Stable"), Choice("nightly", "Nightly"))
private val SERVER_CHOICES = listOf(Choice("builtin", "Built-in"), Choice("custom", "Custom address"))
private val SECTION_ORDER = listOf("Video", "Images", "Audio", "X / Twitter", "Saving", "Server")

private const val PAGE_GENERAL = "General"
internal const val PAGE_SERVER = "Server"
private const val PAGE_SAVING = "Saving"
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
        Choice("downloads", "Downloads/RibbT"),
        Choice("gallery", "Gallery folders"),
        Choice("sdcard", "SD card"),
        Choice("folder", if (vm.folderName.isNotBlank()) "Folder: ${vm.folderName}" else "Choose folder…"),
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
    /** Section titles in display order ("Saving" and "Server" always exist, the rest come from the server). */
    val sections: List<String>,
)

private fun groupSettings(server: ServerSettings?): GroupedSettings {
    val choices = server?.choices.orEmpty()
    val rows = server?.schema
        ?.filter { row -> if (row.type == "choice") !choices[row.path].isNullOrEmpty() else true }
        ?.groupBy { it.section }
        ?: emptyMap()
    val sections = SECTION_ORDER.filter { it == PAGE_SAVING || it == PAGE_SERVER || rows.containsKey(it) } +
        rows.keys.filter { it !in SECTION_ORDER }
    return GroupedSettings(choices, rows, sections)
}

private fun iconFor(title: String): VIcon = when (title) {
    PAGE_GENERAL -> VIcons.Settings
    "Video" -> VIcons.Video
    "Images" -> VIcons.Photo
    "Audio" -> VIcons.Audio
    "X / Twitter" -> VIcons.Link
    PAGE_SAVING -> VIcons.Download
    PAGE_SERVER -> VIcons.Server
    PAGE_SERVICES -> VIcons.Services
    else -> VIcons.Settings
}

private fun summaryFor(title: String, rows: List<SettingRow>): String {
    val labels = rows.map { it.label }
    return when (title) {
        PAGE_SAVING -> (listOf("Save location") + labels).distinct().joinToString(", ")
        PAGE_SERVER -> "Connection, yt-dlp, updates"
        else -> labels.distinct().joinToString(", ")
    }
}

@Composable
private fun SettingsHome(vm: AppViewModel) {
    val data = remember(vm.server) { groupSettings(vm.server) }
    ScreenTitle("Settings") { SavedLabel(vm) }

    CardBox(Modifier.fillMaxWidth()) {
        NavRow(iconFor(PAGE_GENERAL), PAGE_GENERAL, "Theme, layout, paste and go") { vm.settingsPage = PAGE_GENERAL }
    }

    Column {
        SectionTitle("Downloads")
        CardBox(Modifier.fillMaxWidth()) {
            Column {
                data.sections.filter { it != PAGE_SERVER }.forEachIndexed { index, title ->
                    if (index > 0) Separator()
                    NavRow(iconFor(title), title, summaryFor(title, data.rows[title].orEmpty())) { vm.settingsPage = title }
                }
            }
        }
    }

    Column {
        SectionTitle("App")
        CardBox(Modifier.fillMaxWidth()) {
            Column {
                NavRow(iconFor(PAGE_SERVER), PAGE_SERVER, summaryFor(PAGE_SERVER, emptyList())) { vm.settingsPage = PAGE_SERVER }
                Separator()
                NavRow(
                    iconFor(PAGE_SERVICES),
                    PAGE_SERVICES,
                    when {
                        vm.platforms.isNotEmpty() -> "${vm.platforms.size} services"
                        vm.online == false -> "Start the server to see them"
                        else -> "Loading…"
                    },
                ) { vm.settingsPage = PAGE_SERVICES }
            }
        }
    }

    if (vm.server == null) OfflineHint(vm)
}

@Composable
private fun SettingsPage(vm: AppViewModel, page: String, saveChoices: List<Choice>, pickFolder: () -> Unit) {
    val data = remember(vm.server) { groupSettings(vm.server) }
    PageHeader(page, onBack = { vm.settingsPage = null }) { SavedLabel(vm) }
    when (page) {
        PAGE_GENERAL -> GeneralCard(vm)
        PAGE_SERVICES -> ServicesList(vm)
        else -> {
            SectionCard(vm, page, data, saveChoices, pickFolder)
            if (vm.server == null) OfflineHint(vm)
        }
    }
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
private fun GeneralCard(vm: AppViewModel) {
    CardBox(Modifier.fillMaxWidth()) {
        Column {
            ChoiceRow("Theme", "Choose a light or dark appearance, or follow your device.", THEME_CHOICES, vm.theme) { vm.updateTheme(it) }
            Separator()
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                T("Media layout", size = 14.4.sp, lineHeight = 18.7.sp)
                T(
                    "How results are shown. Tap the one that looks right.",
                    size = 12.16.sp,
                    color = LocalPalette.current.muted,
                    modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
                    lineHeight = 15.8.sp,
                )
                LayoutPicker(vm.layout, onSelect = { vm.updateLayout(it) })
            }
            Separator()
            ChoiceRow(
                "Reduce motion",
                "Calms the mascot: no floating, hops or ear sway. Auto follows Android’s remove-animations setting.",
                MOTION_CHOICES,
                vm.reduceMotion,
            ) { vm.updateReduceMotion(it) }
            Separator()
            ToggleRow(
                "Paste and go",
                "Automatically fetch a supported link from your clipboard when RibbT opens.",
                vm.pasteAndGo,
            ) { vm.updatePasteAndGo(it) }
        }
    }
}

@Composable
private fun SectionCard(
    vm: AppViewModel,
    title: String,
    data: GroupedSettings,
    saveChoices: List<Choice>,
    pickFolder: () -> Unit,
) {
    val rows = data.rows[title].orEmpty()
    // A server-driven section that isn't there (yet) has nothing to show.
    if (rows.isEmpty() && title != PAGE_SAVING && title != PAGE_SERVER) return
    CardBox(Modifier.fillMaxWidth()) {
        Column {
            var first = true
            if (title == PAGE_SAVING) {
                ChoiceRow("Save to", "Save to internal storage, an SD card, or a folder of your choice.", saveChoices, vm.saveTo) { value ->
                    if (value == "folder") pickFolder() else vm.updateSaveTo(value)
                }
                first = false
            }
            if (title == PAGE_SERVER) {
                ChoiceRow(
                    "Server", "Run the downloader inside RibbT, or connect to Termux or a PC.",
                    SERVER_CHOICES, vm.serverMode,
                ) { vm.updateServerMode(it) }
                Separator()
                if (vm.serverMode == "custom") AddressRow(vm) else BuiltInStatusRow(vm)
                Separator()
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
                ) {
                    // "Check" looks for a newer yt-dlp; once one is found the button turns into "Update".
                    SmallButton(
                        text = when {
                            vm.ytdlpBusy -> "Updating…"
                            vm.ytdlpChecking -> "Checking…"
                            vm.ytdlpAvailable == true -> "Update"
                            else -> "Check"
                        },
                        enabled = !vm.ytdlpBusy && !vm.ytdlpChecking && vm.online == true,
                        onClick = { if (vm.ytdlpAvailable == true) vm.updateYtdlp() else vm.checkYtdlp(manual = true) },
                    )
                }
                Separator()
                ChoiceRow(
                    "Update channel", "Nightly builds receive site fixes sooner but may be less stable.",
                    CHANNEL_CHOICES, vm.ytdlpChannel,
                ) { vm.updateYtdlpChannel(it) }
                Separator()
                CookiesRow(vm)
                first = false
            }
            rows.forEach { row ->
                if (!first) Separator()
                first = false
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
            if (title == PAGE_SAVING) {
                Separator()
                FilenamePreview(vm.settingValue("metadata.filename_style") as? String ?: "classic")
            }
        }
    }
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
            .padding(end = 4.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Back", role = Role.Button, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { VIconView(VIcons.Back, 20.dp, palette.text) }
        Spacer(Modifier.width(6.dp))
        T(title, size = 20.8.sp, weight = FontWeight.Bold, maxLines = 1, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun NavRow(icon: VIcon, title: String, summary: String?, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Accent.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { VIconView(icon, 18.dp, Accent) }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
            T(title, size = 14.4.sp, lineHeight = 18.7.sp)
            if (!summary.isNullOrBlank()) {
                T(summary, size = 12.16.sp, color = palette.muted, maxLines = 1, lineHeight = 15.8.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        VIconView(VIcons.ChevronRight, 7.dp, palette.muted)
    }
}

@Composable
private fun RowShell(
    label: String,
    hint: String?,
    /** Text of a small pill shown right after the hint (e.g. "Up to date"); none when null. */
    badge: String? = null,
    badgeTone: PillTone = PillTone.Good,
    control: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            T(label, size = 14.4.sp, lineHeight = 18.7.sp)
            if (badge != null) {
                Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!hint.isNullOrBlank()) {
                        T(hint, size = 12.16.sp, color = palette.muted, lineHeight = 15.8.sp, modifier = Modifier.weight(1f, fill = false))
                    }
                    StatusPill(badge, badgeTone)
                }
            } else if (!hint.isNullOrBlank()) {
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
                                    .clickable {
                                        expanded = false
                                        if (!isSelected) onSelect(option.value)
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                T(
                                    option.label,
                                    size = 13.6.sp,
                                    color = if (isSelected) Accent else palette.text,
                                    weight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    lineHeight = 19.sp,
                                    modifier = Modifier.weight(1f),
                                )
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
    RowShell(label, hint) { Dropdown(options, selected, onSelect) }
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
private fun ToggleRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    RowShell(label, hint) { WebToggle(checked, onChange) }
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
                textStyle = TextStyle(color = palette.text, fontSize = 13.6.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.saveBaseUrl(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BuiltInStatusRow(vm: AppViewModel) {
    val status = when {
        vm.starting -> "Starting…"
        vm.online == true -> "Running inside the app."
        vm.builtInFailure != null -> "Couldn’t start: ${vm.builtInFailure}"
        vm.online == false -> "Not answering. Tap Retry."
        else -> "Checking…"
    }
    RowShell("Built-in server", status) {
        if (vm.online == false && !vm.starting) SmallButton(text = "Retry", enabled = true, onClick = { vm.refresh() })
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

/** Import / replace / remove the YouTube sign-in cookies (a cookies.txt exported from a browser). */
@Composable
private fun CookiesRow(vm: AppViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCookies(uri)
    }
    val info = vm.cookies
    val present = info?.present == true
    val status = when {
        vm.cookiesBusy -> "Working…"
        info == null -> if (vm.online == true) "Checking…" else "Start the server to import cookies."
        info.present -> "${info.count} cookies for ${info.domains.take(3).joinToString(", ")}" +
            if (info.domains.size > 3) " and more" else ""
        else -> "For age-restricted videos and “confirm you’re not a bot” checks. Import a cookies.txt exported from your browser."
    }
    RowShell("Sign-in cookies", status) {
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

@Composable
private fun SmallButton(
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
        T(text, size = 12.8.sp, color = if (tonal) palette.text else Color.White, weight = FontWeight.SemiBold, maxLines = 1)
    }
}