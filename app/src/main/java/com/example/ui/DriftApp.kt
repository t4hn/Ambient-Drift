package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.model.PlaybackStatus
import com.example.ui.components.DriftMiniPlayer
import com.example.ui.screens.MixerScreen
import com.example.ui.screens.ScenesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.DriftCharcoal
import com.example.ui.theme.DriftCharcoalElevated
import com.example.ui.theme.DriftEmerald
import com.example.ui.theme.DriftEmeraldContainer
import com.example.ui.theme.DriftObsidian
import com.example.ui.theme.DriftOnEmeraldContainer
import com.example.ui.theme.DriftTextMuted
import com.example.ui.theme.DriftTextSecondary

const val ROUTE_SCENES = "scenes"
const val ROUTE_MIXER = "mixer"
const val ROUTE_SETTINGS = "settings"

private data class DriftNavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val destinations = listOf(
    DriftNavDestination(
        route = ROUTE_SCENES,
        labelRes = R.string.nav_scenes,
        selectedIcon = Icons.Filled.Landscape,
        unselectedIcon = Icons.Outlined.Landscape,
        testTag = "nav_scenes"
    ),
    DriftNavDestination(
        route = ROUTE_MIXER,
        labelRes = R.string.nav_mixer,
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_mixer"
    ),
    DriftNavDestination(
        route = ROUTE_SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings"
    )
)

@Composable
fun DriftApp(
    viewModel: DriftViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_SCENES

    val context = LocalContext.current
    var notificationsGranted by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }
    var hasAskedNotificationPermission by remember { mutableStateOf(false) }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsGranted = granted
    }

    val requestNotificationsIfAppropriate: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationsGranted &&
            !hasAskedNotificationPermission
        ) {
            hasAskedNotificationPermission = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val navigateToTab: (String) -> Unit = { route ->
        if (currentRoute != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val showMiniPlayer = currentRoute != ROUTE_MIXER &&
        uiState.activeScene != null &&
        uiState.playbackStatus != PlaybackStatus.STOPPED

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavigationRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = DriftObsidian,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!useNavigationRail) {
                    Column {
                        if (showMiniPlayer) {
                            DriftMiniPlayer(
                                uiState = uiState,
                                onOpenMixer = { navigateToTab(ROUTE_MIXER) },
                                onTogglePlayPause = {
                                    requestNotificationsIfAppropriate()
                                    viewModel.togglePlayPause()
                                },
                                onStop = { viewModel.stopAll() }
                            )
                        }
                        NavigationBar(
                            containerColor = DriftCharcoal,
                            contentColor = DriftTextSecondary,
                            windowInsets = WindowInsets.navigationBars
                        ) {
                            destinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateToTab(dest.route) },
                                    modifier = Modifier.testTag(dest.testTag),
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(dest.labelRes),
                                            style = MaterialTheme.typography.labelLarge
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = DriftOnEmeraldContainer,
                                        selectedTextColor = DriftEmerald,
                                        indicatorColor = DriftEmeraldContainer,
                                        unselectedIconColor = DriftTextMuted,
                                        unselectedTextColor = DriftTextMuted
                                    )
                                )
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavigationRail) {
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(WindowInsets.statusBars),
                        containerColor = DriftCharcoal,
                        contentColor = DriftTextSecondary
                    ) {
                        destinations.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                modifier = Modifier.testTag(dest.testTag),
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = {
                                    Text(
                                        text = stringResource(dest.labelRes),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = DriftOnEmeraldContainer,
                                    selectedTextColor = DriftEmerald,
                                    indicatorColor = DriftEmeraldContainer,
                                    unselectedIconColor = DriftTextMuted,
                                    unselectedTextColor = DriftTextMuted
                                )
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    BoxWithConstraints(modifier = Modifier.weight(1f)) {
                        NavHost(
                            navController = navController,
                            startDestination = ROUTE_SCENES,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            composable(ROUTE_SCENES) {
                                ScenesScreen(
                                    uiState = uiState,
                                    onSelectScene = { scene ->
                                        requestNotificationsIfAppropriate()
                                        viewModel.selectScene(scene.id, autoStartIfConfigured = true)
                                        navigateToTab(ROUTE_MIXER)
                                    }
                                )
                            }

                            composable(ROUTE_MIXER) {
                                MixerScreen(
                                    uiState = uiState,
                                    onTogglePlayPause = {
                                        requestNotificationsIfAppropriate()
                                        viewModel.togglePlayPause()
                                    },
                                    onStopAll = { viewModel.stopAll() },
                                    onMasterVolumeChange = { viewModel.setMasterVolume(it) },
                                    onChannelVolumeChange = { id, v -> viewModel.setChannelVolume(id, v) },
                                    onToggleChannelMute = { id -> viewModel.toggleChannelMute(id) },
                                    onToggleChannelPlay = { id ->
                                        requestNotificationsIfAppropriate()
                                        viewModel.toggleChannelPlay(id)
                                    },
                                    onResetSceneMix = { viewModel.resetActiveSceneToDefaults() },
                                    onStartSleepTimer = { preset ->
                                        requestNotificationsIfAppropriate()
                                        viewModel.startSleepTimer(preset)
                                    },
                                    onCancelSleepTimer = { viewModel.cancelSleepTimer() }
                                )
                            }

                            composable(ROUTE_SETTINGS) {
                                SettingsScreen(
                                    uiState = uiState,
                                    notificationsGranted = notificationsGranted,
                                    onRequestNotificationPermission = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        }
                                    },
                                    onAutoPlayChange = { viewModel.setAutoPlayOnSceneSwitch(it) },
                                    onSmoothCrossfadeChange = { viewModel.setSmoothCrossfadeEnabled(it) },
                                    onResetAllMixes = { viewModel.resetAllToDefaults() }
                                )
                            }
                        }
                    }

                    if (useNavigationRail && showMiniPlayer) {
                        DriftMiniPlayer(
                            uiState = uiState,
                            onOpenMixer = { navigateToTab(ROUTE_MIXER) },
                            onTogglePlayPause = {
                                requestNotificationsIfAppropriate()
                                viewModel.togglePlayPause()
                            },
                            onStop = { viewModel.stopAll() },
                            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                        )
                    }
                }
            }
        }
    }
}
