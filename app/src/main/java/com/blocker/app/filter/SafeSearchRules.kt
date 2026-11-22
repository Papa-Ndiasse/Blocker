package com.blocker.app.filter

/**
 * Enforces SafeSearch across major search engines via DNS VIP rewriting.
 *
 * When a browser queries e.g. "www.google.fr" or "www.bing.com", the local DNS engine
 * transparently resolves the search engine's official SafeSearch VIP hostname
 * (e.g. "forcesafesearch.google.com", "strict.bing.com") and returns those IP records
 * directly for the queried name.
 *
 * Intentionally does NOT force YouTube Restricted Mode.
 */
object SafeSearchRules {

    const val GOOGLE_SAFE_HOST = "forcesafesearch.google.com"
    const val BING_SAFE_HOST = "strict.bing.com"
    const val DUCKDUCKGO_SAFE_HOST = "safe.duckduckgo.com"
    const val BRAVE_SAFE_HOST = "forcesafe.search.brave.com"
    const val YANDEX_SAFE_HOST = "familysearch.yandex.ru"
    const val PIXABAY_SAFE_HOST = "safesearch.pixabay.com"

    private val BING_DOMAINS = setOf(
        "bing.com",
        "www.bing.com",
        "edgeservices.bing.com"
    )

    private val DUCKDUCKGO_DOMAINS = setOf(
        "duckduckgo.com",
        "www.duckduckgo.com",
        "start.duckduckgo.com",
        "html.duckduckgo.com",
        "lite.duckduckgo.com"
    )

    private val BRAVE_DOMAINS = setOf(
        "search.brave.com"
    )

    private val YANDEX_DOMAINS = setOf(
        "yandex.com",
        "www.yandex.com",
        "yandex.ru",
        "www.yandex.ru",
        "ya.ru",
        "www.ya.ru"
    )

    private val PIXABAY_DOMAINS = setOf(
        "pixabay.com",
        "www.pixabay.com"
    )

    // Matches google.<tld> / www.google.<tld> / google.co.<cc> / www.google.com.<cc>
    // Deliberately does NOT match accounts.google.com, mail.google.com, docs.google.com, play.google.com, etc.
    private val GOOGLE_SEARCH_REGEX =
        Regex("^(www\\.)?google\\.(com|fr|de|es|it|nl|be|ch|ca|at|pl|pt|se|no|dk|fi|ie|cz|ro|hu|gr|ru|ua|tr|in|jp|kr|cn|tw|hk|sg|my|ph|th|vn|id|au|nz|za|eg|ma|dz|tn|sn|ci|cm|ng|ke|br|mx|ar|cl|co|pe|ve|co\\.[a-z]{2}|com\\.[a-z]{2})$")

    /**
     * Returns the SafeSearch target hostname if [rawDomain] should be rewritten, or null otherwise.
     */
    fun targetFor(rawDomain: String): String? {
        val domain = rawDomain.trim().lowercase().trimEnd('.')
        return when {
            GOOGLE_SEARCH_REGEX.matches(domain) -> GOOGLE_SAFE_HOST
            domain in BING_DOMAINS -> BING_SAFE_HOST
            domain in DUCKDUCKGO_DOMAINS -> DUCKDUCKGO_SAFE_HOST
            domain in BRAVE_DOMAINS -> BRAVE_SAFE_HOST
            domain in YANDEX_DOMAINS -> YANDEX_SAFE_HOST
            domain in PIXABAY_DOMAINS -> PIXABAY_SAFE_HOST
            else -> null
        }
    }
}
