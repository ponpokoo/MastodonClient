package io.github.ponpokoo.mastodonclient

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackageNameTest {
    @Test
    fun applicationIdIsStable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.ponpokoo.mastodonclient", context.packageName)
    }
}
