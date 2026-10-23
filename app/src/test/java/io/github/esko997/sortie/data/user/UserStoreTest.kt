package io.github.esko997.sortie.data.user

import io.github.esko997.sortie.data.manifest.UserData
import java.io.File
import java.nio.file.Files
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
}
