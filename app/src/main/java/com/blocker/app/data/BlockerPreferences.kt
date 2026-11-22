package com.blocker.app.data

import android.content.Context
import android.content.SharedPreferences
import com.blocker.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class BlockedEvent(
    val timestampMs: Long,
    val maskedDomain: String,
    val reason: String
)

data class HardeningFlags(
    val sealPermanentProtection: Boolean = false,
    val lockAdbDebugging: Boolean = false,
    val lockUnknownSources: Boolean = false,
    val lockAllAppInstalls: Boolean = false,
    val lockChromeIncognito: Boolean = false,
    val lockRedditEntirely: Boolean = false,
    val lockXEntirely: Boolean = false,
    val lockMihonMixedExtensions: Boolean = false,
    val lockSamsungSecureFolder: Boolean = false,
    val lockFactoryResetSettings: Boolean = false
)

/**
 * Stores persistent Blocker state in Device-Protected Storage so the Always-On VPN
 * and boot receiver can operate cleanly during Android Direct Boot (before first unlock).
 *
 * Security invariants enforced in code:
 * - Hardening flags are strictly ONE-WAY (false -> true only, never reverted to false).
 * - Custom blocked domains are strictly APPEND-ONLY (no removal API exists).
 */
class BlockerPreferences(context: Context) {

    private val deviceContext = context.createDeviceProtectedStorageContext()
    private val prefs: SharedPreferences =
        deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _totalBlocked = MutableStateFlow(prefs.getLong(KEY_TOTAL_BLOCKED, 0L))
    val totalBlocked: StateFlow<Long> = _totalBlocked.asStateFlow()

    private val _todayBlocked = MutableStateFlow(loadTodayBlocked())
    val todayBlocked: StateFlow<Long> = _todayBlocked.asStateFlow()

    private val _blocklistCount = MutableStateFlow(prefs.getInt(KEY_BLOCKLIST_COUNT, 0))
    val blocklistCount: StateFlow<Int> = _blocklistCount.asStateFlow()

