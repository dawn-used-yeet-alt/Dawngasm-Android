package dev.dawngasm.app.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.dawngasm.app.DawngasmApp
import dev.dawngasm.app.NoOpDawngasmActions
import dev.dawngasm.app.NoOpSettingsPlatformActions
import dev.dawngasm.app.sampleAppUiState
import dev.dawngasm.app.state.PlaybackProgress
import dev.dawngasm.app.ui.components.MiniPlayer
import dev.dawngasm.app.ui.theme.DawngasmTheme
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden screenshots for the shared UI as hosted by the Android app.
 *
 * These are the Android half of the Compose Multiplatform parity guardrail:
 * [dev.dawngasm.app.sampleAppUiState] keeps the content deterministic while the
 * Robolectric qualifiers pin the device size, density, and light theme. Record
 * the reference images with `./gradlew :app:recordRoborazziDebug` and check them
 * with `./gradlew :app:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class AppScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = sampleAppUiState()
    private val progress = PlaybackProgress(positionMs = 42_000L, durationMs = 180_000L)

    private fun showApp() {
        compose.setContent {
            DawngasmTheme(darkTheme = false) {
                DawngasmApp(
                    state = state,
                    progress = progress,
                    messages = emptyFlow(),
                    actions = NoOpDawngasmActions,
                    settingsPlatform = NoOpSettingsPlatformActions,
                    appVersion = "1.0.0",
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun home() {
        showApp()
        compose.onRoot().captureRoboImage("src/test/screenshots/home.png")
    }

    @Test
    fun library() {
        showApp()
        compose.onNodeWithTag("nav_tab_library").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/library.png")
    }

    @Test
    fun settings() {
        showApp()
        compose.onNodeWithTag("nav_tab_settings").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/settings.png")
    }

    @Test
    fun searchSheet() {
        showApp()
        compose.onNodeWithTag("search_fab").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/search_sheet.png")
    }

    @Test
    fun miniPlayer() {
        compose.setContent {
            DawngasmTheme(darkTheme = false) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    MiniPlayer(
                        player = state.player,
                        progress = progress,
                        isSaved = true,
                        onTogglePlayPause = {},
                        onToggleSave = {},
                        onNextTrack = {},
                        onDismiss = {},
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/mini_player.png")
    }
}
