package com.tailgunnerx.frameextractor

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Launches the real activity: theme wiring, player construction and the empty state all have to
 * survive a cold start.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun startsOnTheEmptyState() {
        rule.onNodeWithText("Frame Extractor").assertIsDisplayed()
        rule.onNodeWithText("Open Video").assertIsDisplayed()
        rule.onNodeWithText("No video selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Eject video").assertDoesNotExist()
    }
}
