package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.SleepTimerPreset
import com.example.model.SleepTimerState
import com.example.ui.theme.DriftBorderSubtle
import com.example.ui.theme.DriftCharcoal
import com.example.ui.theme.DriftCharcoalElevated
import com.example.ui.theme.DriftCharcoalHighest
import com.example.ui.theme.DriftEmerald
import com.example.ui.theme.DriftEmeraldContainer
import com.example.ui.theme.DriftOnEmeraldContainer
import com.example.ui.theme.DriftTextMuted
import com.example.ui.theme.DriftTextPrimary
import com.example.ui.theme.DriftTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    sleepTimer: SleepTimerState,
    onSelectPreset: (SleepTimerPreset) -> Unit,
    onCancelTimer: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = DriftCharcoal,
        contentColor = DriftTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bedtime,
                    contentDescription = null,
                    tint = DriftEmerald,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.sleep_timer_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = DriftTextPrimary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Audio gradually fades out before stopping when the timer completes.",
                style = MaterialTheme.typography.bodyMedium,
                color = DriftTextSecondary
            )

            if (sleepTimer.isActive) {
                Spacer(modifier = Modifier.height(20.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = DriftCharcoalElevated,
                    border = BorderStroke(1.dp, DriftBorderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = when {
                                sleepTimer.isFadingOut -> stringResource(R.string.sleep_timer_fading)
                                sleepTimer.isPaused ->
                                    "${formatDuration(sleepTimer.remainingSeconds)} • ${stringResource(R.string.mixer_status_paused)}"
                                else -> formatDuration(sleepTimer.remainingSeconds)
                            },
                            style = MaterialTheme.typography.displaySmall,
                            color = DriftEmerald,
                            modifier = Modifier.testTag("sleep_timer_countdown")
                        )

                        if (!sleepTimer.isFadingOut && sleepTimer.totalSeconds > 0) {
                            Spacer(modifier = Modifier.height(12.dp))
                            val progress = (sleepTimer.remainingSeconds.toFloat() / sleepTimer.totalSeconds.toFloat())
                                .coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp),
                                color = DriftEmerald,
                                trackColor = DriftCharcoalHighest
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SleepTimerPreset.entries.forEach { preset ->
                    val isSelected = sleepTimer.isActive && sleepTimer.selectedPreset == preset
                    Button(
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("timer_preset_${preset.minutes}"),
                        shape = MaterialTheme.shapes.medium,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) DriftEmeraldContainer else DriftCharcoalElevated,
                            contentColor = if (isSelected) DriftOnEmeraldContainer else DriftTextPrimary
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isSelected) DriftEmerald else DriftBorderSubtle
                        )
                    ) {
                        Text(
                            text = "${preset.minutes}m",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            if (sleepTimer.isActive) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = {
                        onCancelTimer()
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("cancel_sleep_timer_button"),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, DriftBorderSubtle),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = DriftTextSecondary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sleep_timer_cancel),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Continues running reliably with the screen off.",
                    style = MaterialTheme.typography.labelSmall,
                    color = DriftTextMuted
                )
            }
        }
    }
}

fun formatDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    val hours = safe / 3600
    val mins = (safe % 3600) / 60
    val secs = safe % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}
