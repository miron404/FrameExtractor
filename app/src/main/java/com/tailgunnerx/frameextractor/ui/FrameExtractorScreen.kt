package com.tailgunnerx.frameextractor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tailgunnerx.frameextractor.media.FrameSaver
import com.tailgunnerx.frameextractor.media.VideoInfo
import com.tailgunnerx.frameextractor.util.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_PLAY_SPEED_FPS = 5f
private const val MIN_PLAY_SPEED_FPS = 1f
private const val MAX_PLAY_SPEED_FPS = 60f

/** Coarse enough that the timeline only looks continuous instead of invalidating on every frame. */
private const val POSITION_POLL_MS = 80L

/**
 * @param initialVideoUri clip to open straight away. Left null by [com.tailgunnerx.frameextractor.MainActivity];
 *   instrumented tests use it to drive the real screen with a real clip.
 */
@Composable
fun FrameExtractorScreen(initialVideoUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberVideoPlayerState()

    var videoUri by remember { mutableStateOf(initialVideoUri) }
    var playSpeedFps by remember { mutableFloatStateOf(DEFAULT_PLAY_SPEED_FPS) }
    var isExtracting by remember { mutableStateOf(false) }

    DisposableEffect(state) {
        onDispose { state.release() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "Storage permission required to save frames", Toast.LENGTH_LONG).show()
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) videoUri = uri
    }

    // Opening the source reads container metadata, which can block for a noticeable time on large
    // files - it used to run inside the picker callback, on the main thread.
    LaunchedEffect(videoUri) {
        val uri = videoUri
        if (uri == null) {
            state.unload()
        } else {
            state.load(context, uri)?.let { error ->
                Toast.makeText(context, "Could not open video: ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // The only thing that polls, and only while playing. Paused, position changes exactly when the
    // user asks for a different frame.
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            state.syncFrameFromPlayer()
            delay(POSITION_POLL_MS)
        }
    }

    LaunchedEffect(playSpeedFps, state.info) {
        state.setPlaybackFps(playSpeedFps)
    }

    val saveFrame: () -> Unit = save@{
        val active = state.decoder
        if (isExtracting || active == null) return@save
        val legacyWriteDenied = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (legacyWriteDenied) {
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return@save
        }
        state.pause()
        // Aim at the middle of the frame on screen, the same target the player was seeked to, so the
        // extracted file is the frame the user is looking at.
        val timeMs = Timeline.frameMidpointMs(state.frameIndex, state.fps)
        isExtracting = true
        scope.launch {
            try {
                FrameSaver.saveFrame(context, active, timeMs)
                Toast.makeText(context, "Saved frame to Pictures/FrameExtractor", Toast.LENGTH_SHORT).show()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Toast.makeText(context, "Error saving frame: ${error.message}", Toast.LENGTH_LONG).show()
            } finally {
                isExtracting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TopBar(
            info = state.info,
            hasVideo = videoUri != null,
            onOpenVideo = { pickerLauncher.launch("video/*") },
            onEject = { videoUri = null },
        )

        FrameViewer(
            state = state,
            placeholder = when {
                videoUri == null -> "No video selected"
                state.isLoading -> "Opening video..."
                else -> "Loading frame..."
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        if (videoUri != null) {
            TransportControls(
                frameIndex = state.frameIndex,
                totalFrames = (state.info ?: VideoInfo.EMPTY).totalFrames,
                positionMs = state.positionMs,
                isPlaying = state.isPlaying,
                playSpeedFps = playSpeedFps,
                isExtracting = isExtracting,
                onSeekToFrame = state::seekToFrame,
                onTogglePlay = state::togglePlay,
                onStep = state::stepFrames,
                onSpeedDelta = { delta ->
                    playSpeedFps = (playSpeedFps + delta).coerceIn(MIN_PLAY_SPEED_FPS, MAX_PLAY_SPEED_FPS)
                },
                onSave = saveFrame,
            )
        }
    }
}

@Composable
private fun TopBar(
    info: VideoInfo?,
    hasVideo: Boolean,
    onOpenVideo: () -> Unit,
    onEject: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Frame Extractor",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
            )
            if (info != null) {
                Text(
                    text = "${info.resolutionLabel} • ${info.fpsLabel}",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (hasVideo) {
            IconButton(
                onClick = onEject,
                modifier = Modifier.padding(end = 8.dp),
            ) {
                Icon(CloseIcon, contentDescription = "Eject video", tint = MaterialTheme.colorScheme.error)
            }
        }

        Button(onClick = onOpenVideo) {
            Text(if (hasVideo) "Change" else "Open Video")
        }
    }
}
