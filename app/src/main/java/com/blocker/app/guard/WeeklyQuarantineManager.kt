package com.blocker.app.guard

import org.json.JSONArray
import org.json.JSONObject

enum class QuarantineTargetKind {
    APP,
    SITE
}

data class QuarantineTarget(
    val key: String,
    val kind: QuarantineTargetKind,
    val identifier: String,
    val displayName: String
)

data class QuarantinedEntry(
    val key: String,
    val kind: QuarantineTargetKind,
    val identifier: String,
    val displayName: String,
    val quarantinedAtMs: Long,
    val weeklyBlockCount: Int,
    val lastReason: String,
    val reportedToGitHub: Boolean = false,
    val githubIssueNumber: Int = 0
)

data class WeeklyQuarantineState(
    val weeklyStrikesByKey: Map<String, List<Long>> = emptyMap(),
    val quarantined: Map<String, QuarantinedEntry> = emptyMap()
) {
    fun hasUnreportedEntries(): Boolean = quarantined.values.any { !it.reportedToGitHub }
}

data class QuarantineStrikeOutcome(
    val newState: WeeklyQuarantineState,
    val target: QuarantineTarget,
    val weeklyCount: Int,
    val newlyQuarantined: QuarantinedEntry?
)

/**
 * Pure-Kotlin weekly quarantine engine:
 *   - Tracks content blocks per app ("app:<package>") or website ("site:<domain>") over a
 *     rolling 7-day window (1 week).
 *   - Deduplicates rapid accessibility callbacks (< 3 seconds apart) for the same target.
 *   - Once a target is blocked MORE THAN 10 times within the same week (11th block), it is
 *     PERMANENTLY quarantined (blocked completely on sight) and queued for GitHub Issue reporting.
 *   - ZERO local unblock API exists: the ONLY way to unblock a quarantined app/site is to
 *     release a new signed Blocker update containing an entry in COMPILED_UNBLOCK_GRANTS_MS
 *     with an epoch timestamp newer than `quarantinedAtMs`.
 */
object WeeklyQuarantineEngine {

    const val WEEK_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val MAX_BLOCKS_BEFORE_QUARANTINE: Int = 10
    const val MIN_STRIKE_SPACING_MS: Long = 3_000L

    /**
     * Compiled-in unblock grants released ONLY via a new signed Blocker APK update.
     * Key format: "app:<packageName>" or "site:<domain>"
     * Value: epoch timestamp (ms) strictly greater than the target's `quarantinedAtMs`.
     *
     * Example:
     *   "app:com.reddit.frontpage" to 1795900000000L
     */
    val COMPILED_UNBLOCK_GRANTS_MS: Map<String, Long> = emptyMap()

    // Essential system / infrastructure packages that must NEVER be permanently quarantined
    private val NEVER_QUARANTINE_APP_PACKAGES = setOf(
        "com.blocker.app",
        "android",
        "com.android.systemui",
        "com.sec.android.app.launcher",
        "com.samsung.android.honeyboard",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.app.aodservice",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.emergency",
        "com.samsung.android.emergency",
        "com.google.android.dialer",
        "com.google.android.googlequicksearchbox"
    )

    // Immune sites + essential search/code platforms that must NEVER be whole-site quarantined
    private val NEVER_QUARANTINE_SITES = setOf(
        "poseidon-scan.net",
        "discord.com",
        "discord.gg",
        "discordapp.com",
        "discordapp.net",
        "youtube.com",
        "youtu.be",
        "tiktok.com",
        "instagram.com",
        "snapchat.com",
        "pinterest.com",
        "whatsapp.com",
        "google.com",
        "google.fr",
        "bing.com",
        "duckduckgo.com",
        "ecosia.org",
        "qwant.com",
        "yahoo.com",
        "github.com",
        "githubusercontent.com"
    )

