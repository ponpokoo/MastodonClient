package io.github.ponpokoo.mastodonclient.core.security

import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.*
import java.util.Base64

class WebPushDecryptorTest {
    private val decryptor = WebPushDecryptor()
    @Test fun decryptsPublishedRfc8291Vector() {
        assertEquals("When I grow up, I want to be a watermelon", decryptor.decrypt(standardKeys(), "aes128gcm", emptyMap(), standardBody()).toString(Charsets.UTF_8))
    }
    @Test fun decryptsPublishedDraft04VectorAndQuotedHeaders() {
        assertEquals("I am the walrus", decryptor.decrypt(legacyKeys(), "aesgcm", legacyHeaders(), decode("6nqAQUME8hNqw5J3kl8cpVVJylXKYqZOeseZG8UueKpA")).toString(Charsets.UTF_8))
    }
    @Test fun rejectsTamperingAndWrongAuthenticationSecret() {
        val tampered = standardBody().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        rejected { decryptor.decrypt(standardKeys(), "aes128gcm", emptyMap(), tampered) }
        val key = standardKeys()
        rejected { decryptor.decrypt(WebPushKeys(key.publicKey, key.privateKey, legacyKeys().authSecret), "aes128gcm", emptyMap(), standardBody()) }
        rejected { decryptor.decrypt(legacyKeys(), "aes128gcm", emptyMap(), standardBody()) }
    }
    @Test fun rejectsOffCurveSenderAndTruncatedHeader() {
        val invalid = standardBody().also { for (i in 22 until 86) it[i] = 0 }
        rejected { decryptor.decrypt(standardKeys(), "aes128gcm", emptyMap(), invalid) }
        rejected { decryptor.decrypt(standardKeys(), "aes128gcm", emptyMap(), standardBody().copyOf(90)) }
    }
    @Test fun rejectsAmbiguousLegacyHeadersAndUnsupportedEncoding() {
        val body = decode("6nqAQUME8hNqw5J3kl8cpVVJylXKYqZOeseZG8UueKpA")
        rejected { decryptor.decrypt(legacyKeys(), "aesgcm", legacyHeaders() + ("encryption" to "salt=abc;salt=def"), body) }
        rejected { decryptor.decrypt(legacyKeys(), "aesgcm", emptyMap(), body) }
        rejected { decryptor.decrypt(standardKeys(), "gzip", emptyMap(), standardBody()) }
    }
    private fun rejected(action: () -> Unit) {
        try { action(); fail("Invalid ciphertext was accepted") }
        catch (_: java.security.GeneralSecurityException) { }
        catch (_: IllegalArgumentException) { }
    }
    companion object {
        fun decode(value: String) = Base64.getUrlDecoder().decode(value)
        fun standardBody() = decode("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN")
        fun standardKeys() = keys("q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94", "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4", "BTBZMqHH6r4Tts7J_aSIgg")
        fun legacyKeys() = keys("9FWl15_QUQAWDaD3k3l50ZBZQJ4au27F1V4F0uLSD_M", "BCEkBjzL8Z3C-oi2Q7oE5t2Np-p7osjGLg93qUP0wvqRT21EEWyf0cQDQcakQMqz4hQKYOQ3il2nNZct4HgAUQU", "R29vIGdvbyBnJyBqb29iIQ")
        fun legacyHeaders() = mapOf("encryption" to "salt=\"lngarbyKfMoi9Z75xYXmkg\"", "crypto-key" to "dh=\"BNoRDbb84JGm8g5Z5CFxurSqsXWJ11ItfXEWYVLE85Y7CYkDjXsIEc4aqxYaQ1G8BqkXCJ6DPpDrWtdWj_mugHU\"")
        private fun keys(scalar: String, publicKey: String, auth: String): WebPushKeys {
            val parameters = AlgorithmParameters.getInstance("EC").run { init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java) }
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, decode(scalar)), parameters))
            return WebPushKeys(publicKey, Base64.getUrlEncoder().withoutPadding().encodeToString(privateKey.encoded), auth)
        }
    }
}
