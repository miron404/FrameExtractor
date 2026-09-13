package com.tailgunnerx.frameextractor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tailgunnerx.frameextractor.ui.FrameExtractorScreen
import com.tailgunnerx.frameextractor.ui.theme.FrameExtractorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real screen against a real clip: this is the end-to-end check that the still-frame
 * pipeline, the timeline and the player are wired together and that nothing blocks the main thread
 * long enough to trip up the test clock.
 */
@RunWith(AndroidJUnit4::class)
class FrameExtractorScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun setContentWithVideo() {
        val uri = TestVideo.copyToCache()
        rule.setContent {
            FrameExtractorTheme(darkTheme = true, dynamicColor = false) {
                FrameExtractorScreen(initialVideoUri = uri)
            }
        }
    }

    private fun awaitVideoLoaded() {
        rule.waitUntil(timeoutMillis = 20_000) {
            rule.onAllNodesWithText("Frame 1 / ", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun showsEmptyStateWithoutVideo() {
        rule.setContent {
            FrameExtractorTheme(darkTheme = true, dynamicColor = false) {
                FrameExtractorScreen()
            }
        }
        rule.onNodeWithText("No video selected").assertIsDisplayed()
        rule.onNodeWithText("Open Video").assertIsDisplayed()
    }

    @Test
    fun loadsTheClipAndReportsItsProperties() {
        setContentWithVideo()
        awaitVideoLoaded()
        rule.onNodeWithText("320x240", substring = true).assertIsDisplayed()
        rule.onNodeWithText("30.0 FPS", substring = true).assertIsDisplayed()
    }

    @Test
    fun frameSteppingMovesTheTimeline() {
        setContentWithVideo()
        awaitVideoLoaded()

        repeat(3) { rule.onNodeWithContentDescription("Next frame").performClick() }
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Frame 4 / ", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithContentDescription("Previous frame").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("Frame 3 / ", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun playbackAdvancesThePositionAndPausesAgain() {
        setContentWithVideo()
        awaitVideoLoaded()

        rule.onNodeWithContentDescription("Play").performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithContentDescription("Pause").fetchSemanticsNodes().isNotEmpty()
        }

        // Playback runs at 5 fps of a 30 fps clip, so the position has to leave zero shortly after
        // the button flips. Waiting on real time here also proves the position is being polled from
        // the player rather than the (now removed) decode-every-frame loop.
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("0:00.000", substring = true).fetchSemanticsNodes().isEmpty()
        }

        rule.onNodeWithContentDescription("Pause").performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithContentDescription("Play").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun slowerAndFasterButtonsAdjustThePlaybackRate() {
        setContentWithVideo()
        awaitVideoLoaded()

        rule.onNodeWithContentDescription("Faster").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("6 FPS Speed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        rule.onNodeWithContentDescription("Slower").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("5 FPS Speed", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
