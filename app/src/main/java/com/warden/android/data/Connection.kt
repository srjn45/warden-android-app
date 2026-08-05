package com.warden.android.data

import kotlinx.serialization.Serializable

/**
 * A single saved warden endpoint: a base URL and its bearer token. [label] is a
 * user-facing name (defaults to the host). P0 keeps one active connection but
 * the store is written multi-host-ready (design.md §5, Connect screen).
 */
@Serializable
data class Connection(
    val label: String,
    val baseUrl: String,
    val token: String,
) {
    companion object {
        /**
         * Normalizes user input into a canonical base URL:
         *  - prepends `http://` when no scheme is given,
         *  - appends the default daemon port `:8765` when no port is present on
         *    an `http://` host (https hosts like `*.ts.net` keep their implicit
         *    443),
         *  - guarantees exactly one trailing slash (Retrofit requires it).
         *
         * Returns null when the input has no host.
         */
        fun normalizeBaseUrl(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty()) return null

            val hasScheme = s.startsWith("http://", ignoreCase = true) ||
                s.startsWith("https://", ignoreCase = true)
            if (!hasScheme) s = "http://$s"

            val isHttps = s.startsWith("https://", ignoreCase = true)
            val schemeEnd = s.indexOf("://") + 3
            val afterScheme = s.substring(schemeEnd).trimEnd('/')
            if (afterScheme.isEmpty()) return null

            // Split off any path so port detection only inspects the authority.
            val slash = afterScheme.indexOf('/')
            val authority = if (slash >= 0) afterScheme.substring(0, slash) else afterScheme
            val path = if (slash >= 0) afterScheme.substring(slash) else ""
            if (authority.isEmpty()) return null

            // Detect a port, ignoring IPv6 bracket colons.
            val hasPort = if (authority.startsWith("[")) {
                val close = authority.indexOf(']')
                close >= 0 && authority.indexOf(':', close) >= 0
            } else {
                authority.contains(':')
            }

            val scheme = if (isHttps) "https://" else "http://"
            val withPort = if (!hasPort && !isHttps) "$authority:8765" else authority
            val normalizedPath = path.trimEnd('/')
            return "$scheme$withPort$normalizedPath/"
        }
    }
}
