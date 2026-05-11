package io.github.ntufar.stasi

import androidx.annotation.StringRes
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun str(@StringRes id: Int): String = composeRule.activity.getString(id)

    @Test
    fun homeTopBarShowsAppName() {
        composeRule.onNodeWithText(str(R.string.app_name)).assertExists()
    }

    @Test
    fun drawer_navigateToSearch_showsSearchTitle() {
        composeRule.onNodeWithContentDescription(str(R.string.cd_menu)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.nav_search)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.search_title)).assertExists()
    }

    @Test
    fun drawer_navigateToRouteMap_showsManualMapChrome() {
        composeRule.onNodeWithContentDescription(str(R.string.cd_menu)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.nav_map)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.map_title_fallback)).assertExists()
        composeRule.onNodeWithText(str(R.string.map_show)).assertExists()
    }

    @Test
    fun search_back_returnsToHome() {
        composeRule.onNodeWithContentDescription(str(R.string.cd_menu)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.nav_search)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(str(R.string.app_name)).assertExists()
    }
}
