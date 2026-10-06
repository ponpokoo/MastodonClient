package io.github.ponpokoo.mastodonclient.core.security

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.util.Base64

/** Canonical uncompressed SEC1 P-256 point. This is a server's public signing key. */
object VapidPublicKey {
    fun normalize(value: String): String {
        require(value.length <= 90 && value.matches(Regex("[A-Za-z0-9_-]+={0,2}"))) { "Invalid VAPID public key" }
        val raw = Base64.getUrlDecoder().decode(value)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        require(encoded == value.trimEnd('=') && raw.size == 65 && raw[0] == 4.toByte()) { "Invalid VAPID public key" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val p = (curve.curve.field as ECFieldFp).p
        require(x < p && y < p && y.pow(2).mod(p) == (x.pow(3) + curve.curve.a * x + curve.curve.b).mod(p)) {
            "Invalid VAPID public key"
        }
        return encoded
    }
    private val curve = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
    }
}
