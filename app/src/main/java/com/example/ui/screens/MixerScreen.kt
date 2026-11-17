package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BatterySaver
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DriftPlaybackUiState
import com.example.model.PlaybackStatus
import com.example.model.SceneSpec
import com.example.model.SleepTimerPreset
import com.example.ui.components.ChannelMixerCard
import com.example.ui.components.SleepTimerSheet
import com.example.ui.components.formatDuration
import com.example.ui.components.statusLabel
import com.example.ui.theme.DriftBorderSubtle
import com.example.ui.theme.DriftCharcoal
import com.example.ui.theme.DriftCharcoalElevated
import com.example.ui.theme.DriftCharcoalHighest
import com.example.ui.theme.DriftEmerald
import com.example.ui.theme.DriftEmeraldContainer
import com.example.ui.theme.DriftObsidian
import com.example.ui.theme.DriftOnEmeraldContainer
import com.example.ui.theme.DriftTextMuted
import com.example.ui.theme.DriftTextPrimary
import com.example.ui.theme.DriftTextSecondary
import kotlin.math.roundToInt

@Composable
fun MixerScreen(
    uiState: DriftPlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onStopAll: () -> Unit,
    onMasterVolumeChange: (Float) -> Unit,
    onChannelVolumeChange: (String, Float) -> Unit,
    onToggleChannelMute: (String) -> Unit,
    onToggleChannelPlay: (String) -> Unit,
    onResetSceneMix: () -> Unit,
    onStartSleepTimer: (SleepTimerPreset) -> Unit,
    onCancelSleepTimer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = uiState.activeScene ?: return
    var showTimerSheet by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(DriftObsidian),
        contentAlignment = Alignment.TopCenter
    ) {
        val isTwoColumn = maxWidth >= 700.dp

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 980.dp)
                .testTag("mixer_list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SceneHeroHeader(
                    scene = scene,
                    uiState = uiState
                )
            }

            item {
                MasterControlCard(
                    uiState = uiState,
                    onTogglePlayPause = onTogglePlayPause,
                    onStopAll = onStopAll,
                    onMasterVolumeChange = onMasterVolumeChange,
                    onOpenSleepTimer = { showTimerSheet = true }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "CHANNELS",
                        style = MaterialTheme.typography.labelMedium,
                        color = DriftTextMuted
                    )
                    TextButton(
                        onClick = onResetSceneMix,
                        modifier = Modifier.testTag("reset_scene_mix_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = DriftTextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.mixer_reset_mix),
                            style = MaterialTheme.typography.labelLarge,
                            color = DriftTextSecondary
                        )
                    }
                }
            }

            if (isTwoColumn) {
                val pairs = uiState.channels.chunked(2)
                items(pairs.size) { rowIdx ->
                    val rowChannels = pairs[rowIdx]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowChannels.forEach { ch ->
                            ChannelMixerCard(
                                channel = ch,
                                isMasterPlaying = uiState.isPlayingOrFading,
                                onVolumeChange = { onChannelVolumeChange(ch.spec.id, it) },
                                onToggleMute = { onToggleChannelMute(ch.spec.id) },
                                onTogglePlay = { onToggleChannelPlay(ch.spec.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowChannels.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            } else {
                items(uiState.channels.size, key = { uiState.channels[it].spec.id }) { idx ->
                    val ch = uiState.channels[idx]
                    ChannelMixerCard(
                        channel = ch,
                        isMasterPlaying = uiState.isPlayingOrFading,
                        onVolumeChange = { onChannelVolumeChange(ch.spec.id, it) },
                        onToggleMute = { onToggleChannelMute(ch.spec.id) },
                        onTogglePlay = { onToggleChannelPlay(ch.spec.id) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    if (showTimerSheet) {
        SleepTimerSheet(
            sleepTimer = uiState.sleepTimer,
            onSelectPreset = { preset ->
                onStartSleepTimer(preset)
                showTimerSheet = false
            },
            onCancelTimer = onCancelSleepTimer,
            onDismiss = { showTimerSheet = false }
        )
    }
}

@Composable
private fun SceneHeroHeader(
    scene: SceneSpec,
    uiState: DriftPlaybackUiState
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(142.dp)
            .clip(MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        color = DriftCharcoal,
        border = BorderStroke(1.dp, DriftBorderSubtle)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = scene.artworkResId),
                contentDescription = stringResource(id = scene.titleRes),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x6606090A),
                                Color(0xE606090A)
                            )
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.hasTestAudioStems) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xAA0C1315),
                            border = BorderStroke(1.dp, DriftBorderSubtle)
                        ) {
                            Text(
                                text = stringResource(R.string.mixer_test_audio_badge),
                                style = MaterialTheme.typography.labelSmall,
                                color = DriftTextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    val statusText = if (uiState.isTransitioning) {
                        stringResource(R.string.mixer_status_transitioning)
                    } else {
                        statusLabel(uiState.playbackStatus)
                    }
                    val statusActive = uiState.isPlayingOrFading

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (uiState.isBatterySaverOptimizationActive) {
                            Surface(
                                shape = CircleShape,
                                color = Color(0xCC0D231C),
                                border = BorderStroke(1.dp, DriftEmerald.copy(alpha = 0.6f)),
                                modifier = Modifier.testTag("mixer_battery_saver_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.BatterySaver,
                                        contentDescription = stringResource(R.string.mixer_battery_saver_badge),
                                        tint = DriftEmerald,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = stringResource(R.string.mixer_battery_saver_badge),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = DriftEmerald
                                    )
                                }
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = if (statusActive) DriftEmeraldContainer else Color(0xCC111A1D),
                            border = BorderStroke(
                                1.dp,
                                if (statusActive) DriftEmerald else DriftBorderSubtle
                            ),
                            modifier = Modifier.testTag("mixer_playback_status")
                        ) {
                            Text(
                                text = statusText.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (statusActive) DriftOnEmeraldContainer else DriftTextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Column {
                    Text(
                        text = stringResource(id = scene.titleRes),
                        style = MaterialTheme.typography.headlineMedium,
                        color = DriftTextPrimary,
                        modifier = Modifier.testTag("mixer_scene_title")
                    )
                    Text(
                        text = stringResource(id = scene.subtitleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DriftTextSecondary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun MasterControlCard(
    uiState: DriftPlaybackUiState,
    onTogglePlayPause: () -> Unit,
    onStopAll: () -> Unit,
    onMasterVolumeChange: (Float) -> Unit,
    onOpenSleepTimer: () -> Unit
) {
    val masterPercent = (uiState.masterVolume * 100f).roundToInt().coerceIn(0, 100)
    val isPlaying = uiState.isPlayingOrFading

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = DriftCharcoalElevated,
        border = BorderStroke(1.dp, DriftBorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onTogglePlayPause,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(52.dp)
                        .testTag("master_play_pause_button"),
                    shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DriftEmerald,
                        contentColor = DriftObsidian
                    )
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(
                            if (isPlaying) R.string.mixer_pause_all else R.string.mixer_play_all
                        ),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                OutlinedButton(
                    onClick = onStopAll,
                    enabled = uiState.playbackStatus != PlaybackStatus.STOPPED,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("master_stop_button"),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, DriftBorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = DriftTextPrimary,
                        disabledContentColor = DriftTextMuted
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.mixer_stop_all),
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                OutlinedButton(
                    onClick = onOpenSleepTimer,
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("sleep_timer_button"),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(
                        1.dp,
                        if (uiState.sleepTimer.isActive) DriftEmerald else DriftBorderSubtle
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (uiState.sleepTimer.isActive) {
                            DriftEmeraldContainer.copy(alpha = 0.7f)
                        } else {
                            Color.Transparent
                        },
                        contentColor = if (uiState.sleepTimer.isActive) {
                            DriftOnEmeraldContainer
                        } else {
                            DriftTextSecondary
                        }
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Bedtime,
                        contentDescription = stringResource(R.string.sleep_timer_title),
                        modifier = Modifier.size(18.dp)
                    )
                    if (uiState.sleepTimer.isActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.sleepTimer.isFadingOut) {
                                "FADE"
                            } else {
                                formatDuration(uiState.sleepTimer.remainingSeconds)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = DriftEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.mixer_master_volume),
                        style = MaterialTheme.typography.titleMedium,
                        color = DriftTextPrimary
                    )
                }
                Text(
                    text = "$masterPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = DriftEmerald,
                    modifier = Modifier.testTag("master_volume_label")
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Slider(
                value = uiState.masterVolume,
                onValueChange = onMasterVolumeChange,
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("master_volume_slider"),
                colors = SliderDefaults.colors(
                    thumbColor = DriftEmerald,
                    activeTrackColor = DriftEmerald,
                    inactiveTrackColor = DriftCharcoalHighest
                )
            )
        }
    }
}
