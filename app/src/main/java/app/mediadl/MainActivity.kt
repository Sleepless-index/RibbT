package app.mediadl

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.shadow

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    // Only asks so notifications (built-in server, finished downloads) can be shown; everything works without it.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AppRoot(vm) }
    }

    override fun onStart() {
        super.onStart()
        Foreground.visible = true
    }

    override fun onStop() {
        Foreground.visible = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { vm.receiveShared(it) }
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val dark = isDarkMode(vm.theme)
    AppTheme(dark) {
        val palette = LocalPalette.current
        val view = LocalView.current
        val activity = LocalContext.current as Activity
        SideEffect {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }

        BackHandler(enabled = vm.tab != Tab.Home) {
            if (vm.tab == Tab.Settings && vm.settingsPage != null) vm.settingsPage = null else vm.tab = Tab.Home
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (vm.online != true) vm.refresh() }

        var queueOpen by rememberSaveable { mutableStateOf(false) }
        val running = vm.queue.count { it.phase == ToastPhase.Active && it.parent == null }
        LaunchedEffect(queueOpen, vm.queueUnread) { if (queueOpen && vm.queueUnread) vm.markQueueSeen() }

        Box(
            Modifier
                .fillMaxSize()
                .background(palette.bg)
                .imePadding(),
        ) {
            // The screens stop above the floating bar, so nothing scrolls underneath it.
            Box(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(bottom = 76.dp),
            ) {
                when (vm.tab) {
                    Tab.Home -> HomeScreen(vm)
                    Tab.History -> HistoryScreen(vm)
                    Tab.Settings -> SettingsScreen(vm)
                }

                ToastHost(
                    toasts = vm.toasts,
                    onDismiss = { vm.dismissToast(it) },
                    onCancel = { vm.cancelDownload(it) },
                    onRetry = { vm.retryDownload(it) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
            }
            NavBar(vm, running, vm.queueUnread, { queueOpen = true }, Modifier.align(Alignment.BottomCenter))
        }
        if (queueOpen) QueueDialog(vm) { queueOpen = false }
    }
}

/** A floating pill with Home, Queue, History and Settings. Queue opens the download list instead of a screen. */
@Composable
private fun NavBar(vm: AppViewModel, running: Int, unread: Boolean, onQueue: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .fillMaxWidth()
            .shadow(12.dp, shape)
            .clip(shape)
            .background(palette.card)
            .border(1.dp, palette.line, shape)
            .height(60.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavItem(Tab.Home, VIcons.Home, vm, Modifier.weight(1f))
        NavSlot("Queue", VIcons.Queue, false, Modifier.weight(1f), badge = running, dot = unread && running == 0, onClick = onQueue)
        NavItem(Tab.History, VIcons.History, vm, Modifier.weight(1f))
        NavItem(Tab.Settings, VIcons.Settings, vm, Modifier.weight(1f))
    }
}

@Composable
private fun NavItem(tab: Tab, icon: VIcon, vm: AppViewModel, modifier: Modifier) {
    NavSlot(tab.label, icon, vm.tab == tab, modifier) {
        if (tab == Tab.Home && vm.tab == Tab.Home) vm.reset()
        if (tab == Tab.Settings && vm.tab == Tab.Settings) vm.settingsPage = null
        if (tab == Tab.History) vm.loadHistory()
        vm.tab = tab
    }
}

@Composable
private fun NavSlot(
    label: String,
    icon: VIcon,
    selected: Boolean,
    modifier: Modifier = Modifier,
    /** Number of running downloads, drawn as a badge on the icon. */
    badge: Int = 0,
    /** A small dot for "something finished that you haven't seen". */
    dot: Boolean = false,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val tint by animateColorAsState(if (selected) Accent else palette.muted, tween(200), label = "nav-tint")
    val highlight by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "nav-highlight")
    // A small pop when the tab is chosen: the icon starts a touch smaller and springs back with a slight overshoot.
    val pop = remember { Animatable(1f) }
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(selected) {
        if (selected && settled) {
            pop.snapTo(0.84f)
            pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f))
        }
        settled = true
    }
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Accent.copy(alpha = 0.12f * highlight)),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                VIconView(icon, 20.dp, tint, Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value })
                if (badge > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-5).dp)
                            .size(16.dp)
                            .clip(CircleShape)
                            .background(Accent),
                        contentAlignment = Alignment.Center,
                    ) {
                        T(if (badge > 9) "9+" else badge.toString(), size = 9.sp, color = AccentOn, weight = FontWeight.Bold, lineHeight = 9.sp)
                    }
                } else if (dot) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-2).dp)
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE5484D)),
                    )
                }
            }
            T(
                label,
                size = 11.sp,
                color = if (selected) palette.text else tint,
                weight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                lineHeight = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
