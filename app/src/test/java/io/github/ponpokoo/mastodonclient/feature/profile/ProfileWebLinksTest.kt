package io.github.ponpokoo.mastodonclient.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileWebLinksTest {
    @Test fun aboutUsesProfileServerWhileSettingsFollowSelectedInstance() {
        val profile = "https://remote.example/@alice"
        val first = profileWebLinks(profile, "https://first.example/")
        assertEquals(profile, first.profile)
        assertEquals("https://first.example/settings/profile", first.settings)
        assertEquals("https://remote.example/about", first.about)

        val second = profileWebLinks(profile, "https://second.example")
        assertEquals(profile, second.profile)
        assertEquals("https://second.example/settings/profile", second.settings)
        assertEquals("https://remote.example/about", second.about)
    }

    @Test fun missingSessionStillAllowsAboutForDisplayedProfile() {
        val links = profileWebLinks("https://remote.example/@alice", null)
        assertEquals("https://remote.example/@alice", links.profile)
        assertNull(links.settings)
        assertEquals("https://remote.example/about", links.about)
    }

    @Test fun aboutRemovesProfilePathQueryAndFragmentAndPreservesPort() {
        val links = profileWebLinks("https://remote.example:8443/users/alice?view=posts#profile", "https://local.example")
        assertEquals("https://remote.example:8443/about", links.about)
    }

    @Test fun invalidProfileUrlDoesNotOpenAboutOnUnrelatedBrowsingServer() {
        listOf("", "not a url", "https:///alice", "javascript:alert(1)", "https://alice@remote.example/@alice").forEach { profile ->
            val links = profileWebLinks(profile, "https://local.example")
            assertNull(links.about)
            assertEquals("https://local.example/settings/profile", links.settings)
        }
    }
}
