package io.github.ponpokoo.mastodonclient.feature.settings

import io.github.ponpokoo.mastodonclient.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateNoticeTest {
    @Test fun currentVersionHasAnUpdateNotice() {
        assertEquals(
            "版番号を上げるときは、WelcomeGuideDialog.kt の appUpdateNotice に今回の変更点を設定してください。",
            BuildConfig.VERSION_CODE,
            appUpdateNotice.versionCode,
        )
        assertTrue(
            "今回の変更点を1件以上記載してください。",
            appUpdateNotice.highlights.isNotEmpty() && appUpdateNotice.highlights.none { it.isBlank() },
        )
    }
}
