package io.github.ponpokoo.mastodonclient.core.network

import java.net.IDN
import java.net.URI
import java.util.Locale

object InstanceUrlNormalizer {
    fun normalize(input: String): Result<String> = runCatching {
        val candidate = input.trim().removeSuffix("/")
        require(candidate.isNotBlank()) { "インスタンスを入力してください" }

        val uri = URI(if (candidate.contains("://")) candidate else "https://$candidate")
        require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPSのインスタンスを指定してください" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "インスタンスURLが正しくありません" }
        require(uri.path.isNullOrBlank() || uri.path == "/") { "ドメイン名だけを入力してください" }

        val host = uri.host?.let(IDN::toASCII)?.lowercase(Locale.ROOT)
        require(!host.isNullOrBlank() && host.contains('.')) { "有効なドメイン名を入力してください" }

        buildString {
            append("https://")
            append(host)
            if (uri.port != -1 && uri.port != 443) append(":${uri.port}")
        }
    }
}
