package com.tailgunnerx.frameextractor.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.SurfaceView
import android.view.View
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.tailgunnerx.frameextractor.media.FrameCache
import com.tailgunnerx.frameextractor.media.FrameSaver
import com.tailgunnerx.frameextractor.media.VideoFrameDecoder
import com.tailgunnerx.frameextractor.media.VideoInfo
import com.tailgunnerx.frameextractor.util.Timeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val DEFAULT_PLAY_SPEED_FPS = 5f
private const val MIN_PLAY_SPEED_FPS = 1f
private const val MAX_PLAY_SPEED_FPS = 60f
private const val POSITION_POLL_MS = 80L
private const val HOLD_REPEAT_START_DELAY_MS = 400L
private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 5f

/**
 * What the still-frame decoder should be doing right now.
 *
 * Bundling the inputs into one immutable value lets a single `snapshotFlow` + `collectLatest`
 * drive decoding: when a newer request arrives, the older decode is abandoned instead of being
 * queued, so a fast scrub never builds up a backlog of stale frames.
 */
private data class SeekRequest(
    val positionMs: Long,
    val maxDimension: Int,
    val playing: Boolean,
)

/**
 * @param initialVideoUri clip to open straight away. Left null by [com.tailgunnerx.frameextractor.MainActivity];
 *   instrumented tests use it to drive the real screen with a real clip.
 */
