package com.kavach.app.vpn

import com.kavach.app.vpn.Bytes.u16
import com.kavach.app.vpn.Bytes.u8
import com.kavach.app.vpn.Bytes.writeU16
import java.net.InetAddress

/**
 * Minimal IPv4/IPv6 + UDP codec.
 *
 * Scope is deliberately tiny. Kavach only ever routes its own virtual DNS address
 * into the TUN device (see KavachVpnService), so the only packets that can arrive
 * are UDP datagrams aimed at port 53. Anything else is dropped rather than
 * mis-parsed.
 *
 * That constraint is what lets Kavach avoid shipping a userspace TCP/IP stack:
 * there is no connection state to track, every exchange is one request and one
 * response, and normal app traffic never enters the tunnel at all.
 */
object Packets {

    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    private const val IPV4_HEADER_LEN = 20
    private const val IPV6_HEADER_LEN = 40
    private const val UDP_HEADER_LEN = 8
    private const val DEFAULT_TTL = 64

    /** A parsed UDP datagram plus the addressing needed to answer it. */
    class UdpDatagram(
        val ipVersion: Int,
        val sourceAddress: InetAddress,
        val destinationAddress: InetAddress,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray,
    )

    fun versionOf(packet: ByteArray): Int = (packet[0].toInt() shr 4) and 0x0F

    /** The IP protocol number of a raw packet, or -1 if it cannot be determined. */
    fun protocolOf(packet: ByteArray, length: Int): Int {
        if (length < 1) return -1
        return when (versionOf(packet)) {
            4 -> if (length >= IPV4_HEADER_LEN) u8(packet, 9) else -1
            6 -> if (length >= IPV6_HEADER_LEN) u8(packet, 6) else -1
            else -> -1
        }
    }

    /** Returns null for anything that is not a complete, unfragmented UDP datagram. */
    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? = when {
        length < 1 -> null
        versionOf(packet) == 4 -> parseUdpV4(packet, length)
        versionOf(packet) == 6 -> parseUdpV6(packet, length)
        else -> null
    }

    private fun parseUdpV4(p: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV4_HEADER_LEN) return null
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (ihl < IPV4_HEADER_LEN || length < ihl + UDP_HEADER_LEN) return null
        if (u8(p, 9) != PROTO_UDP) return null

        // Reassembly is out of scope. A fragmented DNS query over UDP is vanishingly
        // rare, and dropping it makes the client resolver retry.
        val fragField = u16(p, 6)
        if ((fragField and 0x2000) != 0 || (fragField and 0x1FFF) != 0) return null

        val totalLength = u16(p, 2)
        val bounded = if (totalLength in (ihl + UDP_HEADER_LEN)..length) totalLength else length

        val udpLength = u16(p, ihl + 4)
        val declared = (udpLength - UDP_HEADER_LEN).coerceAtLeast(0)
        val available = bounded - ihl - UDP_HEADER_LEN
        if (available < 0) return null
        val payloadLength = minOf(declared, available)

