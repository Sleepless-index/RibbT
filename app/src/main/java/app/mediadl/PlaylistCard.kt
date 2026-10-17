package app.mediadl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

private const val PLAYLIST_LIMIT = 100

@Composable
fun PlaylistCard(vm: AppViewModel, list: Playlist, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val selected = remember(list.link) { list.entries.map { it.url }.toMutableStateList() }
    val chosen = list.entries.filter { it.url in selected }
    val busy = vm.downloads["playlist:" + list.link] is DlState.Busy
    val allSelected = selected.size == list.entries.size
    Column(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 2.dp)) {
            T(list.title, size = 16.8.sp, weight = FontWeight.SemiBold, maxLines = 2)
            val parts = listOfNotNull(
                list.author.takeIf { it.isNotBlank() },
                if (list.total > list.entries.size) "${list.entries.size} of ${list.total} videos" else "${list.entries.size} videos",
            )
            T(parts.joinToString(" · "), size = 12.sp, color = palette.muted, modifier = Modifier.padding(top = 2.dp))
        }
        // Save the whole list as video files or as audio only.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.sep)
                .padding(3.dp),
        ) {
            listOf(false to "Video" to VIcons.Video, true to "Audio" to VIcons.Audio).forEach { (choice, icon) ->
                val (audio, label) = choice
                val on = vm.playlistAudio == audio
                Row(
                    Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (on) palette.card else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.RadioButton) { vm.updatePlaylistAudio(audio) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    VIconView(icon, 16.dp, if (on) Accent else palette.muted)
                    Spacer(Modifier.width(6.dp))
                    T(label, size = 13.sp, weight = FontWeight.SemiBold, color = if (on) palette.text else palette.muted)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillButton(
                if (allSelected) "Select none" else "Select all",
                null,
                {
                    if (allSelected) {
                        selected.clear()
                    } else {
                        selected.clear()
                        selected.addAll(list.entries.map { it.url })
                    }
                },
                Modifier.weight(1f),
                style = PillStyle.Tonal,
            )
            PillButton(
                when {
                    busy -> "Saving…"
                    chosen.isEmpty() -> if (vm.playlistAudio) "Pick tracks" else "Pick videos"
                    chosen.size == 1 -> if (vm.playlistAudio) "Save 1 track" else "Save 1 video"
                    else -> if (vm.playlistAudio) "Save ${chosen.size} tracks" else "Save ${chosen.size} videos"
                },
                VIcons.Download,
                { vm.downloadPlaylist(list, chosen, vm.playlistAudio) },
                Modifier.weight(1.4f),
                busy = busy || chosen.isEmpty(),
            )
        }
        Column(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(palette.card)
                .border(1.dp, palette.line, RoundedCornerShape(14.dp)),
        ) {
            list.entries.forEachIndexed { index, entry ->
                val on = entry.url in selected
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.sep))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Checkbox) {
                            if (on) selected.remove(entry.url) else selected.add(entry.url)
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) Accent else palette.sep),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) VIconView(VIcons.Check, 13.dp, AccentOn)
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier
                            .width(80.dp)
                            .height(45.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.line),
                    ) {
                        val thumb = vm.absolute(entry.thumb)
                        if (thumb != null) {
                            AsyncImage(
                                model = thumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(width = 80.dp, height = 45.dp),
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        T(entry.title.ifBlank { "Untitled" }, size = 13.sp, weight = FontWeight.Medium, maxLines = 2)
                        val meta = listOf(entry.author, entry.duration).filter { it.isNotBlank() }.joinToString(" · ")
                        if (meta.isNotEmpty()) T(meta, size = 11.5.sp, color = palette.muted, maxLines = 1, modifier = Modifier.padding(top = 1.dp))
                    }
                }
            }
        }
        if (list.total > PLAYLIST_LIMIT && list.entries.size >= PLAYLIST_LIMIT) {
            T(
                "Showing the first $PLAYLIST_LIMIT videos.",
                size = 11.5.sp, color = palette.muted, modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
fun PlaylistLink(vm: AppViewModel, link: String, modifier: Modifier = Modifier) {
    PillButton(
        "View playlist",
        VIcons.Services,
        { vm.openPlaylist(link) },
        modifier,
        style = PillStyle.Tonal,
    )
}

@Composable
fun SubtitlesCard(vm: AppViewModel, post: Post, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(palette.card)
            .border(1.dp, palette.line, shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        T("Subtitles", size = 13.44.sp, weight = FontWeight.SemiBold)
        post.subtitles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { track ->
                    val state = vm.downloads[track.spec.url]
                    val label = if (track.auto) "${track.label} (auto)" else track.label
                    when (state) {
                        is DlState.Busy -> PillButton(label, VIcons.Download, {}, Modifier.weight(1f), style = PillStyle.Tonal, busy = true)
                        is DlState.Done -> PillButton(label, VIcons.Check, {}, Modifier.weight(1f), style = PillStyle.Done)
                        else -> PillButton(label, VIcons.Download, { vm.downloadSubtitle(post, track) }, Modifier.weight(1f), style = PillStyle.Tonal)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
