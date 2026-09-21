package io.github.ponpokoo.mastodonclient.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.ponpokoo.mastodonclient.core.security.WebPushKeyGenerator
import io.github.ponpokoo.mastodonclient.data.local.EncryptedPushRegistrationStore
import io.github.ponpokoo.mastodonclient.data.local.StoredPushRegistration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import okio.Path.Companion.toPath
import okio.FileSystem
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferencesSerializer

class EncryptedPushRegistrationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").run { init(256); generateKey() }
    private fun encrypt(value: String): String = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(Cipher.ENCRYPT_MODE, key)
        Base64.getEncoder().encodeToString(iv + doFinal(value.toByteArray(Charsets.UTF_8)))
    }
    private fun decrypt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }

    @Test fun recordsAreEncryptedRestorableAndRemovedIndependently() = runTest {
        val file = temporary.newFolder().resolve("push.preferences_pb")
        // Okio provides portable atomic replacement; Android File.renameTo is not portable to Windows.
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.absolutePath.toPath() })
        val store = EncryptedPushRegistrationStore(data, ::encrypt, ::decrypt)
        val keys = WebPushKeyGenerator().generate()
        val record = StoredPushRegistration("binding", "relay", "id", "management-secret", keys)
        store.write("a", record)
        store.write("b", record.copy(registrationId = "second"))
        val raw = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains(keys.privateKey))
        assertFalse(raw.contains(keys.authSecret))
        assertFalse(raw.contains("management-secret"))
        val restored = EncryptedPushRegistrationStore(data, ::encrypt, ::decrypt)
        assertEquals(keys.privateKey, restored.read("a")!!.keys.privateKey)
        assertEquals("management-secret", restored.read("a")!!.managementToken)
        restored.remove("a")
        assertNull(restored.read("a"))
        assertEquals("second", restored.read("b")!!.registrationId)
    }

    @Test fun unreadableSecretsAreNotTreatedAsAbsentRegistration() = runTest {
        val file = temporary.newFolder().resolve("push.preferences_pb")
        val data = PreferenceDataStoreFactory.create(scope = backgroundScope,
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { file.absolutePath.toPath() })
        val ciphertext = Base64.getDecoder().decode(encrypt("{}"))
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        data.edit { it[stringPreferencesKey("registration_a")] = Base64.getEncoder().encodeToString(ciphertext) }
        val store = EncryptedPushRegistrationStore(data, ::encrypt, ::decrypt)
        try { store.read("a"); fail("Corrupt state must be reported") }
        catch (_: javax.crypto.AEADBadTagException) { }
        assertNull(store.read("unknown"))
    }
}
