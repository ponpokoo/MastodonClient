package io.github.ponpokoo.mastodonclient.domain.model

import org.junit.Assert.*
import org.junit.Test

class MediaValidatorTest {
    private val configuration = ComposerConfiguration(supportedMimeTypes = setOf(
        "image/jpeg", "image/png", "video/mp4", "audio/mpeg",
    ))
    private fun media(mime: String, id: String = mime) = DraftAttachment("file:///$id", id, mime)

    @Test fun checksEveryTypeAndRetainsValidItemsInOrder() {
        val items = listOf(media("image/jpeg"), media("application/pdf"), media("image/png"), media("application/octet-stream"))
        val result = MediaValidator.validate(items, emptyList(), configuration)
        assertEquals(listOf("image/jpeg", "image/png"), result.attachments.map { it.mimeType })
        assertEquals(listOf(MediaRejectionReason.UnsupportedType, MediaRejectionReason.UnknownType), result.rejected.map { it.reason })
    }

    @Test fun usesServerLimitsAfterValidationAndDeduplication() {
        val first = media("image/jpeg", "one")
        val items = listOf(first, first, media("application/pdf"), media("image/jpeg", "two"), media("image/jpeg", "three"))
        val result = MediaValidator.validate(items, listOf(media("image/png")), configuration.copy(maxMediaAttachments = 3))
        assertEquals(listOf("one", "two"), result.attachments.map { it.fileName })
        assertEquals(listOf(MediaRejectionReason.UnsupportedType, MediaRejectionReason.TooManyAttachments), result.rejected.map { it.reason })
        assertEquals(4, MediaValidator.validate((1..4).map { media("image/jpeg", "$it") }, emptyList(), configuration).attachments.size)
    }

    @Test fun refusesMixedAndMultipleAudioOrVideoButAcceptsSingles() {
        listOf("video/mp4", "audio/mpeg").forEach { mime ->
            assertEquals(1, MediaValidator.validate(listOf(media(mime)), emptyList(), configuration).attachments.size)
        }
        listOf(
            "image/jpeg" to "video/mp4", "image/jpeg" to "audio/mpeg", "video/mp4" to "video/mp4",
            "audio/mpeg" to "audio/mpeg", "video/mp4" to "audio/mpeg", "audio/mpeg" to "image/jpeg",
        ).forEach { (first, second) ->
            val result = MediaValidator.validate(listOf(media(second, "two")), listOf(media(first, "one")), configuration)
            assertTrue(result.attachments.isEmpty())
            assertEquals(MediaRejectionReason.IncompatibleCombination, result.rejected.single().reason)
        }
    }

    @Test fun distinguishesMissingCapabilitiesFromAnEmptyServerList() {
        val item = listOf(media("image/jpeg"))
        assertEquals(MediaRejectionReason.ConfigurationUnavailable,
            MediaValidator.validate(item, emptyList(), configuration.copy(supportedMimeTypes = null)).rejected.single().reason)
        assertEquals(MediaRejectionReason.UnsupportedType,
            MediaValidator.validate(item, emptyList(), configuration.copy(supportedMimeTypes = emptySet())).rejected.single().reason)
    }

    @Test fun normalizesConcreteTypesAndUsesTheReturnedServerTypeForCombinations() {
        assertEquals("audio/mpeg", MediaValidator.normalizeMimeType(" Audio/MPEG; charset=utf-8 "))
        assertNull(MediaValidator.normalizeMimeType("audio/*"))
        assertNull(MediaValidator.normalizeMimeType(null))
        val disguisedAudio = media("image/jpeg").copy(serverType = "audio")
        assertEquals(MediaRejectionReason.IncompatibleCombination,
            MediaValidator.validate(listOf(media("image/png")), listOf(disguisedAudio), configuration).rejected.single().reason)
    }
}
