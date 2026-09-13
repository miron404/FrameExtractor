package com.tailgunnerx.frameextractor.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Custom UI icons built directly in code so the app does not need to pull in an icon package.
// They are top level singletons: building them once instead of per frame keeps recomposition cheap.

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
