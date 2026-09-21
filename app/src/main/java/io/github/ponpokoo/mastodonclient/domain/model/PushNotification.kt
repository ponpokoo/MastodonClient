package io.github.ponpokoo.mastodonclient.domain.model

/** Already authenticated/decrypted plain text. Do not render as HTML. */
class PushNotification(val id: String, val type: String?, val title: String, val body: String, val icon: String?)
