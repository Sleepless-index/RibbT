package app.mediadl

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.ui.semantics.Role

import androidx.compose.runtime.saveable.rememberSaveable

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import kotlin.math.roundToInt

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.animation.core.animate
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import kotlinx.coroutines.delay

fun isPhoto(item: MediaItem): Boolean = item.type == "image" && item.label.startsWith("Photo")

/**
 * A card always spans the full width it is given. The picture area follows the media's shape, but never
 * gets taller than about two thirds of the screen: a taller picture is shown whole over a blurred copy of
 * itself rather than making the card shrink or scroll forever.
 */
@Composable
private fun CardFrame(
    ratio: Float,
    modifier: Modifier = Modifier,
    stage: @Composable BoxScope.() -> Unit,
    below: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val screenHeight = LocalConfiguration.current.screenHeightDp
    val limit = minOf(screenHeight * 0.65f, 540f).dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val floor = (maxWidth / limit).coerceIn(0.3f, 1f)
        val stageRatio = ratio.coerceIn(floor, 2.4f)
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(palette.card),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(stageRatio), content = stage)
            Column(Modifier.fillMaxWidth().padding(12.dp)) { below() }
        }
    }
}

@Composable
private fun ImageStage(url: String?, description: String, onClick: (() -> Unit)? = null, onRatio: (Float) -> Unit) {
    var loading by remember(url) { mutableStateOf(url != null) }
    var imageRatio by remember(url) { mutableFloatStateOf(0f) }
    val fade by animateFloatAsState(if (loading) 0f else 1f, tween(300), label = "image-fade")
    val tap = remember { MutableInteractionSource() }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(if (onClick != null) Modifier.clickable(interactionSource = tap, indication = null, onClick = onClick) else Modifier),
    ) {
        if (loading) Box(Modifier.fillMaxSize().shimmer())
        val context = LocalContext.current
        val request = remember(url) {
            ImageRequest.Builder(context).data(url).size(Size.ORIGINAL).build()
        }
        // The image is always shown whole. When its shape differs from the stage, the leftover bands are
        // filled with a blurred, dimmed copy of the same picture instead of flat bars.
        val stageRatio = maxWidth / maxHeight
        val mismatched = imageRatio > 0f && kotlin.math.abs(imageRatio - stageRatio) / stageRatio > 0.03f
        if (mismatched) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(28.dp).alpha(0.5f * fade),
            )
        }
        AsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().alpha(fade),
            onSuccess = { state ->
                loading = false
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) {
                    imageRatio = size.width / size.height
                    onRatio(imageRatio)
                }
            },
            onError = { loading = false },
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Full-screen playback in a dialog; landscape videos turn the screen sideways while it is open. */
@OptIn(UnstableApi::class)
@Composable
private fun FullscreenPlayer(player: Player, landscape: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(landscape) {
        val activity = context.findActivity()
        if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (landscape) activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (window != null) {
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).also { view ->
                        view.player = player
                        view.setFullscreenButtonClickListener { onExit() }
                    }
                },
            )
        }
    }
}

