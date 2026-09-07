package io.github.ponpokoo.mastodonclient.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PkceGeneratorTest {
    @Test
    fun createsRfc7636S256Challenge() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkceGenerator.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun generatedValuesAreUniqueAndLongEnough() {
        val first = PkceGenerator.generate()
        val second = PkceGenerator.generate()

        assertNotEquals(first.verifier, second.verifier)
        assert(first.verifier.length in 43..128)
        assert(first.challenge.length == 43)
    }
}
