package io.github.esko997.sortie.ui

import io.github.esko997.sortie.data.db.SessionEntity
import io.github.esko997.sortie.stats.CompanionPanel
import io.github.esko997.sortie.stats.CompanionView
import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionPanelTest {
    private val game = SessionEntity(gameId = "ps2/Okami.chd", profileId = "aethersx2", startedAt = 1_000, endedAt = null)
    private val app = SessionEntity(gameId = "app:org.ppsspp.ppsspp", profileId = null, startedAt = 2_000, endedAt = null)

    private fun panel(view: CompanionView, session: SessionEntity?, selectedId: String? = null, navigationVisible: Boolean = false, nowPlaying: Long? = null) =
        companionPanel(view, session, selectedId, navigationVisible, nowPlaying)

    /** Companion and Perf behave as before Auto existed: Perf always, Companion never shows the Perf view. */
    @Test
    fun fixedViewsKeepTheirPanels() {
        assertEquals(CompanionPanel.HERO, panel(CompanionView.COMPANION, null))
        assertEquals(CompanionPanel.NOW_PLAYING, panel(CompanionView.COMPANION, game))
        assertEquals(CompanionPanel.PERF, panel(CompanionView.PERF, null))
        assertEquals(CompanionPanel.PERF, panel(CompanionView.PERF, game, selectedId = "snes/Other.sfc", navigationVisible = true))
    }

    /** Auto shows Perf only while a game is played; apps and shortcuts keep Now playing, and nothing open keeps the hero. */
    @Test
    fun autoIsPerfOnlyWhileAGameIsPlayed() {
        assertEquals(CompanionPanel.HERO, panel(CompanionView.AUTO, null))
        assertEquals(CompanionPanel.AUTO_PERF, panel(CompanionView.AUTO, game))
        assertEquals(CompanionPanel.NOW_PLAYING, panel(CompanionView.AUTO, app))
    }

    /** Browsing another game in the navigation takes the panel back to the hero; the played game itself does not. */
    @Test
    fun browsingAnotherGameShowsTheHero() {
        assertEquals(CompanionPanel.HERO, panel(CompanionView.AUTO, game, selectedId = "snes/Other.sfc", navigationVisible = true))
        assertEquals(CompanionPanel.AUTO_PERF, panel(CompanionView.AUTO, game, selectedId = game.gameId, navigationVisible = true))
        assertEquals(CompanionPanel.AUTO_PERF, panel(CompanionView.AUTO, game, selectedId = "snes/Other.sfc", navigationVisible = false))
    }

    /** The chip's Now playing holds for the session it was picked in; the next session (another start) opens on Perf again. */
    @Test
    fun nowPlayingChoiceLastsOneSession() {
        assertEquals(CompanionPanel.AUTO_NOW_PLAYING, panel(CompanionView.AUTO, game, nowPlaying = game.startedAt))
        assertEquals(CompanionPanel.AUTO_PERF, panel(CompanionView.AUTO, game.copy(startedAt = 5_000), nowPlaying = game.startedAt))
        assertEquals(CompanionPanel.NOW_PLAYING, panel(CompanionView.COMPANION, game, nowPlaying = game.startedAt))
    }
}
