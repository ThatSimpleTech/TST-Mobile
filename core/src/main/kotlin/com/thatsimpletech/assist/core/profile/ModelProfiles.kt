package com.thatsimpletech.assist.core.profile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * App-private JSON next to audit (`filesDir/profiles/models.json`). Core reads and writes
 * the data class; the app supplies the path. Not a rules file.
 */
object ModelProfiles {
    const val RELATIVE_PATH = "profiles/models.json"

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun load(file: File): List<ModelProfile> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()
        return json.decodeFromString(FileFormat.serializer(), text).profiles.map { it.sanitized() }
    }

    fun save(file: File, profiles: List<ModelProfile>) {
        file.parentFile?.mkdirs()
        val body = FileFormat(profiles.map { it.sanitized() })
        file.writeText(json.encodeToString(FileFormat.serializer(), body))
    }

    fun upsert(file: File, profile: ModelProfile) {
        val next = load(file).toMutableList()
        val i = next.indexOfFirst { it.baseUrl == profile.baseUrl && it.slug == profile.slug }
        if (i >= 0) next[i] = profile.sanitized() else next += profile.sanitized()
        save(file, next)
    }

    @Serializable
    private data class FileFormat(val profiles: List<ModelProfile> = emptyList())
}
