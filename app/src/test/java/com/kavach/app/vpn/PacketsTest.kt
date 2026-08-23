package com.kavach.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress

/**
 * Packet codec tests.
 *
 * The checksum verifier below is written independently of the production code on
 * purpose. Checking the output with the same routine that produced it would pass
 * even if that routine were wrong, and a bad checksum produces packets the kernel
 * discards without any error the app can see - the single hardest class of bug to
 * diagnose in a VPN service.
 */
class PacketsTest {

    private val clientV4 = "198.18.71.1"
    private val dnsV4 = "198.18.71.53"
    private val clientV6 = "fd6b:6176:6163:68::1"
    private val dnsV6 = "fd6b:6176:6163:68::53"

    // ---- independent RFC 1071 verifier -------------------------------------

    private fun sum(vararg parts: ByteArray): Int {
        var acc = 0L
        for (part in parts) {
            var i = 0
            while (i + 1 < part.size) {
                acc += (((part[i].toInt() and 0xFF) shl 8) or (part[i + 1].toInt() and 0xFF)).toLong()
                i += 2
            }
            if (i < part.size) acc += ((part[i].toInt() and 0xFF) shl 8).toLong()
        }
        while ((acc ushr 16) != 0L) acc = (acc and 0xFFFF) + (acc ushr 16)
        return (acc and 0xFFFF).toInt()
    }

    /** A correct checksum makes the ones-complement sum over the covered bytes 0xFFFF. */
    private fun assertChecksumValid(vararg parts: ByteArray) {
        assertEquals("checksum does not verify", 0xFFFF, sum(*parts))
    }

    private fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun readU16(data: ByteArray, offset: Int) =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    // ---- request builders --------------------------------------------------

    private fun ipv4Udp(
        src: String = clientV4,
        srcPort: Int = 43512,
        dst: String = dnsV4,
        dstPort: Int = 53,
        payload: ByteArray,
        fragmentField: Int = 0x4000,
        protocol: Int = Packets.PROTO_UDP,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45
        writeU16(p, 2, total)
        writeU16(p, 6, fragmentField)
        p[8] = 64
        p[9] = protocol.toByte()
        System.arraycopy(InetAddress.getByName(src).address, 0, p, 12, 4)
        System.arraycopy(InetAddress.getByName(dst).address, 0, p, 16, 4)
        writeU16(p, 20, srcPort)
        writeU16(p, 22, dstPort)
        writeU16(p, 24, udpLen)
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    private fun ipv6Udp(
        src: String = clientV6,
        srcPort: Int = 43512,
        dst: String = dnsV6,
        dstPort: Int = 53,
        payload: ByteArray,
        nextHeader: Int = Packets.PROTO_UDP,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val p = ByteArray(40 + udpLen)
        p[0] = 0x60
        writeU16(p, 4, udpLen)
        p[6] = nextHeader.toByte()
        p[7] = 64
        System.arraycopy(InetAddress.getByName(src).address, 0, p, 8, 16)
        System.arraycopy(InetAddress.getByName(dst).address, 0, p, 24, 16)
        writeU16(p, 40, srcPort)
        writeU16(p, 42, dstPort)
        writeU16(p, 44, udpLen)
        System.arraycopy(payload, 0, p, 48, payload.size)
        return p
    }

    // ---- parsing -----------------------------------------------------------

    @Test
    fun `parses an IPv4 UDP datagram`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val d = Packets.parseUdp(ipv4Udp(payload = payload), 20 + 8 + payload.size)

        assertNotNull(d)
        assertEquals(4, d!!.ipVersion)
        assertEquals(clientV4, d.sourceAddress.hostAddress)
        assertEquals(dnsV4, d.destinationAddress.hostAddress)
        assertEquals(43512, d.sourcePort)
        assertEquals(53, d.destinationPort)
        assertArrayEquals(payload, d.payload)
    }

    @Test
    fun `parses an IPv6 UDP datagram`() {
        val payload = byteArrayOf(9, 8, 7)
        val raw = ipv6Udp(payload = payload)
        val d = Packets.parseUdp(raw, raw.size)

        assertNotNull(d)
        assertEquals(6, d!!.ipVersion)
        assertEquals(43512, d.sourcePort)
        assertEquals(53, d.destinationPort)
        assertArrayEquals(payload, d.payload)
    }

    @Test
    fun `reports the IP protocol number`() {
        val udp = ipv4Udp(payload = ByteArray(4))
        assertEquals(Packets.PROTO_UDP, Packets.protocolOf(udp, udp.size))

        val tcp = ipv4Udp(payload = ByteArray(4), protocol = Packets.PROTO_TCP)
        assertEquals(Packets.PROTO_TCP, Packets.protocolOf(tcp, tcp.size))

        assertEquals(-1, Packets.protocolOf(ByteArray(0), 0))
    }

    @Test
    fun `refuses a fragmented IPv4 packet rather than mis-parsing it`() {
        val moreFragments = ipv4Udp(payload = ByteArray(8), fragmentField = 0x2000)
        assertNull(Packets.parseUdp(moreFragments, moreFragments.size))

        val nonZeroOffset = ipv4Udp(payload = ByteArray(8), fragmentField = 0x0001)
        assertNull(Packets.parseUdp(nonZeroOffset, nonZeroOffset.size))
    }

    @Test
    fun `refuses a non-UDP packet`() {
        val tcp = ipv4Udp(payload = ByteArray(8), protocol = Packets.PROTO_TCP)
        assertNull(Packets.parseUdp(tcp, tcp.size))

        val v6Tcp = ipv6Udp(payload = ByteArray(8), nextHeader = Packets.PROTO_TCP)
        assertNull(Packets.parseUdp(v6Tcp, v6Tcp.size))
    }

