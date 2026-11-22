package app.focal.engine.webview

import java.net.URI
import java.util.Locale

/**
 * Pure JVM detector for OAuth 2.0, OIDC, SAML/SSO, and interactive login endpoints.
 *
 * Used by Link Peek to bypass ephemeral preview profiles on authentication flows so
 * first-party `sessionStorage`, state nonces, and PKCE cookies stay bound to the main session.
 */
object OAuthUrlDetector {

    private val KNOWN_IDP_DOMAINS: Set<String> = setOf(
        "accounts.google.com",
        "login.microsoftonline.com",
        "login.live.com",
        "appleid.apple.com",
        "idmsa.apple.com",
        "auth0.com",
        "okta.com",
        "oktapreview.com",
        "onelogin.com",
        "duosecurity.com",
        "amazoncognito.com",
        "b2clogin.com",
        "login.yahoo.com",
    )

    private val AUTH_HOST_PREFIXES: List<String> = listOf(
        "sso.",
        "oauth.",
        "login.",
        "signin.",
        "idp.",
        "auth.",
    )

    private val AUTH_PATH_SEGMENTS: Set<String> = setOf(
        "oauth",
        "oauth2",
        "authorize",
        "signin",
        "sign-in",
        "sign_in",
        "login",
        "log-in",
        "log_in",
        "sso",
        "oidc",
        "openid-connect",
        "saml",
        "saml2",
    )

    private val EDITORIAL_PREFIX_SEGMENTS: Set<String> = setOf(
        "wiki",
        "blog",
        "blogs",
        "post",
        "posts",
        "article",
        "articles",
        "news",
        "docs",
        "doc",
        "guide",
        "guides",
        "tutorial",
        "tutorials",
    )

    private val PAGE_EXTENSIONS: List<String> = listOf(
        ".html",
        ".htm",
        ".php",
        ".aspx",
        ".jsp",
        ".do",
    )

    private val LOCALE_SEGMENT_REGEX = Regex("^[a-z]{2}(?:-[a-z]{2,4})?$")

    fun isAuthOrOAuthUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return false

        val parsed = parseHttpParts(trimmed) ?: return false
        val host = parsed.host
        val path = parsed.path
        val query = parsed.query

        if (isKnownAuthHost(host)) return true
        if (hasOAuthQueryParams(query)) return true
        if (hasAuthPath(path)) return true
        return false
    }

    private fun isKnownAuthHost(host: String): Boolean {
        if (host.isEmpty()) return false
        for (domain in KNOWN_IDP_DOMAINS) {
            if (host == domain || host.endsWith(".$domain")) return true
        }
        for (prefix in AUTH_HOST_PREFIXES) {
            if (host.startsWith(prefix)) return true
        }
        if (host.contains(".sso.")) return true
        return false
    }

    private fun hasOAuthQueryParams(query: String): Boolean {
        if (query.isEmpty()) return false
        var hasClientId = false
        var hasRedirectUri = false
        var hasCodeChallenge = false
        var hasResponseType = false

        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = (if (eq >= 0) pair.substring(0, eq) else pair).trim()
            val value = if (eq >= 0) pair.substring(eq + 1).trim() else ""
            if (value.isEmpty()) continue
            when (key) {
                "client_id" -> hasClientId = true
                "redirect_uri" -> hasRedirectUri = true
                "code_challenge" -> hasCodeChallenge = true
                "response_type" -> hasResponseType = true
            }
        }
        return hasClientId && (hasRedirectUri || hasCodeChallenge || hasResponseType)
    }

    private fun hasAuthPath(path: String): Boolean {
        if (path.isEmpty() || path == "/") return false
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return false

        // Skip editorial / documentation paths (e.g. /wiki/OAuth, /en-US/docs/oauth)
        val first = segments[0]
        if (first in EDITORIAL_PREFIX_SEGMENTS) return false
        if (segments.size >= 2 && LOCALE_SEGMENT_REGEX.matches(first) && segments[1] in EDITORIAL_PREFIX_SEGMENTS) {
            return false
        }

        for (rawSegment in segments) {
            val normalized = stripPageExtension(rawSegment)
            if (normalized in AUTH_PATH_SEGMENTS) return true
            if (normalized.startsWith("oauth_") || normalized.startsWith("oauth2_")) return true
        }
        return false
    }

    private fun stripPageExtension(segment: String): String {
        for (ext in PAGE_EXTENSIONS) {
            if (segment.endsWith(ext) && segment.length > ext.length) {
                return segment.substring(0, segment.length - ext.length)
            }
        }
        return segment
    }

    private data class ParsedHttpUrl(
        val host: String,
        val path: String,
        val query: String,
    )

    private fun parseHttpParts(url: String): ParsedHttpUrl? {
        val lowerPrefix = url.take(8).lowercase(Locale.US)
        if (!lowerPrefix.startsWith("https://") && !lowerPrefix.startsWith("http://")) {
            return null
        }

        try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.US)
            if (scheme != "https" && scheme != "http") return null
            val host = uri.host?.lowercase(Locale.US)?.trimEnd('.').orEmpty()
            if (host.isNotEmpty()) {
                return ParsedHttpUrl(
                    host = host,
                    path = uri.rawPath?.lowercase(Locale.US).orEmpty(),
                    query = uri.rawQuery?.lowercase(Locale.US).orEmpty(),
                )
            }
        } catch (_: Exception) {
            // Fall through to tolerant manual parser for URLs containing unescaped characters
        }

        val schemeSep = url.indexOf("://")
        if (schemeSep < 0) return null
        val afterScheme = url.substring(schemeSep + 3)
        val withoutFragment = afterScheme.substringBefore('#')
        val queryIndex = withoutFragment.indexOf('?')
        val authorityAndPath = if (queryIndex >= 0) withoutFragment.substring(0, queryIndex) else withoutFragment
        val query = if (queryIndex >= 0) withoutFragment.substring(queryIndex + 1).lowercase(Locale.US) else ""

        val slashIndex = authorityAndPath.indexOf('/')
        val authority = if (slashIndex >= 0) authorityAndPath.substring(0, slashIndex) else authorityAndPath
        val path = if (slashIndex >= 0) authorityAndPath.substring(slashIndex).lowercase(Locale.US) else ""

        val hostPort = authority.substringAfterLast('@')
        val host = hostPort.substringBefore(':').lowercase(Locale.US).trimEnd('.')
        if (host.isEmpty()) return null
        return ParsedHttpUrl(host = host, path = path, query = query)
    }
}