    private val _lastSyncEpochMs = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC_MS, 0L))
    val lastSyncEpochMs: StateFlow<Long> = _lastSyncEpochMs.asStateFlow()

    private val _lastUpdateCheckMs = MutableStateFlow(prefs.getLong(KEY_LAST_UPDATE_CHECK_MS, 0L))
    val lastUpdateCheckMs: StateFlow<Long> = _lastUpdateCheckMs.asStateFlow()

    private val _githubUpdateToken = MutableStateFlow(prefs.getString(KEY_GITHUB_UPDATE_TOKEN, "") ?: "")
    val githubUpdateToken: StateFlow<String> = _githubUpdateToken.asStateFlow()

    private val _customDomains = MutableStateFlow(loadCustomDomains())
    val customDomains: StateFlow<Set<String>> = _customDomains.asStateFlow()

    private val _hardening = MutableStateFlow(loadHardeningFlags())
    val hardening: StateFlow<HardeningFlags> = _hardening.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<BlockedEvent>>(emptyList())
    val recentEvents: StateFlow<List<BlockedEvent>> = _recentEvents.asStateFlow()

    private fun loadTodayBlocked(): Long {
        val today = LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_TODAY_DATE, "")
        return if (storedDate == today) prefs.getLong(KEY_TODAY_BLOCKED, 0L) else 0L
    }

    private fun loadCustomDomains(): Set<String> {
        return prefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet())
            ?.map { it.lowercase().trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    private fun loadHardeningFlags(): HardeningFlags {
        return HardeningFlags(
            sealPermanentProtection = prefs.getBoolean(KEY_SEALED, false),
            lockAdbDebugging = prefs.getBoolean(KEY_LOCK_ADB, false),
            lockUnknownSources = prefs.getBoolean(KEY_LOCK_UNKNOWN_SOURCES, false),
            lockAllAppInstalls = prefs.getBoolean(KEY_LOCK_ALL_INSTALLS, false),
            lockChromeIncognito = prefs.getBoolean(KEY_LOCK_INCOGNITO, false),
            lockRedditEntirely = prefs.getBoolean(KEY_LOCK_REDDIT, false),
            lockXEntirely = prefs.getBoolean(KEY_LOCK_X, false),
            lockMihonMixedExtensions = prefs.getBoolean(KEY_LOCK_MIHON_MIXED, false),
            lockSamsungSecureFolder = prefs.getBoolean(KEY_LOCK_SECURE_FOLDER, false),
            lockFactoryResetSettings = prefs.getBoolean(KEY_LOCK_FACTORY_RESET, false)
        )
    }

    private var lastDiskFlushMs: Long = 0L

    @Synchronized
    fun recordBlocked(domain: String, reason: String) {
        val today = LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_TODAY_DATE, "")
        val newTotal = _totalBlocked.value + 1L
        val newToday = if (storedDate == today) {
            _todayBlocked.value + 1L
        } else {
            1L
        }

        _totalBlocked.value = newTotal
        _todayBlocked.value = newToday

        // Throttle disk persistence to at most once every 2s during burst blocks (saves battery/UFS wear on A55)
        val now = System.currentTimeMillis()
        if (storedDate != today || now - lastDiskFlushMs >= 2_000L) {
            lastDiskFlushMs = now
            prefs.edit()
                .putLong(KEY_TOTAL_BLOCKED, newTotal)
                .putString(KEY_TODAY_DATE, today)
                .putLong(KEY_TODAY_BLOCKED, newToday)
                .apply()
        }

        val event = BlockedEvent(
            timestampMs = now,
            maskedDomain = maskDomain(domain),
            reason = reason
        )
        _recentEvents.value = (listOf(event) + _recentEvents.value).take(MAX_RECENT_EVENTS)
    }

    /**
     * Permanently appends a domain to the custom blocklist.
     * Intentionally append-only: domains can never be removed once added.
     */
    @Synchronized
    fun addCustomDomain(rawInput: String): String? {
        val normalized = normalizeDomainInput(rawInput) ?: return null
        val updated = LinkedHashSet(_customDomains.value)
        updated.add(normalized)
        prefs.edit().putStringSet(KEY_CUSTOM_DOMAINS, updated).apply()
        _customDomains.value = updated.toSet()
        return normalized
    }

    fun updateBlocklistMetadata(domainCount: Int, syncedAtMs: Long) {
        prefs.edit()
            .putInt(KEY_BLOCKLIST_COUNT, domainCount)
            .putLong(KEY_LAST_SYNC_MS, syncedAtMs)
            .apply()
        _blocklistCount.value = domainCount
        _lastSyncEpochMs.value = syncedAtMs
    }

    fun recordUpdateCheck(checkedAtMs: Long) {
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_MS, checkedAtMs).apply()
        _lastUpdateCheckMs.value = checkedAtMs
    }

    fun saveGithubUpdateToken(token: String) {
        val clean = token.trim()
        prefs.edit().putString(KEY_GITHUB_UPDATE_TOKEN, clean).apply()
        _githubUpdateToken.value = clean
    }

    // --- One-way irreversible latches (can only transition false -> true) ---

    @Synchronized
    fun latchPermanentSeal() {
        prefs.edit().putBoolean(KEY_SEALED, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchAdbLock() {
        prefs.edit().putBoolean(KEY_LOCK_ADB, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchUnknownSourcesLock() {
        prefs.edit().putBoolean(KEY_LOCK_UNKNOWN_SOURCES, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchAllAppInstallsLock() {
        prefs.edit().putBoolean(KEY_LOCK_ALL_INSTALLS, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchChromeIncognitoLock() {
        prefs.edit().putBoolean(KEY_LOCK_INCOGNITO, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchRedditTotalLock() {
        prefs.edit().putBoolean(KEY_LOCK_REDDIT, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchXTotalLock() {
        prefs.edit().putBoolean(KEY_LOCK_X, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchMihonMixedLock() {
        prefs.edit().putBoolean(KEY_LOCK_MIHON_MIXED, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchSamsungSecureFolderLock() {
        prefs.edit().putBoolean(KEY_LOCK_SECURE_FOLDER, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    @Synchronized
    fun latchFactoryResetLock() {
        prefs.edit().putBoolean(KEY_LOCK_FACTORY_RESET, true).apply()
        _hardening.value = loadHardeningFlags()
    }

    /**
     * Strictly gated by compile-time BuildConfig.EMERGENCY_DISABLE: cannot ever run
     * in a normal release build, only in an emergency GitHub recovery update.
     */
    @Synchronized
    fun clearSealForEmergency() {
        if (!BuildConfig.EMERGENCY_DISABLE) return
        prefs.edit()
            .putBoolean(KEY_SEALED, false)
            .putBoolean(KEY_LOCK_REDDIT, false)
            .putBoolean(KEY_LOCK_X, false)
            .putBoolean(KEY_LOCK_SECURE_FOLDER, false)
            .apply()
        _hardening.value = loadHardeningFlags()
    }

    companion object {
        private const val PREFS_NAME = "blocker_secure_prefs"
        private const val KEY_TOTAL_BLOCKED = "total_blocked"
        private const val KEY_TODAY_BLOCKED = "today_blocked"
        private const val KEY_TODAY_DATE = "today_date"
        private const val KEY_BLOCKLIST_COUNT = "blocklist_count"
        private const val KEY_LAST_SYNC_MS = "last_sync_ms"
        private const val KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms"
        private const val KEY_GITHUB_UPDATE_TOKEN = "github_update_token"
        private const val KEY_CUSTOM_DOMAINS = "custom_domains"
        private const val KEY_SEALED = "sealed_permanent"
        private const val KEY_LOCK_ADB = "lock_adb"
        private const val KEY_LOCK_UNKNOWN_SOURCES = "lock_unknown_sources"
        private const val KEY_LOCK_ALL_INSTALLS = "lock_all_app_installs"
        private const val KEY_LOCK_INCOGNITO = "lock_incognito"
        private const val KEY_LOCK_REDDIT = "lock_reddit_total"
        private const val KEY_LOCK_X = "lock_x_total"
        private const val KEY_LOCK_MIHON_MIXED = "lock_mihon_mixed"
        private const val KEY_LOCK_SECURE_FOLDER = "lock_samsung_secure_folder"
        private const val KEY_LOCK_FACTORY_RESET = "lock_factory_reset"
        private const val MAX_RECENT_EVENTS = 50

        fun normalizeDomainInput(input: String): String? {
            var cleaned = input.trim().lowercase()
            cleaned = cleaned.removePrefix("https://").removePrefix("http://")
            cleaned = cleaned.substringBefore('/').substringBefore('?').substringBefore('#')
            cleaned = cleaned.substringBefore(':')
            cleaned = cleaned.removePrefix("www.")
            cleaned = cleaned.trimEnd('.')
            if (cleaned.length < 3 || !cleaned.contains('.')) return null
            if (!cleaned.matches(Regex("^[a-z0-9.-]+$"))) return null
            if (cleaned.startsWith('.') || cleaned.startsWith('-') || cleaned.contains("..")) return null
            return cleaned
        }

        /**
         * Masks the domain name in logs (e.g. "example.com" -> "e***e.com") so the activity
         * log stays clean and non-triggering while still confirming blocks.
         */
        fun maskDomain(domain: String): String {
            val clean = domain.trimEnd('.').lowercase()
            val parts = clean.split('.')
            if (parts.size < 2) return "***"
            val tld = parts.last()
            val body = parts.dropLast(1).joinToString(".")
            return when {
                body.length <= 2 -> "***." + tld
                else -> "${body.first()}***${body.last()}.$tld"
            }
        }
    }
}