/**
 * Inline video player (media3 ExoPlayer). The poster and a play button show until playback starts;
 * after that the player's own controls (play/pause, seek bar, time) sit inside the card like the
 * browser's video element does on the web page.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoStage(
    play: String?,
    audio: String?,
    fallback: String?,
    poster: String?,
    onRatio: (Float) -> Unit,
    onFail: () -> Unit,
    /** Drawn over the poster until playback starts (duration and size chips). */
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    if (play == null) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (poster != null) ImageStage(poster, "Video preview", onRatio = onRatio)
            overlay()
        }
        return
    }
    val context = LocalContext.current
    var started by remember(play) { mutableStateOf(false) }
    var usingFallback by remember(play) { mutableStateOf(false) }
    var fullscreen by remember(play) { mutableStateOf(false) }
    var videoRatio by remember(play) { mutableFloatStateOf(0f) }
    val url = if (usingFallback && fallback != null) fallback else play

    val player = remember(url, audio) {
        ExoPlayer.Builder(context).build().apply {
            if (audio != null && !usingFallback) {
                // The picture and the sound come as two streams; play them together, no conversion.
                val factory = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory())
                setMediaSource(
                    MergingMediaSource(
                        factory.createMediaSource(androidx.media3.common.MediaItem.fromUri(url)),
                        factory.createMediaSource(androidx.media3.common.MediaItem.fromUri(audio)),
                    ),
                )
            } else {
                setMediaItem(androidx.media3.common.MediaItem.fromUri(url))
            }
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoRatio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    onRatio(videoRatio)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!usingFallback && fallback != null && fallback != play) {
                    usingFallback = true
                } else {
                    started = false
                    onFail()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).also { view ->
                    view.player = player
                    view.setFullscreenButtonClickListener { fullscreen = true }
                }
            },
            update = { it.player = if (fullscreen) null else player },
        )
        if (fullscreen) {
            FullscreenPlayer(player, landscape = videoRatio >= 1f, onExit = { fullscreen = false })
        }
        if (!started) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                if (poster != null) ImageStage(poster, "Video preview", onRatio = onRatio)
                Box(
                    Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                        .border(1.dp, Color(0x33FFFFFF), CircleShape)
                        .clickable {
                            started = true
                            player.play()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    VIconView(VIcons.Play, 24.dp, Color.White)
                }
                overlay()
            }
        }
    }
}

/**
 * Download / Audio buttons under a card. They share the row equally. In the masonry layout on a
 * phone the web page lets them wrap (each needs 84px), so a narrow card stacks them full width.
 */
