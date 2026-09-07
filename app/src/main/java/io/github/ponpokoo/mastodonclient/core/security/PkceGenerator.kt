package io.github.ponpokoo.mastodonclient.core.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class PkcePair(val verifier: String, val challenge: String)

object PkceGenerator {
    private val secureRandom = SecureRandom()

    fun generate(): PkcePair {
        val verifierBytes = ByteArray(64).also(secureRandom::nextBytes)
        val verifier = base64Url(verifierBytes)
        return PkcePair(verifier, challengeFor(verifier))
    }

    fun randomState(): String = ByteArray(32).also(secureRandom::nextBytes).let(::base64Url)

    fun challengeFor(verifier: String): String = base64Url(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
    )

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(bytes)
}
