package app.mediadl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/**
 * A small player for listening before saving: play / pause, a seek bar and the time.
 * Anything with sound works as the source, so a video's file is fine (its picture is switched off).
 */
@Composable
fun AudioPreview(url: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var playing by remember(url) { mutableStateOf(false) }
    var buffering by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var position by remember(url) { mutableLongStateOf(0L) }
    var duration by remember(url) { mutableLongStateOf(0L) }

    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.pause()
                }
            }
            override fun onPlayerError(error: PlaybackException) { failed = true; playing = false }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            delay(250)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(palette.soft)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Accent)
                .clickable(
                    onClickLabel = if (playing) "Pause preview" else "Play preview",
                    role = Role.Button,
                    enabled = !failed,
                ) { if (playing) player.pause() else player.play() },
            contentAlignment = Alignment.Center,
        ) {
            if (buffering && playing) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp), color = AccentOn, strokeWidth = 2.dp,
                )
            } else {
                VIconView(if (playing) VIcons.Pause else VIcons.Play, 16.dp, AccentOn)
            }
        }
        if (failed) {
            T("Can’t play a preview of this one.", size = 12.5.sp, color = palette.muted, modifier = Modifier.weight(1f), maxLines = 1)
        } else {
            var barWidth by remember { mutableLongStateOf(0L) }
            fun seek(x: Float) {
                if (duration > 0 && barWidth > 0) {
                    player.seekTo((duration * (x / barWidth).coerceIn(0f, 1f)).toLong())
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .onSizeChanged { barWidth = it.width.toLong() }
                    .pointerInput(duration) { detectTapGestures { seek(it.x) } }
                    .pointerInput(duration) { detectHorizontalDragGestures { change, _ -> change.consume(); seek(change.position.x) } },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(palette.line))
                val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth(fraction).height(4.dp).clip(CircleShape).background(Accent))
            }
            T(
                if (duration > 0) "${clock(position)} / ${clock(duration)}" else clock(position),
                size = 12.sp, color = palette.muted, weight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.padding(end = 6.dp).width(if (duration > 0) 78.dp else 36.dp),
            )
        }
    }
}
