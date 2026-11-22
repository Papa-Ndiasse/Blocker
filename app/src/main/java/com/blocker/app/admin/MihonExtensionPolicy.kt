package com.blocker.app.admin

import android.content.pm.PackageInfo
import android.os.Bundle
import com.blocker.app.filter.KeywordFilter

/**
 * Identifies NSFW / 18+ (and optionally Mixed) Mihon / Tachiyomi / Keiyoushi / Aniyomi
 * extension APKs using Mihon's exact AndroidManifest `<meta-data>` and `<uses-feature>` contract:
 *   - Feature: "tachiyomi.extension" or "tachiyomi.animeextension"
 *   - Legacy metadata: "tachiyomi.extension.nsfw" == 1
 *   - Modern Mihon metadata: "tachiyomix.contentWarning" == 2 (NSFW) or 1 (MIXED)
 */
object MihonExtensionPolicy {

    const val FEATURE_MANGA_EXTENSION = "tachiyomi.extension"
    const val FEATURE_ANIME_EXTENSION = "tachiyomi.animeextension"
    const val META_LEGACY_NSFW = "tachiyomi.extension.nsfw"
    const val META_CONTENT_WARNING = "tachiyomix.contentWarning"

    private val EXTENSION_PKG_PREFIXES = listOf(
        "eu.kanade.tachiyomi.extension.",
        "eu.kanade.tachiyomi.animeextension.",
        "eu.kanade.tachiyomi.lib."
    )

    private val ADULT_EXT_NAME_PATTERNS = listOf(
        "hentai", "pornhwa", "pornwha", "porn", "nsfw", "nhentai", "ehentai", "exhentai",
        "hitomi", "hanime", "rule34", "r34", "booru", "doujin", "ecchi", "erotic", "erotica",
        "manhwa18", "toonily", "comick", "hiperdex", "manytoon", "pururin", "tsumino", "fakku",
        "wnacg", "18comic", "jmcomic", "ahottie", "ahrigallery", "akuma", "allporncomic",
        "8muses", "multporn", "milftoon", "jabcomix", "luscious", "jav", "missav",
        "adult", "smut", "lewd", "18plus", "r18", "xxx", "f95zone", "nutaku", "iwara",
        "kemono", "coomer", "e621", "furaffinity", "pixiv"
    )

    fun isMihonExtensionPackage(pkgInfo: PackageInfo): Boolean {
        val pkg = pkgInfo.packageName.lowercase()
        if (EXTENSION_PKG_PREFIXES.any { pkg.startsWith(it) }) return true
        return pkgInfo.reqFeatures.orEmpty().any {
            it.name == FEATURE_MANGA_EXTENSION || it.name == FEATURE_ANIME_EXTENSION
        }
    }

    /**
     * Pure metadata/name classifier (unit-testable without Android PackageInfo).
     */
    fun shouldBlockExtension(
        packageName: String,
        appLabel: String?,
        legacyNsfwFlag: Int,
        contentWarningLevel: Int?, // null = absent, 0 = SAFE, 1 = MIXED, 2 = NSFW
        blockMixedExtensions: Boolean = false
    ): Boolean {
        val pkg = packageName.lowercase()
        val label = appLabel.orEmpty().lowercase()

        // Total immunity for Poseidon Scan extension regardless of contentWarning/mixed lock
        if (pkg.contains("poseidonscan") || pkg.contains("poseidon_scan") ||
            label.contains("poseidon-scan") || label.contains("poseidon scan") ||
            label.contains("poseidonscan")
        ) {
            return false
        }

        if (legacyNsfwFlag == 1) return true

        if (contentWarningLevel != null) {
            if (contentWarningLevel >= 2) return true
            if (blockMixedExtensions && contentWarningLevel >= 1) return true
        }

        // Heuristic fallback for unlabelled or repackaged adult extensions
        val suffix = pkg.substringAfterLast('.')
        if (KeywordFilter.matches("$suffix.com")) return true
        if (ADULT_EXT_NAME_PATTERNS.any { suffix.contains(it) || label.contains(it) }) {
            return true
        }

        return false
    }

    fun shouldBlockPackageInfo(
        pkgInfo: PackageInfo,
        appLabel: String?,
        blockMixedExtensions: Boolean = false
    ): Boolean {
        if (!isMihonExtensionPackage(pkgInfo)) return false
        val meta: Bundle? = pkgInfo.applicationInfo?.metaData
        val legacyNsfw = meta?.getInt(META_LEGACY_NSFW, 0) ?: 0
        val contentWarning = if (meta != null && meta.containsKey(META_CONTENT_WARNING)) {
            meta.getInt(META_CONTENT_WARNING, 0)
        } else {
            null
        }
        return shouldBlockExtension(
            packageName = pkgInfo.packageName,
            appLabel = appLabel,
            legacyNsfwFlag = legacyNsfw,
            contentWarningLevel = contentWarning,
            blockMixedExtensions = blockMixedExtensions
        )
    }
}
