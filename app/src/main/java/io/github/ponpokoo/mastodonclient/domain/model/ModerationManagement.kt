package io.github.ponpokoo.mastodonclient.domain.model

enum class ModerationListKind { Mutes, Blocks }
@kotlinx.serialization.Serializable
data class ModerationAccount(val account: StatusAuthor, val relationship: AccountRelationship, val muteExpiresAt: String? = null)
data class ModerationAccountsPage(val accounts: List<ModerationAccount>, val nextMaxId: String?)
