package io.github.esko997.sortie.data.user

import io.github.esko997.sortie.data.manifest.UserData
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class UserStoreTest {
    private fun fresh(): File = File(Files.createTempDirectory("sortie").toFile(), "user.json")

    /** A burst of edits must leave the final state on disk, not whichever write happened to finish last. */
    @Test
    fun rapidUpdatesPersistTheLatestState() = runBlocking {
        val file = fresh()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        val ids = (1..200).map { "game$it" }
        ids.forEach { store.addToLayout(it) }
        ids.take(50).forEach { store.toggleFavourite(it) }
        store.setLayout(ids.reversed())
        store.setHomeSort("custom")
        val expected = store.data.value
        withTimeout(5_000) { store.written.first { it == expected } }
        val onDisk = Json { ignoreUnknownKeys = true }.decodeFromString(UserData.serializer(), file.readText())
        assertEquals(expected, onDisk)
        assertEquals(ids.reversed(), onDisk.layout)
        assertEquals(ids.take(50).toSet(), onDisk.favourites)
        assertEquals(expected, UserStore(file).data.value)
    }

    /** An edit made before the writer coroutine ever ran must still be saved once it runs. */
    @Test
    fun editBeforeTheWriterStartsIsSaved() {
        val file = fresh()
        val gate = Gate()
        val store = UserStore(file, CoroutineScope(SupervisorJob() + gate))
        store.setHomeSort("custom")
        assertEquals(false, file.exists())
        gate.open()
        assertEquals("custom", UserStore(file).data.value.homeSort)
    }

    /** Holds every dispatched block until [open]; then runs them inline on the calling thread. */
    private class Gate : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()
        private var opened = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (opened) block.run() else queue += block
        }

        fun open() {
            opened = true
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
    }
}
