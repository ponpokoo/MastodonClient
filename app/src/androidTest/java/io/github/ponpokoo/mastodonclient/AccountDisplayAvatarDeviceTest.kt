package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import coil3.compose.AsyncImage
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.model.MediaAttachment
import io.github.ponpokoo.mastodonclient.feature.common.accountAvatarModel
import io.github.ponpokoo.mastodonclient.feature.media.MediaViewerScreen
import java.io.File
import org.junit.Rule
import org.junit.Test

class AccountDisplayAvatarDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun revisionReloadsChangedImageAtTheSameAddress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "account-display-avatar-${System.nanoTime()}.png")
        fun write(color: Int) {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val account = mutableStateOf(AccountSession("one", "https://one.example", "me", "me", "Me",
            file.toURI().toString(), "test-token"))
        val viewerOpen = mutableStateOf(false)
        try {
            write(android.graphics.Color.RED)
            rule.setContent {
                if (viewerOpen.value) MediaViewerScreen(
                    media = listOf(MediaAttachment("avatar", "image", account.value.avatarUrl,
                        account.value.avatarUrl, "プロフィール画像", cacheRevision = account.value.avatarRevision)),
                    initialIndex = 0, onBack = {},
                ) else AsyncImage(model = accountAvatarModel(account.value), contentDescription = null,
                    modifier = Modifier.size(48.dp).testTag("registered_avatar"))
            }
            waitFor(Color.Red)
            write(android.graphics.Color.BLUE)
            // The data URI stays the same; the persisted revision changes the memory/disk cache keys.
            rule.runOnIdle { account.value = account.value.copy(avatarRevision = 1) }
            waitFor(Color.Blue)
            write(android.graphics.Color.GREEN)
            rule.runOnIdle {
                account.value = account.value.copy(avatarRevision = 2)
                viewerOpen.value = true
            }
            waitFor(Color.Green, "media_viewer")
        } finally { file.delete() }
    }

    private fun waitFor(color: Color, tag: String = "registered_avatar") {
        rule.waitUntil(10_000) {
            val pixels = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            pixels[pixels.width / 2, pixels.height / 2] == color
        }
    }
}
