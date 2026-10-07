package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppLicensesLocalDataSource(
    private val readAsset: (String) -> String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context) : this({ path ->
        context.applicationContext.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    })

    private val json = Json { ignoreUnknownKeys = true }

    internal suspend fun readNotices(): List<LocalLicenseNotice> = withContext(dispatcher) {
        val entries = json.decodeFromString<LicenseIndex>(readAsset("licenses/index.json")).entries
        require(entries.isNotEmpty() && entries.map { it.id }.distinct().size == entries.size)
        val documents = mutableMapOf<String, String>()
        entries.map { entry ->
            ensureActive()
            require(entry.id.isNotBlank() && entry.title.isNotBlank() && entry.license.isNotBlank())
            require(entry.file.matches(Regex("[a-zA-Z0-9_-]+\\.txt")))
            val text = documents.getOrPut(entry.file) { readAsset("licenses/${entry.file}") }
            require(text.isNotBlank())
            LocalLicenseNotice(entry.id, entry.title, entry.version, entry.license, text)
        }
    }
}

internal data class LocalLicenseNotice(
    val id: String,
    val title: String,
    val version: String,
    val license: String,
    val text: String,
)

@Serializable
private data class LicenseIndex(val entries: List<LicenseEntry>)

@Serializable
private data class LicenseEntry(
    val id: String,
    val title: String,
    val version: String = "",
    val license: String,
    val file: String,
)
