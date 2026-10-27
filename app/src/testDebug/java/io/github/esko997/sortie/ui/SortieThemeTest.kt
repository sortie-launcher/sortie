package io.github.esko997.sortie.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

    private fun tokens(palette: SortiePalette): List<Color> = palette.colours.run {
        listOf(primary, background, surfaceContainer, outlineVariant, onSurface)
    }
}
