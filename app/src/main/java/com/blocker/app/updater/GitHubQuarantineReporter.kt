package com.blocker.app.updater

import android.util.Log
import com.blocker.app.BuildConfig
import com.blocker.app.data.BlockerPreferences
import com.blocker.app.guard.QuarantineTargetKind
import com.blocker.app.guard.QuarantinedEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed class GitHubQuarantineSyncResult {
    data object NothingPending : GitHubQuarantineSyncResult()
    data object MissingToken : GitHubQuarantineSyncResult()
    data class Synced(val reportedCount: Int, val failedCount: Int) : GitHubQuarantineSyncResult()
    data class Error(val message: String) : GitHubQuarantineSyncResult()
}

/**
 * Reports permanently quarantined apps/sites (>10 blocks within 7 days) as GitHub Issues
 * labeled `quarantine` on `Papa-Ndiasse/Blocker`.
 *
 * Uses a tiny (~1 KB) REST API request — deduplicates against existing open `quarantine`
 * issues so the same app/site is never posted twice.
 */
class GitHubQuarantineReporter(
    private val preferences: BlockerPreferences
) {
    suspend fun reportPendingQuarantines(): GitHubQuarantineSyncResult = withContext(Dispatchers.IO) {
        val state = preferences.loadQuarantineState()
        val pending = state.quarantined.values.filter { !it.reportedToGitHub }
        if (pending.isEmpty()) return@withContext GitHubQuarantineSyncResult.NothingPending

        val token = resolveAuthToken()
        if (token.isEmpty()) return@withContext GitHubQuarantineSyncResult.MissingToken

        return@withContext try {
            val existingOpenByKey = fetchOpenQuarantineIssueNumbers(token)
            var reported = 0
            var failed = 0

            for (entry in pending) {
                val existingIssue = existingOpenByKey[entry.key]
                if (existingIssue != null && existingIssue > 0) {
                    preferences.markQuarantineReportedToGitHub(entry.key, existingIssue)
                    reported++
                    continue
                }

                val createdIssue = createQuarantineIssue(token, entry)
                if (createdIssue != null && createdIssue > 0) {
                    preferences.markQuarantineReportedToGitHub(entry.key, createdIssue)
                    reported++
                } else {
                    failed++
                }
            }

            GitHubQuarantineSyncResult.Synced(reportedCount = reported, failedCount = failed)
        } catch (e: Exception) {
            Log.w(TAG, "GitHub quarantine reporting failed: ${e.message}")
            GitHubQuarantineSyncResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun resolveAuthToken(): String {
        val saved = preferences.githubUpdateToken.value.trim()
        if (saved.isNotEmpty()) return saved
        return BuildConfig.GITHUB_UPDATE_TOKEN.trim()
    }

    private fun fetchOpenQuarantineIssueNumbers(token: String): Map<String, Int> {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/issues?labels=$QUARANTINE_LABEL&state=open&per_page=100"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Blocker-Android/${BuildConfig.VERSION_NAME}")
        }
        return try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "GET quarantine issues returned HTTP ${conn.responseCode}")
                return emptyMap()
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            val result = HashMap<String, Int>(arr.length())
            for (i in 0 until arr.length()) {
                val issue = arr.optJSONObject(i) ?: continue
                val number = issue.optInt("number", 0)
                if (number <= 0) continue
                val title = issue.optString("title", "")
                val issueBody = issue.optString("body", "")
                val combined = "$title\n$issueBody"
                val keyMatch = KEY_MARKER_REGEX.find(combined)?.groupValues?.getOrNull(1)
                if (!keyMatch.isNullOrBlank()) {
                    result[keyMatch.lowercase()] = number
                }
            }
            result
        } finally {
            conn.disconnect()
        }
    }

    private fun createQuarantineIssue(token: String, entry: QuarantinedEntry): Int? {
        val url = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/issues"
        val payload = JSONObject().apply {
            put("title", buildIssueTitle(entry))
            put("body", buildIssueBody(entry))
            put("labels", JSONArray().put(QUARANTINE_LABEL))
        }.toString()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 12_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Blocker-Android/${BuildConfig.VERSION_NAME}")
        }

        return try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
                writer.flush()
            }
            val code = conn.responseCode
            if (code in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val issueNumber = JSONObject(resp).optInt("number", 0)
                Log.i(TAG, "Reported quarantine ${entry.key} to GitHub Issue #$issueNumber")
                if (issueNumber > 0) issueNumber else null
            } else {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                Log.w(TAG, "POST quarantine issue failed (HTTP $code): $errBody")
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "BlockerGitHubQuarantine"
        private const val QUARANTINE_LABEL = "quarantine"
        private val KEY_MARKER_REGEX = Regex("""\b((?:app|site):[a-z0-9._-]+)\b""", RegexOption.IGNORE_CASE)

        fun buildIssueTitle(entry: QuarantinedEntry): String {
            return "Quarantaine : ${entry.displayName} (${entry.key})"
        }

        fun buildIssueBody(entry: QuarantinedEntry): String {
            val typeLabel = if (entry.kind == QuarantineTargetKind.APP) "Application" else "Site web"
            val isoDate = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(entry.quarantinedAtMs))
            val unlockTimestamp = entry.quarantinedAtMs + 1_000L

            return buildString {
                appendLine("## Blocage complet automatique (> 10 blocages dans la même semaine)")
                appendLine()
                appendLine("- **Type** : $typeLabel")
                appendLine("- **Cible** : `${entry.displayName}` (`${entry.identifier}`)")
                appendLine("- **Clé technique** : `${entry.key}`")
                appendLine("- **Blocages sur 7 jours** : **${entry.weeklyBlockCount}**")
                appendLine("- **Date de quarantaine (UTC)** : `$isoDate` (`${entry.quarantinedAtMs}`)")
                appendLine("- **Dernier motif** : ${entry.lastReason}")
                appendLine("- **Version Blocker** : `v${BuildConfig.VERSION_NAME}`")
                appendLine()
                appendLine("---")
                appendLine("### Déblocage (mise à jour Blocker uniquement)")
                appendLine("Aucun déblocage n'est possible sur le téléphone. Pour débloquer cette cible, ajouter dans `WeeklyQuarantineEngine.COMPILED_UNBLOCK_GRANTS_MS` :")
                appendLine()
                appendLine("```kotlin")
                appendLine("\"${entry.key}\" to ${unlockTimestamp}L")
                appendLine("```")
                appendLine("puis publier une nouvelle mise à jour Blocker et fermer cette issue.")
            }
        }
    }
}
