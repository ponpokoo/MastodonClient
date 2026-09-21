package io.github.ponpokoo.mastodonclient.core.security

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.serialization.Serializable

@Serializable
class WebPushKeys(val publicKey: String, val privateKey: String, val authSecret: String)

/** P-256, uncompressed SEC1 public key, PKCS8 private key, Base64URL without padding. */
class WebPushKeyGenerator {
    private val random = SecureRandom()
    fun generate(): WebPushKeys {
        val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }
        val point = (pair.public as ECPublicKey).w
        fun coordinate(value: java.math.BigInteger): ByteArray =
            value.toByteArray().takeLast(32).toByteArray().let { ByteArray(32 - it.size) + it }
        val publicKey = byteArrayOf(4) + coordinate(point.affineX) + coordinate(point.affineY)
        return WebPushKeys(encode(publicKey), encode(pair.private.encoded), randomSecret(16))
    }

    fun randomSecret(bytes: Int = 32): String = encode(ByteArray(bytes).also(random::nextBytes))
    private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
