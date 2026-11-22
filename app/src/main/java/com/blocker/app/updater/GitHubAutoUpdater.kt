package com.blocker.app.updater

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.UserManager
import android.util.Log
import com.blocker.app.BuildConfig
import com.blocker.app.admin.BlockerDeviceAdminReceiver
import com.blocker.app.admin.DeviceOwnerController
import com.blocker.app.data.BlockerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class ReleaseUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkDownloadUrl: String,
    val isApiAssetUrl: Boolean,
    val expectedSha256: String?
)

sealed class UpdateCheckResult {
    data class UpToDate(val currentVersionName: String, val currentVersionCode: Int) : UpdateCheckResult()
    data class InstallingSilently(val versionName: String, val versionCode: Int) : UpdateCheckResult()
    data class ReadyNeedsDeviceOwner(val versionName: String, val versionCode: Int) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Checks GitHub Releases on `Papa-Ndiasse/Blocker` and silently installs newer signed APKs
 * via Android Device Owner `PackageInstaller.Session` (zero user confirmation required).
 *
 * Supports both private repositories (via read-only GitHub token + GitHub API asset URLs
 * with safe redirect handling) and public repositories.
 */
class GitHubAutoUpdater(
    private val context: Context,
    private val preferences: BlockerPreferences
) {
    private val deviceContext = context.createDeviceProtectedStorageContext()

    suspend fun checkAndInstallIfAvailable(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val token = resolveAuthToken()
            val release = fetchLatestRelease(token)
                ?: return@withContext UpdateCheckResult.UpToDate(
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                )

            preferences.recordUpdateCheck(System.currentTimeMillis())

            if (release.versionCode <= BuildConfig.VERSION_CODE) {
                return@withContext UpdateCheckResult.UpToDate(
                    BuildConfig.VERSION_NAME,
                    BuildConfig.VERSION_CODE
                )
            }

            val controller = DeviceOwnerController(context)
            val isOwner = controller.isDeviceOwner()

            val apkFile = File(deviceContext.cacheDir, PENDING_APK_NAME)
            downloadAssetToFile(
                urlString = release.apkDownloadUrl,
                token = token,
                isApiAssetUrl = release.isApiAssetUrl,
                destination = apkFile
            )

            // Verify SHA-256 integrity before touching PackageInstaller
            val expectedHash = release.expectedSha256?.trim()?.lowercase()
            if (!expectedHash.isNullOrEmpty()) {
                val actualHash = sha256Hex(apkFile)
                if (actualHash != expectedHash) {
                    apkFile.delete()
                    return@withContext UpdateCheckResult.Error(
                        "Intégrité SHA-256 invalide pour la mise à jour ${release.versionName}"
                    )
                }
            }

            installViaPackageInstaller(apkFile, release.versionName, isOwner)
            if (isOwner) {
                UpdateCheckResult.InstallingSilently(release.versionName, release.versionCode)
            } else {
                UpdateCheckResult.ReadyNeedsDeviceOwner(release.versionName, release.versionCode)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Auto-update check failed: ${e.message}")
            UpdateCheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun resolveAuthToken(): String {
        val saved = preferences.githubUpdateToken.value.trim()
        if (saved.isNotEmpty()) return saved
        return BuildConfig.GITHUB_UPDATE_TOKEN.trim()
    }

    private fun fetchLatestRelease(token: String): ReleaseUpdateInfo? {
        val apiUrl = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
        val jsonText = httpGetString(apiUrl, token, accept = "application/vnd.github+json")
            ?: return null

        val root = JSONObject(jsonText)
        val tagName = root.optString("tag_name", "")
        val assets = root.optJSONArray("assets") ?: return null

        var apkBrowserUrl: String? = null
        var apkApiUrl: String? = null
        var manifestBrowserUrl: String? = null
        var manifestApiUrl: String? = null

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name", "")
            when {
                name.endsWith(".apk", ignoreCase = true) -> {
                    apkBrowserUrl = asset.optString("browser_download_url", "").ifEmpty { null }
                    apkApiUrl = asset.optString("url", "").ifEmpty { null }
                }
                name.equals("update.json", ignoreCase = true) -> {
                    manifestBrowserUrl = asset.optString("browser_download_url", "").ifEmpty { null }
                    manifestApiUrl = asset.optString("url", "").ifEmpty { null }
                }
            }
        }

        val usePrivateApi = token.isNotEmpty()
        val chosenApkUrl = if (usePrivateApi) (apkApiUrl ?: apkBrowserUrl) else (apkBrowserUrl ?: apkApiUrl)
        if (chosenApkUrl.isNullOrBlank()) return null

        var versionCode = parseVersionCodeFromTag(tagName)
        var versionName = tagName.removePrefix("v").ifEmpty { "1.0.$versionCode" }
        var expectedSha256: String? = null

        // Download update.json metadata if present (provides exact versionCode & SHA-256)
        val manifestUrl = if (usePrivateApi) (manifestApiUrl ?: manifestBrowserUrl) else (manifestBrowserUrl ?: manifestApiUrl)
        if (!manifestUrl.isNullOrBlank()) {
            val manifestText = downloadAssetAsString(manifestUrl, token, usePrivateApi && manifestApiUrl != null)
            if (!manifestText.isNullOrBlank()) {
                val parsed = parseUpdateManifestJson(manifestText)
                if (parsed != null) {
                    versionCode = parsed.first
                    versionName = parsed.second
                    expectedSha256 = parsed.third
                }
            }
        }

        if (versionCode <= 0) return null

        return ReleaseUpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkDownloadUrl = chosenApkUrl,
            isApiAssetUrl = usePrivateApi && apkApiUrl != null,
            expectedSha256 = expectedSha256
        )
    }

    /**
     * Commits a silent full-APK self-update via Device Owner PackageInstaller.Session.
     * Temporarily lifts DISALLOW_INSTALL_APPS only if it was latched (since Android blocks
     * even Device Owner installs while DISALLOW_INSTALL_APPS is active); InstallResultReceiver
     * and BootCompletedReceiver(MY_PACKAGE_REPLACED) immediately re-seal all restrictions.
     */
    private fun installViaPackageInstaller(apkFile: File, versionName: String, isDeviceOwner: Boolean) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val admin = BlockerDeviceAdminReceiver.componentName(context)

        val hadInstallAppsLock = isDeviceOwner && userManager.hasUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
        if (hadInstallAppsLock) {
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apkFile.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            FileInputStream(apkFile).use { input ->
                session.openWrite("blocker_update_$versionName.apk", 0, apkFile.length()).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                    session.fsync(out)
                }
            }

            val callbackIntent = Intent(context, InstallResultReceiver::class.java).apply {
                putExtra(InstallResultReceiver.EXTRA_VERSION_NAME, versionName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                flags
            )
            session.commit(pendingIntent.intentSender)
            Log.i(TAG, "Committed silent self-update session $sessionId for v$versionName")
        } catch (e: Exception) {
            session.abandon()
            if (hadInstallAppsLock) {
                runCatching { dpm.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS) }
            }
            throw e
        } finally {
            session.close()
        }
    }

    private fun httpGetString(urlString: String, token: String, accept: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "BlockerAndroid/${BuildConfig.VERSION_NAME}")
            if (token.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return try {
            when (conn.responseCode) {
                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IllegalStateException("GitHub API HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAssetAsString(
        urlString: String,
        token: String,
        isApiAssetUrl: Boolean
    ): String? {
        val conn = openAssetConnection(urlString, token, isApiAssetUrl)
        return try {
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun downloadAssetToFile(
        urlString: String,
        token: String,
        isApiAssetUrl: Boolean,
        destination: File
    ) {
        val conn = openAssetConnection(urlString, token, isApiAssetUrl)
        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("Téléchargement APK échoué (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Handles GitHub's 302 redirect from api.github.com/releases/assets/<id> to
     * objects.githubusercontent.com WITHOUT forwarding the GitHub Authorization header
     * (which S3/Azure rejects with HTTP 400).
     */
    private fun openAssetConnection(
        urlString: String,
        token: String,
        isApiAssetUrl: Boolean
    ): HttpURLConnection {
        var currentUrl = urlString
        var sendAuth = token.isNotEmpty()
        var hops = 0

        while (hops < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "BlockerAndroid/${BuildConfig.VERSION_NAME}")
                if (isApiAssetUrl && sendAuth) {
                    setRequestProperty("Accept", "application/octet-stream")
                }
                if (sendAuth) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    throw IllegalStateException("Redirect sans Location (HTTP $code)")
                }
                currentUrl = location
                // Strip GitHub Bearer token once redirected to GitHub's signed storage CDN
                sendAuth = location.contains("api.github.com")
                hops++
            } else {
                return conn
            }
        }
        throw IllegalStateException("Trop de redirections lors du téléchargement")
    }

    companion object {
        private const val TAG = "GitHubAutoUpdater"
        const val PENDING_APK_NAME = "pending_blocker_update.apk"

        /**
         * Parses `update.json` returning Triple(versionCode, versionName, sha256?).
         */
        fun parseUpdateManifestJson(jsonText: String): Triple<Int, String, String?>? {
            return try {
                val obj = JSONObject(jsonText)
                val code = obj.optInt("versionCode", 0)
                if (code <= 0) return null
                val name = obj.optString("versionName", "1.0.$code")
                val sha = obj.optString("sha256", "").trim().ifEmpty { null }
                Triple(code, name, sha)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Fallback parser when a GitHub Release tag like "v1.0.42" or "v42" has no update.json.
         */
        fun parseVersionCodeFromTag(tag: String): Int {
            val clean = tag.trim().removePrefix("v").removePrefix("V")
            if (clean.isEmpty()) return 0
            val lastSegment = clean.substringAfterLast('.')
            return lastSegment.toIntOrNull() ?: 0
        }

        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(32 * 1024)
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    digest.update(buf, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
