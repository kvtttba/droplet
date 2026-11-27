package io.github.kvtttba.droplet.ui

import android.app.Application
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.kvtttba.droplet.core.ui.theme.DropletTheme
import io.github.kvtttba.droplet.core.ui.theme.ThemeMode
import io.github.kvtttba.droplet.feature.aicapture.QuickCaptureSheetContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    application = Application::class,
    sdk = [35],
    qualifiers = "w412dp-h915dp-xxhdpi",
)
class DropletAppScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val roborazziOptions = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(
            changeThreshold = 0.015f,
        ),
    )

    @Test
    fun captureLedgerLight() {
        composeRule.setContent {
            DropletApp(
                initialTab = DropletTab.LEDGER,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
            )
        }
        composeRule.onNodeWithTag("screen_ledger").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ledger_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureLedgerDark() {
        composeRule.setContent {
            DropletApp(
                initialTab = DropletTab.LEDGER,
                initialThemeMode = ThemeMode.DARK,
                initialDynamicColor = false,
            )
        }
        composeRule.onNodeWithTag("screen_ledger").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ledger_dark.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureLedgerPrivacyHidden() {
        composeRule.setContent {
            DropletApp(
                initialTab = DropletTab.LEDGER,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
                initialAmountsHidden = true,
            )
        }
        composeRule.onNodeWithTag("ledger_monthly_balance").assertTextContains("****", substring = true)
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/ledger_privacy_hidden.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureAssetsLight() {
        composeRule.setContent {
            DropletApp(
                initialTab = DropletTab.ASSETS,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
            )
        }
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/assets_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureAssetsDark() {
        composeRule.setContent {
            DropletApp(
                initialTab = DropletTab.ASSETS,
                initialThemeMode = ThemeMode.DARK,
                initialDynamicColor = false,
            )
        }
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/assets_dark.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureStatsLight() {
        composeRule.setContent {
            DropletApp(
                initialTab = DropletTab.STATS,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
            )
        }
        composeRule.onNodeWithTag("screen_stats").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/stats_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureSettingsLight() {
        composeRule.setContent {
            DropletApp(
                initialTab = DropletTab.SETTINGS,
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
            )
        }
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/settings_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun captureQuickCaptureSheetLight() {
        composeRule.setContent {
            DropletTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                Surface {
                    QuickCaptureSheetContent(onSelectMode = {})
                }
            }
        }
        composeRule.onNodeWithTag("quick_capture_sheet").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage(
            filePath = "src/test/snapshots/quick_capture_sheet_light.png",
            roborazziOptions = roborazziOptions,
        )
    }

    @Test
    fun interactiveShell_navigationPrivacyAndFabWork() {
        composeRule.setContent {
            DropletApp(
                initialThemeMode = ThemeMode.LIGHT,
                initialDynamicColor = false,
            )
        }

        // Toggle privacy on Ledger and confirm masking persists to Assets
        composeRule.onNodeWithTag("privacy_toggle_button").performClick()
        composeRule.onNodeWithTag("ledger_monthly_balance").assertTextContains("****", substring = true)

        composeRule.onNodeWithTag("nav_tab_assets").performClick()
        composeRule.onNodeWithTag("screen_assets").assertIsDisplayed()
        composeRule.onNodeWithTag("assets_net_worth").assertTextContains("****", substring = true)

        composeRule.onNodeWithTag("nav_tab_stats").performClick()
        composeRule.onNodeWithTag("screen_stats").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.onNodeWithTag("screen_settings").assertIsDisplayed()

        // Open Quick Capture FAB sheet
        composeRule.onNodeWithTag("fab_quick_add").performClick()
        composeRule.onNodeWithTag("quick_capture_sheet").assertIsDisplayed()
    }
}
