package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class ServerCatalogCache(val domains: List<String>, val fetchedAt: Long)

interface ServerSuggestionsLocalDataSource {
    suspend fun readCache(): ServerCatalogCache?
    suspend fun writeCache(cache: ServerCatalogCache)
    suspend fun bundledDomains(): List<String>
}

class FileServerSuggestionsLocalDataSource(
    private val cacheFile: File,
    private val readBundled: () -> String,
) : ServerSuggestionsLocalDataSource {
    constructor(context: Context) : this(
        File(context.applicationContext.cacheDir, "server-catalog.json"),
        { context.applicationContext.assets.open("server_suggestions.json").bufferedReader().use { it.readText() } },
    )

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun readCache(): ServerCatalogCache? = withContext(Dispatchers.IO) {
        if (cacheFile.isFile) json.decodeFromString<ServerCatalogCache>(cacheFile.readText()) else null
    }

    override suspend fun writeCache(cache: ServerCatalogCache) = withContext(Dispatchers.IO) {
        val temporary = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
        try {
            temporary.writeText(json.encodeToString(cache))
            ensureActive()
            Files.move(temporary.toPath(), cacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            Unit
        } finally {
            temporary.delete()
        }
    }

    override suspend fun bundledDomains(): List<String> = withContext(Dispatchers.IO) {
        json.decodeFromString<List<String>>(readBundled())
    }
}
