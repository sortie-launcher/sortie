package io.github.esko997.sortie.data.user

import io.github.esko997.sortie.data.manifest.UserData
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** Favourites, hidden entries and per-game profile overrides, persisted as `<root>/sortie/user.json` (device-owned). */
class UserStore(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<UserData> = _data

    private fun load(): UserData = runCatching {
        json.decodeFromString(UserData.serializer(), file.readText())
    }.getOrDefault(UserData())

    @Synchronized
    private fun update(f: (UserData) -> UserData) {
        val next = f(_data.value)
        _data.value = next
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(UserData.serializer(), next))
            tmp.renameTo(file)
        }
    }

    fun toggleFavourite(id: String) = update {
        it.copy(favourites = if (id in it.favourites) it.favourites - id else it.favourites + id)
    }

    fun setOverride(id: String, profileId: String?) = update {
        it.copy(
            profileOverrides = if (profileId == null) it.profileOverrides - id else it.profileOverrides + (id to profileId)
        )
    }

    fun hide(id: String, hidden: Boolean) = update { it.copy(hidden = if (hidden) it.hidden + id else it.hidden - id) }
}
