package app.mediadl

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val MUSIC_SITES = setOf("spotify", "soundcloud")
private val IMAGE_SITES = setOf("danbooru", "gelbooru", "safebooru", "yandere", "imgur", "pixiv")

/** The order sites are listed in, within their group; a service that isn't here goes last. */
private val SITE_ORDER = listOf(
    "youtube", "tiktok", "x", "instagram", "reddit", "pinterest", "bilibili", "rednote",
    "spotify", "soundcloud",
    "danbooru", "gelbooru", "safebooru", "yandere", "imgur", "pixiv",
)

/** The "Supported sites" subpage of Settings: what each site gives you, grouped by kind. */
@Composable
fun ServicesList(vm: AppViewModel) {
    val palette = LocalPalette.current
    if (vm.platforms.isEmpty()) {
        T(
            if (vm.online == false) "Start the server to see the supported sites." else "Loading…",
            modifier = Modifier.padding(start = 6.dp),
            size = 13.44.sp,
            color = palette.muted,
        )
        return
    }
    // One row open at a time; tapping it again closes it.
    var open by remember { mutableStateOf<String?>(null) }
    val ordered = vm.platforms.sortedBy { SITE_ORDER.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    val groups = listOf(
        "Video & social" to ordered.filter { it.name !in MUSIC_SITES && it.name !in IMAGE_SITES },
        "Music" to ordered.filter { it.name in MUSIC_SITES },
        "Image sites" to ordered.filter { it.name in IMAGE_SITES },
    ).filter { it.second.isNotEmpty() }

    T(
        "Paste a link from any of these, or share it to RibbT.",
        modifier = Modifier.padding(start = 6.dp, end = 6.dp),
        size = 13.44.sp,
        color = palette.muted,
        lineHeight = 19.sp,
    )
    groups.forEach { (title, platforms) ->
        Column {
            SectionTitle(title)
            CardBox(Modifier.fillMaxWidth()) {
                Column {
                    platforms.forEachIndexed { index, platform ->
                        if (index > 0) Separator()
                        ServiceRow(platform, expanded = open == platform.name) {
                            open = if (open == platform.name) null else platform.name
                        }
                    }
                }
            }
        }
    }
    T(
        "Listing a service doesn’t imply any affiliation or endorsement.",
        modifier = Modifier.padding(horizontal = 6.dp),
        size = 12.16.sp,
        color = palette.muted,
        lineHeight = 18.2.sp,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServiceRow(platform: Platform, expanded: Boolean, onToggle: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val logo = context.resources.getIdentifier("logo_${platform.name}", "drawable", context.packageName)
    val shortHosts = platform.display.filterNot { it.startsWith("regional") }.take(2).joinToString(" · ")
    val abilities = buildList {
        if ("Video" in platform.features) add("Video" to VIcons.Video)
        if ("Photos" in platform.features) add("Photos" to VIcons.Photo)
        if ("Audio" in platform.features) add("Audio" to VIcons.Audio)
    }
    val turn by animateFloatAsState(if (expanded) 180f else 0f, tween(220), label = "chevron")

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide details" else "Show details", role = Role.Button, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .animateContentSize(),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Accent.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                if (logo != 0) {
                    Image(
                        painter = painterResource(logo),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(44.dp),
                    )
                } else {
                    T(platform.label.take(1).uppercase(), size = 16.8.sp, color = Accent, weight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                T(platform.label, size = 15.2.sp, weight = FontWeight.SemiBold, lineHeight = 21.3.sp)
                T(shortHosts, size = 12.16.sp, color = palette.muted, lineHeight = 16.4.sp, maxLines = 1)
                if (platform.note.isNotBlank()) {
                    Row(
                        Modifier
                            .padding(top = 6.dp)
                            .clip(CircleShape)
                            .background(palette.soft)
                            .padding(start = 7.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        VIconView(VIcons.Error, 13.dp, palette.muted)
                        T(platform.note, size = 11.8.sp, color = palette.muted, lineHeight = 15.sp, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
            if (expanded) {
                VIconView(VIcons.ChevronDown, 12.dp, palette.muted, Modifier.graphicsLayer { rotationZ = turn })
            } else {
                Row(
                    Modifier.semantics { contentDescription = abilities.joinToString(", ") { it.first } },
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    abilities.forEach { (_, icon) -> VIconView(icon, 16.dp, palette.muted) }
                }
            }
        }
        if (expanded) {
            Column(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.bg)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                T("Supported links", size = 11.5.sp, color = palette.muted, weight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    platform.display.forEach { Tag(it) }
                }
                if (platform.features.isNotEmpty()) {
                    T("Features", size = 11.5.sp, color = palette.muted, weight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        platform.features.forEach { Tag(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    val palette = LocalPalette.current
    T(
        text,
        size = 12.sp,
        lineHeight = 16.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(palette.card)
            .border(1.dp, palette.line, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