@Composable
fun FrameExtractorScreen(initialVideoUri: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var videoUri by remember { mutableStateOf(initialVideoUri) }
    var decoder by remember { mutableStateOf<VideoFrameDecoder?>(null) }
    var info by remember { mutableStateOf<VideoInfo?>(null) }
    var isLoadingVideo by remember { mutableStateOf(false) }

    var stillFrame by remember { mutableStateOf<ImageBitmap?>(null) }
    var viewerSize by remember { mutableStateOf(IntSize.Zero) }

    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var playSpeedFps by remember { mutableFloatStateOf(DEFAULT_PLAY_SPEED_FPS) }
    var isExtracting by remember { mutableStateOf(false) }

    val frameCache = remember { FrameCache() }
    val durationMs = info?.durationMs ?: 0L
    val msPerFrame = (info ?: VideoInfo.EMPTY).msPerFrame

    /**
     * Hardware accelerated playback. Decoding 30 fps through MediaMetadataRetriever (what the
     * previous implementation did) is impossible to keep up with; ExoPlayer hands the file to the
     * platform's codec, so playback no longer competes with the frame extractor for the decoder.
     */
    val player = remember {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            // Audio is muted and not even selected: at these playback speeds audio would only
            // cost CPU and sound wrong.
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
            // Start playing from wherever the user scrubbed to without decoding up to an exact
            // frame first; that is what made "press play" feel sluggish.
            setSeekParameters(SeekParameters.CLOSEST_SYNC)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "Storage permission required to save frames", Toast.LENGTH_LONG).show()
        }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            isPlaying = false
            videoUri = uri
        }
    }

    // Opening the source reads container metadata, which can block for a noticeable time on large
    // files - it used to run inside the picker callback, on the main thread.
    LaunchedEffect(videoUri) {
        val uri = videoUri ?: return@LaunchedEffect
        isLoadingVideo = true
        stillFrame = null
        positionMs = 0L
        val opened = try {
            Result.success(VideoFrameDecoder.open(context, uri))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            Result.failure(t)
        }
        isLoadingVideo = false
        opened.onSuccess { loaded ->
            val previous = decoder
            decoder = loaded
            info = loaded.info
            previous?.close()
        }.onFailure { error ->
            decoder = null
            info = null
            Toast.makeText(context, "Could not open video: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(videoUri, player) {
        val uri = videoUri
        if (uri == null) {
            player.stop()
            player.clearMediaItems()
        } else {
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
        }
    }

    // The only decoding path for stills. Nothing polls: a decode happens when the requested
    // position, the viewer size or the playback state actually changes, and only the latest
    // request survives.
    LaunchedEffect(decoder) {
        val active = decoder ?: return@LaunchedEffect
        snapshotFlow { SeekRequest(positionMs, Timeline.previewDimension(viewerSize), isPlaying) }
            .distinctUntilChanged()
            .collectLatest { request ->
                if (request.playing) return@collectLatest
                val frameIndex = Timeline.frameIndex(request.positionMs, active.info.msPerFrame)
                val bitmap = frameCache.get(frameIndex, request.maxDimension)
                    ?: active.decodePreview(request.positionMs, request.maxDimension)?.also {
                        frameCache.put(frameIndex, request.maxDimension, it)
                    }
                if (bitmap != null) {
                    stillFrame = bitmap.asImageBitmap()
                }
            }
    }

    LaunchedEffect(isPlaying, player) {
        if (isPlaying) {
            player.seekTo(positionMs)
            player.play()
        } else {
            player.pause()
            val current = player.currentPosition
            if (current > 0L) {
                positionMs = if (durationMs > 0L) current.coerceAtMost(durationMs) else current
            }
        }
    }

    // Coarse polling instead of per-frame updates: the timeline only needs to look continuous,
    // and this keeps the composable from being invalidated 60 times a second.
    LaunchedEffect(isPlaying, player, durationMs) {
        while (isPlaying) {
            val current = player.currentPosition.coerceAtLeast(0L)
            positionMs = if (durationMs > 0L) current.coerceAtMost(durationMs) else current
            if (player.playbackState == Player.STATE_ENDED) {
                positionMs = durationMs
                isPlaying = false
            }
            delay(POSITION_POLL_MS)
        }
    }

    val playbackSpeed = Timeline.playbackSpeed(playSpeedFps, (info ?: VideoInfo.EMPTY).fps)
    LaunchedEffect(playbackSpeed, player) {
        player.setPlaybackSpeed(playbackSpeed)
    }

    val saveFrame: () -> Unit = {
        val active = decoder
        if (!isExtracting && active != null) {
            val legacyWriteDenied = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED
            if (legacyWriteDenied) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                val timeMs = positionMs
                isPlaying = false
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
            info = info,
            hasVideo = videoUri != null,
            onOpenVideo = { pickerLauncher.launch("video/*") },
            onEject = {
                isPlaying = false
                videoUri = null
                decoder = null
                info = null
                stillFrame = null
                positionMs = 0L
                // The viewer keeps its measured size: resetting it would leave the next clip
                // decoding at the fallback resolution until the layout changed again.
                frameCache.clear()
            },
        )

        FrameViewer(
            stillFrame = stillFrame,
            player = player,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            placeholder = when {
                videoUri == null -> "No video selected"
                isLoadingVideo -> "Opening video..."
                else -> "Loading frame..."
            },
            onViewerSizeChanged = { viewerSize = it },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        if (videoUri != null) {
            TransportControls(
                positionMs = positionMs,
                durationMs = durationMs,
                msPerFrame = msPerFrame,
                isPlaying = isPlaying,
                playSpeedFps = playSpeedFps,
                isExtracting = isExtracting,
                onScrub = { target ->
                    isPlaying = false
                    positionMs = target.coerceIn(0L, durationMs.coerceAtLeast(0L))
                },
                onTogglePlay = { isPlaying = !isPlaying },
                onStep = { delta ->
                    isPlaying = false
                    positionMs = Timeline.step(positionMs, delta, msPerFrame, durationMs)
                },
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

@Composable
private fun FrameViewer(
    stillFrame: ImageBitmap?,
    player: Player,
    isPlaying: Boolean,
    isBuffering: Boolean,
    placeholder: String,
    onViewerSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    var zoom by remember { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Playback renders through the player's surface, which cannot be transformed, so drop any
    // zoom instead of silently ignoring it.
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            zoom = MIN_ZOOM
            offset = Offset.Zero
        }
    }

    Box(
        modifier = modifier.onSizeChanged(onViewerSizeChanged),
        contentAlignment = Alignment.Center,
    ) {
        VideoSurface(
            player = player,
            visible = isPlaying,
            modifier = Modifier.fillMaxSize(),
        )

        if (!isPlaying) {
            // Opaque so the playback surface behind it can never bleed through the letterbox.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoomChange, _ ->
                            val updatedZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                            val maxX = (updatedZoom - 1f) * size.width / 2f
                            val maxY = (updatedZoom - 1f) * size.height / 2f
                            zoom = updatedZoom
                            offset = Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                val frame = stillFrame
                if (frame == null) {
                    Text(color = Color.Gray, text = placeholder)
                } else {
                    Image(
                        bitmap = frame,
                        contentDescription = "Current frame",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                }
            }
        }

        if (isPlaying && isBuffering) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun VideoSurface(
    player: Player,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            SurfaceView(viewContext).also(player::setVideoSurfaceView)
        },
        update = { view ->
            view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        },
    )
}

@Composable
private fun TransportControls(
    positionMs: Long,
    durationMs: Long,
    msPerFrame: Long,
    isPlaying: Boolean,
    playSpeedFps: Float,
    isExtracting: Boolean,
    onScrub: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    onStep: (Long) -> Unit,
    onSpeedDelta: (Float) -> Unit,
    onSave: () -> Unit,
) {
    val previousInteraction = remember { MutableInteractionSource() }
    val isPreviousPressed by previousInteraction.collectIsPressedAsState()
    val nextInteraction = remember { MutableInteractionSource() }
    val isNextPressed by nextInteraction.collectIsPressedAsState()

    val repeatDelayMs = (1000f / playSpeedFps).toLong().coerceAtLeast(16L)

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
                    text = "Frame ${Timeline.frameNumber(positionMs, msPerFrame)} / " +
                        "${Timeline.totalFrames(durationMs, msPerFrame)} • ${Timeline.formatPosition(positionMs)}",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = "${playSpeedFps.toInt()} FPS Speed",
                    color = Color.Gray,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Slider(
                value = positionMs.toFloat(),
                onValueChange = { onScrub(it.toLong()) },
                valueRange = 0f..(if (durationMs > 0L) durationMs.toFloat() else 100f),
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
