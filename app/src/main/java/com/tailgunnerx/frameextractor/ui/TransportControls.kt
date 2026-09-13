package com.tailgunnerx.frameextractor.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tailgunnerx.frameextractor.util.Timeline
import kotlinx.coroutines.delay
import kotlin.math.roundToLong

private const val HOLD_REPEAT_START_DELAY_MS = 400L

/** Slowest a held step button will repeat, so holding it never outruns the decoder by much. */
private const val MIN_HOLD_REPEAT_MS = 40L

@Composable
fun TransportControls(
    frameIndex: Long,
    totalFrames: Long,
    positionMs: Long,
    isPlaying: Boolean,
    playSpeedFps: Float,
    isExtracting: Boolean,
    onSeekToFrame: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onStep: (Long) -> Unit,
    onSpeedDelta: (Float) -> Unit,
    onSave: () -> Unit,
) {
    val previousInteraction = remember { MutableInteractionSource() }
    val isPreviousPressed by previousInteraction.collectIsPressedAsState()
    val nextInteraction = remember { MutableInteractionSource() }
    val isNextPressed by nextInteraction.collectIsPressedAsState()

    // A held button used to repeat as fast as 60 times a second while each step still had to wait on
    // a seek, so the counter ran away from the picture. Capping the rate keeps the two together.
    val repeatDelayMs = (1000f / playSpeedFps).toLong().coerceAtLeast(MIN_HOLD_REPEAT_MS)

    LaunchedEffect(isPreviousPressed, isPlaying, repeatDelayMs) {
        if (isPreviousPressed && !isPlaying) {
            delay(HOLD_REPEAT_START_DELAY_MS)
            while (true) {
                onStep(-1L)
                delay(repeatDelayMs)
            }
        }
    }

    LaunchedEffect(isNextPressed, isPlaying, repeatDelayMs) {
        if (isNextPressed && !isPlaying) {
            delay(HOLD_REPEAT_START_DELAY_MS)
            while (true) {
                onStep(1L)
                delay(repeatDelayMs)
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Frame ${Timeline.frameNumber(frameIndex)} / $totalFrames • " +
                        Timeline.formatPosition(positionMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "${playSpeedFps.toInt()} FPS Speed",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            // The scrubber works in frames, not milliseconds: dragging it can only ever land on a
            // real frame, and the thumb position always agrees with the counter above it.
            val lastFrame = (totalFrames - 1L).coerceAtLeast(0L)
            Slider(
                value = frameIndex.toFloat().coerceIn(0f, lastFrame.toFloat()),
                onValueChange = { onSeekToFrame(it.roundToLong()) },
                valueRange = 0f..lastFrame.toFloat().coerceAtLeast(1f),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingActionButton(
                    onClick = onSave,
                    containerColor = Color.White,
                    contentColor = Color.Black,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                ) {
                    if (isExtracting) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(SaveIcon, contentDescription = "Extract frame", tint = Color.Black, modifier = Modifier.size(24.dp))
                    }
                }

                IconButton(onClick = { onSpeedDelta(-1f) }) {
                    Icon(RemoveIcon, contentDescription = "Slower", tint = Color.Gray)
                }

                IconButton(
                    onClick = { onStep(-1L) },
                    interactionSource = previousInteraction,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(SkipPreviousIcon, contentDescription = "Previous frame", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                FloatingActionButton(
                    onClick = onTogglePlay,
                    containerColor = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = if (isPlaying) PauseIcon else PlayIcon,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(
                    onClick = { onStep(1L) },
                    interactionSource = nextInteraction,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(SkipNextIcon, contentDescription = "Next frame", tint = Color.White, modifier = Modifier.size(36.dp))
                }

                IconButton(onClick = { onSpeedDelta(1f) }) {
                    Icon(AddIcon, contentDescription = "Faster", tint = Color.Gray)
                }
            }
        }
    }
}
