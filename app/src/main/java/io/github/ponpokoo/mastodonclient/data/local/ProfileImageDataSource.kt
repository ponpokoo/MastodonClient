package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class ProfileImageDataSource(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.cacheDir, "profile_images")

    suspend fun importImage(value: String): String {
        var created: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val uri = Uri.parse(value)
                require(uri.scheme == "content") { "Unsupported URI" }
                check(directory.isDirectory || directory.mkdirs()) { "画像の保存先を作成できません" }
                val file = File(directory, UUID.randomUUID().toString()).also { created = it }
                val input = resolver.openInputStream(uri) ?: throw IOException("画像を開けません")
                input.use { source -> file.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
                file.absolutePath
            }
        } catch (error: Exception) {
            // Also handles cancellation while returning from the IO dispatcher.
            withContext(NonCancellable + Dispatchers.IO) { created?.delete() }
            throw error
        }
    }

    suspend fun deleteImages(paths: List<String>) = withContext(Dispatchers.IO) {
        paths.distinct().forEach { path ->
            val file = File(path)
            if (file.canonicalFile.parentFile == directory.canonicalFile) file.delete()
        }
    }
}
