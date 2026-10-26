package io.github.esko997.sortie.data.manifest

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameAnnotationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun roundTripsWithStableLowerCaseKeys() {
        val data = UserData(
            gameAnnotations = mapOf("psp/game.iso" to GameAnnotation(ControllerSupport.TOUCH)),
            bookmarks = mapOf("psp/game.iso" to GameBookmark("Chapter 3 boss", 1_700_000_000_000))
        )
        val text = json.encodeToString(UserData.serializer(), data)
        assertTrue(text, "\"controller\":\"touch\"" in text)
        assertEquals(data, json.decodeFromString(UserData.serializer(), text))
    }

    @Test
    fun unknownControllerValueDecodesAsUnknownWithoutLosingTheRest() {
        val text = """{"favourites":["a/b"],"gameAnnotations":{"a/b":{"controller":"balance-board"}}}"""
        val data = json.decodeFromString(UserData.serializer(), text)
        assertEquals(setOf("a/b"), data.favourites)
        assertEquals(GameAnnotation(ControllerSupport.UNKNOWN), data.gameAnnotations["a/b"])
    }

    /** The invitation is on unless the player turned it off; older files without the key read as on. */
    @Test
    fun bookmarkInvitationDefaultsOn() {
        assertTrue(json.decodeFromString(UserData.serializer(), "{}").bookmarkInvitation)
        assertEquals(false, json.decodeFromString(UserData.serializer(), """{"bookmarkInvitation":false}""").bookmarkInvitation)
    }
}
