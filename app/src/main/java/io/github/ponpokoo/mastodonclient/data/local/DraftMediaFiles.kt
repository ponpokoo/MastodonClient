package io.github.ponpokoo.mastodonclient.data.local

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID

/** Only app-owned copies are deleted; content URIs and files outside draft_media are untouched. */
class DraftMediaFiles(private val root: File) {
    fun directory(sessionId: String) = File(root, UUID.nameUUIDFromBytes(sessionId.toByteArray(Charsets.UTF_8)).toString())

    fun deleteAccount(sessionId: String, legacyUris: Set<String>) {
        val owned = directory(sessionId).canonicalFile
        check(owned.parentFile == root.canonicalFile) { "Invalid attachment directory" }
        legacyUris.forEach { value ->
            val uri = runCatching { URI(value) }.getOrNull() ?: return@forEach
            if (uri.scheme != "file") return@forEach
            val file = runCatching { File(uri).canonicalFile }.getOrNull() ?: return@forEach
            if (file.parentFile == root.canonicalFile || file.parentFile == owned) deleteFile(file)
        }
        if (owned.exists()) {
            val files = owned.listFiles() ?: throw IOException("添付ファイルを確認できません")
            files.forEach { file ->
                check(file.canonicalFile.parentFile == owned && !file.isDirectory) { "Invalid attachment file" }
                deleteFile(file)
            }
            if (!owned.delete() && owned.exists()) throw IOException("添付フォルダーを削除できません")
        }
    }

    private fun deleteFile(file: File) {
        if (file.exists() && (!file.isFile || !file.delete())) throw IOException("添付ファイルを削除できません")
    }
}
