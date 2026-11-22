package com.blocker.app

import com.blocker.app.vpn.DnsCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

class DnsCodecTest {

    @Test
    fun `buildQuery and parseQuery roundtrip succeeds`() {
        val raw = DnsCodec.buildQuery("www.google.fr", DnsCodec.TYPE_A, transactionId = 0x1A2B)
        val parsed = DnsCodec.parseQuery(raw)

        assertNotNull(parsed)
        assertEquals(0x1A2B, parsed!!.transactionId)
        assertEquals("www.google.fr", parsed.domain)
        assertEquals(DnsCodec.TYPE_A, parsed.qType)
        assertEquals(DnsCodec.CLASS_IN, parsed.qClass)
    }

    @Test
    fun `buildNxDomainResponse sets QR and RCODE=3 and preserves txId`() {
        val raw = DnsCodec.buildQuery("pornhub.com", DnsCodec.TYPE_A, transactionId = 0xBEEF)
        val parsed = DnsCodec.parseQuery(raw)!!
        val resp = DnsCodec.buildNxDomainResponse(raw, parsed)

        val txId = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
        val flags = ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)
        val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)

        assertEquals(0xBEEF, txId)
        assertEquals(0x8000, flags and 0x8000) // QR=1
        assertEquals(3, flags and 0x000F)      // RCODE=3 (NXDOMAIN)
        assertEquals(0, anCount)
        // parseQuery must reject responses
        assertNull(DnsCodec.parseQuery(resp))
    }

    @Test
    fun `buildNoDataResponse sets NOERROR with zero answers`() {
        val raw = DnsCodec.buildQuery("www.google.com", DnsCodec.TYPE_HTTPS, transactionId = 0x0042)
        val parsed = DnsCodec.parseQuery(raw)!!
        val resp = DnsCodec.buildNoDataResponse(raw, parsed)

        val flags = ((resp[2].toInt() and 0xFF) shl 8) or (resp[3].toInt() and 0xFF)
        val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)

        assertEquals(0x8000, flags and 0x8000)
        assertEquals(0, flags and 0x000F) // RCODE=0 (NOERROR)
        assertEquals(0, anCount)
    }

    @Test
    fun `buildAddressResponse and extractAddressRecords roundtrip IPv4 and IPv6`() {
        val safeIp = InetAddress.getByName("216.239.38.120").address
        val rawV4 = DnsCodec.buildQuery("www.google.com", DnsCodec.TYPE_A, transactionId = 0x1234)
        val parsedV4 = DnsCodec.parseQuery(rawV4)!!
        val respV4 = DnsCodec.buildAddressResponse(rawV4, parsedV4, listOf(safeIp))

        val extractedV4 = DnsCodec.extractAddressRecords(respV4, DnsCodec.TYPE_A)
        assertEquals(1, extractedV4.size)
        assertArrayEquals(safeIp, extractedV4[0])

        val safeIp6 = InetAddress.getByName("2001:4860:4802:32::78").address
        val rawV6 = DnsCodec.buildQuery("www.google.com", DnsCodec.TYPE_AAAA, transactionId = 0x5678)
        val parsedV6 = DnsCodec.parseQuery(rawV6)!!
        val respV6 = DnsCodec.buildAddressResponse(rawV6, parsedV6, listOf(safeIp6))

        val extractedV6 = DnsCodec.extractAddressRecords(respV6, DnsCodec.TYPE_AAAA)
        assertEquals(1, extractedV6.size)
        assertArrayEquals(safeIp6, extractedV6[0])
    }

    @Test
    fun `patchTransactionId updates first two bytes without mutating original`() {
        val original = DnsCodec.buildQuery("example.com", DnsCodec.TYPE_A, transactionId = 0x1111)
        val patched = DnsCodec.patchTransactionId(original, 0x9999)

        assertEquals(0x11, original[0].toInt() and 0xFF)
        assertEquals(0x99, patched[0].toInt() and 0xFF)
        assertEquals(0x99, patched[1].toInt() and 0xFF)
    }
}
