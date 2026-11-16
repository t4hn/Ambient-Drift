package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.ChannelState
import com.example.ui.theme.DriftBorderSubtle
import com.example.ui.theme.DriftCharcoal
import com.example.ui.theme.DriftCharcoalElevated
import com.example.ui.theme.DriftCharcoalHighest
import com.example.ui.theme.DriftEmerald
import com.example.ui.theme.DriftEmeraldContainer
import com.example.ui.theme.DriftEmeraldMuted
import com.example.ui.theme.DriftTextMuted
import com.example.ui.theme.DriftTextPrimary
import com.example.ui.theme.DriftTextSecondary
import kotlin.math.roundToInt

@Composable
fun ChannelMixerCard(
    channel: ChannelState,
    isMasterPlaying: Boolean,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val channelName = stringResource(id = channel.spec.nameRes)
    val isChannelActiveAndAudible = isMasterPlaying && channel.isAudible
    val volumePercent = (channel.volume * 100f).roundToInt().coerceIn(0, 100)

    val borderColor by animateColorAsState(
        targetValue = if (isChannelActiveAndAudible) {
            DriftEmeraldMuted.copy(alpha = 0.55f)
        } else {
            DriftBorderSubtle.copy(alpha = 0.7f)
        },
        animationSpec = tween(250),
        label = "channel_border"
    )

    val containerColor by animateColorAsState(
        targetValue = if (isChannelActiveAndAudible) {
            DriftCharcoalElevated
        } else {
            DriftCharcoal
        },
        animationSpec = tween(250),
        label = "channel_bg"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("channel_card_${channel.spec.id}"),
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChannelActivityIndicator(
                    isActive = isChannelActiveAndAudible,
                    isMuted = channel.isMuted,
                    isChannelPaused = !channel.isPlaying
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channelName,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (channel.isMuted || !channel.isPlaying) DriftTextSecondary else DriftTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (channel.isMuted || !channel.isPlaying) {
                        Text(
                            text = if (channel.isMuted) {
                                stringResource(R.string.mixer_channel_muted)
                            } else {
                                stringResource(R.string.mixer_channel_paused)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = DriftTextMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "$volumePercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (channel.isMuted || !channel.isPlaying) DriftTextMuted else DriftEmerald,
                    modifier = Modifier.testTag("channel_volume_label_${channel.spec.id}")
                )

                Spacer(modifier = Modifier.width(4.dp))

                IconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("channel_play_button_${channel.spec.id}"),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (channel.isPlaying) {
                            DriftEmeraldContainer.copy(alpha = 0.65f)
                        } else {
                            DriftCharcoalHighest
                        },
                        contentColor = if (channel.isPlaying) DriftEmerald else DriftTextMuted
                    )
                ) {
                    Icon(
                        imageVector = if (channel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.mixer_channel_play_cd, channelName),
                        modifier = Modifier.size(18.dp)
                    )
                }

                IconButton(
                    onClick = onToggleMute,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("channel_mute_button_${channel.spec.id}"),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (channel.isMuted) {
                            DriftCharcoalHighest
                        } else {
                            DriftCharcoalElevated
                        },
                        contentColor = if (channel.isMuted) DriftTextMuted else DriftTextPrimary
                    )
                ) {
                    Icon(
                        imageVector = if (channel.isMuted) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = stringResource(R.string.mixer_mute_cd, channelName),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Slider(
                value = channel.volume,
                onValueChange = onVolumeChange,
                valueRange = 0f..1f,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("channel_slider_${channel.spec.id}"),
                colors = SliderDefaults.colors(
                    thumbColor = if (channel.isMuted || !channel.isPlaying) DriftTextMuted else DriftEmerald,
                    activeTrackColor = if (channel.isMuted || !channel.isPlaying) {
                        DriftTextMuted.copy(alpha = 0.45f)
                    } else {
                        DriftEmerald
                    },
                    inactiveTrackColor = DriftCharcoalHighest
                )
            )
        }
    }
}

@Composable
private fun ChannelActivityIndicator(
    isActive: Boolean,
    isMuted: Boolean,
    isChannelPaused: Boolean
) {
    if (!isActive) {
        Box(
            modifier = Modifier
                .size(18.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isMuted || isChannelPaused) DriftTextMuted.copy(alpha = 0.5f) else DriftEmeraldMuted.copy(alpha = 0.5f))
            )
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "channel_wave")
    val bar1 by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "b1"
    )
    val bar2 by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "b2"
    )
    val bar3 by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "b3"
    )

    Row(
        modifier = Modifier
            .width(18.dp)
            .height(16.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(bar1, bar2, bar3).forEach { fraction ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((14 * fraction).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(DriftEmerald)
            )
        }
    }
}
