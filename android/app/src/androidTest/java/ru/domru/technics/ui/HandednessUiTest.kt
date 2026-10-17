package ru.domru.technics.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.domru.technics.model.CameraState
import ru.domru.technics.model.CodeActionState
import ru.domru.technics.model.DoorActionState
import ru.domru.technics.model.Entrance
import ru.domru.technics.model.Handedness
import ru.domru.technics.model.ThemeMode
import ru.domru.technics.ui.screens.EntranceListRow
import ru.domru.technics.ui.screens.WorkScreen
import ru.domru.technics.ui.theme.DomRuTechnicsTheme

/** Exercises the actual composables with an offline door, without creating a repository. */
@RunWith(AndroidJUnit4::class)
class HandednessUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mainGearOpensHandSettingsWithoutOpeningSavedLogins() {
        val state = mutableStateOf(AppUiState(showLogin = false))
        compose.setContent {
            DomRuTechnicsTheme(themeMode = ThemeMode.LIGHT) {
                WorkScreen(
                    state = state.value,
                    snackbarHostState = remember { SnackbarHostState() },
                    onAddAccount = {}, onRemoveAccount = {}, onRefreshAccounts = {},
                    onBeginPasswordRenewal = {}, onCancelPasswordRenewal = {}, onRenewPassword = {},
                    onThemeModeChange = { state.value = state.value.copy(themeMode = it) },
                    onHandednessChange = { state.value = state.value.copy(handedness = it) },
                    onRefreshAddresses = {}, onSearchChange = {}, onSearchResultClick = {},
                    onLocalityClick = {}, onStreetClick = {}, onHouseClick = { _, _ -> },
                    onEntranceClick = { _, _, _ -> }, onOpenDoor = {}, onRequestCode = {},
                    onRetryCamera = {}, onCloseCode = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Удобная рука").assertIsDisplayed()
        compose.onNodeWithText("Левая рука").performClick()
        compose.runOnIdle { assertTrue(state.value.handedness == Handedness.LEFT) }
        compose.onNodeWithText("Правая рука").performClick()
        compose.runOnIdle { assertTrue(state.value.handedness == Handedness.RIGHT) }
    }

    @Test
    fun compactAndExpandedDoorButtonsMirrorAndLargeFontKeepsTheSelectedSide() {
        val hand = mutableStateOf(Handedness.RIGHT)
        val expanded = mutableStateOf(false)
        val largeFont = mutableStateOf(false)
        val entrance = Entrance("test-door", "test-house", "Подъезд 1", cameraAvailable = false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,
                if (largeFont.value) 1.5f else 1f)) {
                DomRuTechnicsTheme(themeMode = ThemeMode.LIGHT) {
                    EntranceListRow(entrance = entrance, handedness = hand.value,
                        expanded = expanded.value, cameraState = CameraState.Idle,
                        doorState = DoorActionState.Idle, codeState = CodeActionState.Idle,
                        onOpenDoor = {}, onRequestCode = {}, onRetryCamera = {}, onToggle = {})
                }
            }
        }
        assertButtonBefore("ОТКРЫТЬ", "Под. 1")
        compose.runOnIdle { hand.value = Handedness.LEFT }
        assertButtonBefore("Под. 1", "ОТКРЫТЬ")
        compose.runOnIdle { expanded.value = true; hand.value = Handedness.RIGHT }
        assertButtonBefore("ОТКРЫТЬ", "КОД")
        compose.runOnIdle { hand.value = Handedness.LEFT }
        assertButtonBefore("КОД", "ОТКРЫТЬ")
        compose.runOnIdle { expanded.value = false; largeFont.value = true; hand.value = Handedness.RIGHT }
        compose.waitForIdle()
        val rightHandLeft = compose.onNodeWithText("ОТКРЫТЬ").fetchSemanticsNode().boundsInRoot.left
        compose.runOnIdle { hand.value = Handedness.LEFT }
        compose.waitForIdle()
        val leftHandLeft = compose.onNodeWithText("ОТКРЫТЬ").fetchSemanticsNode().boundsInRoot.left
        assertTrue("Large-font opening must move to the right for a left hand", leftHandLeft > rightHandLeft)
        compose.onNodeWithText("Под. 1").assertIsDisplayed()
        compose.onNodeWithText("ОТКРЫТЬ").assertIsDisplayed()
    }

    private fun assertButtonBefore(left: String, right: String) {
        compose.waitForIdle()
        val first = compose.onNodeWithText(left).fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText(right).fetchSemanticsNode().boundsInRoot
        assertTrue("$left must appear before $right", first.right <= second.left)
    }
}
