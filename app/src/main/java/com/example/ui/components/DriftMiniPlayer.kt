package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DriftPlaybackUiState
import com.example.model.PlaybackStatus
import com.example.ui.theme.DriftBorderSubtle
import com.example.ui.theme.DriftCharcoalElevated
import com.example.ui.theme.DriftCharcoalHighest
import com.example.ui.theme.DriftEmerald
import com.example.ui.theme.DriftEmeraldContainer
import com.example.ui.theme.DriftTextPrimary
import com.example.ui.theme.DriftTextSecondary

@Composable
fun DriftMiniPlayer(
    uiState: DriftPlaybackUiState,
    onOpenMixer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scene = uiState.activeScene ?: return

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpenMixer)
            .testTag("mini_player_bar"),
        shape = MaterialTheme.shapes.medium,
        color = DriftCharcoalElevated,
        border = BorderStroke(1.dp, DriftBorderSubtle),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = scene.artworkResId),
                contentDescription = stringResource(id = scene.titleRes),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(id = scene.titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    color = DriftTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val statusText = when {
                    uiState.sleepTimer.isActive && !uiState.sleepTimer.isFadingOut ->
                        "${statusLabel(uiState.playbackStatus)} • ${formatDuration(uiState.sleepTimer.remainingSeconds)}"
                    else -> statusLabel(uiState.playbackStatus)
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.isPlayingOrFading) DriftEmerald else DriftTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = onTogglePlayPause,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("mini_player_play_pause"),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = DriftEmeraldContainer,
                    contentColor = DriftEmerald
                )
            ) {
                Icon(
                    imageVector = if (uiState.isPlayingOrFading) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (uiState.isPlayingOrFading) {
                        stringResource(R.string.notification_action_pause)
                    } else {
                        stringResource(R.string.notification_action_play)
                    }
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("mini_player_stop"),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = DriftCharcoalHighest,
                    contentColor = DriftTextSecondary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Stop,
                    contentDescription = stringResource(R.string.notification_action_stop)
                )
            }
        }
    }
}

@Composable
fun statusLabel(status: PlaybackStatus): String {
    return when (status) {
        PlaybackStatus.PLAYING -> stringResource(R.string.mixer_status_playing)
        PlaybackStatus.PAUSED -> stringResource(R.string.mixer_status_paused)
        PlaybackStatus.STOPPED -> stringResource(R.string.mixer_status_stopped)
        PlaybackStatus.FADING_OUT -> stringResource(R.string.mixer_status_fading_out)
    }
}
