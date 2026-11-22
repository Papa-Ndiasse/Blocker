package com.blocker.app.vpn

sealed class ParsedIpPacket {
    data class Udp(
        val ipVersion: Int,
        val srcAddress: ByteArray,
        val dstAddress: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    ) : ParsedIpPacket()

    data class TcpSyn(
        val ipVersion: Int,
        val srcAddress: ByteArray,
        val dstAddress: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val seqNumber: Long
    ) : ParsedIpPacket()
}

/**
 * Lightweight, zero-dependency IPv4/IPv6 + UDP + TCP-RST packet parser & builder
 * for the local TUN interface in BlockerVpnService.
 */
object PacketParser {

    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17
    private const val IPV4_MIN_HEADER = 20
    private const val IPV6_HEADER = 40
    private const val UDP_HEADER = 8
    private const val TCP_MIN_HEADER = 20

    fun parse(buffer: ByteArray, length: Int): ParsedIpPacket? {
        if (length < 1) return null
        val version = (buffer[0].toInt() ushr 4) and 0x0F
        return when (version) {
            4 -> parseIpv4(buffer, length)
            6 -> parseIpv6(buffer, length)
            else -> null
        }
    }

    private fun parseIpv4(buf: ByteArray, length: Int): ParsedIpPacket? {
        if (length < IPV4_MIN_HEADER) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_MIN_HEADER || length < ihl) return null

        val totalLen = readU16(buf, 2).coerceAtMost(length)
        val protocol = buf[9].toInt() and 0xFF
        val srcIp = buf.copyOfRange(12, 16)
        val dstIp = buf.copyOfRange(16, 20)

