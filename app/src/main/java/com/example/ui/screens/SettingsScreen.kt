package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DriftPlaybackUiState
import com.example.ui.theme.DriftBorderSubtle
import com.example.ui.theme.DriftCharcoal
import com.example.ui.theme.DriftCharcoalElevated
import com.example.ui.theme.DriftEmerald
import com.example.ui.theme.DriftEmeraldContainer
import com.example.ui.theme.DriftObsidian
import com.example.ui.theme.DriftTextMuted
import com.example.ui.theme.DriftTextPrimary
import com.example.ui.theme.DriftTextSecondary

@Composable
fun SettingsScreen(
    uiState: DriftPlaybackUiState,
    notificationsGranted: Boolean,
    onRequestNotificationPermission: () -> Unit,
    onAutoPlayChange: (Boolean) -> Unit,
    onSmoothCrossfadeChange: (Boolean) -> Unit,
    onResetAllMixes: () -> Unit,
    modifier: Modifier = Modifier
) {
    var resetConfirmationVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DriftObsidian),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp)
                .testTag("settings_list"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.displaySmall,
                    color = DriftTextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                SectionLabel(text = stringResource(R.string.settings_playback_section))
            }

            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.PlayCircle,
                    title = stringResource(R.string.settings_auto_resume_title),
                    subtitle = stringResource(R.string.settings_auto_resume_desc),
                    checked = uiState.autoPlayOnSceneSwitch,
                    onCheckedChange = onAutoPlayChange,
                    testTag = "setting_auto_play_switch"
                )
            }

            item {
                SettingsToggleRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = stringResource(R.string.settings_crossfade_title),
                    subtitle = stringResource(R.string.settings_crossfade_desc),
                    checked = uiState.smoothCrossfadeEnabled,
                    onCheckedChange = onSmoothCrossfadeChange,
                    testTag = "setting_crossfade_switch"
                )
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable(enabled = !notificationsGranted, onClick = onRequestNotificationPermission),
                    shape = MaterialTheme.shapes.medium,
                    color = DriftCharcoal,
                    border = BorderStroke(1.dp, DriftBorderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (notificationsGranted) {
                                Icons.Outlined.NotificationsActive
                            } else {
                                Icons.Outlined.NotificationsOff
                            },
                            contentDescription = null,
                            tint = if (notificationsGranted) DriftEmerald else DriftTextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_notifications_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = DriftTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(
                                    if (notificationsGranted) {
                                        R.string.settings_notifications_granted
                                    } else {
                                        R.string.settings_notifications_denied
                                    }
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = DriftTextSecondary
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionLabel(text = stringResource(R.string.settings_audio_section))
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = DriftCharcoal,
                    border = BorderStroke(1.dp, DriftBorderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AudioFile,
                            contentDescription = null,
                            tint = DriftEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_audio_source_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = DriftTextPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.settings_audio_source_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = DriftTextSecondary
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .clickable {
                            onResetAllMixes()
                            resetConfirmationVisible = true
                        }
                        .testTag("reset_all_mixes_button"),
                    shape = MaterialTheme.shapes.medium,
                    color = DriftCharcoalElevated,
                    border = BorderStroke(1.dp, DriftBorderSubtle)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Restore,
                            contentDescription = null,
                            tint = DriftEmerald,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_reset_all_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = DriftTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (resetConfirmationVisible) {
                                    "All scenes and master volume restored to defaults."
                                } else {
                                    stringResource(R.string.settings_reset_all_desc)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (resetConfirmationVisible) DriftEmerald else DriftTextSecondary
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                SectionLabel(text = stringResource(R.string.settings_about_section))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_version_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = DriftTextMuted
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = DriftTextMuted
    )
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = DriftCharcoal,
        border = BorderStroke(1.dp, DriftBorderSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DriftEmerald,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = DriftTextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DriftTextSecondary
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(testTag),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = DriftObsidian,
                    checkedTrackColor = DriftEmerald,
                    uncheckedThumbColor = DriftTextSecondary,
                    uncheckedTrackColor = DriftCharcoalElevated,
                    uncheckedBorderColor = DriftBorderSubtle
                )
            )
        }
    }
}
