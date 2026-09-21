package io.github.ponpokoo.mastodonclient.core.security

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.spec.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RFC 8291 and draft-ietf-webpush-encryption-04 single-record Web Push. */
class WebPushDecryptor {
    fun decrypt(keys: WebPushKeys, encoding: String, headers: Map<String, String>, body: ByteArray): ByteArray {
        require(body.size in 18..65536) { "Invalid encrypted payload size" }
        val standard = encoding == "aes128gcm"
        require(standard || encoding == "aesgcm") { "Unsupported Web Push encoding" }
        val salt: ByteArray
        val sender: ByteArray
        val ciphertext: ByteArray
        if (standard) {
            require(body.size >= 103) { "Truncated Web Push header" }
            val buffer = ByteBuffer.wrap(body)
            salt = ByteArray(16).also(buffer::get)
            val recordSize = buffer.int.toLong() and 0xffffffffL
            require(buffer.get().toInt() and 255 == 65) { "Invalid sender key length" }
            sender = ByteArray(65).also(buffer::get)
            ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            require(recordSize > ciphertext.size) { "Multiple or truncated records are unsupported" }
        } else {
            val encryption = parameters(requireNotNull(headers["encryption"]) { "Missing encryption header" })
            require(!encryption.containsKey("aesgcm")) { "Invalid legacy header" }
            salt = decode(requireNotNull(encryption["salt"]))
            val dhValues = requireNotNull(headers["crypto-key"]) { "Missing sender key" }.split(',')
                .map { parameters(it) }.mapNotNull { it["dh"] }
            require(dhValues.size == 1) { "Ambiguous sender key" }
            sender = decode(dhValues.single())
            val recordSize = encryption["rs"]?.toLongOrNull() ?: if (encryption.containsKey("rs")) 0 else 4096
            require(recordSize > body.size) { "Invalid legacy record size" }
            ciphertext = body
        }
        require(salt.size == 16) { "Invalid Web Push salt" }
        val receiver = decode(keys.publicKey)
        validatePoint(receiver)
        val senderPublic = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(validatePoint(sender), curve))
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decode(keys.privateKey)))
        val shared = KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(senderPublic, true); generateSecret() }
        val auth = decode(keys.authSecret)
        require(auth.size == 16) { "Invalid authentication secret" }
        val info = if (standard) ascii("WebPush: info\u0000") + receiver + sender else ascii("Content-Encoding: auth\u0000")
        val ikm = expand(hmac(auth, shared), info, 32)
        val prk = hmac(salt, ikm)
        val context = if (standard) byteArrayOf() else ascii("P-256\u0000") + byteArrayOf(0, 65) + receiver + byteArrayOf(0, 65) + sender
        val key = expand(prk, ascii("Content-Encoding: $encoding\u0000") + context, 16)
        val nonce = expand(prk, ascii("Content-Encoding: nonce\u0000") + context, 12)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(ciphertext)
        }
        if (standard) {
            val delimiter = plain.indexOfLast { it != 0.toByte() }
            require(delimiter >= 0 && plain[delimiter] == 2.toByte()) { "Invalid final record delimiter" }
            return plain.copyOfRange(0, delimiter)
        }
        require(plain.size >= 2) { "Missing legacy padding" }
        val padding = ((plain[0].toInt() and 255) shl 8) or (plain[1].toInt() and 255)
        require(padding <= plain.size - 2 && (2 until 2 + padding).all { plain[it] == 0.toByte() }) { "Invalid legacy padding" }
        return plain.copyOfRange(2 + padding, plain.size)
    }

    private fun parameters(header: String): Map<String, String> {
        require(header.length <= 2048 && !header.contains(',')) { "Invalid encryption parameters" }
        val pairs = header.split(';').map {
            val pair = it.trim().split('=', limit = 2)
            require(pair.size == 2) { "Invalid encryption parameter" }
            pair[0] to pair[1].trim().removeSurrounding("\"")
        }
        require(pairs.map { it.first }.distinct().size == pairs.size) { "Duplicate encryption parameter" }
        return pairs.toMap()
    }

    private fun validatePoint(bytes: ByteArray): ECPoint {
        require(bytes.size == 65 && bytes[0] == 4.toByte()) { "Invalid P-256 point encoding" }
        val x = BigInteger(1, bytes.copyOfRange(1, 33)); val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val p = (curve.curve.field as ECFieldFp).p
        require(x < p && y < p && y.pow(2).mod(p) == (x.pow(3) + curve.curve.a * x + curve.curve.b).mod(p)) { "Point is not on P-256" }
        return ECPoint(x, y)
    }
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
    private fun ascii(value: String) = value.toByteArray(Charsets.US_ASCII)
    private fun hmac(key: ByteArray, input: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(input) }
    private fun expand(key: ByteArray, info: ByteArray, size: Int) = hmac(key, info + byteArrayOf(1)).copyOf(size)
    private companion object {
        val curve: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
        }
    }
}