        return when (protocol) {
            PROTO_UDP -> parseUdp(4, srcIp, dstIp, buf, ihl, totalLen)
            PROTO_TCP -> parseTcpSyn(4, srcIp, dstIp, buf, ihl, totalLen)
            else -> null
        }
    }

    private fun parseIpv6(buf: ByteArray, length: Int): ParsedIpPacket? {
        if (length < IPV6_HEADER) return null
        val payloadLen = readU16(buf, 4)
        val totalLen = (IPV6_HEADER + payloadLen).coerceAtMost(length)
        val nextHeader = buf[6].toInt() and 0xFF
        val srcIp = buf.copyOfRange(8, 24)
        val dstIp = buf.copyOfRange(24, 40)

        return when (nextHeader) {
            PROTO_UDP -> parseUdp(6, srcIp, dstIp, buf, IPV6_HEADER, totalLen)
            PROTO_TCP -> parseTcpSyn(6, srcIp, dstIp, buf, IPV6_HEADER, totalLen)
            else -> null
        }
    }

    private fun parseUdp(
        ipVersion: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        buf: ByteArray,
        offset: Int,
        totalLen: Int
    ): ParsedIpPacket.Udp? {
        if (offset + UDP_HEADER > totalLen) return null
        val srcPort = readU16(buf, offset)
        val dstPort = readU16(buf, offset + 2)
        val udpLen = readU16(buf, offset + 4)
        if (udpLen < UDP_HEADER || offset + udpLen > totalLen) return null

        val payloadStart = offset + UDP_HEADER
        val payloadEnd = offset + udpLen
        return ParsedIpPacket.Udp(
            ipVersion = ipVersion,
            srcAddress = srcIp,
            dstAddress = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            payload = buf.copyOfRange(payloadStart, payloadEnd)
        )
    }

    private fun parseTcpSyn(
        ipVersion: Int,
        srcIp: ByteArray,
        dstIp: ByteArray,
        buf: ByteArray,
        offset: Int,
        totalLen: Int
    ): ParsedIpPacket.TcpSyn? {
        if (offset + TCP_MIN_HEADER > totalLen) return null
        val flags = buf[offset + 13].toInt() and 0xFF
        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isRst = (flags and 0x04) != 0
        if (!isSyn || isAck || isRst) return null

        val srcPort = readU16(buf, offset)
        val dstPort = readU16(buf, offset + 2)
        val seq = readU32(buf, offset + 4)

        return ParsedIpPacket.TcpSyn(
            ipVersion = ipVersion,
            srcAddress = srcIp,
            dstAddress = dstIp,
            srcPort = srcPort,
            dstPort = dstPort,
            seqNumber = seq
        )
    }

    /**
     * Builds a UDP response packet swapping source and destination addresses/ports.
     */
    fun buildUdpResponse(request: ParsedIpPacket.Udp, responsePayload: ByteArray): ByteArray {
        return when (request.ipVersion) {
            4 -> buildIpv4Udp(
                srcIp = request.dstAddress,
                dstIp = request.srcAddress,
                srcPort = request.dstPort,
                dstPort = request.srcPort,
                payload = responsePayload
            )
            6 -> buildIpv6Udp(
                srcIp = request.dstAddress,
                dstIp = request.srcAddress,
                srcPort = request.dstPort,
                dstPort = request.srcPort,
                payload = responsePayload
            )
            else -> ByteArray(0)
        }
    }

    /**
     * Builds a clean TCP RST+ACK packet in response to a TCP SYN.
     * Used to immediately fail Android Private DNS port 853 probes (<1ms fallback to port 53)
     * and immediately reject blocked DoH/DoT TCP connections (zero browser stall).
     */
    fun buildTcpRstResponse(syn: ParsedIpPacket.TcpSyn): ByteArray {
        val ackNum = (syn.seqNumber + 1L) and 0xFFFFFFFFL
        return when (syn.ipVersion) {
            4 -> buildIpv4TcpRst(
                srcIp = syn.dstAddress,
                dstIp = syn.srcAddress,
                srcPort = syn.dstPort,
                dstPort = syn.srcPort,
                ackNum = ackNum
            )
            6 -> buildIpv6TcpRst(
                srcIp = syn.dstAddress,
                dstIp = syn.srcAddress,
                srcPort = syn.dstPort,
                dstPort = syn.srcPort,
                ackNum = ackNum
            )
            else -> ByteArray(0)
        }
    }

    private fun buildIpv4Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = UDP_HEADER + payload.size
        val totalLen = IPV4_MIN_HEADER + udpLen
        val packet = ByteArray(totalLen)

        // IPv4 Header
        packet[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
        packet[1] = 0x00
        writeU16(packet, 2, totalLen)
        writeU16(packet, 4, 0)
        writeU16(packet, 6, 0x4000) // Don't Fragment
        packet[8] = 64.toByte()     // TTL
        packet[9] = PROTO_UDP.toByte()
        writeU16(packet, 10, 0)     // Header checksum placeholder
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)
        writeU16(packet, 10, ipv4HeaderChecksum(packet, IPV4_MIN_HEADER))

        // UDP Header + Payload
        val udpOffset = IPV4_MIN_HEADER
        writeU16(packet, udpOffset, srcPort)
        writeU16(packet, udpOffset + 2, dstPort)
        writeU16(packet, udpOffset + 4, udpLen)
        writeU16(packet, udpOffset + 6, 0)
        System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER, payload.size)

        val udpChecksum = transportChecksumIpv4(srcIp, dstIp, PROTO_UDP, packet, udpOffset, udpLen)
        writeU16(packet, udpOffset + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    private fun buildIpv6Udp(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = UDP_HEADER + payload.size
        val totalLen = IPV6_HEADER + udpLen
        val packet = ByteArray(totalLen)

        // IPv6 Header
        packet[0] = 0x60.toByte() // Version 6
        writeU16(packet, 4, udpLen)
        packet[6] = PROTO_UDP.toByte()
        packet[7] = 64.toByte() // Hop Limit
        System.arraycopy(srcIp, 0, packet, 8, 16)
        System.arraycopy(dstIp, 0, packet, 24, 16)

        // UDP Header + Payload
        val udpOffset = IPV6_HEADER
        writeU16(packet, udpOffset, srcPort)
        writeU16(packet, udpOffset + 2, dstPort)
        writeU16(packet, udpOffset + 4, udpLen)
        writeU16(packet, udpOffset + 6, 0)
        System.arraycopy(payload, 0, packet, udpOffset + UDP_HEADER, payload.size)

        val udpChecksum = transportChecksumIpv6(srcIp, dstIp, PROTO_UDP, packet, udpOffset, udpLen)
        writeU16(packet, udpOffset + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    private fun buildIpv4TcpRst(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        ackNum: Long
    ): ByteArray {
        val totalLen = IPV4_MIN_HEADER + TCP_MIN_HEADER
        val packet = ByteArray(totalLen)

        packet[0] = 0x45.toByte()
        writeU16(packet, 2, totalLen)
        writeU16(packet, 6, 0x4000)
        packet[8] = 64.toByte()
        packet[9] = PROTO_TCP.toByte()
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)
        writeU16(packet, 10, ipv4HeaderChecksum(packet, IPV4_MIN_HEADER))

        val tcpOffset = IPV4_MIN_HEADER
        writeU16(packet, tcpOffset, srcPort)
        writeU16(packet, tcpOffset + 2, dstPort)
        writeU32(packet, tcpOffset + 4, 0L)
        writeU32(packet, tcpOffset + 8, ackNum)
        packet[tcpOffset + 12] = 0x50.toByte() // Data offset = 5 (20 bytes)
        packet[tcpOffset + 13] = 0x14.toByte() // RST (0x04) | ACK (0x10)
        writeU16(packet, tcpOffset + 14, 0)    // Window 0
        val checksum = transportChecksumIpv4(srcIp, dstIp, PROTO_TCP, packet, tcpOffset, TCP_MIN_HEADER)
        writeU16(packet, tcpOffset + 16, checksum)
        return packet
    }

    private fun buildIpv6TcpRst(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        ackNum: Long
    ): ByteArray {
        val totalLen = IPV6_HEADER + TCP_MIN_HEADER
        val packet = ByteArray(totalLen)

        packet[0] = 0x60.toByte()
        writeU16(packet, 4, TCP_MIN_HEADER)
        packet[6] = PROTO_TCP.toByte()
        packet[7] = 64.toByte()
        System.arraycopy(srcIp, 0, packet, 8, 16)
        System.arraycopy(dstIp, 0, packet, 24, 16)

        val tcpOffset = IPV6_HEADER
        writeU16(packet, tcpOffset, srcPort)
        writeU16(packet, tcpOffset + 2, dstPort)
        writeU32(packet, tcpOffset + 4, 0L)
        writeU32(packet, tcpOffset + 8, ackNum)
        packet[tcpOffset + 12] = 0x50.toByte()
        packet[tcpOffset + 13] = 0x14.toByte() // RST | ACK
        val checksum = transportChecksumIpv6(srcIp, dstIp, PROTO_TCP, packet, tcpOffset, TCP_MIN_HEADER)
        writeU16(packet, tcpOffset + 16, checksum)
        return packet
    }

    internal fun ipv4HeaderChecksum(buf: ByteArray, length: Int): Int {
        var sum = 0L
        var i = 0
        while (i < length) {
            sum += readU16(buf, i)
            i += 2
        }
        return finishChecksum(sum)
    }

    private fun transportChecksumIpv4(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        buf: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L
        sum += readU16(srcIp, 0) + readU16(srcIp, 2)
        sum += readU16(dstIp, 0) + readU16(dstIp, 2)
        sum += protocol
        sum += length
        sum += sumBytes(buf, offset, length)
        return finishChecksum(sum)
    }

    private fun transportChecksumIpv6(
        srcIp: ByteArray,
        dstIp: ByteArray,
        protocol: Int,
        buf: ByteArray,
        offset: Int,
        length: Int
    ): Int {
        var sum = 0L
        for (i in 0 until 16 step 2) {
            sum += readU16(srcIp, i)
            sum += readU16(dstIp, i)
        }
        sum += (length ushr 16) and 0xFFFF
        sum += length and 0xFFFF
        sum += protocol
        sum += sumBytes(buf, offset, length)
        return finishChecksum(sum)
    }

    private fun sumBytes(buf: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += readU16(buf, i)
            i += 2
        }
        if (i < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8)
        }
        return sum
    }

    private fun finishChecksum(initialSum: Long): Int {
        var sum = initialSum
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFFL) + (sum ushr 16)
        }
        return (sum.inv() and 0xFFFFL).toInt()
    }

    private fun readU16(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
    }

    private fun readU32(buf: ByteArray, offset: Int): Long {
        return (((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF))
    }

    private fun writeU16(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeU32(buf: ByteArray, offset: Int, value: Long) {
        buf[offset] = ((value ushr 24) and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 3] = (value and 0xFF).toByte()
    }
}
