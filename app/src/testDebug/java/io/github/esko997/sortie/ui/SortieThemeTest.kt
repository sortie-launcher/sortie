package io.github.esko997.sortie.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SortieThemeTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    private lateinit var controller: ActivityController<ComponentActivity>

    @get:Rule(order = 1)
    val host = object : ExternalResource() {
        override fun before() {
            controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        }

        override fun after() {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun previewIsLocalAndEveryTokenFollowsItsOwnHost() {
        val selected = mutableStateOf(SortiePalette.DEFAULT)
        val preview = mutableStateOf(SortiePalette.PLUM)
        var before = emptyList<Color>()
        var inside = emptyList<Color>()
        var after = emptyList<Color>()
        controller.get().setContent {
            SortieTheme(selected.value) {
                val outer = listOf(Accent, CanvasColour, PanelColour, RuleColour, MaterialTheme.colorScheme.onSurface)
                SideEffect { before = outer }
                SortieTheme(preview.value) {
                    val local = listOf(Accent, CanvasColour, PanelColour, RuleColour, MaterialTheme.colorScheme.onSurface)
                    SideEffect { inside = local }
                }
                val restored = listOf(Accent, CanvasColour, PanelColour, RuleColour, MaterialTheme.colorScheme.onSurface)
                SideEffect { after = restored }
            }
        }
        compose.runOnIdle {
            assertEquals(tokens(SortiePalette.DEFAULT), before)
            assertEquals(tokens(SortiePalette.PLUM), inside)
            assertEquals(before, after)
            preview.value = SortiePalette.LICHEN
        }
        compose.runOnIdle {
            assertEquals(tokens(SortiePalette.DEFAULT), before)
            assertEquals(tokens(SortiePalette.LICHEN), inside)
            assertEquals(before, after)
            selected.value = SortiePalette.HARBOR
        }
        compose.runOnIdle {
            assertEquals(tokens(SortiePalette.HARBOR), before)
            assertEquals(tokens(SortiePalette.LICHEN), inside)
            assertEquals(before, after)
        }
    }

    @Test
    fun launcherSelectionsAreScopedAndDoNotLeakFromPreview() {
        val folders = mutableStateOf(SortieFolderIcons.DRAWERS)
        val buttons = mutableStateOf(SortieButtonStyle.INSTRUMENT)
        var before: Pair<SortieFolderIcons, SortieButtonStyle>? = null
        var inside: Pair<SortieFolderIcons, SortieButtonStyle>? = null
        var after: Pair<SortieFolderIcons, SortieButtonStyle>? = null
        controller.get().setContent {
            SortieTheme(folderIcons = folders.value, buttonStyle = buttons.value) {
                val outer = LocalSortieFolderIcons.current to LocalSortieButtonStyle.current
                SideEffect { before = outer }
                SortieTheme(folderIcons = SortieFolderIcons.PIXEL, buttonStyle = SortieButtonStyle.ARCADE) {
                    val preview = LocalSortieFolderIcons.current to LocalSortieButtonStyle.current
                    SideEffect { inside = preview }
                    SortieFolderIconPreview(SortieFolderIcons.PIXEL)
                    SortieButtonStylePreview(SortieButtonStyle.ARCADE)
                }
                val restored = LocalSortieFolderIcons.current to LocalSortieButtonStyle.current
                SideEffect { after = restored }
            }
        }
        compose.runOnIdle {
            assertEquals(SortieFolderIcons.DRAWERS to SortieButtonStyle.INSTRUMENT, before)
            assertEquals(SortieFolderIcons.PIXEL to SortieButtonStyle.ARCADE, inside)
            assertEquals(before, after)
            folders.value = SortieFolderIcons.CARTRIDGES
            buttons.value = SortieButtonStyle.DEFAULT
        }
        compose.runOnIdle {
            assertEquals(SortieFolderIcons.CARTRIDGES to SortieButtonStyle.DEFAULT, before)
            assertEquals(SortieFolderIcons.PIXEL to SortieButtonStyle.ARCADE, inside)
            assertEquals(before, after)
        }
    }

    @Test
    fun everyButtonTreatmentRetainsClickAndDisabledSemantics() {
        val style = mutableStateOf(SortieButtonStyle.DEFAULT)
        var clicks = 0
        controller.get().setContent {
            SortieTheme(buttonStyle = style.value) {
                androidx.compose.foundation.layout.Column {
                    SortieButton(onClick = { clicks++ }) { Text("Launch") }
                    SortieOutlinedButton(onClick = { clicks++ }, enabled = false) { Text("Unavailable") }
                }
            }
        }
        for (treatment in SortieButtonStyle.entries) {
            compose.runOnIdle { style.value = treatment }
            compose.onNodeWithText("Launch").performClick()
            compose.onNodeWithText("Unavailable").assertIsNotEnabled()
        }
        compose.runOnIdle { assertEquals(SortieButtonStyle.entries.size, clicks) }
    }

    private fun tokens(palette: SortiePalette): List<Color> = palette.colours.run {
        listOf(primary, background, surfaceContainer, outlineVariant, onSurface)
    }
}