        val start = ihl + UDP_HEADER_LEN
        return UdpDatagram(
            ipVersion = 4,
            sourceAddress = InetAddress.getByAddress(p.copyOfRange(12, 16)),
            destinationAddress = InetAddress.getByAddress(p.copyOfRange(16, 20)),
            sourcePort = u16(p, ihl),
            destinationPort = u16(p, ihl + 2),
            payload = p.copyOfRange(start, start + payloadLength),
        )
    }

    private fun parseUdpV6(p: ByteArray, length: Int): UdpDatagram? {
        if (length < IPV6_HEADER_LEN + UDP_HEADER_LEN) return null
        // Extension headers are not walked: the DNS traffic Kavach routes never has any.
        if (u8(p, 6) != PROTO_UDP) return null

        val payloadLength = u16(p, 4)
        val bounded = minOf(length, IPV6_HEADER_LEN + payloadLength)
        val available = bounded - IPV6_HEADER_LEN - UDP_HEADER_LEN
        if (available < 0) return null

        val udpLength = u16(p, IPV6_HEADER_LEN + 4)
        val declared = (udpLength - UDP_HEADER_LEN).coerceAtLeast(0)
        val finalLength = minOf(declared, available)

        val start = IPV6_HEADER_LEN + UDP_HEADER_LEN
        return UdpDatagram(
            ipVersion = 6,
            sourceAddress = InetAddress.getByAddress(p.copyOfRange(8, 24)),
            destinationAddress = InetAddress.getByAddress(p.copyOfRange(24, 40)),
            sourcePort = u16(p, IPV6_HEADER_LEN),
            destinationPort = u16(p, IPV6_HEADER_LEN + 2),
            payload = p.copyOfRange(start, start + finalLength),
        )
    }

    /**
     * Builds the reply to [request], swapping source and destination so the packet
     * looks like it came from the virtual DNS server the app queried.
     */
    fun buildUdpReply(request: UdpDatagram, payload: ByteArray): ByteArray? {
        val src = request.destinationAddress.address
        val dst = request.sourceAddress.address
        return when (request.ipVersion) {
            4 -> if (src.size == 4 && dst.size == 4) {
                buildUdpV4(src, request.destinationPort, dst, request.sourcePort, payload)
            } else {
                null
            }

            6 -> if (src.size == 16 && dst.size == 16) {
                buildUdpV6(src, request.destinationPort, dst, request.sourcePort, payload)
            } else {
                null
            }

            else -> null
        }
    }

    private fun buildUdpV4(
        src: ByteArray,
        srcPort: Int,
        dst: ByteArray,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = UDP_HEADER_LEN + payload.size
        val total = IPV4_HEADER_LEN + udpLength
        val out = ByteArray(total)

        out[0] = 0x45                       // IPv4, IHL = 5 words
        out[1] = 0                          // DSCP / ECN
        writeU16(out, 2, total)
        writeU16(out, 4, 0)                 // identification (safe at zero: DF is set)
        writeU16(out, 6, 0x4000)            // don't fragment
        out[8] = DEFAULT_TTL.toByte()
        out[9] = PROTO_UDP.toByte()
        writeU16(out, 10, 0)                // checksum field must be zero while computing
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        writeU16(out, 10, Bytes.Checksum().addBytes(out, 0, IPV4_HEADER_LEN).fold())

        val u = IPV4_HEADER_LEN
        writeU16(out, u, srcPort)
        writeU16(out, u + 2, dstPort)
        writeU16(out, u + 4, udpLength)
        writeU16(out, u + 6, 0)
        System.arraycopy(payload, 0, out, u + UDP_HEADER_LEN, payload.size)

        val checksum = Bytes.Checksum()
            .addBytes(src, 0, 4)
            .addBytes(dst, 0, 4)
            .addWord(PROTO_UDP)
            .addWord(udpLength)
            .addBytes(out, u, udpLength)
            .fold()
        // RFC 768: a computed checksum of zero is transmitted as all ones.
        writeU16(out, u + 6, if (checksum == 0) 0xFFFF else checksum)
        return out
    }

    private fun buildUdpV6(
        src: ByteArray,
        srcPort: Int,
        dst: ByteArray,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLength = UDP_HEADER_LEN + payload.size
        val total = IPV6_HEADER_LEN + udpLength
        val out = ByteArray(total)

        out[0] = 0x60                       // version 6, traffic class 0
        writeU16(out, 4, udpLength)         // payload length
        out[6] = PROTO_UDP.toByte()         // next header
        out[7] = DEFAULT_TTL.toByte()       // hop limit
        System.arraycopy(src, 0, out, 8, 16)
        System.arraycopy(dst, 0, out, 24, 16)

        val u = IPV6_HEADER_LEN
        writeU16(out, u, srcPort)
        writeU16(out, u + 2, dstPort)
        writeU16(out, u + 4, udpLength)
        writeU16(out, u + 6, 0)
        System.arraycopy(payload, 0, out, u + UDP_HEADER_LEN, payload.size)

        // IPv6 pseudo-header: src, dst, 32-bit upper-layer length, 24 zero bits, next header.
        val checksum = Bytes.Checksum()
            .addBytes(src, 0, 16)
            .addBytes(dst, 0, 16)
            .addWord(udpLength ushr 16)
            .addWord(udpLength and 0xFFFF)
            .addWord(0)
            .addWord(PROTO_UDP)
            .addBytes(out, u, udpLength)
            .fold()
        // Unlike IPv4, the UDP checksum is mandatory over IPv6.
        writeU16(out, u + 6, if (checksum == 0) 0xFFFF else checksum)
        return out
    }
}
