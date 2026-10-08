package io.github.ponpokoo.mastodonclient.data.local

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DraftMediaFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun deletesSavedAndUnsavedAccountCopiesButPreservesOtherAccountsAndOriginals() {
        val root = temporary.newFolder("draft_media")
        val files = DraftMediaFiles(root)
        val a = files.directory("a").apply { mkdirs() }
        val b = files.directory("b").apply { mkdirs() }
        File(a, "unsaved").writeText("private")
        val other = File(b, "other").apply { writeText("keep") }
        val legacy = File(root, "legacy").apply { writeText("private") }
        val original = temporary.newFile("original").apply { writeText("keep") }
        files.deleteAccount("a", setOf(legacy.toURI().toString(), original.toURI().toString(), other.toURI().toString(), "content://photos/original"))
        assertFalse(a.exists()); assertFalse(legacy.exists())
        assertEquals("keep", original.readText()); assertEquals("keep", other.readText())
        files.deleteAccount("a", setOf(legacy.toURI().toString()))
        assertTrue(b.exists())
        assertEquals(root.canonicalFile, files.directory("../../outside").canonicalFile.parentFile)
    }
}
