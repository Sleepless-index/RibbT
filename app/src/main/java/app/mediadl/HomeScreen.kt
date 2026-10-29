package app.mediadl

import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(focused, vm.platforms.size, vm.tab, vm.input.isBlank()) {
        if (focused && vm.tab == Tab.Home) vm.checkClipboard(readClipboard(context))
    }

    val post = vm.post
    val playlist = vm.playlist
    val ready = post != null || playlist != null
    val scroll = rememberScrollState()
    LaunchedEffect(post, playlist) { if (post == null && playlist == null) scroll.scrollTo(0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        var contentHeight by remember { mutableIntStateOf(0) }
        val target = if (ready) {
            0.dp
        } else {
            with(density) { maxOf(0f, (maxHeight.toPx() - 52.dp.toPx() - contentHeight) / 2f).toDp() }
        }
        val top by animateDpAsState(target, tween(550, easing = WebEase), label = "slide")

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(top))
            Column(
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .onSizeChanged { contentHeight = it.height },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (vm.online == false && vm.everOnline && !vm.starting) {
                    OfflinePill(vm)
                    Spacer(Modifier.height(10.dp))
                }
                // The offline card sits above the input; "Starting RibbT" is pinned to the top of the screen instead.
                if (vm.online == false && !vm.everOnline && !vm.starting && post == null) {
                    ServerStatusCard(vm)
                    Spacer(Modifier.height(14.dp))
                }
                if (ready) LinkPill(vm) else LinkRow(vm)
                vm.error?.takeIf { it.inline }?.let { info -> ErrorBox(info) }
                if (post == null && playlist == null && vm.loading) {
                    Spacer(Modifier.height(16.dp))
                    ParseSkeleton()
                }
                if (ready && vm.loading) {
                    // Opening a playlist from a post, or parsing again: show progress while the old result stays.
                    Spacer(Modifier.height(16.dp))
                    ParseSkeleton()
                }
                if (post != null) {
                    Spacer(Modifier.height(16.dp))
                    Results(vm, post)
                } else if (playlist != null) {
                    Spacer(Modifier.height(16.dp))
                    PlaylistCard(vm, playlist, Modifier.appear(0))
                }
            }
        }
        if (vm.starting && post == null) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) { ServerStatusCard(vm) }
        }
    }
}

@Composable
private fun Results(vm: AppViewModel, post: Post) {
    val layout = MediaLayout.from(vm.layout)
    val photos = post.items.filter { isPhoto(it) }
    val useCarousel = layout == MediaLayout.Carousel && photos.size >= 2
    // On a video post the thumbnail is the "Cover" tab under the first video, not a card of its own.
    val cover = post.items.firstOrNull { it.type == "image" && it.label == "Thumbnail" }
        ?.takeIf { post.items.any { item -> item.type == "video" } }
    val base = if (useCarousel) post.items.filter { !isPhoto(it) } else post.items
    val singles = base.filter { it !== cover }
    val firstVideo = singles.firstOrNull { it.type == "video" }
    val hasInfo = post.author.isNotBlank() || post.title.isNotBlank() || !post.avatar.isNullOrBlank()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        var step = 0
        if (hasInfo) {
            InfoCard(vm, post, Modifier.appear(0))
            step++
        }
        post.playlist?.let { link ->
            PlaylistLink(vm, link, Modifier.appear(0))
            step++
        }
        if (layout == MediaLayout.Masonry) {
            MasonryWall(vm, post, singles, cover)
        } else {
            singles.forEach { media ->
                MediaCard(
                    vm, post, media, wide = false,
                    modifier = Modifier.appear(minOf(step, 8) * 35),
                    cover = if (media === firstVideo) cover else null,
                )
                step++
            }
            if (useCarousel) {
                CarouselCard(vm, post, photos, Modifier.appear(minOf(step, 8) * 35))
            }
        }
        if (post.subtitles.isNotEmpty()) {
            SubtitlesCard(vm, post, Modifier.appear(minOf(step + 1, 8) * 35))
        }
    }
}

@Composable
private fun LinkRow(vm: AppViewModel) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val filled = vm.input.isNotBlank()
    var focused by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Same pill as the one shown once a link is read (link icon, text, clear), minus the retry button.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .drawBehind {
                    if (focused) {
                        val grow = 3.dp.toPx()
                        drawRoundRect(
                            AccentRing,
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + grow * 2, size.height + grow * 2),
                            cornerRadius = CornerRadius(size.height / 2f + grow),
                        )
                    }
                }
                .clip(CircleShape)
                .background(palette.card)
                .border(1.dp, if (focused) Accent else palette.line, CircleShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            VIconView(VIcons.Link, 20.dp, palette.muted, Modifier.padding(start = 16.dp))
            BasicTextField(
                value = vm.input,
                onValueChange = { vm.input = it; vm.error = null },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 15.sp, fontFamily = AppFont),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.parse() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 46.dp, end = 58.dp)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (!filled && !focused) {
                            T("Paste a link", color = palette.muted, size = 15.sp, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
            PasteClearButton(
                filled = filled,
                onClick = { if (filled) vm.reset() else vm.pasteFromClipboard(readClipboard(context)) },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            )
        }
        if (filled) {
            PillButton(
                text = "Parse link",
                icon = VIcons.Play,
                onClick = { vm.parse() },
                modifier = Modifier.fillMaxWidth(),
                busy = vm.loading,
                height = 46.dp,
                spinner = vm.loading,
            )
        }
    }
}

