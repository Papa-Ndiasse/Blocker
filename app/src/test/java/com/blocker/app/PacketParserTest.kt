package com.blocker.app

import com.blocker.app.vpn.DnsCodec
import com.blocker.app.vpn.PacketParser
import com.blocker.app.vpn.ParsedIpPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PacketParserTest {

    @Test
    fun `IPv4 UDP packet build and parse roundtrip swaps endpoints and validates header checksum`() {
        val clientIp = InetAddress.getByName("10.200.0.1").address
        val dnsIp = InetAddress.getByName("10.200.0.2").address
        val payload = DnsCodec.buildQuery("example.com", DnsCodec.TYPE_A, 0x2222)

        val fakeIncoming = ParsedIpPacket.Udp(
            ipVersion = 4,
            srcAddress = clientIp,
            dstAddress = dnsIp,
            srcPort = 54321,
            dstPort = 53,
            payload = payload
        )

        val responsePacket = PacketParser.buildUdpResponse(fakeIncoming, payload)
        // Verify IPv4 header checksum validates to 0
        assertEquals(0, PacketParser.ipv4HeaderChecksum(responsePacket, 20))

        val parsed = PacketParser.parse(responsePacket, responsePacket.size)
        assertTrue(parsed is ParsedIpPacket.Udp)
        val udp = parsed as ParsedIpPacket.Udp
        assertEquals(4, udp.ipVersion)
        assertArrayEquals(dnsIp, udp.srcAddress)
        assertArrayEquals(clientIp, udp.dstAddress)
        assertEquals(53, udp.srcPort)
        assertEquals(54321, udp.dstPort)
        assertArrayEquals(payload, udp.payload)
    }

    @Test
    fun `IPv6 UDP packet build and parse roundtrip produces non-zero UDP checksum`() {
        val clientIp6 = InetAddress.getByName("fd00:200::1").address
        val dnsIp6 = InetAddress.getByName("fd00:200::2").address
        val payload = DnsCodec.buildQuery("example.org", DnsCodec.TYPE_AAAA, 0x3333)

        val fakeIncoming = ParsedIpPacket.Udp(
            ipVersion = 6,
            srcAddress = clientIp6,
            dstAddress = dnsIp6,
            srcPort = 40000,
            dstPort = 53,
            payload = payload
        )

        val responsePacket = PacketParser.buildUdpResponse(fakeIncoming, payload)
        // UDP checksum at IPv6 header (40) + 6 must be non-zero per RFC 8200
        val udpChecksum = ((responsePacket[46].toInt() and 0xFF) shl 8) or
            (responsePacket[47].toInt() and 0xFF)
        assertNotEquals(0, udpChecksum)

        val parsed = PacketParser.parse(responsePacket, responsePacket.size) as ParsedIpPacket.Udp
        assertEquals(6, parsed.ipVersion)
        assertArrayEquals(dnsIp6, parsed.srcAddress)
        assertArrayEquals(clientIp6, parsed.dstAddress)
        assertEquals(53, parsed.srcPort)
        assertEquals(40000, parsed.dstPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `TCP SYN to port 853 produces clean IPv4 TCP RST+ACK`() {
        val syn = ParsedIpPacket.TcpSyn(
            ipVersion = 4,
            srcAddress = InetAddress.getByName("10.200.0.1").address,
            dstAddress = InetAddress.getByName("10.200.0.2").address,
            srcPort = 49152,
            dstPort = 853,
            seqNumber = 1000L
        )
        val rstPacket = PacketParser.buildTcpRstResponse(syn)
        assertEquals(40, rstPacket.size)
        assertEquals(0, PacketParser.ipv4HeaderChecksum(rstPacket, 20))
        // TCP flags byte at offset 20 + 13 must be RST|ACK (0x14)
        assertEquals(0x14, rstPacket[33].toInt() and 0xFF)
        // ACK number at offset 20 + 8 must be seq + 1 = 1001
        val ackNum = (((rstPacket[28].toLong() and 0xFF) shl 24) or
            ((rstPacket[29].toLong() and 0xFF) shl 16) or
            ((rstPacket[30].toLong() and 0xFF) shl 8) or
            (rstPacket[31].toLong() and 0xFF))
        assertEquals(1001L, ackNum)
    }
}
