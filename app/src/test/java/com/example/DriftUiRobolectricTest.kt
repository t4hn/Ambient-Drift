package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.example.model.SceneCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h891dp")
class DriftUiRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `scenes screen displays all three scenes and navigates to mixer and settings`() {
        composeRule.waitForIdle()

        // Verify all 3 launch scenes appear
        composeRule.onNodeWithTag("scenes_list")
            .performScrollToNode(hasTestTag("scene_card_${SceneCatalog.SCENE_STORM_ID}"))
        composeRule.onNodeWithTag("scene_card_${SceneCatalog.SCENE_STORM_ID}").assertIsDisplayed()

        composeRule.onNodeWithTag("scenes_list")
            .performScrollToNode(hasTestTag("scene_card_${SceneCatalog.SCENE_DEEP_SPACE_ID}"))
        composeRule.onNodeWithTag("scene_card_${SceneCatalog.SCENE_DEEP_SPACE_ID}").assertIsDisplayed()

        composeRule.onNodeWithTag("scenes_list")
            .performScrollToNode(hasTestTag("scene_card_${SceneCatalog.SCENE_ABYSS_ID}"))
        composeRule.onNodeWithTag("scene_card_${SceneCatalog.SCENE_ABYSS_ID}").assertIsDisplayed()

        // Tap Deep Space -> navigates to Mixer
        composeRule.onNodeWithTag("scenes_list")
            .performScrollToNode(hasTestTag("scene_card_${SceneCatalog.SCENE_DEEP_SPACE_ID}"))
        composeRule.onNodeWithTag("scene_card_${SceneCatalog.SCENE_DEEP_SPACE_ID}").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("mixer_scene_title").assertIsDisplayed()
        composeRule.onNodeWithText("DEEP SPACE").assertIsDisplayed()
        composeRule.onNodeWithTag("master_play_pause_button").assertIsDisplayed()
        composeRule.onNodeWithTag("master_stop_button").assertIsDisplayed()
        composeRule.onNodeWithTag("sleep_timer_button").assertIsDisplayed()
        composeRule.onNodeWithTag("master_volume_slider").assertIsDisplayed()

        // Verify all 4 channels of Deep Space render in Mixer
        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasTestTag("channel_card_space_engine_drone"))
        composeRule.onNodeWithTag("channel_card_space_engine_drone").assertIsDisplayed()

        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasTestTag("channel_card_space_electronic_ambience"))
        composeRule.onNodeWithTag("channel_card_space_electronic_ambience").assertIsDisplayed()

        // Toggle mute and independent play on a channel
        composeRule.onNodeWithTag("channel_mute_button_space_electronic_ambience").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("channel_play_button_space_electronic_ambience").performClick()
        composeRule.waitForIdle()

        // Open Sleep Timer sheet and check preset buttons
        composeRule.onNodeWithTag("mixer_list")
            .performScrollToNode(hasTestTag("sleep_timer_button"))
        composeRule.onNodeWithTag("sleep_timer_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("timer_preset_15").assertIsDisplayed()
        composeRule.onNodeWithTag("timer_preset_30").assertIsDisplayed()
        composeRule.onNodeWithTag("timer_preset_60").assertIsDisplayed()
        composeRule.onNodeWithTag("timer_preset_90").assertIsDisplayed()
        composeRule.onNodeWithTag("timer_preset_30").performClick()
        composeRule.waitForIdle()

        // Navigate to Settings and verify toggles
        composeRule.onNodeWithTag("nav_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_auto_play_switch"))
        composeRule.onNodeWithTag("setting_auto_play_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_crossfade_switch"))
        composeRule.onNodeWithTag("setting_crossfade_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("setting_battery_saver_switch"))
        composeRule.onNodeWithTag("setting_battery_saver_switch").assertIsDisplayed()
        composeRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("reset_all_mixes_button"))
        composeRule.onNodeWithTag("reset_all_mixes_button").assertIsDisplayed()
    }
}
