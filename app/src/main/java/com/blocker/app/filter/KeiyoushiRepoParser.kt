package com.blocker.app.filter

import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.GZIPInputStream

data class KeiyoushiExtensionEntry(
    val name: String,
    val pkg: String,
    val contentWarning: Int, // 1 = SAFE, 2 = MIXED, 3 = NSFW (18+)
    val domains: Set<String>
)

/**
 * Zero-dependency GZIP + Protobuf wire decoder for Mihon/Keiyoushi `index.pb` repositories.
 *
 * Verified live schema of `keiyoushi/extensions/repo/index.pb`:
 *   Top message -> field 101 (bytes): ExtensionList
 *     ExtensionList -> repeated field 1 (bytes): Extension
 *       field 1 (string): extension display name
 *       field 2 (string): Android package name (eu.kanade.tachiyomi.extension.*)
 *       field 7 (varint): contentWarning (1 = SAFE, 2 = MIXED, 3 = NSFW/18+)
 *       field 8 (bytes, repeated): Source
 *         field 4 (string): primary baseUrl
 *         field 5 (string, repeated): mirror baseUrls
 */
object KeiyoushiRepoParser {

    const val CONTENT_WARNING_SAFE = 1
    const val CONTENT_WARNING_MIXED = 2
    const val CONTENT_WARNING_NSFW = 3

    const val KEIYOUSHI_INDEX_PB_URL =
        "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb"

    fun parseIndexPb(rawBytes: ByteArray): List<KeiyoushiExtensionEntry> {
        val decompressed = maybeGunzip(rawBytes)
        val topFields = parseFields(decompressed, 0, decompressed.size)
        val listPayload = topFields.firstOrNull { it.number == 101 && it.wireType == 2 }?.bytes
            ?: return emptyList()

        val extMessages = parseFields(listPayload, 0, listPayload.size)
        val results = ArrayList<KeiyoushiExtensionEntry>(extMessages.size)

        for (extMsg in extMessages) {
            if (extMsg.number != 1 || extMsg.wireType != 2 || extMsg.bytes == null) continue
            val fields = parseFields(extMsg.bytes, 0, extMsg.bytes.size)

            var name = ""
            var pkg = ""
            var contentWarning = CONTENT_WARNING_SAFE
            val domains = LinkedHashSet<String>()

            for (f in fields) {
                when {
                    f.number == 1 && f.wireType == 2 && f.bytes != null -> {
                        name = f.bytes.toString(Charsets.UTF_8)
                    }
                    f.number == 2 && f.wireType == 2 && f.bytes != null -> {
                        pkg = f.bytes.toString(Charsets.UTF_8)
                    }
                    f.number == 7 && f.wireType == 0 -> {
                        contentWarning = f.varint.toInt()
                    }
                    f.number == 8 && f.wireType == 2 && f.bytes != null -> {
                        extractDomainsFromSource(f.bytes, domains)
                    }
                }
            }

            if (pkg.isNotEmpty() || domains.isNotEmpty()) {
                results.add(
                    KeiyoushiExtensionEntry(
                        name = name,
                        pkg = pkg,
                        contentWarning = contentWarning,
                        domains = domains
                    )
                )
            }
        }
        return results
    }

    /**
     * Extracts all blocked domains from parsed Keiyoushi extensions.
     * Always includes NSFW (contentWarning == 3); also includes MIXED (contentWarning == 2)
     * when [includeMixed] is true.
     */
    fun extractBlockedDomains(
        entries: List<KeiyoushiExtensionEntry>,
        includeMixed: Boolean = false
    ): Set<String> {
        val minLevel = if (includeMixed) CONTENT_WARNING_MIXED else CONTENT_WARNING_NSFW
        val out = LinkedHashSet<String>(1024)
        for (entry in entries) {
            val forcedBlocked = entry.pkg.contains("comick", ignoreCase = true) ||
                entry.name.contains("comick", ignoreCase = true)
            if (entry.contentWarning >= minLevel || forcedBlocked) {
                for (domain in entry.domains) {
                    val isImmune = DnsFilterEngine.IMMUNE_DOMAINS.any {
                        domain == it || domain.endsWith(".$it")
                    }
                    if (!isImmune) out.add(domain)
                }
            }
        }
        return out
    }

    private fun extractDomainsFromSource(sourceBytes: ByteArray, out: MutableSet<String>) {
        val sFields = parseFields(sourceBytes, 0, sourceBytes.size)
        for (sf in sFields) {
            if ((sf.number == 4 || sf.number == 5) && sf.wireType == 2 && sf.bytes != null) {
                val rawUrls = sf.bytes.toString(Charsets.UTF_8)
                for (part in rawUrls.split(',')) {
                    extractHost(part.trim())?.let { out.add(it) }
                }
            }
        }
    }

    internal fun extractHost(rawUrl: String): String? {
        if (rawUrl.isBlank()) return null
        val host = try {
            URI(rawUrl).host
        } catch (_: Exception) {
            null
        }?.lowercase()?.removePrefix("www.")?.trimEnd('.') ?: return null

        if (host.length < 3 || !host.contains('.')) return null
        if (host == "localhost" || host == "127.0.0.1" || host.startsWith("192.168.") || host.startsWith("10.")) {
            return null
        }
        return host
    }

    private fun maybeGunzip(data: ByteArray): ByteArray {
        return if (data.size >= 2 &&
            (data[0].toInt() and 0xFF) == 0x1F &&
            (data[1].toInt() and 0xFF) == 0x8B
        ) {
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        } else {
            data
        }
    }

    private data class PbField(
        val number: Int,
        val wireType: Int,
        val varint: Long = 0L,
        val bytes: ByteArray? = null
    )

    private fun parseFields(buf: ByteArray, start: Int, end: Int): List<PbField> {
        val out = ArrayList<PbField>()
        var pos = start
        while (pos < end) {
            val (tag, afterTag) = readVarint(buf, pos, end) ?: break
            pos = afterTag
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            when (wireType) {
                0 -> {
                    val (v, next) = readVarint(buf, pos, end) ?: break
                    pos = next
                    out.add(PbField(fieldNumber, wireType, varint = v))
                }
                1 -> {
                    if (pos + 8 > end) break
                    pos += 8
                }
                2 -> {
                    val (lenLong, afterLen) = readVarint(buf, pos, end) ?: break
                    val len = lenLong.toInt()
                    if (len < 0 || afterLen + len > end) break
                    val slice = buf.copyOfRange(afterLen, afterLen + len)
                    pos = afterLen + len
                    out.add(PbField(fieldNumber, wireType, bytes = slice))
                }
                5 -> {
                    if (pos + 4 > end) break
                    pos += 4
                }
                else -> break
            }
        }
        return out
    }

    private fun readVarint(buf: ByteArray, start: Int, end: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < end && shift < 64) {
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return Pair(result, pos)
            shift += 7
        }
        return null
    }
}