/** One 38dp button that is "paste" while the field is empty and "clear" once it has text. Pressing only scales it. */
@Composable
private fun PasteClearButton(filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120, easing = FastOutSlowInEasing), label = "press")
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .size(38.dp)
            .graphicsLayer { scaleX = press; scaleY = press }
            .clip(shape)
            .background(if (pressed) palette.soft else Color.Transparent)
            .clickable(
                interactionSource = source,
                indication = null,
                onClickLabel = if (filled) "Clear link" else "Paste link",
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        SwapIcon(VIcons.Paste, VIcons.Clear, filled, 18.dp, palette.muted)
    }
}

/** Shown when the server drops after it had been working; the full card is only for a cold start. */
@Composable
private fun OfflinePill(vm: AppViewModel) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .clip(CircleShape)
            .background(palette.errBg)
            .clickable { vm.refresh() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        VIconView(VIcons.Cloud, 16.dp, palette.err)
        T("Offline", size = 13.sp, color = palette.err, weight = FontWeight.SemiBold)
        T("Retry", size = 13.sp, color = Accent, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun ErrorBox(info: ErrorInfo) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(palette.errBg)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        VIconView(VIcons.Error, 18.dp, palette.err, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f)) {
            T(info.title, size = 14.4.sp, color = palette.err, weight = FontWeight.SemiBold)
            if (!info.hint.isNullOrBlank()) {
                T(info.hint, size = 13.44.sp, color = palette.err.copy(alpha = 0.9f), modifier = Modifier.padding(top = 3.dp), lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun InfoCard(vm: AppViewModel, post: Post, modifier: Modifier) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val hasAuthor = post.author.isNotBlank()
    val avatar = vm.absolute(post.avatar)
    var avatarFailed by remember(avatar) { mutableStateOf(false) }
    Column(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 6.dp),
    ) {
        if (hasAuthor || avatar != null) {
            Row(
                Modifier.padding(bottom = if (post.title.isNotBlank()) 12.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp)) {
                    CircleLetter(post.author, 40.dp)
                    if (avatar != null && !avatarFailed) {
                        AsyncImage(
                            model = avatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onError = { avatarFailed = true },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(palette.line),
                        )
                    }
                }
                Column(Modifier.padding(start = 10.dp)) {
                    if (hasAuthor) T(post.author, size = 14.4.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    val service = vm.platforms.firstOrNull { it.name == post.platform }?.label
                    val logo = remember(post.platform) {
                        context.resources.getIdentifier("logo_${post.platform}", "drawable", context.packageName)
                    }
                    Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (logo != 0) {
                            Image(
                                painter = painterResource(logo),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.padding(end = 6.dp).size(16.dp).clip(RoundedCornerShape(5.dp)),
                            )
                        }
                        T(
                            listOfNotNull(service, when (post.kind) { "video" -> "Video"; "audio" -> "Audio"; else -> "Photos" }).joinToString(" · "),
                            size = 11.52.sp,
                            color = palette.muted,
                        )
                    }
                }
            }
        }
        if (post.title.isNotBlank()) {
            T(
                post.title,
                size = 22.sp,
                weight = FontWeight.Bold,
                maxLines = 3,
                lineHeight = 27.sp,
                fontFamily = DisplayFont,
                letterSpacing = (-0.4).sp,
            )
        }
    }
}

/** Once a link has been read, the field shrinks to a pill showing it, with a clear button. */
@Composable
private fun LinkPill(vm: AppViewModel) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(palette.card)
            .border(1.dp, palette.line, CircleShape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VIconView(VIcons.Link, 20.dp, palette.muted)
        T(
            vm.input.ifBlank { vm.post?.link.orEmpty() }.removePrefix("https://").removePrefix("http://").removePrefix("www."),
            size = 15.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Parse this link again", role = Role.Button) {
                    vm.parse(vm.input.ifBlank { vm.post?.link.orEmpty() })
                },
            contentAlignment = Alignment.Center,
        ) { VIconView(VIcons.Refresh, 18.dp, palette.muted) }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = "Clear link", role = Role.Button) { vm.reset() },
            contentAlignment = Alignment.Center,
        ) { VIconView(VIcons.Clear, 18.dp, palette.muted) }
    }
}
