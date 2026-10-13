package app.mediadl

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
    val ready = post != null
    val scroll = rememberScrollState()
    LaunchedEffect(post) { if (post == null) scroll.scrollTo(0) }

    val mood = when {
        vm.loading -> Mood.Thinking
        vm.error != null -> Mood.Error
        vm.idea -> Mood.Idea
        else -> Mood.Normal
    }

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
                Mascot(mood, vm.hopSignal)
                Spacer(Modifier.height(22.dp))
                if (vm.online == false && vm.everOnline && !vm.starting) {
                    OfflinePill(vm)
                }
                LinkRow(vm)
                vm.error?.takeIf { it.inline }?.let { info -> ErrorBox(info) }
                if (((vm.online == false && !vm.everOnline) || vm.starting) && post == null) {
                    Spacer(Modifier.height(14.dp))
                    OfflineCard(vm)
                }
                if (post == null && vm.loading) {
                    Spacer(Modifier.height(16.dp))
                    ParseSkeleton()
                }
                if (post != null) {
                    Spacer(Modifier.height(16.dp))
                    Results(vm, post)
                }
            }
        }
    }
}

@Composable
private fun Results(vm: AppViewModel, post: Post) {
    val layout = MediaLayout.from(vm.layout)
    val photos = post.items.filter { isPhoto(it) }
    val useCarousel = layout == MediaLayout.Carousel && photos.size >= 2
    val singles = if (useCarousel) post.items.filter { !isPhoto(it) } else post.items
    val hasInfo = post.author.isNotBlank() || post.title.isNotBlank() || !post.avatar.isNullOrBlank()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        var step = 0
        if (hasInfo) {
            InfoCard(vm, post, Modifier.appear(0))
            step++
        }
        if (layout == MediaLayout.Masonry) {
            MasonryWall(vm, post, singles)
        } else {
            singles.forEach { media ->
                MediaCard(vm, post, media, wide = false, modifier = Modifier.appear(minOf(step, 8) * 35))
                step++
            }
            if (useCarousel) {
                CarouselCard(vm, post, photos, Modifier.appear(minOf(step, 8) * 35))
            }
        }
    }
}

@Composable
private fun LinkRow(vm: AppViewModel) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val filled = vm.input.isNotBlank()
    var focused by remember { mutableStateOf(false) }
    val fieldShape = RoundedCornerShape(10.dp)
    val ready = vm.pasteReady && !filled
    val ping by rememberInfiniteTransition(label = "ping").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = WebEase)),
        label = "ping-phase",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .drawBehind {
                    if (focused) {
                        val grow = 3.dp.toPx()
                        drawRoundRect(
                            AccentRing,
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + grow * 2, size.height + grow * 2),
                            cornerRadius = CornerRadius(10.dp.toPx() + grow),
                        )
                    }
                }
                .background(palette.card, fieldShape)
                .border(1.dp, if (focused) Accent else Color.Transparent, fieldShape),
            contentAlignment = Alignment.CenterStart,
        ) {
            VIconView(VIcons.Link, 20.dp, palette.muted, Modifier.padding(start = 14.dp))
            BasicTextField(
                value = vm.input,
                onValueChange = { vm.input = it; vm.error = null },
                singleLine = true,
                textStyle = TextStyle(color = palette.text, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.parse() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 42.dp, end = 14.dp)
                    .onFocusChanged { focused = it.isFocused },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (!filled && !focused) {
                            T("Paste a link", color = palette.muted, size = 16.sp, maxLines = 1)
                        }
                        inner()
                    }
                },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                text = if (filled) "Clear" else "Paste",
                icon = if (filled) VIcons.Clear else VIcons.Paste,
                onClick = { if (filled) vm.reset() else vm.pasteFromClipboard(readClipboard(context)) },
                modifier = Modifier.weight(1f),
                style = PillStyle.Tonal,
                height = 40.dp,
                pulse = if (ready) ping else null,
            )
            PillButton(
                text = "Parse",
                icon = VIcons.Play,
                onClick = { vm.parse() },
                modifier = Modifier.weight(1f),
                busy = vm.loading,
                height = 40.dp,
                spinner = vm.loading,
            )
        }
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
            .clip(RoundedCornerShape(12.dp))
            .background(palette.errBg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
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
private fun OfflineCard(vm: AppViewModel) {
    val palette = LocalPalette.current
    CardBox(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                VIconView(VIcons.Cloud, 22.dp, palette.err)
                T(
                    if (vm.starting) "Starting the built-in server…" else "Server offline",
                    modifier = Modifier.padding(start = 10.dp), weight = FontWeight.SemiBold, size = 15.2.sp,
                )
            }
            T(
                when {
                    vm.starting -> "This takes a few seconds, a little longer right after installing."
                    vm.serverMode == "builtin" ->
                        vm.builtInFailure?.let { "The built-in server couldn’t start: $it" }
                            ?: "The built-in server isn’t answering. Tap Retry, or pick a Custom address in Settings → Server."
                    else -> "Start it in Termux:  cd ~/media-downloader && python app.py"
                },
                modifier = Modifier.padding(top = 8.dp),
                size = 13.44.sp,
                color = palette.muted,
            )
            PillButton(
                text = "Retry",
                icon = VIcons.Refresh,
                onClick = { vm.refresh() },
                modifier = Modifier.padding(top = 12.dp),
                style = PillStyle.Tonal,
            )
        }
    }
}

@Composable
private fun InfoCard(vm: AppViewModel, post: Post, modifier: Modifier) {
    val palette = LocalPalette.current
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
                Box(Modifier.size(34.dp)) {
                    CircleLetter(post.author)
                    if (avatar != null && !avatarFailed) {
                        AsyncImage(
                            model = avatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            onError = { avatarFailed = true },
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(palette.line),
                        )
                    }
                }
                Column(Modifier.padding(start = 10.dp)) {
                    if (hasAuthor) T(post.author, size = 13.76.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    T(
                        if (post.kind == "video") "Video" else "Photos",
                        size = 11.52.sp,
                        color = palette.muted,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }
        if (post.title.isNotBlank()) {
            T(post.title, size = 16.8.sp, weight = FontWeight.SemiBold, maxLines = 3)
        }
    }
}
