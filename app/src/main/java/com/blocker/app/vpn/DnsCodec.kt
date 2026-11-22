package com.blocker.app.vpn

import java.io.ByteArrayOutputStream

data class DnsQuery(
    val transactionId: Int,
    val flags: Int,
    val domain: String,
    val qType: Int,
    val qClass: Int,
    val questionEndOffset: Int
)

/**
 * RFC 1035 DNS wire-format parser and response builder.
 */
object DnsCodec {

    const val TYPE_A = 1
    const val TYPE_CNAME = 5
    const val TYPE_AAAA = 28
    const val TYPE_SVCB = 64
    const val TYPE_HTTPS = 65
    const val CLASS_IN = 1

    private const val HEADER_SIZE = 12
    private const val MAX_POINTER_HOPS = 16

    /**
     * Parses a DNS query packet and extracts the first question.
     * Returns null if malformed or not a query.
     */
    fun parseQuery(packet: ByteArray, length: Int = packet.size): DnsQuery? {
        if (length < HEADER_SIZE) return null

        val txId = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)
        val flags = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        if (isResponse) return null

        val qdCount = ((packet[4].toInt() and 0xFF) shl 8) or (packet[5].toInt() and 0xFF)
        if (qdCount < 1) return null

        val (domain, nextOffset) = readDomainName(packet, HEADER_SIZE, length) ?: return null
        if (nextOffset + 4 > length) return null

        val qType = ((packet[nextOffset].toInt() and 0xFF) shl 8) or
            (packet[nextOffset + 1].toInt() and 0xFF)
        val qClass = ((packet[nextOffset + 2].toInt() and 0xFF) shl 8) or
            (packet[nextOffset + 3].toInt() and 0xFF)

