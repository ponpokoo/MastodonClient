package io.github.ponpokoo.mastodonclient.data.repository

import io.github.ponpokoo.mastodonclient.core.security.WebPushDecryptor
import io.github.ponpokoo.mastodonclient.data.local.PushRegistrationStore
import io.github.ponpokoo.mastodonclient.data.local.StoredPushRegistration
import io.github.ponpokoo.mastodonclient.data.remote.PushMessageSource
import io.github.ponpokoo.mastodonclient.data.remote.dto.RelayMessageDto
import io.github.ponpokoo.mastodonclient.data.remote.dto.WebPushNotificationDto
import io.github.ponpokoo.mastodonclient.domain.model.AccountSession
import io.github.ponpokoo.mastodonclient.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import java.security.GeneralSecurityException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

class DefaultPushMessageRepository(
    private val sessions: suspend () -> List<AccountSession>,
    private val store: PushRegistrationStore,
    private val source: PushMessageSource,
    private val presenter: PushNotificationPresenter,
    private val decryptor: WebPushDecryptor = WebPushDecryptor(),
) : PushMessageRepository {
    override suspend fun receive(data: Map<String, String>): PushReceiveResult = withContext(Dispatchers.IO) {
        if (data.size > 16 || data.entries.sumOf { it.key.length.toLong() + it.value.length } > 100_000 ||
            data["version"] != "1" || data["transport"] !in setOf("inline", "fetch")) return@withContext PushReceiveResult.REJECTED
        val registrationId = data["registrationId"] ?: return@withContext PushReceiveResult.REJECTED
        val messageId = data["messageId"] ?: return@withContext PushReceiveResult.REJECTED
        if (!registrationId.matches(Regex("[A-Za-z0-9_-]{22,128}")) || !messageId.matches(Regex("[A-Za-z0-9_-]{43}"))) return@withContext PushReceiveResult.REJECTED
        val target = sessions().firstNotNullOfOrNull { session ->
            store.read(session.sessionId)?.takeIf { valid(it, session, registrationId) }?.let { session to it }
        } ?: return@withContext PushReceiveResult.IGNORED
        val (session, record) = target
        val notification = try {
            val envelope = if (data["transport"] == "inline") RelayMessageDto.inline(data)
            else source.fetch(record.relayIdentity, registrationId, messageId, record.managementToken)
                ?: return@withContext PushReceiveResult.IGNORED
            envelope.validate(registrationId, messageId)
            val bytes = decryptor.decrypt(record.keys, envelope.encoding, envelope.cryptoHeaders(), envelope.bytes())
            val plain = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            // access_token in the Mastodon push payload is deliberately ignored, never persisted/logged.
            RelayMessageDto.json.decodeFromString<WebPushNotificationDto>(plain).toDomain()
        } catch (_: GeneralSecurityException) { return@withContext PushReceiveResult.REJECTED }
        catch (_: SerializationException) { return@withContext PushReceiveResult.REJECTED }
        catch (_: IllegalArgumentException) { return@withContext PushReceiveResult.REJECTED }
        catch (_: NoSuchElementException) { return@withContext PushReceiveResult.REJECTED }
        catch (_: java.nio.charset.CharacterCodingException) { return@withContext PushReceiveResult.REJECTED }

        PushRegistrationGuard.mutex.withLock {
            suspend fun current(): Boolean {
                val activeSession = sessions().firstOrNull { it.sessionId == session.sessionId } ?: return false
                val activeRecord = store.read(session.sessionId) ?: return false
                return valid(activeRecord, activeSession, registrationId) && activeRecord.credentialBinding == record.credentialBinding &&
                    activeRecord.keys.publicKey == record.keys.publicKey && activeRecord.relayIdentity == record.relayIdentity
            }
            if (!current()) return@withLock PushReceiveResult.IGNORED
            presenter.show(session, notification, ::current)
            PushReceiveResult.PROCESSED
        }
    }
    private fun valid(record: StoredPushRegistration, session: AccountSession, id: String) =
        record.registrationId == id && record.state == PushRegistrationState.ACTIVE && record.endpoint != null &&
            record.credentialBinding == PushRegistrationGuard.binding(session)
}