    @Test
    fun `refuses a runt packet`() {
        assertNull(Packets.parseUdp(ByteArray(0), 0))
        assertNull(Packets.parseUdp(byteArrayOf(0x45), 1))
        assertNull(Packets.parseUdp(ByteArray(20) { if (it == 0) 0x45 else 0 }, 20))
    }

    @Test
    fun `ignores trailing bytes beyond the declared length`() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val raw = ipv4Udp(payload = payload) + ByteArray(32)   // padding after the datagram
        val d = Packets.parseUdp(raw, raw.size)
        assertArrayEquals(payload, d!!.payload)
    }

    // ---- reply construction ------------------------------------------------

    @Test
    fun `IPv4 reply has a valid header checksum and swapped endpoints`() {
        val request = Packets.parseUdp(ipv4Udp(payload = ByteArray(12) { it.toByte() }), 40)!!
        val answer = ByteArray(20) { (it * 7).toByte() }
        val reply = Packets.buildUdpReply(request, answer)!!

        assertEquals(20 + 8 + answer.size, reply.size)
        assertEquals(20 + 8 + answer.size, readU16(reply, 2))
        assertEquals(4, Packets.versionOf(reply))
        assertEquals(Packets.PROTO_UDP, reply[9].toInt())
        assertEquals(0x4000, readU16(reply, 6))                       // don't fragment

        // Source is the virtual DNS address, destination is the app.
        assertArrayEquals(InetAddress.getByName(dnsV4).address, reply.copyOfRange(12, 16))
        assertArrayEquals(InetAddress.getByName(clientV4).address, reply.copyOfRange(16, 20))
        assertEquals(53, readU16(reply, 20))
        assertEquals(43512, readU16(reply, 22))

        assertChecksumValid(reply.copyOfRange(0, 20))
    }

    @Test
    fun `IPv4 reply has a valid UDP checksum over its pseudo-header`() {
        val request = Packets.parseUdp(ipv4Udp(payload = ByteArray(8)), 36)!!
        val answer = ByteArray(33) { (it + 1).toByte() }               // odd length on purpose
        val reply = Packets.buildUdpReply(request, answer)!!

        val udpLength = 8 + answer.size
        assertEquals(udpLength, readU16(reply, 24))

        val pseudo = ByteArray(12)
        System.arraycopy(reply, 12, pseudo, 0, 4)                      // source address
        System.arraycopy(reply, 16, pseudo, 4, 4)                      // destination address
        pseudo[8] = 0
        pseudo[9] = Packets.PROTO_UDP.toByte()
        writeU16(pseudo, 10, udpLength)

        assertChecksumValid(pseudo, reply.copyOfRange(20, reply.size))
    }

    @Test
    fun `IPv6 reply has a valid UDP checksum over its pseudo-header`() {
        val raw = ipv6Udp(payload = ByteArray(8))
        val request = Packets.parseUdp(raw, raw.size)!!
        val answer = ByteArray(41) { (it * 3).toByte() }               // odd length on purpose
        val reply = Packets.buildUdpReply(request, answer)!!

        val udpLength = 8 + answer.size
        assertEquals(40 + udpLength, reply.size)
        assertEquals(6, Packets.versionOf(reply))
        assertEquals(udpLength, readU16(reply, 4))                     // IPv6 payload length
        assertEquals(Packets.PROTO_UDP, reply[6].toInt())
        assertEquals(udpLength, readU16(reply, 44))                    // UDP length

        assertArrayEquals(InetAddress.getByName(dnsV6).address, reply.copyOfRange(8, 24))
        assertArrayEquals(InetAddress.getByName(clientV6).address, reply.copyOfRange(24, 40))
        assertEquals(53, readU16(reply, 40))
        assertEquals(43512, readU16(reply, 42))

        val pseudo = ByteArray(40)
        System.arraycopy(reply, 8, pseudo, 0, 16)                      // source address
        System.arraycopy(reply, 24, pseudo, 16, 16)                    // destination address
        pseudo[32] = ((udpLength ushr 24) and 0xFF).toByte()
        pseudo[33] = ((udpLength ushr 16) and 0xFF).toByte()
        pseudo[34] = ((udpLength ushr 8) and 0xFF).toByte()
        pseudo[35] = (udpLength and 0xFF).toByte()
        pseudo[36] = 0; pseudo[37] = 0; pseudo[38] = 0
        pseudo[39] = Packets.PROTO_UDP.toByte()

        assertChecksumValid(pseudo, reply.copyOfRange(40, reply.size))
    }

    @Test
    fun `an empty payload still produces a well-formed reply`() {
        val request = Packets.parseUdp(ipv4Udp(payload = ByteArray(4)), 32)!!
        val reply = Packets.buildUdpReply(request, ByteArray(0))!!

        assertEquals(28, reply.size)
        assertEquals(8, readU16(reply, 24))
        assertChecksumValid(reply.copyOfRange(0, 20))
    }

    @Test
    fun `a UDP checksum is never transmitted as zero`() {
        // RFC 768: zero means "no checksum", so a computed zero must go out as 0xFFFF.
        val request = Packets.parseUdp(ipv4Udp(payload = ByteArray(4)), 32)!!
        for (size in 0..64) {
            val reply = Packets.buildUdpReply(request, ByteArray(size) { (it * 11).toByte() })!!
            assertEquals("zero checksum at payload size $size", true, readU16(reply, 26) != 0)
        }
    }
}
