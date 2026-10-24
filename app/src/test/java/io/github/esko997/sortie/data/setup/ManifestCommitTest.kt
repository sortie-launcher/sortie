package io.github.esko997.sortie.data.setup

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Test

/** A power loss at an exact step: an Error, so commit code does not clean up after it. */
internal class SimulatedCrash : Error("simulated crash")

/** Counts write/rename/delete steps and crashes ([SimulatedCrash]) or fails ([IOException]) before step [at]. */
internal class FaultyIo(private val at: Int, private val crash: Boolean = true, private val only: String? = null) : CommitIo() {
    var steps = 0
        private set

    private fun step(kind: String) {
        if (only != null && kind != only) return
        val n = steps++
        if (n == at) if (crash) throw SimulatedCrash() else throw IOException("injected $kind failure")
    }

    override fun write(file: File, bytes: ByteArray) {
        step("write")
        super.write(file, bytes)
    }

    override fun rename(from: File, to: File) {
        step("rename")
        super.rename(from, to)
    }

    override fun delete(file: File) {
        step("delete")
        super.delete(file)
    }
}

class ManifestCommitTest {
    private val oldA = "{\"a\":1}".toByteArray()
    private val oldB = "{\"b\":1}".toByteArray()
    private val newA = "{\"a\":2}".toByteArray()
    private val newB = "{\"b\":2}".toByteArray()
    private val change = mapOf("a.json" to newA, "b.json" to newB)

    private fun dir(): File = Files.createTempDirectory("sortie-commit").toFile().also {
        File(it, "a.json").writeBytes(oldA)
        File(it, "b.json").writeBytes(oldB)
    }

    private fun contents(d: File) = File(d, "a.json").readBytes().decodeToString() to File(d, "b.json").readBytes().decodeToString()

    private fun leftovers(d: File) = d.list()!!.filter { it.endsWith(ManifestCommit.STAGED_SUFFIX) || it.startsWith(ManifestCommit.JOURNAL) }

    /** Steps of a two-file commit: 2 staged writes, journal write, journal rename (the decision), 2 renames, journal delete. */
    @Test
    fun aCrashAtAnyStepRecoversToTheWholeOldOrTheWholeNewSet() {
        val total = FaultyIo(at = Int.MAX_VALUE).also { io ->
            val d = dir()
            ManifestCommit.commit(d, change, io)
        }.steps
        for (k in 0 until total) {
            val d = dir()
            try {
                ManifestCommit.commit(d, change, FaultyIo(at = k))
                fail("step $k should have crashed")
            } catch (expected: SimulatedCrash) {
            }
            val recovery = ManifestCommit.recover(d)
            assertTrue("step $k: ${recovery.error}", recovery.clean)
            val decided = k > 3 // the journal rename (step 3) completed
            val expected = if (decided) "{\"a\":2}" to "{\"b\":2}" else "{\"a\":1}" to "{\"b\":1}"
            assertEquals("crash before step $k", expected, contents(d))
            assertEquals("crash before step $k", emptyList<String>(), leftovers(d))
        }
    }

    /** Recovery itself can be interrupted; running it again still finishes the decided commit. */
    @Test
    fun recoveryIsIdempotentAcrossACrashDuringRecovery() {
        val d = dir()
        runCatching { ManifestCommit.commit(d, change, FaultyIo(at = 5)) } // a.json renamed, b.json still staged
        assertEquals("{\"a\":2}" to "{\"b\":1}", contents(d))
        try {
            ManifestCommit.recover(d, FaultyIo(at = 0, only = "rename"))
            fail("recovery should have crashed")
        } catch (expected: SimulatedCrash) {
        }
        assertTrue(ManifestCommit.recover(d).clean)
        assertEquals("{\"a\":2}" to "{\"b\":2}", contents(d))
        assertEquals(emptyList<String>(), leftovers(d))
    }

    /** An ordinary failure before the decision cleans up and leaves every target byte-identical. */
    @Test
    fun aFailureBeforeTheDecisionChangesNothing() {
        for (k in 0..3) {
            val d = dir()
            try {
                ManifestCommit.commit(d, change, FaultyIo(at = k, crash = false))
                fail("step $k should have failed")
            } catch (e: IOException) {
                assertFalse(e is ManifestCommit.PartiallyApplied)
            }
            assertArrayEquals(oldA, File(d, "a.json").readBytes())
            assertArrayEquals(oldB, File(d, "b.json").readBytes())
            assertEquals(emptyList<String>(), leftovers(d))
        }
    }

    /** A failure after the decision is reported as partial and finished by the next recovery. */
    @Test
    fun aFailureAfterTheDecisionIsFinishedByRecovery() {
        val d = dir()
        try {
            ManifestCommit.commit(d, change, FaultyIo(at = 1, crash = false, only = "rename")) // journal ok, a.json rename fails
            fail("should be partial")
        } catch (expected: ManifestCommit.PartiallyApplied) {
        }
        assertTrue(File(d, ManifestCommit.JOURNAL).isFile)
        assertTrue(ManifestCommit.recover(d).clean)
        assertEquals("{\"a\":2}" to "{\"b\":2}", contents(d))
        assertEquals(emptyList<String>(), leftovers(d))
    }

