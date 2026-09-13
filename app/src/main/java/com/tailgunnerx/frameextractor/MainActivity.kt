package com.tailgunnerx.frameextractor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.tailgunnerx.frameextractor.ui.FrameExtractorScreen
import com.tailgunnerx.frameextractor.ui.theme.FrameExtractorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // The theme (and therefore the whole colour scheme) is hoisted out of the composable
            // that changes every frame, so UI updates never rebuild it.
            FrameExtractorTheme(darkTheme = true, dynamicColor = false) {
                FrameExtractorScreen()
            }
        }
    }
}
