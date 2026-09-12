package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.ponpokoo.mastodonclient.domain.model.DraftAttachment
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DraftMediaDataSource(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val directory = File(context.applicationContext.filesDir, "draft_media")

    suspend fun importMedia(uris: List<String>): List<DraftAttachment> {
        val created = mutableListOf<File>()
        try {
            return withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "添付ファイルの保存先を作成できません" }
                uris.map { value ->
                    currentCoroutineContext().ensureActive()
                    val uri = Uri.parse(value)
                    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                        ?: "attachment"
                    val target = File(directory, UUID.randomUUID().toString())
                    created += target
                    val input = resolver.openInputStream(uri) ?: throw IOException("添付ファイルを開けません")
                    input.use { source ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    DraftAttachment(Uri.fromFile(target).toString(), name, mimeType)
                }
            }
        } catch (error: Exception) {
            // Includes cancellation during the return to the caller's dispatcher.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                created.forEach { it.delete() }
            }
            throw error
        }
    }
}
