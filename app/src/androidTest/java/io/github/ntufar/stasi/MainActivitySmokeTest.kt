package io.github.ntufar.stasi

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun waitForAppReady() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodes(hasTestTag("screen_home"))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun homeScreenIsDisplayed() {
        composeRule.onNodeWithTag("screen_home").assertIsDisplayed()
    }

    @Test
    fun drawer_navigateToSearch() {
        composeRule.onNodeWithTag("btn_menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawer_search").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("screen_search"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("screen_search").assertIsDisplayed()
    }

    @Test
    fun drawer_navigateToRouteMap() {
        composeRule.onNodeWithTag("btn_menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawer_map").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("screen_map"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("screen_map").assertIsDisplayed()
    }

    @Test
    fun search_back_returnsToHome() {
        composeRule.onNodeWithTag("btn_menu").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("drawer_search").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("screen_search"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("btn_back").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasTestTag("screen_home"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("screen_home").assertIsDisplayed()
    }
}