    private val MULTI_PART_PUBLIC_SUFFIXES = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk",
        "com.br", "com.au", "co.jp", "co.kr",
        "co.in", "com.mx", "com.tr", "com.ar",
        "com.tw", "com.hk", "co.nz", "co.za"
    )

    private val FRIENDLY_APP_NAMES = mapOf(
        "com.reddit.frontpage" to "Reddit",
        "com.reddit.android" to "Reddit",
        "com.rubenmayayo.reddit" to "Boost for Reddit",
        "ml.docilealligator.infinityforreddit" to "Infinity for Reddit",
        "com.laurencedawson.reddit_sync" to "Sync for Reddit",
        "com.andrewshu.android.reddit" to "rif is fun",
        "com.twitter.android" to "X (Twitter)",
        "com.twitter.android.lite" to "X Lite",
        "app.mihon" to "Mihon",
        "app.mihon.debug" to "Mihon",
        "eu.kanade.tachiyomi" to "Tachiyomi",
        "eu.kanade.tachiyomi.sy" to "TachiyomiSY",
        "eu.kanade.tachiyomi.j2k" to "TachiyomiJ2K",
        "app.komikku" to "Komikku",
        "app.aniyomi" to "Aniyomi",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "org.thunderdog.challegram" to "Telegram X",
        "com.tumblr" to "Tumblr"
    )

    fun resolveTarget(
        sourcePackage: String,
        extractedHost: String?,
        isBrowser: Boolean
    ): QuarantineTarget? {
        val pkg = sourcePackage.trim().lowercase()
        if (pkg.isEmpty()) return null

        // Inside browsers or Google Search, quarantine the offending SITE, never the browser itself
        if (isBrowser || pkg == "com.google.android.googlequicksearchbox") {
            val site = normalizeSiteDomain(extractedHost) ?: return null
            if (isNeverQuarantineSite(site)) return null
            return QuarantineTarget(
                key = "site:$site",
                kind = QuarantineTargetKind.SITE,
                identifier = site,
                displayName = site
            )
        }

        // Native apps: exclude system, settings, installers, stores, and immune everyday apps
        if (isNeverQuarantineApp(pkg)) return null
        val displayName = FRIENDLY_APP_NAMES[pkg] ?: pkg
        return QuarantineTarget(
            key = "app:$pkg",
            kind = QuarantineTargetKind.APP,
            identifier = pkg,
            displayName = displayName
        )
    }

    fun isNeverQuarantineApp(packageName: String): Boolean {
        val low = packageName.trim().lowercase()
        if (low.isEmpty()) return true
        if (low in NEVER_QUARANTINE_APP_PACKAGES) return true
        if (low in ScreenContentClassifier.IMMUNE_PACKAGES) return true
        if (low in ScreenContentClassifier.SETTINGS_PACKAGES) return true
        if (low in ScreenContentClassifier.STORE_AND_INSTALLER_PACKAGES) return true
        if (ScreenContentClassifier.isBrowserPackage(low)) return true
        if (low.contains("settings") ||
            low.contains("packageinstaller") ||
            low.contains("systemui") ||
            low.contains("launcher") ||
            low.contains("discord")
        ) {
            return true
        }
        return false
    }

    fun isNeverQuarantineSite(normalizedSite: String): Boolean {
        val low = normalizedSite.trim().lowercase()
        if (low.isEmpty()) return true
        if (low in NEVER_QUARANTINE_SITES) return true
        if (ScreenContentClassifier.isImmuneUrl(low)) return true
        return NEVER_QUARANTINE_SITES.any { low.endsWith(".$it") }
    }

    fun normalizeSiteDomain(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var cleaned = raw.trim().lowercase()
        cleaned = cleaned.removePrefix("https://").removePrefix("http://")
        cleaned = cleaned.substringBefore('/').substringBefore('?').substringBefore('#')
        cleaned = cleaned.substringBefore(':')
        cleaned = cleaned.trimEnd('.')
        if (cleaned.length < 3 || !cleaned.contains('.')) return null
        if (!cleaned.matches(Regex("^[a-z0-9.-]+$"))) return null
        if (cleaned.startsWith('.') || cleaned.startsWith('-') || cleaned.contains("..")) return null

        val parts = cleaned.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        // Collapse subdomains to registrable domain (e.g. old.reddit.com -> reddit.com, sub.site.co.uk -> site.co.uk)
        if (parts.size >= 3) {
            val lastTwo = "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
            return if (lastTwo in MULTI_PART_PUBLIC_SUFFIXES) {
                "${parts[parts.size - 3]}.$lastTwo"
            } else {
                lastTwo
            }
        }
        return cleaned
    }

    fun applyUnblockGrants(
        state: WeeklyQuarantineState,
        unblockGrantsMs: Map<String, Long> = COMPILED_UNBLOCK_GRANTS_MS
    ): WeeklyQuarantineState {
        if (unblockGrantsMs.isEmpty()) return state

        var changed = false
        val remainingQuarantined = LinkedHashMap<String, QuarantinedEntry>(state.quarantined.size)
        for ((key, entry) in state.quarantined) {
            val grantMs = unblockGrantsMs[key]
            if (grantMs != null && grantMs > entry.quarantinedAtMs) {
                changed = true
            } else {
                remainingQuarantined[key] = entry
            }
        }

        val updatedStrikes = LinkedHashMap<String, List<Long>>(state.weeklyStrikesByKey.size)
        for ((key, timestamps) in state.weeklyStrikesByKey) {
            val grantMs = unblockGrantsMs[key]
            if (grantMs != null) {
                val kept = timestamps.filter { it > grantMs }
                if (kept.size != timestamps.size) changed = true
                if (kept.isNotEmpty()) updatedStrikes[key] = kept
            } else if (timestamps.isNotEmpty()) {
                updatedStrikes[key] = timestamps
            }
        }

        return if (changed) {
            WeeklyQuarantineState(
                weeklyStrikesByKey = updatedStrikes,
                quarantined = remainingQuarantined
            )
        } else {
            state
        }
    }

    fun findQuarantinedApp(
        state: WeeklyQuarantineState,
        packageName: String,
        unblockGrantsMs: Map<String, Long> = COMPILED_UNBLOCK_GRANTS_MS
    ): QuarantinedEntry? {
        val key = "app:${packageName.trim().lowercase()}"
        val entry = state.quarantined[key] ?: return null
        val grantMs = unblockGrantsMs[key]
        if (grantMs != null && grantMs > entry.quarantinedAtMs) return null
        return entry
    }

    fun findQuarantinedSite(
        state: WeeklyQuarantineState,
        rawHostOrUrl: String?,
        unblockGrantsMs: Map<String, Long> = COMPILED_UNBLOCK_GRANTS_MS
    ): QuarantinedEntry? {
        val site = normalizeSiteDomain(rawHostOrUrl) ?: return null
        val key = "site:$site"
        val entry = state.quarantined[key] ?: return null
        val grantMs = unblockGrantsMs[key]
        if (grantMs != null && grantMs > entry.quarantinedAtMs) return null
        return entry
    }

    fun recordBlock(
        state: WeeklyQuarantineState,
        target: QuarantineTarget,
        reason: String,
        nowMs: Long,
        unblockGrantsMs: Map<String, Long> = COMPILED_UNBLOCK_GRANTS_MS
    ): QuarantineStrikeOutcome {
        val cleaned = applyUnblockGrants(state, unblockGrantsMs)

        // Already permanently quarantined — do not re-trigger
        val existingEntry = cleaned.quarantined[target.key]
        if (existingEntry != null) {
            return QuarantineStrikeOutcome(
                newState = cleaned,
                target = target,
                weeklyCount = existingEntry.weeklyBlockCount,
                newlyQuarantined = null
            )
        }

        // Prune expired (> 7 days old) strikes across all tracked targets
        val cutoff = nowMs - WEEK_WINDOW_MS
        val updatedStrikesMap = LinkedHashMap<String, List<Long>>()
        for ((k, list) in cleaned.weeklyStrikesByKey) {
            val fresh = list.filter { it in cutoff..nowMs }
            if (fresh.isNotEmpty()) {
                updatedStrikesMap[k] = fresh
            }
        }

        val currentForTarget = updatedStrikesMap[target.key].orEmpty()
        val lastTimestamp = currentForTarget.lastOrNull()
        val updatedForTarget = if (lastTimestamp != null && nowMs - lastTimestamp < MIN_STRIKE_SPACING_MS) {
            currentForTarget
        } else {
            currentForTarget + nowMs
        }

        // Trigger permanent quarantine strictly when blocked MORE than 10 times in 7 days (11th block)
        if (updatedForTarget.size > MAX_BLOCKS_BEFORE_QUARANTINE) {
            val entry = QuarantinedEntry(
                key = target.key,
                kind = target.kind,
                identifier = target.identifier,
                displayName = target.displayName,
                quarantinedAtMs = nowMs,
                weeklyBlockCount = updatedForTarget.size,
                lastReason = reason,
                reportedToGitHub = false,
                githubIssueNumber = 0
            )
            updatedStrikesMap.remove(target.key)
            val updatedQuarantined = LinkedHashMap(cleaned.quarantined)
            updatedQuarantined[target.key] = entry
            return QuarantineStrikeOutcome(
                newState = WeeklyQuarantineState(
                    weeklyStrikesByKey = updatedStrikesMap,
                    quarantined = updatedQuarantined
                ),
                target = target,
                weeklyCount = updatedForTarget.size,
                newlyQuarantined = entry
            )
        }

        updatedStrikesMap[target.key] = updatedForTarget
        return QuarantineStrikeOutcome(
            newState = WeeklyQuarantineState(
                weeklyStrikesByKey = updatedStrikesMap,
                quarantined = cleaned.quarantined
            ),
            target = target,
            weeklyCount = updatedForTarget.size,
            newlyQuarantined = null
        )
    }

    fun markReportedToGitHub(
        state: WeeklyQuarantineState,
        key: String,
        issueNumber: Int
    ): WeeklyQuarantineState {
        val existing = state.quarantined[key] ?: return state
        val updated = LinkedHashMap(state.quarantined)
        updated[key] = existing.copy(
            reportedToGitHub = true,
            githubIssueNumber = issueNumber
        )
        return state.copy(quarantined = updated)
    }

    fun serialize(state: WeeklyQuarantineState): String {
        val root = JSONObject()

        val strikesObj = JSONObject()
        for ((key, list) in state.weeklyStrikesByKey) {
            val arr = JSONArray()
            for (ts in list) arr.put(ts)
            strikesObj.put(key, arr)
        }
        root.put("strikes", strikesObj)

        val quarantinedArr = JSONArray()
        for (entry in state.quarantined.values) {
            val kindStr = if (entry.kind == QuarantineTargetKind.SITE) "SITE" else "APP"
            val obj = JSONObject()
                .put("key", entry.key)
                .put("kind", kindStr)
                .put("identifier", entry.identifier)
                .put("displayName", entry.displayName)
                .put("quarantinedAtMs", entry.quarantinedAtMs)
                .put("weeklyBlockCount", entry.weeklyBlockCount)
                .put("lastReason", entry.lastReason)
                .put("reportedToGitHub", entry.reportedToGitHub)
                .put("githubIssueNumber", entry.githubIssueNumber)
            quarantinedArr.put(obj)
        }
        root.put("quarantined", quarantinedArr)
        return root.toString()
    }

    fun deserialize(rawJson: String?): WeeklyQuarantineState {
        if (rawJson.isNullOrBlank()) return WeeklyQuarantineState()
        return try {
            val root = JSONObject(rawJson)
            val strikesMap = LinkedHashMap<String, List<Long>>()
            val strikesObj = root.optJSONObject("strikes")
            if (strikesObj != null) {
                val keys = strikesObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val arr = strikesObj.optJSONArray(key) ?: continue
                    val list = ArrayList<Long>(arr.length())
                    for (i in 0 until arr.length()) {
                        val v = arr.optLong(i, 0L)
                        if (v > 0L) list.add(v)
                    }
                    if (list.isNotEmpty()) strikesMap[key] = list
                }
            }

            val quarantinedMap = LinkedHashMap<String, QuarantinedEntry>()
            val qArr = root.optJSONArray("quarantined")
            if (qArr != null) {
                for (i in 0 until qArr.length()) {
                    val obj = qArr.optJSONObject(i) ?: continue
                    val key = obj.optString("key", "").trim()
                    if (key.isEmpty()) continue
                    val kindName = obj.optString("kind", "APP")
                    val kind = if (kindName.equals("SITE", ignoreCase = true)) {
                        QuarantineTargetKind.SITE
                    } else {
                        QuarantineTargetKind.APP
                    }
                    val identifier = obj.optString("identifier", key.substringAfter(':'))
                    val displayName = obj.optString("displayName", identifier)
                    quarantinedMap[key] = QuarantinedEntry(
                        key = key,
                        kind = kind,
                        identifier = identifier,
                        displayName = displayName,
                        quarantinedAtMs = obj.optLong("quarantinedAtMs", 0L),
                        weeklyBlockCount = obj.optInt("weeklyBlockCount", MAX_BLOCKS_BEFORE_QUARANTINE + 1),
                        lastReason = obj.optString("lastReason", ""),
                        reportedToGitHub = obj.optBoolean("reportedToGitHub", false),
                        githubIssueNumber = obj.optInt("githubIssueNumber", 0)
                    )
                }
            }

            WeeklyQuarantineState(
                weeklyStrikesByKey = strikesMap,
                quarantined = quarantinedMap
            )
        } catch (_: Throwable) {
            WeeklyQuarantineState()
        }
    }
}
