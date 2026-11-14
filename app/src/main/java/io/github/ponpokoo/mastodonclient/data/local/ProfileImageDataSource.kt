package io.github.ponpokoo.mastodonclient.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.net.toUri
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import io.github.ponpokoo.mastodonclient.domain.repository.ImportedProfileImage
import io.github.ponpokoo.mastodonclient.domain.repository.ProfileImageCrop
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.floor

class ProfileImageDataSource(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val directory = File(appContext.cacheDir, "profile_images")

    suspend fun importImage(value: String): ImportedProfileImage = temporaryImage { track ->
        val uri = value.toUri()
        require(uri.scheme == "content") { "Unsupported URI" }
        val input = resolver.openInputStream(uri) ?: throw IOException("画像を開けません")
        input.buffered().use { source ->
            source.mark(6)
            val signature = CharArray(6) { source.read().toChar() }.concatToString()
            source.reset()
            val isGif = signature == "GIF87a" || signature == "GIF89a"
            val file = track(if (isGif) ".gif" else "")
            file.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            ImportedProfileImage(file.absolutePath, isGif)
        }
    }

    suspend fun cropImage(path: String, crop: ProfileImageCrop, maxWidth: Int, aspectRatio: Int): String =
        temporaryImage { track ->
            val request = ImageRequest.Builder(appContext).data(File(path))
                .size(PROFILE_CROP_DECODE_SIZE).scale(Scale.FIT).allowHardware(false).build()
            val result = SingletonImageLoader.get(appContext).execute(request) as? SuccessResult
                ?: throw IOException("画像を開けません")
            val source = result.image.toBitmap()
            val left = crop.left.coerceIn(0f, 1f) * source.width
            val top = crop.top.coerceIn(0f, 1f) * source.height
            val right = crop.right.coerceIn(0f, 1f) * source.width
            val bottom = crop.bottom.coerceIn(0f, 1f) * source.height
            require(right > left && bottom > top)
            val height = floor(minOf((right - left) / aspectRatio, bottom - top, maxWidth.toFloat() / aspectRatio))
                .toInt().coerceAtLeast(1)
            val width = height * aspectRatio
            val cropped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(cropped)
                canvas.scale(width / (right - left), height / (bottom - top))
                canvas.translate(-left, -top)
                canvas.drawBitmap(
                    source, null as Rect?, RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                    Paint(Paint.FILTER_BITMAP_FLAG),
                )
                currentCoroutineContext().ensureActive()
                val file = track(".png")
                file.outputStream().use { output ->
                    if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, output)) throw IOException("画像を保存できません")
                }
                file.absolutePath
            } finally {
                cropped.recycle()
            }
        }

    private suspend fun <T> temporaryImage(write: suspend ((String) -> File) -> T): T {
        var created: File? = null
        try {
            return withContext(Dispatchers.IO) {
                check(directory.mkdirs() || directory.isDirectory) { "画像の保存先を作成できません" }
                write { suffix -> File(directory, "${UUID.randomUUID()}$suffix").also { created = it } }
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

private const val PROFILE_CROP_DECODE_SIZE = 3072
