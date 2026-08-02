package com.tailgunnerx.frameextractor

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Custom UI Icons built directly so you don't have to download external Google ML/Icon packages!
val PauseIcon = ImageVector.Builder("Pause", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(6f, 19f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineTo(6f); verticalLineToRelative(14f); close()
        moveTo(14f, 5f); verticalLineToRelative(14f); horizontalLineToRelative(4f); verticalLineTo(5f); horizontalLineToRelative(-4f); close()
    }
}.build()

val SkipNextIcon = ImageVector.Builder("SkipNext", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(6f, 18f); lineToRelative(8.5f, -6f); lineTo(6f, 6f); verticalLineToRelative(12f); close()
        moveTo(16f, 6f); verticalLineToRelative(12f); horizontalLineToRelative(2f); verticalLineTo(6f); horizontalLineToRelative(-2f); close()
    }
}.build()

val SkipPreviousIcon = ImageVector.Builder("SkipPrevious", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(6f, 6f); horizontalLineToRelative(2f); verticalLineToRelative(12f); horizontalLineTo(6f); close()
        moveTo(8.5f, 12f); lineTo(17f, 18f); verticalLineTo(6f); lineToRelative(-8.5f, 6f); close()
    }
}.build()

val PlayIcon = ImageVector.Builder("Play", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(8f, 5f); verticalLineToRelative(14f); lineToRelative(11f, -7f); close()
    }
}.build()

val AddIcon = ImageVector.Builder("Add", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(19f, 13f); horizontalLineToRelative(-6f); verticalLineToRelative(6f); horizontalLineToRelative(-2f); verticalLineToRelative(-6f); horizontalLineTo(5f); verticalLineToRelative(-2f); horizontalLineToRelative(6f); verticalLineTo(5f); horizontalLineToRelative(2f); verticalLineToRelative(6f); horizontalLineToRelative(6f); verticalLineToRelative(2f); close()
    }
}.build()

val RemoveIcon = ImageVector.Builder("Remove", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(19f, 13f); horizontalLineTo(5f); verticalLineToRelative(-2f); horizontalLineToRelative(14f); verticalLineToRelative(2f); close()
    }
}.build()

val CloseIcon = ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(19f, 6.41f); lineTo(17.59f, 5f); lineTo(12f, 10.59f); lineTo(6.41f, 5f); lineTo(5f, 6.41f); lineTo(10.59f, 12f); lineTo(5f, 17.59f); lineTo(6.41f, 19f); lineTo(12f, 13.41f); lineTo(17.59f, 19f); lineTo(19f, 17.59f); lineTo(13.41f, 12f); lineTo(19f, 6.41f); close()
    }
}.build()

val SaveIcon = ImageVector.Builder("Save", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(12f, 16f); lineTo(7f, 11f); horizontalLineToRelative(4f); verticalLineTo(4f); horizontalLineToRelative(2f); verticalLineToRelative(7f); horizontalLineToRelative(4f); lineTo(12f, 16f); close()
        moveTo(5f, 18f); horizontalLineToRelative(14f); verticalLineToRelative(2f); horizontalLineTo(5f); close()
    }
}.build()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FrameExtractorApp()
            }
        }
    }
}