@Composable
private fun Actions(wrapWhenNarrow: Boolean, buttons: List<@Composable (Modifier) -> Unit>) {
    val screenWidth = LocalConfiguration.current.screenWidthDp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val needed = 84.dp * buttons.size + 6.dp * (buttons.size - 1)
        val wrap = wrapWhenNarrow && screenWidth < 700 && buttons.size > 1 && maxWidth < needed
        if (wrap) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                buttons.forEach { it(Modifier.fillMaxWidth()) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                buttons.forEach { it(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DownloadButton(
    vm: AppViewModel,
    post: Post,
    spec: DownloadSpec,
    pending: String,
    modifier: Modifier,
) {
    val lower = spec.label.lowercase()
    val icon = if (lower.contains("sound") || lower.contains("audio")) VIcons.Audio else VIcons.Download
    when (vm.downloads[spec.url]) {
        is DlState.Busy -> PillButton(spec.label, icon, {}, modifier, busy = true, spinner = true, height = 46.dp)
        is DlState.Done -> PillButton("Downloaded", VIcons.Check, {}, modifier, style = PillStyle.Done, height = 46.dp)
        else -> PillButton(spec.label, icon, { vm.download(post, spec, pending) }, modifier, height = 46.dp)
    }
}

@Composable
fun MediaCard(
    vm: AppViewModel,
    post: Post,
    media: MediaItem,
    wide: Boolean,
    modifier: Modifier = Modifier,
    onLoaded: () -> Unit = {},
    onRatio: (Float) -> Unit = {},
    /** The post's thumbnail, offered as a "Cover" tab on the first video. */
    cover: MediaItem? = null,
) {
    var ratio by remember { mutableFloatStateOf(0.75f) }
    var viewing by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    val report: (Float) -> Unit = { value ->
        ratio = value
        onRatio(value)
        if (!reported) {
            reported = true
            onLoaded()
        }
    }
    val preview = vm.absolute(media.preview)
    val isVideo = media.type == "video"
    LaunchedEffect(preview) {
        if (preview == null && !reported) {
            reported = true
            onLoaded()
        }
    }
    CardFrame(
        ratio = ratio,
        modifier = modifier,
        stage = {
            if (isVideo) {
                VideoStage(
                    play = vm.absolute(media.play),
                    audio = vm.absolute(media.playAudio),
                    fallback = vm.absolute(media.playAlt),
                    poster = preview,
                    onRatio = report,
                    onFail = {
                        vm.report(
                            ErrorInfo(
                                "Can’t play that video here",
                                "Use Download to save it, then open it from your gallery.",
                            ),
                        )
                    },
                    overlay = {
                        val width = if (reported && media.height > 0) ((media.height * ratio).roundToInt() / 2) * 2 else 0
                        MediaChips(media.duration, if (width > 0) "$width × ${media.height}" else null)
                    },
                )
            } else {
                ImageStage(preview, media.label, onClick = { viewing = true }, onRatio = report)
            }
        },
        below = {
            if (isVideo) {
                VideoControls(vm, post, media, cover)
            } else {
                media.playAudio?.let { listen ->
                    AudioPreview(vm.absolute(listen) ?: listen, Modifier.padding(bottom = 10.dp))
                }
                Actions(
                    wrapWhenNarrow = wide,
                    buttons = media.downloads.mapIndexed { _, spec ->
                        val button: @Composable (Modifier) -> Unit = { m -> DownloadButton(vm, post, spec, "Preparing…", m) }
                        button
                    },
                )
            }
        },
    )
    if (viewing && !isVideo) PhotoViewer(vm, listOf(media), 0) { viewing = false }
}

/** Full-screen photo viewer: swipe between photos, pinch or double-tap to zoom, drag to pan when zoomed. */
@Composable
private fun PhotoViewer(vm: AppViewModel, photos: List<MediaItem>, start: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.size - 1)) { photos.size }
    var zoomed by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { page ->
                ZoomableImage(
                    url = vm.absolute(photos[page].preview),
                    description = photos[page].label,
                    active = page == pager.currentPage,
                    onZoomChange = { zoomed = it },
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                VIconView(VIcons.CloseSmall, 20.dp, Color.White)
            }
            if (photos.size > 1) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x99000000))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    T("${pager.currentPage + 1} / ${photos.size}", size = 12.8.sp, color = Color.White, weight = FontWeight.SemiBold, lineHeight = 17.sp)
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(url: String?, description: String, active: Boolean, onZoomChange: (Boolean) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var imageRatio by remember(url) { mutableFloatStateOf(0f) }
    var loading by remember(url) { mutableStateOf(url != null) }
    val fade by animateFloatAsState(if (loading) 0f else 1f, tween(250), label = "viewer-fade")
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var animation by remember { mutableStateOf<Job?>(null) }
    val request = remember(url) { ImageRequest.Builder(context).data(url).size(Size(4096, 4096)).build() }
    LaunchedEffect(active) {
        if (!active) {
            animation?.cancel()
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale, active) { if (active) onZoomChange(scale > 1.01f) }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // How far the picture may be dragged at a given zoom: its own displayed size, not the whole screen,
                // so a narrow photo never slides off into the black.
                fun limit(zoom: Float, offset: Offset): Offset {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val shownW = if (imageRatio <= 0f) w else minOf(w, h * imageRatio)
                    val shownH = if (imageRatio <= 0f) h else minOf(h, w / imageRatio)
                    val maxX = maxOf(0f, (shownW * zoom - w) / 2f)
                    val maxY = maxOf(0f, (shownH * zoom - h) / 2f)
                    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                }
                detectTapGestures(onDoubleTap = { tap ->
                    animation?.cancel()
                    val from = scale
                    val fromOffset = offset
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val shownW = if (imageRatio <= 0f) w else minOf(w, h * imageRatio)
                    val target = if (from > 1.01f) 1f else maxOf(2.5f, w / shownW).coerceAtMost(5f)
                    // Zoom about the tapped point: the spot under the finger stays under the finger.
                    val focus = tap - Offset(w / 2f, h / 2f)
                    val toOffset = if (target == 1f) Offset.Zero else limit(target, (fromOffset - focus) * (target / from) + focus)
                    animation = scope.launch {
                        animate(0f, 1f, animationSpec = tween(240)) { t, _ ->
                            scale = from + (target - from) * t
                            val next = fromOffset + (toOffset - fromOffset) * t
                            offset = if (next.x.isFinite() && next.y.isFinite()) next else Offset.Zero
                        }
                    }
                })
            }
            .pointerInput(Unit) {
                fun limit(zoom: Float, offset: Offset): Offset {
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val shownW = if (imageRatio <= 0f) w else minOf(w, h * imageRatio)
                    val shownH = if (imageRatio <= 0f) h else minOf(h, w / imageRatio)
                    val maxX = maxOf(0f, (shownW * zoom - w) / 2f)
                    val maxY = maxOf(0f, (shownH * zoom - h) / 2f)
                    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
                }
                // Any two fingers pinch (thumb and finger, or two thumbs); one finger only pans while zoomed in,
                // so swiping between photos still works at 1x.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1.01f) {
                            animation?.cancel()
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val next = (scale * zoom).coerceIn(1f, 6f)
                            // Keep the point between the fingers fixed while scaling, then follow the fingers.
                            val focus = centroid - pan - Offset(size.width / 2f, size.height / 2f)
                            val moved = (offset - focus) * (next / scale) + focus + pan
                            val safe = if (moved.x.isFinite() && moved.y.isFinite()) moved else offset
                            scale = if (next.isFinite()) next else 1f
                            offset = if (scale <= 1.01f) Offset.Zero else limit(scale, safe)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (scale < 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    }
                }
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .alpha(fade)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            onSuccess = { state ->
                loading = false
                val size = state.painter.intrinsicSize
                if (size.width > 0f && size.height > 0f) imageRatio = size.width / size.height
            },
            onError = { loading = false },
        )
    }
}