        return DnsQuery(
            transactionId = txId,
            flags = flags,
            domain = domain.lowercase(),
            qType = qType,
            qClass = qClass,
            questionEndOffset = nextOffset + 4
        )
    }

    /**
     * Builds a standard recursive DNS query packet for [domain] and [qType].
     */
    fun buildQuery(domain: String, qType: Int, transactionId: Int = 0x424C): ByteArray {
        val out = ByteArrayOutputStream(64)
        // Header: ID, Flags (RD=1 -> 0x0100), QDCOUNT=1, AN/NS/AR=0
        writeShort(out, transactionId)
        writeShort(out, 0x0100)
        writeShort(out, 1)
        writeShort(out, 0)
        writeShort(out, 0)
        writeShort(out, 0)

        // Question name labels
        val labels = domain.trimEnd('.').split('.')
        for (label in labels) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.US_ASCII)
            out.write(bytes.size and 0x3F)
            out.write(bytes)
        }
        out.write(0) // root label
        writeShort(out, qType)
        writeShort(out, CLASS_IN)
        return out.toByteArray()
    }

    /**
     * Builds an NXDOMAIN (RCODE=3) response for a blocked domain.
     * Causes Android browsers to fail immediately without waiting for TCP timeouts
     * and signals Firefox to disable DoH when querying use-application-dns.net.
     */
    fun buildNxDomainResponse(queryBytes: ByteArray, query: DnsQuery): ByteArray {
        val out = ByteArrayOutputStream(query.questionEndOffset)
        val rd = query.flags and 0x0100
        // QR=1 (0x8000), AA=1 (0x0400), RA=1 (0x0080), RCODE=3 (NXDOMAIN)
        val responseFlags = 0x8483 or rd
        writeShort(out, query.transactionId)
        writeShort(out, responseFlags)
        writeShort(out, 1) // QDCOUNT
        writeShort(out, 0) // ANCOUNT
        writeShort(out, 0) // NSCOUNT
        writeShort(out, 0) // ARCOUNT
        out.write(queryBytes, HEADER_SIZE, query.questionEndOffset - HEADER_SIZE)
        return out.toByteArray()
    }

    /**
     * Builds an empty NOERROR (NODATA) response, e.g. to suppress HTTPS/SVCB records
     * on SafeSearch-rewritten domains so browsers honor our A/AAAA SafeSearch VIPs.
     */
    fun buildNoDataResponse(queryBytes: ByteArray, query: DnsQuery): ByteArray {
        val out = ByteArrayOutputStream(query.questionEndOffset)
        val rd = query.flags and 0x0100
        // QR=1, AA=1, RA=1, RCODE=0 (NOERROR)
        val responseFlags = 0x8480 or rd
        writeShort(out, query.transactionId)
        writeShort(out, responseFlags)
        writeShort(out, 1) // QDCOUNT
        writeShort(out, 0) // ANCOUNT
        writeShort(out, 0) // NSCOUNT
        writeShort(out, 0) // ARCOUNT
        out.write(queryBytes, HEADER_SIZE, query.questionEndOffset - HEADER_SIZE)
        return out.toByteArray()
    }

    /**
     * Builds a synthetic A or AAAA response answering the original question with [addresses].
     */
    fun buildAddressResponse(
        queryBytes: ByteArray,
        query: DnsQuery,
        addresses: List<ByteArray>,
        ttlSeconds: Int = 300
    ): ByteArray {
        if (addresses.isEmpty()) return buildNoDataResponse(queryBytes, query)

        val expectedLen = when (query.qType) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> return buildNoDataResponse(queryBytes, query)
        }
        val validAddresses = addresses.filter { it.size == expectedLen }
        if (validAddresses.isEmpty()) return buildNoDataResponse(queryBytes, query)

        val out = ByteArrayOutputStream(query.questionEndOffset + validAddresses.size * 32)
        val rd = query.flags and 0x0100
        val responseFlags = 0x8480 or rd // QR=1, AA=1, RA=1, RCODE=0
        writeShort(out, query.transactionId)
        writeShort(out, responseFlags)
        writeShort(out, 1)                   // QDCOUNT
        writeShort(out, validAddresses.size) // ANCOUNT
        writeShort(out, 0)                   // NSCOUNT
        writeShort(out, 0)                   // ARCOUNT

        // Copy original Question section
        out.write(queryBytes, HEADER_SIZE, query.questionEndOffset - HEADER_SIZE)

        // Write Answer RRs pointing back to offset 12 (0xC00C) for the QNAME
        for (addr in validAddresses) {
            writeShort(out, 0xC00C)          // Compression pointer to QNAME at header+0
            writeShort(out, query.qType)     // TYPE (A or AAAA)
            writeShort(out, CLASS_IN)        // CLASS IN
            writeInt(out, ttlSeconds)        // TTL
            writeShort(out, addr.size)       // RDLENGTH
            out.write(addr)                  // RDATA
        }
        return out.toByteArray()
    }

    /**
     * Extracts all raw A (4 bytes) or AAAA (16 bytes) record payloads from a DNS wire response.
     */
    fun extractAddressRecords(response: ByteArray, targetType: Int, length: Int = response.size): List<ByteArray> {
        if (length < HEADER_SIZE) return emptyList()
        val expectedLen = when (targetType) {
            TYPE_A -> 4
            TYPE_AAAA -> 16
            else -> return emptyList()
        }

        val qdCount = ((response[4].toInt() and 0xFF) shl 8) or (response[5].toInt() and 0xFF)
        val anCount = ((response[6].toInt() and 0xFF) shl 8) or (response[7].toInt() and 0xFF)

        var offset = HEADER_SIZE
        // Skip questions
        for (i in 0 until qdCount) {
            val next = skipDomainName(response, offset, length) ?: return emptyList()
            offset = next + 4
            if (offset > length) return emptyList()
        }

        val results = mutableListOf<ByteArray>()
        for (i in 0 until anCount) {
            val nameEnd = skipDomainName(response, offset, length) ?: break
            if (nameEnd + 10 > length) break

            val rType = ((response[nameEnd].toInt() and 0xFF) shl 8) or
                (response[nameEnd + 1].toInt() and 0xFF)
            val rdLen = ((response[nameEnd + 8].toInt() and 0xFF) shl 8) or
                (response[nameEnd + 9].toInt() and 0xFF)
            val rDataStart = nameEnd + 10
            val rDataEnd = rDataStart + rdLen
            if (rDataEnd > length) break

            if (rType == targetType && rdLen == expectedLen) {
                results.add(response.copyOfRange(rDataStart, rDataEnd))
            }
            offset = rDataEnd
        }
        return results
    }

    /**
     * Rewrites the 16-bit transaction ID in a DNS response packet (used when serving from cache).
     */
    fun patchTransactionId(response: ByteArray, newTxId: Int): ByteArray {
        val copy = response.copyOf()
        if (copy.size >= 2) {
            copy[0] = ((newTxId ushr 8) and 0xFF).toByte()
            copy[1] = (newTxId and 0xFF).toByte()
        }
        return copy
    }

    private fun readDomainName(packet: ByteArray, startOffset: Int, length: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var offset = startOffset
        var endOffset = -1
        var hops = 0

        while (offset < length) {
            val labelLen = packet[offset].toInt() and 0xFF
            if (labelLen == 0) {
                if (endOffset == -1) endOffset = offset + 1
                break
            }
            // Compression pointer (top two bits = 11)
            if ((labelLen and 0xC0) == 0xC0) {
                if (offset + 1 >= length) return null
                if (++hops > MAX_POINTER_HOPS) return null
                val ptr = ((labelLen and 0x3F) shl 8) or (packet[offset + 1].toInt() and 0xFF)
                if (ptr >= length) return null
                if (endOffset == -1) endOffset = offset + 2
                offset = ptr
            } else if ((labelLen and 0xC0) == 0) {
                offset++
                if (offset + labelLen > length) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (i in 0 until labelLen) {
                    sb.append(packet[offset + i].toInt().toChar())
                }
                offset += labelLen
            } else {
                return null
            }
        }
        if (endOffset == -1) return null
        return Pair(sb.toString(), endOffset)
    }

    private fun skipDomainName(packet: ByteArray, startOffset: Int, length: Int): Int? {
        var offset = startOffset
        while (offset < length) {
            val labelLen = packet[offset].toInt() and 0xFF
            if (labelLen == 0) return offset + 1
            if ((labelLen and 0xC0) == 0xC0) {
                return if (offset + 2 <= length) offset + 2 else null
            }
            if ((labelLen and 0xC0) != 0) return null
            offset += 1 + labelLen
        }
        return null
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
