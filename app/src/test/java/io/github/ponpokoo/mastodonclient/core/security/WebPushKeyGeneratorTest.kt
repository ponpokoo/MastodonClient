package io.github.ponpokoo.mastodonclient.core.security

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Base64
import javax.crypto.KeyAgreement
import org.junit.Assert.*
import org.junit.Test

class WebPushKeyGeneratorTest {
    @Test fun exportedKeysCanBeRestoredAndUsedForEcdh() {
        val keys = WebPushKeyGenerator().generate()
        val decode = Base64.getUrlDecoder()
        val encoded = decode.decode(keys.publicKey)
        assertEquals(65, encoded.size)
        assertEquals(4, encoded[0].toInt())
        assertEquals(16, decode.decode(keys.authSecret).size)
        val sender = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        val factory = KeyFactory.getInstance("EC")
        val restoredPrivate = factory.generatePrivate(PKCS8EncodedKeySpec(decode.decode(keys.privateKey)))
        val restoredPublic = factory.generatePublic(ECPublicKeySpec(
            ECPoint(BigInteger(1, encoded.copyOfRange(1, 33)), BigInteger(1, encoded.copyOfRange(33, 65))),
            (sender.public as ECPublicKey).params,
        ))
        val receiverSecret = KeyAgreement.getInstance("ECDH").run {
            init(restoredPrivate); doPhase(sender.public, true); generateSecret()
        }
        val senderSecret = KeyAgreement.getInstance("ECDH").run {
            init(sender.private); doPhase(restoredPublic, true); generateSecret()
        }
        assertArrayEquals(senderSecret, receiverSecret)
        assertFalse(keys.toString().contains(keys.privateKey))
    }
}