@Composable
fun CarouselCard(vm: AppViewModel, post: Post, photos: List<MediaItem>, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val pager = rememberPagerState { photos.size }
    var ratio by remember { mutableFloatStateOf(0.75f) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val current = photos[pager.currentPage.coerceIn(0, photos.size - 1)]
    val spec = current.downloads.firstOrNull()
    CardFrame(
        ratio = ratio,
        modifier = modifier,
        stage = {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                ImageStage(vm.absolute(photos[page].preview), photos[page].label, onClick = { viewing = page }) { value ->
                    if (page == 0) ratio = value
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x8C000000))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                T(
                    "${pager.currentPage + 1} / ${photos.size}",
                    size = 11.84.sp,
                    color = Color.White,
                    weight = FontWeight.SemiBold,
                    lineHeight = 16.sp,
                )
            }
        },
        below = {
            if (photos.size <= 9) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                ) {
                    photos.indices.forEach { index ->
                        val on = index == pager.currentPage
                        Box(
                            Modifier
                                .size(if (on) 9.dp else 7.dp)
                                .clip(CircleShape)
                                .background(if (on) Accent else palette.line),
                        )
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // On a narrow card two half-width buttons cut their labels, so they stack instead.
                val stacked = maxWidth < 340.dp
                val allKey = "all:" + post.link
                val label = "Download all (${photos.size})"
                val buttons: @Composable (Modifier) -> Unit = { each ->
                    if (spec != null) DownloadButton(vm, post, spec, "Preparing…", each)
                    when (vm.downloads[allKey]) {
                        is DlState.Busy -> PillButton(label, VIcons.Download, {}, each, busy = true, spinner = true, height = 46.dp)
                        is DlState.Done -> PillButton("Downloaded", VIcons.Check, {}, each, style = PillStyle.Done, height = 46.dp)
                        else -> PillButton(label, VIcons.Download, { vm.downloadAll(post, photos) }, each, height = 46.dp)
                    }
                }
                if (stacked) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) { buttons(Modifier.fillMaxWidth()) }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { buttons(Modifier.weight(1f)) }
                }
            }
        },
    )
    viewing?.let { start -> PhotoViewer(vm, photos, start) { viewing = null } }
}

/**
 * Photos in a tidy grid: two equal tiles per row, every tile the same shape. Tap a tile to open it full
 * screen; each tile has its own Download button.
 */