    /** A damaged staged copy stops the whole recovery before any rename: the other, valid copy is not applied either. */
    @Test
    fun aDamagedStagedCopyBlocksTheWholeRecovery() {
        val d = dir()
        runCatching { ManifestCommit.commit(d, change, FaultyIo(at = 4)) } // decided, nothing renamed yet
        File(d, "b.json" + ManifestCommit.STAGED_SUFFIX).writeText("{\"b\":\"torn")
        val recovery = ManifestCommit.recover(d)
        assertFalse(recovery.consistent)
        assertTrue(recovery.error!!.contains("b.json"))
        assertEquals("{\"a\":1}" to "{\"b\":1}", contents(d)) // a.json's valid copy was not applied alone
        assertTrue(File(d, ManifestCommit.JOURNAL).isFile)
        assertTrue(File(d, "a.json" + ManifestCommit.STAGED_SUFFIX).isFile) // kept for inspection
        // a new commit is refused while the damaged one is unresolved
        try {
            ManifestCommit.commit(d, change)
            fail("commit over an unresolved journal")
        } catch (expected: IOException) {
        }
        assertEquals("{\"a\":1}" to "{\"b\":1}", contents(d))
    }

    /** After a partial roll-forward, damage to the remaining copy is reported as inconsistent, not published as a set. */
    @Test
    fun aPartialCommitWithADamagedRemainingCopyIsInconsistent() {
        val d = dir()
        runCatching { ManifestCommit.commit(d, change, FaultyIo(at = 5)) } // a.json renamed, b.json staged
        File(d, "b.json" + ManifestCommit.STAGED_SUFFIX).writeText("{}")
        val recovery = ManifestCommit.recover(d)
        assertFalse(recovery.consistent)
        assertTrue(recovery.pending.isEmpty())
        assertEquals("{\"a\":2}" to "{\"b\":1}", contents(d)) // left exactly as found
    }

    /** A target edited by someone else while its change was pending is never overwritten. */
    @Test
    fun aPendingCopyNeverOverwritesAnExternalEdit() {
        val d = dir()
        runCatching { ManifestCommit.commit(d, change, FaultyIo(at = 4)) } // decided, nothing renamed yet
        File(d, "b.json").writeText("{\"b\":\"desktop\"}")
        val recovery = ManifestCommit.recover(d)
        assertFalse(recovery.consistent)
        assertTrue(recovery.error!!.contains("b.json"))
        assertEquals("{\"a\":1}" to "{\"b\":\"desktop\"}", contents(d))
    }

    /** A staged copy that vanished while its target is still the old file cannot be finished; nothing else is applied. */
    @Test
    fun aLostStagedCopyBlocksRecovery() {
        val d = dir()
        runCatching { ManifestCommit.commit(d, change, FaultyIo(at = 4)) }
        File(d, "b.json" + ManifestCommit.STAGED_SUFFIX).delete()
        assertFalse(ManifestCommit.recover(d).consistent)
        assertEquals("{\"a\":1}" to "{\"b\":1}", contents(d))
    }

    /** Once a staged file was renamed, a later edit of the target (desktop) wins over a leftover journal. */
    @Test
    fun aLeftoverJournalNeverRevertsANewerEdit() {
        val d = dir()
        runCatching { ManifestCommit.commit(d, change, FaultyIo(at = 6)) } // both renamed, journal delete crashed
        File(d, "a.json").writeText("{\"a\":3}")
        assertTrue(ManifestCommit.recover(d).clean)
        assertEquals("{\"a\":3}" to "{\"b\":2}", contents(d))
        assertFalse(File(d, ManifestCommit.JOURNAL).exists())
    }

    /** When roll-forward cannot write, readers get the staged copies so the decided set is still read whole. */
    @Test
    fun anUnwritableDirectoryExposesTheDecidedSetAsPending() {
        val d = dir()
        runCatching { ManifestCommit.commit(d, change, FaultyIo(at = 5)) } // a.json renamed, b.json staged
        d.setWritable(false)
        try {
            assumeFalse("running as root: directory permissions are not enforced", d.canWrite())
            val recovery = ManifestCommit.recover(d)
            assertNotNull(recovery.error)
            assertEquals(setOf("b.json"), recovery.pending.keys)
            assertEquals("{\"b\":2}", recovery.pending.getValue("b.json").readText())
        } finally {
            d.setWritable(true)
        }
        assertTrue(ManifestCommit.recover(d).clean)
        assertEquals("{\"a\":2}" to "{\"b\":2}", contents(d))
    }

    /** Staged files of an attempt that never decided are discarded; the targets are untouched. */
    @Test
    fun undecidedLeftoversAreDiscarded() {
        val d = dir()
        runCatching { ManifestCommit.commit(d, change, FaultyIo(at = 2)) } // both staged, no journal
        assertEquals(listOf("a.json.setup-new", "b.json.setup-new").sorted(), leftovers(d).sorted())
        assertNull(ManifestCommit.recover(d).error)
        assertEquals(emptyList<String>(), leftovers(d))
        assertArrayEquals(oldA, File(d, "a.json").readBytes())
    }
}
