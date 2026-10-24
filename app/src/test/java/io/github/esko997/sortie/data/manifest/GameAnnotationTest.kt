package io.github.esko997.sortie.data.manifest

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAnnotationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roundTripsWithStableLowerCaseKeys() {
        val data = UserData(gameAnnotations = mapOf("psp/game.iso" to GameAnnotation(ControllerSupport.TOUCH, "L = map", "Chapter 3 boss")))
        val text = json.encodeToString(UserData.serializer(), data)
        assertTrue(text, "\"controller\":\"touch\"" in text)
        assertEquals(data, json.decodeFromString(UserData.serializer(), text))
    }

    @Test
    fun unknownControllerValueDecodesAsUnknownWithoutLosingTheRest() {
        val text = """{"favourites":["a/b"],"gameAnnotations":{"a/b":{"controller":"balance-board","returnNote":"world 2"}}}"""
        val data = json.decodeFromString(UserData.serializer(), text)
        assertEquals(setOf("a/b"), data.favourites)
        assertEquals(GameAnnotation(ControllerSupport.UNKNOWN, returnNote = "world 2"), data.gameAnnotations["a/b"])
    }

    @Test
    fun blankNotesNormaliseToEmpty() {
        assertEquals(GameAnnotation(), GameAnnotation(mappingNotes = "  \n", returnNote = " ").normalised())
    }
}