@Composable
fun FrameExtractorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var videoUri by remember { mutableStateOf<Uri?>(null) }
    var currentPositionMs by remember { mutableStateOf(0L) }
    var videoDurationMs by remember { mutableStateOf(0L) }
    var isExtracting by remember { mutableStateOf(false) }

    var videoResolution by remember { mutableStateOf("") }
    var videoFps by remember { mutableFloatStateOf(30f) }
    var msPerFrame by remember { mutableLongStateOf(33L) }

    var isPlaying by remember { mutableStateOf(false) }
    var playSpeedFps by remember { mutableFloatStateOf(5f) }

    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var playerSurfaceSet by remember { mutableStateOf(false) }

    val player = remember {
        ExoPlayer.Builder(context).build()
    }

    // Pause when activity goes to background
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        player.pause()
        isPlaying = false
    }

    // Release player when composable leaves composition
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Storage permission required to save frames", Toast.LENGTH_LONG).show()
        }
    }

    // Player listener – updates UI state from ExoPlayer events
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                if (!playerSurfaceSet && availableCommands.contains(Player.COMMAND_SET_VIDEO_SURFACE)) {
                    textureView?.let { player.setVideoTextureView(it) }
                    playerSurfaceSet = true
                }
            }

            override fun onEvents(player: Player, events: Player.Events) {
                super.onEvents(player, events)
                if (player.duration > 0L) {
                    videoDurationMs = player.duration
                    val fps = player.videoFormat?.frameRate ?: 30f
                    videoFps = fps
                    msPerFrame = if (fps > 0f) (1000f / fps).toLong().coerceAtLeast(1L) else 33L

                    val w = player.videoFormat?.width ?: 0
                    val h = player.videoFormat?.height ?: 0
                    if (w > 0 && h > 0) videoResolution = "${w}x${h}"
                }
                isPlaying = player.isPlaying
                currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                if (player.playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            videoUri = uri
            currentPositionMs = 0L
            isPlaying = false
            videoDurationMs = 0L
            videoFps = 30f
            msPerFrame = 33L
            videoResolution = ""

            player.stop()
            playerSurfaceSet = false
            player.setMediaItem(MediaItem.fromUri(uri))
            player.prepare()
            player.pause()
            player.seekTo(0L)
        }
    }

    // Long-press: previous frame
    val prevInteractionSource = remember { MutableInteractionSource() }
    val isPrevPressed by prevInteractionSource.collectIsPressedAsState()

    LaunchedEffect(isPrevPressed) {
        if (isPrevPressed && !isPlaying) {
            delay(400)
            while (isActive && isPrevPressed) {
                stepFrame(player, videoDurationMs, msPerFrame, -1)
                delay((1000f / playSpeedFps).toLong())
            }
        }
    }

    // Long-press: next frame
    val nextInteractionSource = remember { MutableInteractionSource() }
    val isNextPressed by nextInteractionSource.collectIsPressedAsState()

    LaunchedEffect(isNextPressed) {
        if (isNextPressed && !isPlaying) {
            delay(400)
            while (isActive && isNextPressed) {
                stepFrame(player, videoDurationMs, msPerFrame, 1)
                delay((1000f / playSpeedFps).toLong())
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- TOP BAR ---
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Frame Extractor", color = Color.White, style = MaterialTheme.typography.titleLarge)
                if (videoUri != null) {
                    val formattedFps = "%.1f".format(videoFps)
                    Text("$videoResolution • $formattedFps FPS", color = Color.Gray, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (videoUri != null) {
                IconButton(
                    onClick = {
                        player.stop()
                        playerSurfaceSet = false
                        videoUri = null
                        isPlaying = false
                        currentPositionMs = 0L
                        videoDurationMs = 0L
                    },
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(CloseIcon, contentDescription = "Eject Video", tint = MaterialTheme.colorScheme.error)
                }
            }

            Button(onClick = { pickerLauncher.launch("video/*") }) {
                Text(if (videoUri == null) "Open Video" else "Change")
            }
        }

        // --- VIDEO VIEWER ---
        var scale by remember { mutableFloatStateOf(1f) }
        var offsetX by remember { mutableFloatStateOf(0f) }
        var offsetY by remember { mutableFloatStateOf(0f) }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        val panLimit = (scale - 1f) * 800f
                        offsetX = (offsetX + pan.x).coerceIn(-panLimit, panLimit)
                        offsetY = (offsetY + pan.y).coerceIn(-panLimit, panLimit)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (videoUri != null) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                    factory = {
                        TextureView(context).apply {
                            textureView = this
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    }
                )
            } else {
                Text("No video selected", color = Color.Gray)
            }
        }

        // --- CONTROL BOARD ---
        if (videoUri != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Frame & Speed Info
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val currentFrame = if (msPerFrame > 0) (currentPositionMs / msPerFrame).toInt() + 1 else 1
                        val totalFrames = if (msPerFrame > 0) (videoDurationMs / msPerFrame).toInt() + 1 else 1
                        Text("Frame $currentFrame / $totalFrames", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Text("${playSpeedFps.roundToInt()} FPS Speed", color = Color.Gray, style = MaterialTheme.typography.labelLarge)
                    }

                    // Timeline Slider
                    Slider(
                        value = currentPositionMs.toFloat(),
                        onValueChange = {
                            currentPositionMs = it.toLong()
                            isPlaying = false
                            player.pause()
                            player.seekTo(it.toLong())
                        },
                        valueRange = 0f..(if (videoDurationMs > 0) videoDurationMs.toFloat() else 100f),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Player Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Save Frame
                        FloatingActionButton(
                            onClick = {
                                if (isExtracting || videoUri == null) return@FloatingActionButton
                                val tv = textureView ?: return@FloatingActionButton

                                // Storage permission for legacy devices
                                if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
                                    val permission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    if (context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        permissionLauncher.launch(permission)
                                        return@FloatingActionButton
                                    }
                                }

                                isExtracting = true
                                isPlaying = false
                                player.pause()
                                scope.launch {
                                    try {
                                        val bitmap = tv.bitmap
                                        if (bitmap == null) {
                                            Toast.makeText(context, "Failed to capture frame", Toast.LENGTH_SHORT).show()
                                            return@launch
                                        }
                                        saveBitmapToPictures(context, bitmap, currentPositionMs)
                                        Toast.makeText(context, "Saved frame to Pictures!", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(context, "Error saving frame: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        isExtracting = false
                                    }
                                }
                            },
                            containerColor = Color.White,
                            contentColor = Color.Black,
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape
                        ) {
                            if (isExtracting) {
                                CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                            } else {
                                Icon(SaveIcon, contentDescription = "Extract Frame", tint = Color.Black, modifier = Modifier.size(24.dp))
                            }
                        }

                        // Slow Down
                        IconButton(onClick = { playSpeedFps = (playSpeedFps - 1f).coerceAtLeast(1f) }) {
                            Icon(RemoveIcon, contentDescription = "Slower", tint = Color.Gray)
                        }

                        // Prev Frame
                        IconButton(
                            onClick = {
                                player.pause()
                                isPlaying = false
                                stepFrame(player, videoDurationMs, msPerFrame, -1)
                            },
                            interactionSource = prevInteractionSource,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(SkipPreviousIcon, contentDescription = "Prev Frame", tint = Color.White, modifier = Modifier.size(36.dp))
                        }

                        // Play/Pause
                        FloatingActionButton(
                            onClick = {
                                if (isPlaying) {
                                    player.pause()
                                    isPlaying = false
                                } else {
                                    if (player.playbackState == Player.STATE_ENDED) {
                                        player.seekTo(0)
                                    }
                                    player.play()
                                    isPlaying = true
                                }
                            },
                            containerColor = if (isPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape
                        ) {
                            Icon(
                                imageVector = if (isPlaying) PauseIcon else PlayIcon,
                                contentDescription = "Play/Pause",
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        // Next Frame
                        IconButton(
                            onClick = {
                                player.pause()
                                isPlaying = false
                                stepFrame(player, videoDurationMs, msPerFrame, 1)
                            },
                            interactionSource = nextInteractionSource,
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(SkipNextIcon, contentDescription = "Next Frame", tint = Color.White, modifier = Modifier.size(36.dp))
                        }

                        // Speed Up
                        IconButton(onClick = { playSpeedFps = (playSpeedFps + 1f).coerceAtMost(60f) }) {
                            Icon(AddIcon, contentDescription = "Faster", tint = Color.Gray)
                        }
                    }
                }
            }
        }
    }
}

/** Seek the player one frame forward (+1) or backward (-1). */
private fun stepFrame(player: ExoPlayer, durationMs: Long, msPerFrame: Long, direction: Int) {
    val newPos = if (direction > 0)
        (player.currentPosition + msPerFrame).coerceAtMost(durationMs)
    else
        (player.currentPosition - msPerFrame).coerceAtLeast(0L)
    player.seekTo(newPos)
}

private fun saveBitmapToPictures(context: android.content.Context, bitmap: Bitmap, timestampMs: Long) {
    val fileName = "ExtractedFrame_${timestampMs}ms.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FrameExtractor")
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        ?: throw Exception("Failed to create MediaStore entry")

    try {
        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw Exception("Failed to compress bitmap")
            }
        } ?: throw Exception("Failed to open output stream")

        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES).absolutePath + "/FrameExtractor/" + fileName),
            arrayOf("image/png"),
            null
        )
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        throw e
    }
}
