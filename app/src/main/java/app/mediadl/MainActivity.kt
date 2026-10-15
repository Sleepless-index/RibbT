package app.mediadl

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

        Column(
            Modifier
                .fillMaxSize()
                .background(palette.card)
                .imePadding(),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
                    .background(palette.bg)
                    .statusBarsPadding(),
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
            NavBar(vm)
        }
    }
}

@Composable
private fun NavBar(vm: AppViewModel) {
    val tabs = listOf(Tab.Home to VIcons.Home, Tab.History to VIcons.History, Tab.Settings to VIcons.Settings)
    val index = tabs.indexOfFirst { it.first == vm.tab }.coerceAtLeast(0)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .height(46.dp)
            .padding(start = 10.dp, end = 10.dp, top = 5.dp),
    ) {
        // The highlight slides to the chosen tab with a little spring instead of jumping.
        val cell = maxWidth / tabs.size
        val pillWidth = 64.dp
        val pillX by animateDpAsState(
            targetValue = cell * index + (cell - pillWidth) / 2,
            animationSpec = spring(dampingRatio = 0.68f, stiffness = 420f),
            label = "nav-pill",
        )
        Box(
            Modifier
                .offset(x = pillX)
                .width(pillWidth)
                .height(37.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Accent.copy(alpha = 0.12f)),
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEach { (tab, icon) -> NavItem(tab, icon, vm, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, icon: VIcon, vm: AppViewModel, modifier: Modifier) {
    val palette = LocalPalette.current
    val selected = vm.tab == tab
    val tint by animateColorAsState(if (selected) Accent else palette.muted, tween(220), label = "nav-tint")
    // The icon pops up a touch and settles when its tab is chosen.
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.14f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "nav-scale",
    )
    Column(
        modifier
            .height(37.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable {
                if (tab == Tab.Home && vm.tab == Tab.Home) vm.reset()
                if (tab == Tab.Settings && vm.tab == Tab.Settings) vm.settingsPage = null
                if (tab == Tab.History) vm.loadHistory()
                vm.tab = tab
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        VIconView(icon, 18.dp, tint, Modifier.graphicsLayer { scaleX = scale; scaleY = scale })
        T(tab.label, size = 9.6.sp, color = tint, lineHeight = 11.5.sp, modifier = Modifier.padding(top = 1.dp))
    }
}
