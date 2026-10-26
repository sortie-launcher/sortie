package io.github.esko997.sortie.mecha.ui

import io.github.esko997.sortie.mecha.MechaElement
import io.github.esko997.sortie.mecha.MechaRect
import io.github.esko997.sortie.mecha.MechaSpotKind
import io.github.esko997.sortie.mecha.MechaStore
import io.github.esko997.sortie.mecha.MechaThrowTransfer
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaPresenceTest {
    private val dir = Files.createTempDirectory("sortie-mecha-presence").toFile()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private val store = MechaStore({ dir.resolve("state.json") }, { emptyList() }, { emptyFlow() }, { 0L }, scope)
    private val stages = ArrayList<MechaStage>()
    private val transfer = MechaThrowTransfer(0.5f, 0f, 30f)

    private fun stage(display: Int): MechaStage = MechaStage(store, display, 1, 40).also {
        stages += it
        val bounds = MechaRect(0, 0, 1000, 800)
        val ledge = MechaRect(0, 700, 1000, 760)
        it.setBounds(bounds)
        it.report(MechaElement("ledge", MechaSpotKind.LEDGE, ledge, ledge, false))
        MechaPresence.register(it)
        MechaPresence.setActive(it, true)
    }

    @After
    fun tearDown() {
        stages.forEach(MechaPresence::unregister)
        MechaPresence.arriving = null
        MechaPresence.carriedIn = null
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun recipientThatStopsDuringFlightCannotTakeOwnership() {
        val source = stage(0)
        val destination = stage(4)
        assertSame(destination, MechaPresence.otherWithRoom(source))
        MechaPresence.setActive(destination, false)
        assertFalse(MechaPresence.handOffDrop(destination, transfer.fraction, transfer))
        assertSame(source, MechaPresence.owner)
        assertNull(MechaPresence.carriedIn)
    }

    @Test
    fun lostLandingSpaceKeepsTheThrowOnItsOriginalDisplay() {
        val source = stage(0)
        val destination = stage(4)
        destination.remove("ledge")
        assertFalse(MechaPresence.handOffDrop(destination, transfer.fraction, transfer))
        assertSame(source, MechaPresence.owner)
        assertNull(MechaPresence.otherWithRoom(source))
    }

    @Test
    fun aNewWindowOnTheReceivingDisplayDoesNotReplayTheOldFlight() {
        stage(0)
        val destination = stage(4)
        assertTrue(MechaPresence.handOffDrop(destination, transfer.fraction, transfer))
        assertSame(destination, MechaPresence.owner)
        val replacement = stage(4)
        assertSame(replacement, MechaPresence.owner)
        assertNull(MechaPresence.carriedIn)
        assertFalse(MechaPresence.handOffDrop(destination, transfer.fraction, transfer))
        assertSame(replacement, MechaPresence.owner)
    }

    @Test
    fun resumingAfterAnInterruptedArrivalDoesNotReplayItsFlight() {
        stage(0)
        val destination = stage(4)
        assertTrue(MechaPresence.handOffDrop(destination, transfer.fraction, transfer))
        MechaPresence.setActive(destination, false)
        assertNull(MechaPresence.carriedIn)
        MechaPresence.setActive(destination, true)
        assertSame(destination, MechaPresence.owner)
        assertNull(MechaPresence.carriedIn)
    }
}
