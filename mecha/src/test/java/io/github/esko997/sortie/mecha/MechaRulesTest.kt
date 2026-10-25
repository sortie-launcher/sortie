package io.github.esko997.sortie.mecha

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MechaRulesTest {
    companion object {
        const val INIT = 1_000_000_000_000L
        const val MINUTE = 60_000L

        fun rows(family: String): List<MechaPart> = MechaSlot.entries.flatMap { slot ->
            (1..2).map { v ->
                val id = "${family}_${slot.name.lowercase()}_$v"
                MechaPart(id, slot, family, "${family.replaceFirstChar { it.uppercase() }} ${slot.name.lowercase()} $v", "mecha/$family/$id.png")
            }
        }

        fun text(rows: List<MechaPart>): String = Json.encodeToString(ListSerializer(MechaPart.serializer()), rows)

        val parts: List<MechaPart> = MECHA_FAMILIES.flatMap { parseMechaCatalog(it, text(rows(it).reversed())) { null } }

        fun starter(): MechaState = starterMechaState(parts, INIT)

        fun session(gameId: String, start: Long, minutes: Long?, abandoned: Boolean = false, isGame: Boolean = true) = PlaySession(
            gameId = gameId,
            startedAt = start,
            endedAt = minutes?.let { start + it * MINUTE },
            abandoned = abandoned,
            isGame = isGame
        )
    }

    @Test
    fun starterOwnsEveryVariantOneAndWearsTheYardSet() {
        val s = starter()
        assertEquals(12, s.owned.size)
        assertTrue(s.owned.all { it.endsWith("_1") })
        assertEquals(MechaSlot.entries.associateWith { "yard_${it.name.lowercase()}_1" }, s.equipped)
        assertEquals(MECHA_STARTER_CREDITS, s.salvage)
        assertEquals(MECHA_STARTER_ORIGIN, s.origins["courier_legs_1"])
        assertEquals(12, s.unownedIn(parts).size)
        assertNull(mechaStateProblem(s, parts))
    }

    @Test
    fun equippingNeedsAnOwnedPart() {
        val s = starter()
        val unowned = s.equipping("survey_head_2", parts)
        assertSame(s, unowned.state)
        assertNotNull(unowned.error)
        assertNotNull(s.equipping("nope", parts).error)
        val fitted = s.equipping("courier_arms_1", parts)
        assertEquals("courier_arms_1", fitted.state.equipped[MechaSlot.ARMS])
        assertEquals("yard_head_1", fitted.state.equipped[MechaSlot.HEAD])
        assertSame(fitted.state, fitted.state.equipping("courier_arms_1", parts).state)
    }

    @Test
    fun salvageIsDeterministicAndSpendsOneCreditPerPart() {
        val s = starter()
        val a = s.salvaged(parts)
        assertEquals(a, s.salvaged(parts))
        val found = (a.state.owned - s.owned).single()
        assertTrue(found.endsWith("_2"))
        assertEquals(MECHA_STARTER_CREDITS - 1, a.state.salvage)
        assertEquals("Salvage run #1", a.state.origins[found])
        assertEquals(s.equipped, a.state.equipped)

        var state = s
        repeat(MECHA_STARTER_CREDITS) { state = state.salvaged(parts).state }
        assertEquals(15, state.owned.size)
        assertEquals(0, state.salvage)
        val broke = state.salvaged(parts)
        assertSame(state, broke.state)
        assertNull(broke.error)
        assertNotNull(broke.message)

        val head = s.salvaged(parts, MechaSlot.HEAD).state
        assertEquals(MechaSlot.HEAD, parts.first { it.id == (head.owned - s.owned).single() }.slot)
    }

    @Test
    fun aCompleteSlotOrCollectionCostsNothing() {
        val heads = parts.filter { it.slot == MechaSlot.HEAD }.map { it.id }
        val s = starter().copy(owned = starter().owned + heads, salvage = 5)
        val slot = s.salvaged(parts, MechaSlot.HEAD)
        assertSame(s, slot.state)
        assertEquals(5, slot.state.salvage)
        assertNotNull(slot.message)

        val all = s.copy(owned = parts.map { it.id }.toSet())
        assertSame(all, all.salvaged(parts).state)
        assertSame(all, all.salvaged(parts, MechaSlot.LEGS).state)
    }

    @Test
    fun onlyNewCompletedGameSessionsAfterInitializationEarn() {
        val s = starter()
        val result = s.credited(
            listOf(
                session("snes/old.sfc", INIT - 1, 60),
                session("app:com.example", INIT + MINUTE, 60, isGame = false),
                session("shortcut:com.example/x", INIT + MINUTE, 60, isGame = false),
                session("psx/quit.cue", INIT + 2 * MINUTE, 60, abandoned = true),
                session("psx/open.cue", INIT + 3 * MINUTE, null),
                session("snes/new.sfc", INIT + 4 * MINUTE, 12)
            )
        )
        val n = result.state
        assertEquals(setOf("snes/new.sfc@${INIT + 4 * MINUTE}"), n.processedSessions)
        assertEquals(12, n.creditedMinutes)
        assertEquals(2, n.pendingMinutes)
        assertEquals(3, n.minutesToNextCredit)
        assertEquals(MECHA_STARTER_CREDITS + 2, n.salvage)
        assertNotNull(result.message)

        val more = n.credited(listOf(session("snes/next.sfc", INIT + 30 * MINUTE, 3))).state
        assertEquals(MECHA_STARTER_CREDITS + 3, more.salvage)
        assertEquals(0, more.pendingMinutes)
        assertEquals(15, more.creditedMinutes)
    }

    @Test
    fun historyAloneNeverEarns() {
        val s = starter()
        val history = (1..500).map { session("snes/g$it.sfc", INIT - it * 120 * MINUTE, 90) }
        assertSame(s, s.credited(history).state)
    }

    @Test
    fun replayedRestoredOrReimportedSessionsCountOnce() {
        val sessions = listOf(session("gba/a.gba", INIT + MINUTE, 7), session("gba/b.gba", INIT + 20 * MINUTE, 8))
        val once = starter().credited(sessions).state
        // the same sessions as a restore or history import re-inserts them (under new row ids, which never reach the rules), any order
        val reimported = sessions.reversed().map { it.copy() }
        assertSame(once, once.credited(sessions).state)
        assertSame(once, once.credited(reimported).state)
        val plus = once.credited(reimported + session("gba/c.gba", INIT + 60 * MINUTE, 5)).state
        assertEquals(once.creditedMinutes + 5, plus.creditedMinutes)
        assertEquals(once.salvage + 1, plus.salvage)
    }

    @Test
    fun rewardsAndChargeDoNotDependOnSessionDeliveryBatches() {
        val first = session("gba/a.gba", INIT + MINUTE, 3)
        val second = session("gba/b.gba", INIT + 10 * MINUTE, 3)
        val initial = starter()
        val together = initial.credited(listOf(first, second)).state
        val separately = initial.credited(listOf(first)).state.credited(listOf(first, second)).state
        assertEquals(initial.charge - 1, together.charge)
        assertEquals(together, separately)
    }

    @Test
    fun oneSessionCountsAtMostTheCap() {
        val s = starter()
        val n = s.credited(listOf(session("ps2/left-running.iso", INIT + MINUTE, 10 * 60))).state
        assertEquals(MECHA_SESSION_MINUTES_CAP, n.creditedMinutes)
        assertEquals(s.salvage + MECHA_SESSION_MINUTES_CAP / MECHA_MINUTES_PER_CREDIT, n.salvage)
        val backwards = s.credited(listOf(session("ps2/clock.iso", INIT + MINUTE, -30))).state
        assertEquals(0, backwards.creditedMinutes)
        assertEquals(s.salvage, backwards.salvage)
        assertEquals(1, backwards.processedSessions.size)
    }

    @Test
    fun careNeverReachesDistressAndRestToggles() {
        var s = starter().copy(salvage = 50)
        repeat(30) { s = s.salvaged(parts).state }
        repeat(40) { i -> s = s.credited(listOf(session("n64/g$i.z64", INIT + i * 200 * MINUTE, 120))).state }
        assertTrue(s.condition >= MECHA_STAT_FLOOR)
        assertTrue(s.charge >= MECHA_STAT_FLOOR)
        assertEquals(MECHA_STAT_FLOOR, s.charge)
        assertTrue(s.bond <= MECHA_STAT_MAX)
        assertNull(mechaStateProblem(s, parts))

        val tuned = s.cared(MechaCare.TUNE).state
        assertTrue(tuned.condition > s.condition)
        assertTrue(tuned.bond >= s.bond)
        val charged = s.cared(MechaCare.CHARGE).state
        assertTrue(charged.charge > s.charge)

        var full = s
        repeat(10) { full = full.cared(MechaCare.TUNE).state.cared(MechaCare.CHARGE).state }
        assertEquals(MECHA_STAT_MAX, full.condition)
        assertEquals(MECHA_STAT_MAX, full.charge)
        assertSame(full, full.cared(MechaCare.TUNE).state)
        assertSame(full, full.cared(MechaCare.CHARGE).state)

        val asleep = full.cared(MechaCare.REST).state
        assertTrue(asleep.sleeping)
        assertFalse(asleep.cared(MechaCare.REST).state.sleeping)
    }

    @Test
    fun namesAreTrimmedAndBounded() {
        val s = starter()
        assertEquals("Bolt", s.renamed("  Bolt ").state.name)
        assertSame(s, s.renamed("   ").state)
        assertNotNull(s.renamed("x".repeat(MECHA_NAME_MAX + 1)).error)
        assertNotNull(s.renamed("a\nb").error)
        assertEquals("x".repeat(MECHA_NAME_MAX), s.renamed("x".repeat(MECHA_NAME_MAX)).state.name)
    }

    @Test
    fun inconsistentStatesAreRecognised() {
        val s = starter()
        assertNotNull(mechaStateProblem(s.copy(equipped = s.equipped + (MechaSlot.HEAD to "survey_head_2")), parts))
        assertNotNull(mechaStateProblem(s.copy(equipped = s.equipped + (MechaSlot.HEAD to "yard_body_1")), parts))
        assertNotNull(mechaStateProblem(s.copy(equipped = s.equipped - MechaSlot.LEGS), parts))
        assertNotNull(mechaStateProblem(s.copy(owned = s.owned + "mystery_head_9"), parts))
        assertNotNull(mechaStateProblem(s.copy(charge = 0), parts))
        assertNotNull(mechaStateProblem(s.copy(pendingMinutes = MECHA_MINUTES_PER_CREDIT), parts))
        assertNotNull(mechaStateProblem(s.copy(salvage = -1), parts))
        assertNotEquals(s, s.copy(version = 2))
        assertNotNull(mechaStateProblem(s.copy(version = 2), parts))
    }

    @Test
    fun aPetCountsTowardBondAtMostOncePerWindow() {
        val t = INIT + 10 * MINUTE
        assertTrue(mechaPetCounts(t, 0))
        // tapping him again and again inside the window earns nothing
        for (d in listOf(0L, 1L, 30_000L, MECHA_PET_BOND_MS - 1)) assertFalse(mechaPetCounts(t + d, t))
        assertTrue(mechaPetCounts(t + MECHA_PET_BOND_MS, t))
        // a clock that went backwards does not lock pets out
        assertTrue(mechaPetCounts(t - 1, t))
        val s = starter()
        assertEquals(s.bond + 1, s.petted().state.bond)
        assertNull(s.petted().message)
        // bond stays capped, and a capped pet changes nothing
        val full = s.copy(bond = MECHA_STAT_MAX)
        assertEquals(MECHA_STAT_MAX, full.petted().state.bond)
        assertEquals(full, full.petted().state)
    }

    @Test
    fun aRestoredHangarKeepsEverySessionEitherSideCreditedAndPaysNothingTwice() {
        val device = starter().credited(listOf(session("dev", INIT + MINUTE, 10))).state
        val backup = starter().copy(name = "Rivet").credited(listOf(session("old", INIT + 2 * MINUTE, 15))).state
        val restored = backup.restoredOver(device)
        assertEquals("Rivet", restored.name)
        assertEquals(backup.salvage, restored.salvage)
        assertEquals(setOf("dev@${INIT + MINUTE}", "old@${INIT + 2 * MINUTE}"), restored.processedSessions)
        // the merged history and the device's own history read again: nothing new is credited
        val again = restored.credited(listOf(session("dev", INIT + MINUTE, 10), session("old", INIT + 2 * MINUTE, 15)))
        assertSame(restored, again.state)
        // play neither side credited yet still counts, once
        val fresh = restored.credited(listOf(session("new", INIT + 3 * MINUTE, 5))).state
        assertEquals(restored.salvage + 1, fresh.salvage)
        assertSame(fresh, fresh.credited(listOf(session("new", INIT + 3 * MINUTE, 5))).state)
        assertSame(device, device.restoredOver(null))
    }

    @Test
    fun historyMergedFromABackupWithoutAHangarEarnsNothing() {
        val s = starter()
        val merged = listOf(session("elsewhere", INIT + MINUTE, 30))
        val marked = s.creditedElsewhere(merged).state
        assertEquals(s.salvage, marked.salvage)
        assertSame(marked, marked.credited(merged).state)
        assertSame(marked, marked.creditedElsewhere(merged).state)
    }
}
