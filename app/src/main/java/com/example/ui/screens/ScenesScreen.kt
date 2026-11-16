package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.DriftPlaybackUiState
import com.example.model.SceneSpec
import com.example.ui.theme.DriftBorderSubtle
import com.example.ui.theme.DriftCharcoal
import com.example.ui.theme.DriftEmerald
import com.example.ui.theme.DriftEmeraldContainer
import com.example.ui.theme.DriftEmeraldMuted
import com.example.ui.theme.DriftObsidian
import com.example.ui.theme.DriftOnEmeraldContainer
import com.example.ui.theme.DriftTextMuted
import com.example.ui.theme.DriftTextPrimary
import com.example.ui.theme.DriftTextSecondary

@Composable
fun ScenesScreen(
    uiState: DriftPlaybackUiState,
    onSelectScene: (SceneSpec) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(DriftObsidian)
    ) {
        val isWide = maxWidth >= 680.dp
        val columns = if (maxWidth >= 960.dp) 3 else if (isWide) 2 else 1

        if (columns == 1) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("scenes_list"),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ScenesHeader(uiState = uiState)
                    Spacer(modifier = Modifier.height(6.dp))
                }
                items(uiState.scenes, key = { it.id }) { scene ->
                    val isSelected = uiState.activeScene?.id == scene.id
                    val isPlaying = isSelected && uiState.isPlayingOrFading
                    SceneCard(
                        scene = scene,
                        isSelected = isSelected,
                        isPlaying = isPlaying,
                        onClick = { onSelectScene(scene) }
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("scenes_list"),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item(span = { GridItemSpan(columns) }) {
                    ScenesHeader(uiState = uiState)
                }
                items(uiState.scenes, key = { it.id }) { scene ->
                    val isSelected = uiState.activeScene?.id == scene.id
                    val isPlaying = isSelected && uiState.isPlayingOrFading
                    SceneCard(
                        scene = scene,
                        isSelected = isSelected,
                        isPlaying = isPlaying,
                        onClick = { onSelectScene(scene) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenesHeader(uiState: DriftPlaybackUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = DriftTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.scenes_header_subtitle),
                style = MaterialTheme.typography.labelMedium,
                color = DriftTextMuted
            )
        }

        if (uiState.isPlayingOrFading) {
            Surface(
                shape = CircleShape,
                color = DriftEmeraldContainer,
                border = BorderStroke(1.dp, DriftEmeraldMuted.copy(alpha = 0.6f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = DriftEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.scenes_playing_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = DriftOnEmeraldContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun SceneCard(
    scene: SceneSpec,
    isSelected: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    val borderColor = when {
        isPlaying -> DriftEmerald.copy(alpha = 0.75f)
        isSelected -> DriftEmeraldMuted.copy(alpha = 0.55f)
        else -> DriftBorderSubtle
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(182.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .testTag("scene_card_${scene.id}"),
        shape = MaterialTheme.shapes.large,
        color = DriftCharcoal,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Image(
                painter = painterResource(id = scene.artworkResId),
                contentDescription = stringResource(id = scene.titleRes),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            // Atmospheric nocturnal gradient scrim
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x5506090A),
                                Color(0x9906090A),
                                Color(0xF006090A)
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
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xAA0C1315),
                        border = BorderStroke(1.dp, DriftBorderSubtle.copy(alpha = 0.7f))
                    ) {
                        Text(
                            text = scene.indexLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = DriftTextSecondary,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                        )
                    }

                    if (isSelected) {
                        Surface(
                            shape = CircleShape,
                            color = if (isPlaying) DriftEmeraldContainer else Color(0xCC111A1D),
                            border = BorderStroke(
                                1.dp,
                                if (isPlaying) DriftEmerald else DriftEmeraldMuted
                            )
                        ) {
                            Text(
                                text = stringResource(
                                    if (isPlaying) R.string.scenes_playing_badge else R.string.scenes_active_badge
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isPlaying) DriftOnEmeraldContainer else DriftEmerald,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = scene.titleRes),
                        style = MaterialTheme.typography.headlineMedium,
                        color = DriftTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(id = scene.subtitleRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = DriftTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.scenes_channel_count),
                            style = MaterialTheme.typography.labelSmall,
                            color = DriftEmerald
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = DriftTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