@Composable
fun PhotoGrid(vm: AppViewModel, post: Post, photos: List<MediaItem>, modifier: Modifier = Modifier) {
    var viewing by remember { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (photos.size == 1) {
            // A lone photo takes both columns, like the other layouts.
            MediaCard(vm, post, photos[0], wide = false)
        } else {
            photos.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { column, media ->
                        GridTile(vm, post, media, Modifier.weight(1f)) { viewing = row * 2 + column }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    viewing?.let { start -> PhotoViewer(vm, photos, start) { viewing = null } }
}

@Composable
private fun GridTile(vm: AppViewModel, post: Post, media: MediaItem, modifier: Modifier, onOpen: () -> Unit) {
    val palette = LocalPalette.current
    val tap = remember { MutableInteractionSource() }
    var loading by remember(media.preview) { mutableStateOf(true) }
    val fade by animateFloatAsState(if (loading) 0f else 1f, tween(300), label = "tile-fade")
    val spec = media.downloads.firstOrNull()
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(palette.card)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clickable(interactionSource = tap, indication = null, onClick = onOpen),
        ) {
            if (loading) Box(Modifier.fillMaxSize().shimmer())
            AsyncImage(
                model = vm.absolute(media.preview),
                contentDescription = media.label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(fade),
                onSuccess = { loading = false },
                onError = { loading = false },
            )
        }
        if (spec != null) {
            Box(Modifier.fillMaxWidth().padding(8.dp)) {
                DownloadButton(vm, post, spec, "Preparing…", Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * Two columns of cards, each placed under the shorter column. A card marked wide (landscape) breaks
 * out of the columns and takes a full-width row; the cards around it keep packing, in order.
 */
@Composable
private fun MasonryLayout(gap: Dp, content: @Composable () -> Unit) {
    Layout(content = content, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val columnWidth = (constraints.maxWidth - gapPx) / 2
        val heights = intArrayOf(0, 0)
        val placed = measurables.map { measurable ->
            if (measurable.layoutId == WIDE_CARD) {
                val placeable = measurable.measure(Constraints.fixedWidth(constraints.maxWidth))
                val y = maxOf(heights[0], heights[1])
                heights[0] = y + placeable.height + gapPx
                heights[1] = heights[0]
                Triple(placeable, 0, y)
            } else {
                val placeable = measurable.measure(Constraints.fixedWidth(columnWidth))
                val column = if (heights[0] <= heights[1]) 0 else 1
                val y = heights[column]
                heights[column] += placeable.height + gapPx
                Triple(placeable, column * (columnWidth + gapPx), y)
            }
        }
        val total = (maxOf(heights[0], heights[1]) - gapPx).coerceAtLeast(0)
        layout(constraints.maxWidth, total) {
            placed.forEach { (placeable, x, y) -> placeable.placeRelative(x, y) }
        }
    }
}

private const val WIDE_CARD = "wide"
private const val WIDE_RATIO = 1.2f

@Composable
fun MasonryWall(vm: AppViewModel, post: Post, items: List<MediaItem>, cover: MediaItem? = null) {
    val firstVideo = items.firstOrNull { it.type == "video" }
    var loaded by remember(post) { mutableIntStateOf(0) }
    var timedOut by remember(post) { mutableStateOf(false) }
    val ratios = remember(post) { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(post) {
        delay(1500)
        timedOut = true
    }
    val ready = timedOut || loaded >= items.size
    Box(Modifier.alpha(if (ready) 1f else 0f)) {
        MasonryLayout(12.dp) {
            items.forEachIndexed { index, media ->
                // A lone card takes the whole width, like the stacked layout; with more it goes back to two columns.
                val wide = items.size == 1 || (ratios[index] ?: 0f) >= WIDE_RATIO
                Box(Modifier.layoutId(if (wide) WIDE_CARD else "column")) {
                    MediaCard(
                        vm, post, media,
                        wide = true,
                        onLoaded = { loaded++ },
                        onRatio = { ratios[index] = it },
                        cover = if (media === firstVideo) cover else null,
                    )
                }
            }
        }
    }
}

/** Duration (bottom left) and picture size (bottom right) over the video poster. */
@Composable
private fun BoxScope.MediaChips(duration: String?, resolution: String?) {
    if (!duration.isNullOrBlank()) MediaChip(duration, Modifier.align(Alignment.BottomStart))
    if (!resolution.isNullOrBlank()) MediaChip(resolution, Modifier.align(Alignment.BottomEnd))
}

@Composable
private fun MediaChip(text: String, modifier: Modifier) {
    Box(
        modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xB3000000))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) { T(text, size = 11.5.sp, color = Color.White, weight = FontWeight.SemiBold, maxLines = 1, lineHeight = 14.sp) }
}

/**
 * Under a video: Video / Audio / Cover tabs, the resolutions the server found, and one Download button.
 * Every file the old Download / Audio / Thumbnail buttons could save is still reachable from here.
 */
@Composable
private fun VideoControls(vm: AppViewModel, post: Post, media: MediaItem, cover: MediaItem?) {
    val palette = LocalPalette.current
    val videoSpec = media.downloads.firstOrNull()
    val audioSpec = media.downloads.getOrNull(1)
    val coverSpec = cover?.downloads?.firstOrNull()
    val tabs = buildList {
        if (videoSpec != null) add("video")
        if (audioSpec != null) add("audio")
        if (coverSpec != null) add("cover")
    }
    var tab by remember(post.link, media.play) { mutableStateOf("video") }
    var quality by remember(post.link, media.play) { mutableIntStateOf(media.height) }
    val current = if (tab in tabs) tab else tabs.firstOrNull() ?: "video"
    val picked = media.variants.firstOrNull { it.height == quality } ?: media.variants.firstOrNull { it.height == media.height }

    // A half-width masonry card is too narrow for labels: tabs go icon-only and the chips scroll.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val narrow = maxWidth < 300.dp
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (tabs.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.soft)
                    .padding(3.dp),
            ) {
                tabs.forEach { key ->
                    val on = key == current
                    Row(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (on) palette.card else Color.Transparent)
                            .clickable(role = Role.Tab) { tab = key },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = if (on) palette.text else palette.muted
                        VIconView(
                            when (key) {
                                "audio" -> VIcons.Audio
                                "cover" -> VIcons.Photo
                                else -> VIcons.Video
                            },
                            17.dp, tint,
                        )
                        if (!narrow) {
                            Spacer(Modifier.width(6.dp))
                            T(
                                when (key) {
                                    "audio" -> "Audio"
                                    "cover" -> "Cover"
                                    else -> "Video"
                                },
                                size = 13.5.sp, color = tint, weight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        if (current == "video" && media.variants.size > 1) {
            val chips: @Composable (Modifier) -> Unit = { m ->
                media.variants.forEach { option ->
                    val on = option.height == (picked?.height ?: quality)
                    Box(
                        m
                            .height(40.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) Accent.copy(alpha = 0.12f) else Color.Transparent)
                            .border(1.5.dp, if (on) Accent else palette.line, RoundedCornerShape(14.dp))
                            .clickable(role = Role.RadioButton) { quality = option.height },
                        contentAlignment = Alignment.Center,
                    ) {
                        T(option.label, size = 13.5.sp, color = if (on) Accent else palette.text, weight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
            if (!narrow && media.variants.size <= 4) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips(Modifier.weight(1f))
                }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips(Modifier.width(76.dp))
                }
            }
        }
        when (current) {
            "audio" -> if (audioSpec != null) {
                // Listen first: the separate sound track when there is one, else the video file's own sound.
                (media.playAudio ?: media.play)?.let { listen ->
                    AudioPreview(vm.absolute(listen) ?: listen)
                }
                ActionButton(
                    vm, post, audioSpec,
                    if (audioSpec.label.contains("sound", ignoreCase = true)) "Download original sound" else "Download audio",
                    VIcons.Audio, "Extracting…",
                )
            }
            "cover" -> if (coverSpec != null) {
                ActionButton(vm, post, coverSpec, "Download cover", VIcons.Download, "Preparing…")
            }
            else -> if (videoSpec != null) {
                val spec = picked?.spec ?: videoSpec
                ActionButton(vm, post, spec, if (spec.label.contains("GIF")) "Download GIF" else "Download", VIcons.Download, "Making GIF…".takeIf { spec.label.contains("GIF") } ?: "Encoding…")
            }
        }
    }
    }
}

@Composable
private fun ActionButton(vm: AppViewModel, post: Post, spec: DownloadSpec, label: String, icon: VIcon, pending: String) {
    val modifier = Modifier.fillMaxWidth()
    when (vm.downloads[spec.url]) {
        is DlState.Busy -> PillButton(label, icon, {}, modifier, busy = true, spinner = true, height = 46.dp)
        is DlState.Done -> PillButton("Downloaded", VIcons.Check, {}, modifier, style = PillStyle.Done, height = 46.dp)
        else -> PillButton(label, icon, { vm.download(post, spec, pending) }, modifier, height = 46.dp)
    }
}
