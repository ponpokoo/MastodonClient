package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import io.github.ponpokoo.mastodonclient.domain.model.DraftAttachment
import io.github.ponpokoo.mastodonclient.domain.model.MediaImportResult
import io.github.ponpokoo.mastodonclient.domain.model.MediaRejectionReason
import io.github.ponpokoo.mastodonclient.domain.model.MediaValidator
import io.github.ponpokoo.mastodonclient.domain.model.RejectedMedia
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DraftMediaDataSource(context: Context, private val isAccountPresent: suspend (String) -> Boolean = { id ->
    io.github.ponpokoo.mastodonclient.core.security.SecureAuthStore(context).getSessions().any { it.sessionId == id }
}) {
    private val resolver = context.applicationContext.contentResolver
    private val files = DraftMediaFiles(File(context.applicationContext.filesDir, "draft_media"))

    suspend fun importMedia(sessionId: String, uris: List<String>): MediaImportResult = mediaMutex.withLock {
        check(isAccountPresent(sessionId)) { "アカウントの登録が削除されています" }
        val directory = files.directory(sessionId)
        val created = mutableListOf<File>()
        try {
            withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "添付ファイルの保存先を作成できません" }
                val attachments = mutableListOf<DraftAttachment>()
                val rejected = mutableListOf<RejectedMedia>()
                uris.distinct().forEach { value ->
                    currentCoroutineContext().ensureActive()
                    val uri = Uri.parse(value)
                    var name = "attachment"
                    var mimeType: String? = null
                    var target: File? = null
                    try {
                        require(uri.scheme == "content") { "Unsupported URI" }
                        mimeType = MediaValidator.normalizeMimeType(resolver.getType(uri))
                        name = displayName(uri) ?: "attachment"
                        mimeType = mimeType ?: MediaValidator.normalizeMimeType(MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                                name.substringAfterLast('.', "").lowercase(Locale.ROOT),
                            ))
                        if (mimeType == null) {
                            rejected += RejectedMedia(name, null, MediaRejectionReason.UnknownType)
                            return@forEach
                        }
                        val file = File(directory, UUID.randomUUID().toString())
                        target = file
                        created += file
                        val input = resolver.openInputStream(uri) ?: throw IOException("添付ファイルを開けません")
                        input.use { source ->
                            file.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        attachments += DraftAttachment(Uri.fromFile(file).toString(), name, mimeType)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        target?.delete()
                        val reason = if (error is SecurityException) MediaRejectionReason.PermissionDenied
                            else MediaRejectionReason.Unreadable
                        rejected += RejectedMedia(name, mimeType, reason)
                    }
                }
                check(isAccountPresent(sessionId)) { "アカウントの登録が削除されています" }
                MediaImportResult(attachments, rejected)
            }
        } catch (error: Exception) {
            // Includes cancellation during the return to the caller's dispatcher.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                created.forEach { it.delete() }
            }
            throw error
        }
    }

    suspend fun deleteAccount(sessionId: String, legacyUris: Set<String>) = mediaMutex.withLock {
        withContext(Dispatchers.IO) { files.deleteAccount(sessionId, legacyUris) }
    }

    private companion object { val mediaMutex = Mutex() }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }?.takeIf(String::isNotBlank)
    } catch (error: Exception) {
        if (error is CancellationException || error is SecurityException) throw error
        // Optional metadata is not required to read a file with a concrete resolver MIME.
        null
    }
}
