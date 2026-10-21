package io.github.ponpokoo.mastodonclient.data.local

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ServerSuggestionsLocalDataSourceTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun catalogSurvivesRecreationAndReplacementLeavesNoTemporaryFile() = runTest {
        val file = File(folder.root, "catalog.json")
        fun source() = FileServerSuggestionsLocalDataSource(file) { """["bundled.example"]""" }
        assertNull(source().readCache())
        assertEquals(listOf("bundled.example"), source().bundledDomains())
        source().writeCache(ServerCatalogCache(listOf("old.example"), 10))
        val updated = ServerCatalogCache(listOf("new.example"), 20)
        source().writeCache(updated)
        assertEquals(updated, source().readCache())
        assertFalse(File(folder.root, "catalog.json.tmp").exists())
    }
}
