package io.github.ponpokoo.mastodonclient

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ponpokoo.mastodonclient.data.local.ProfileImageDataSource
import io.github.ponpokoo.mastodonclient.domain.model.EditableProfile
import io.github.ponpokoo.mastodonclient.domain.model.ProfileEditRequest
import io.github.ponpokoo.mastodonclient.domain.repository.ProfileImageCrop
import io.github.ponpokoo.mastodonclient.feature.profile.EditProfileDialog
import io.github.ponpokoo.mastodonclient.feature.profile.PendingProfileImageCrop
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileImageCropDialog
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileImageEditState
import io.github.ponpokoo.mastodonclient.feature.profile.ProfileImageSlot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class ProfileEditorDeviceTest {
    @get:Rule val rule = createComposeRule()

    @Test fun imageButtonsOpenThePickerForTheirOwnSlotWithAnExistingSelection() {
        val uri = Uri.parse("content://test/profile-image")
        val selections = mutableListOf<Pair<ProfileImageSlot, String>>()
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    assertEquals("image/*", input)
                    dispatchResult(requestCode, uri)
                }
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MaterialTheme {
                    EditProfileDialog(
                        EditableProfile("凪", "", false, emptyList()),
                        ProfileImageEditState(isOpen = true, avatarPath = "/preview.png"),
                        onSelectImage = { slot, path -> selections += slot to path },
                        onDismiss = {},
                        onSave = {},
                    )
                }
            }
        }

        rule.onNodeWithText("アイコンを変更").performScrollTo().performClick()
        rule.onNodeWithText("ヘッダーを変更").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(ProfileImageSlot.Avatar to uri.toString(), ProfileImageSlot.Header to uri.toString()),
                selections,
            )
        }
    }

    @Test fun addingAFieldFocusesItsNameAndSavesFromTheFooter() {
        var saved: ProfileEditRequest? = null
        val existing = listOf("サイト" to "https://example.com", "趣味" to "写真", "地域" to "日本")
        rule.setContent {
            MaterialTheme {
                EditProfileDialog(
                    EditableProfile("凪", "自己紹介の原文", false, existing),
                    ProfileImageEditState(isOpen = true),
                    onSelectImage = { _, _ -> },
                    onDismiss = {},
                    onSave = { saved = it },
                )
            }
        }

        rule.onNodeWithText("プロフィール項目を追加").performScrollTo().performClick()
        rule.onAllNodesWithText("項目名")[3].assertIsFocused().performTextInput("活動")
        rule.onNodeWithText("保存").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(ProfileEditRequest(fields = existing + ("活動" to "")), saved)
        }
    }

    @Test fun headerCropUsesTheVisibleCenterAndExportsAThreeToOneImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File.createTempFile("profile-test", ".png", context.cacheDir)
        val source = ProfileImageDataSource(context)
        try {
            val bitmap = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            for (y in 100 until 260) {
                bitmap.setPixels(IntArray(360) { Color.BLUE }, 0, 360, 0, y, 360, 1)
            }
            input.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()

            var selected: ProfileImageCrop? = null
            rule.setContent {
                MaterialTheme {
                    ProfileImageCropDialog(
                        PendingProfileImageCrop(ProfileImageSlot.Header, input.absolutePath),
                        onCancel = {},
                        onConfirm = { selected = it },
                    )
                }
            }
            val done = rule.onAllNodes(hasText("決定") and isEnabled())
            try {
                rule.waitUntil(8_000) { done.fetchSemanticsNodes().size == 1 }
            } catch (error: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError(rule.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("all") { true }, useUnmergedTree = true).printToString(), error)
            }
            done[0].performClick()
            val selection = rule.runOnIdle { selected }
            assertNotNull(selection)
            runBlocking {
                val output = source.cropImage(input.absolutePath, checkNotNull(selection), 1500, 3)
                try {
                    val cropped = BitmapFactory.decodeFile(output)
                    assertEquals(cropped.height * 3, cropped.width)
                    assertEquals(Color.BLUE, cropped.getPixel(cropped.width / 2, 0))
                    assertEquals(Color.BLUE, cropped.getPixel(cropped.width / 2, cropped.height - 1))
                    cropped.recycle()
                } finally {
                    source.deleteImages(listOf(output))
                }
            }
        } finally {
            input.delete()
        }
    }
}
