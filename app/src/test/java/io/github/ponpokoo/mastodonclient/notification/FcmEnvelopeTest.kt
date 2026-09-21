package io.github.ponpokoo.mastodonclient.notification

import org.junit.Assert.*
import org.junit.Test

class FcmEnvelopeTest {
    private val fetch = mapOf("version" to "1", "registrationId" to "r".repeat(43), "messageId" to "m".repeat(43), "transport" to "fetch")
    @Test fun validEncryptedEnvelopesAreAcceptedButExtraFieldsAreNotQueued() {
        assertNotNull(FcmEnvelope.encode(fetch))
        assertNotNull(FcmEnvelope.encode(fetch + mapOf("transport" to "inline", "encoding" to "aes128gcm", "body" to "ciphertext")))
        assertNull(FcmEnvelope.encode(fetch + ("access_token" to "secret")))
        assertNull(FcmEnvelope.encode(fetch + ("transport" to "inline")))
        assertNull(FcmEnvelope.encode(fetch + ("messageId" to "../message")))
    }
    @Test fun utf8SizeIsBoundedBeforeDurableQueue() {
        assertNull(FcmEnvelope.encode(fetch + mapOf("transport" to "inline", "encoding" to "aes128gcm", "body" to "あ".repeat(3000))))
    }
    @Test fun expiredZeroTtlAndFutureMessagesAreDroppedAndLongTtlIsCapped() {
        val now = 1_000_000L
        assertNull(FcmEnvelope.deadline(now, 0, now))
        assertNull(FcmEnvelope.deadline(now - 60_000, 60, now))
        assertNull(FcmEnvelope.deadline(now + 120_000, 60, now))
        assertEquals(now + 30_000, FcmEnvelope.deadline(now, 30, now))
        assertEquals(now + 86_400_000, FcmEnvelope.deadline(now, Int.MAX_VALUE, now))
    }
}
