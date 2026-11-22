package com.blocker.app

import com.blocker.app.admin.MihonExtensionPolicy
import com.blocker.app.filter.KeiyoushiExtensionEntry
import com.blocker.app.filter.KeiyoushiRepoParser
import com.blocker.app.guard.ScreenContentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class KeiyoushiAndMihonTest {

    @Test
    fun `MihonExtensionPolicy blocks legacy NSFW, modern contentWarning=2, and heuristic adult extensions`() {
        // Legacy tachiyomi.extension.nsfw=1
        assertTrue(
            MihonExtensionPolicy.shouldBlockExtension(
                packageName = "eu.kanade.tachiyomi.extension.all.some18site",
                appLabel = "Tachiyomi: Some18Site",
                legacyNsfwFlag = 1,
                contentWarningLevel = null
            )
        )

        // Modern Mihon tachiyomix.contentWarning=2 (NSFW)
        assertTrue(
            MihonExtensionPolicy.shouldBlockExtension(
                packageName = "eu.kanade.tachiyomi.extension.all.ahottie",
                appLabel = "AHottie",
                legacyNsfwFlag = 0,
                contentWarningLevel = 2
            )
        )

        // Modern Mihon tachiyomix.contentWarning=1 (MIXED): allowed by default, blocked when lockMihonMixedExtensions=true
        assertFalse(
            MihonExtensionPolicy.shouldBlockExtension(
                packageName = "eu.kanade.tachiyomi.extension.all.mangadex",
                appLabel = "MangaDex",
                legacyNsfwFlag = 0,
                contentWarningLevel = 1,
                blockMixedExtensions = false
            )
        )
        assertTrue(
            MihonExtensionPolicy.shouldBlockExtension(
                packageName = "eu.kanade.tachiyomi.extension.all.mangadex",
                appLabel = "MangaDex",
                legacyNsfwFlag = 0,
                contentWarningLevel = 1,
                blockMixedExtensions = true
            )
        )

        // Comick is ALWAYS blocked by default even though Keiyoushi tags it as MIXED (cw=1/2)
        assertTrue(
            MihonExtensionPolicy.shouldBlockExtension(
                packageName = "eu.kanade.tachiyomi.extension.all.comicklive",
                appLabel = "Comick (Unoriginal)",
                legacyNsfwFlag = 0,
                contentWarningLevel = 1,
                blockMixedExtensions = false
            )
        )

        // Pure SAFE extension (MANGA Plus) is never blocked
        assertFalse(
            MihonExtensionPolicy.shouldBlockExtension(
                packageName = "eu.kanade.tachiyomi.extension.all.mangaplus",
                appLabel = "MANGA Plus by SHUEISHA",
                legacyNsfwFlag = 0,
                contentWarningLevel = 0,
                blockMixedExtensions = true
            )
        )
    }

    @Test
    fun `KeiyoushiRepoParser decodes gzipped index_pb and extracts NSFW vs Mixed domains accurately`() {
        // Build synthetic Protobuf matching live Keiyoushi index.pb schema:
        // top.101 -> [ ext(1): name(1), pkg(2), cw(7), source(8) -> baseUrl(4), mirror(5) ]
        val safeExt = buildExtBytes("MangaPlus", "eu.kanade.tachiyomi.extension.all.mangaplus", 1, listOf("https://mangaplus.shueisha.co.jp"))
        val mixedExt = buildExtBytes("MangaDex", "eu.kanade.tachiyomi.extension.all.mangadex", 2, listOf("https://mangadex.org"))
        val nsfwExt = buildExtBytes("Ahri Gallery", "eu.kanade.tachiyomi.extension.all.ahrigallery", 3, listOf("https://ahri-gallery.com", "https://www.ahri-mirror.net"))

        val listMsg = ByteArrayOutputStream().apply {
            writeBytesField(1, safeExt)
            writeBytesField(1, mixedExt)
            writeBytesField(1, nsfwExt)
        }.toByteArray()

        val topMsg = ByteArrayOutputStream().apply {
            writeBytesField(101, listMsg)
        }.toByteArray()

        val gzipped = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(topMsg) }
        }.toByteArray()

        val entries = KeiyoushiRepoParser.parseIndexPb(gzipped)
        assertEquals(3, entries.size)

        val nsfwOnly = KeiyoushiRepoParser.extractBlockedDomains(entries, includeMixed = false)
        assertEquals(setOf("ahri-gallery.com", "ahri-mirror.net"), nsfwOnly)

        val nsfwAndMixed = KeiyoushiRepoParser.extractBlockedDomains(entries, includeMixed = true)
        assertEquals(setOf("mangadex.org", "ahri-gallery.com", "ahri-mirror.net"), nsfwAndMixed)
    }

    @Test
    fun `ScreenContentClassifier blocks 18+ badges, NSFW toggle, and X genres inside Mihon while allowing SFW manga`() {
        val ext18Badge = ScreenContentClassifier.classifyAppText(
            "app.mihon",
            listOf("Extensions", "AHottie", "18+", "Installer")
        )
        assertNotNull(ext18Badge)
        assertEquals(ScreenContentClassifier.REASON_MIHON_NSFW, ext18Badge!!.reason)

        val mihonNsfwSetting = ScreenContentClassifier.classifyAppText(
            "app.mihon",
            listOf("Parcourir", "Afficher dans les listes de sources et d'extensions")
        )
        assertNotNull(mihonNsfwSetting)

        val smutGenre = ScreenContentClassifier.classifyAppText(
            "app.mihon",
            listOf("MangaDex", "Genres", "Romance", "Smut", "Erotica")
        )
        assertNotNull(smutGenre)

        val sfwManga = ScreenContentClassifier.classifyAppText(
            "app.mihon",
            listOf("One Piece", "Eiichiro Oda", "Shonen", "Action", "Adventure", "Chapitre 1130")
        )
        assertNull(sfwManga)
    }

    private fun buildExtBytes(name: String, pkg: String, cw: Int, urls: List<String>): ByteArray {
        val source = ByteArrayOutputStream().apply {
            if (urls.isNotEmpty()) writeStringField(4, urls[0])
            for (i in 1 until urls.size) writeStringField(5, urls[i])
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            writeStringField(1, name)
            writeStringField(2, pkg)
            writeVarintField(7, cw.toLong())
            writeBytesField(8, source)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                write(v.toInt())
                return
            }
            write(((v and 0x7FL) or 0x80L).toInt())
            v = v ushr 7
        }
    }

    private fun ByteArrayOutputStream.writeVarintField(fieldNum: Int, value: Long) {
        writeVarint((fieldNum.toLong() shl 3) or 0L)
        writeVarint(value)
    }

    private fun ByteArrayOutputStream.writeBytesField(fieldNum: Int, payload: ByteArray) {
        writeVarint((fieldNum.toLong() shl 3) or 2L)
        writeVarint(payload.size.toLong())
        write(payload)
    }

    private fun ByteArrayOutputStream.writeStringField(fieldNum: Int, value: String) {
        writeBytesField(fieldNum, value.toByteArray(Charsets.UTF_8))
    }
}
