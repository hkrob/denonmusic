package com.denonmusic.app.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.denonmusic.app.avr.AvrScreen
import com.denonmusic.app.browse.BrowseScreen
import com.denonmusic.app.nowplaying.NowPlayingScreen
import com.denonmusic.app.player.MiniPlayerBar
import com.denonmusic.app.player.PlayerViewModel
import com.denonmusic.app.queue.QueueScreen
import com.denonmusic.app.settings.SettingsScreen
import com.denonmusic.app.ui.Winamp

private enum class MainTab(val route: String, val label: String) {
    Browse("browse", "BROWSE"),
    Queue("queue", "QUEUE"),
    Avr("avr", "AVR"),
    Settings("settings", "SETTINGS"),
}

private const val NOW_PLAYING_ROUTE = "nowplaying"

/**
 * Hosts the three phase-3 screens. [PlayerViewModel] is created here - outside the nav graph, scoped
 * to the activity - so Browse, Queue and Now Playing all observe the one player/queue state instead
 * of each resolving the player id and re-subscribing to the HEOS event socket independently.
 *
 * [MainActivity][com.denonmusic.app.MainActivity] draws edge-to-edge, so the status bar inset is
 * real screen space here, not something Scaffold hides for free. It is applied once at the very top
 * ([Modifier.statusBarsPadding]) and then marked consumed for the nav-host content below, so the
 * per-screen `TopAppBar`s further down (which each ask for the same inset by default) don't reserve
 * it a second time and shove their titles down an extra status-bar's worth of blank space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val navController = rememberNavController()
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerState by playerViewModel.uiState.collectAsState()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Winamp.Background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (currentRoute != NOW_PLAYING_ROUTE) {
            ScrollableTabRow(
                selectedTabIndex = MainTab.entries.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0),
                containerColor = Winamp.Panel,
                contentColor = Winamp.Green,
                edgePadding = 0.dp,
            ) {
                MainTab.entries.forEach { tab ->
                    Tab(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        text = { Text(tab.label, style = Winamp.labelStyle) },
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
            NavHost(navController = navController, startDestination = MainTab.Browse.route) {
                composable(MainTab.Browse.route) { BrowseScreen() }
                composable(MainTab.Queue.route) { QueueScreen(playerViewModel = playerViewModel) }
                composable(MainTab.Avr.route) { AvrScreen() }
                composable(MainTab.Settings.route) { SettingsScreen() }
                composable(NOW_PLAYING_ROUTE) {
                    NowPlayingScreen(playerViewModel = playerViewModel, onBack = { navController.popBackStack() })
                }
            }
        }

        if (currentRoute != null && currentRoute != NOW_PLAYING_ROUTE) {
            MiniPlayerBar(
                state = playerState,
                onTogglePlay = playerViewModel::togglePlayPause,
                onExpand = { navController.navigate(NOW_PLAYING_ROUTE) },
            )
        }
    }
}
