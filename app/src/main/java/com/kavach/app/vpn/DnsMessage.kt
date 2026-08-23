package com.kavach.app.vpn

import com.kavach.app.vpn.Bytes.u16
import com.kavach.app.vpn.Bytes.u8
import com.kavach.app.vpn.Bytes.writeU16
import com.kavach.app.vpn.Bytes.writeU32

/**
 * Just enough DNS wire format (RFC 1035 / RFC 8484) to read a question and
 * synthesise an answer.
 *
 * Kavach never parses an upstream *response*: allowed queries are forwarded to the
 * resolver verbatim and the reply bytes are handed straight back to the app. That
 * keeps DNSSEC, EDNS and any future record type working untouched, and it means a
 * malformed upstream reply cannot crash the tunnel.
 */
object DnsMessage {

    const val TYPE_A = 1
    const val TYPE_NS = 2
    const val TYPE_CNAME = 5
    const val TYPE_SOA = 6
    const val TYPE_PTR = 12
    const val TYPE_MX = 15
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    const val TYPE_SVCB = 64
    const val TYPE_HTTPS = 65

    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3
    const val RCODE_REFUSED = 5

    const val HEADER_LEN = 12

    /** Short TTL on sinkholed answers so un-blocking an app takes effect quickly. */
    private const val SINKHOLE_TTL = 60L
    private const val MAX_LABELS = 128
    private const val MAX_NAME_LENGTH = 253

    class Question(
        /** Lowercased, dot separated, no trailing dot. A root query yields "". */
        val name: String,
        val type: Int,
        val klass: Int,
        /** Offset just past the question, i.e. the length of header + question. */
        val endOffset: Int,
    )

    fun isQuery(message: ByteArray): Boolean =
        message.size >= HEADER_LEN && (message[2].toInt() and 0x80) == 0

    /**
     * Reads the first question. Returns null for anything malformed so the caller
     * can drop the packet instead of acting on a half-parsed name.
     */
    fun parseQuestion(message: ByteArray): Question? {
        if (message.size < HEADER_LEN) return null
        if (u16(message, 4) < 1) return null

        val name = StringBuilder()
        var offset = HEADER_LEN
        var labels = 0

        while (true) {
            if (offset >= message.size) return null
            val length = u8(message, offset)
            if (length == 0) {
                offset += 1
                break
            }
            // A compression pointer is illegal inside a question. Reject rather than
            // follow it: following attacker-controlled pointers is how DNS parsers
            // end up in infinite loops.
            if ((length and 0xC0) != 0) return null

            offset += 1
            if (offset + length > message.size) return null
            if (++labels > MAX_LABELS) return null
            if (name.isNotEmpty()) name.append('.')
            if (name.length + length > MAX_NAME_LENGTH) return null

            for (i in 0 until length) {
                val c = u8(message, offset + i)
                // ASCII lowercase; DNS names on the wire are punycode, never UTF-8.
                name.append(if (c in 0x41..0x5A) (c + 0x20).toChar() else c.toChar())
            }
            offset += length
        }

        if (offset + 4 > message.size) return null
        val type = u16(message, offset)
        val klass = u16(message, offset + 2)
        return Question(name.toString(), type, klass, offset + 4)
    }

    fun typeName(type: Int): String = when (type) {
        TYPE_A -> "A"
        TYPE_NS -> "NS"
        TYPE_CNAME -> "CNAME"
        TYPE_SOA -> "SOA"
        TYPE_PTR -> "PTR"
        TYPE_MX -> "MX"
        TYPE_TXT -> "TXT"
        TYPE_AAAA -> "AAAA"
        TYPE_SRV -> "SRV"
        TYPE_SVCB -> "SVCB"
        TYPE_HTTPS -> "HTTPS"
        else -> "TYPE$type"
    }

    /**
     * Answers an A/AAAA question with the unspecified address, and any other
     * question type with NXDOMAIN.
     *
     * Returning 0.0.0.0 rather than NXDOMAIN makes the calling app fail fast with a
     * connection error instead of retrying the lookup against every resolver it
     * knows, which is what actually saves battery.
     */
    fun buildSinkhole(query: ByteArray, question: Question): ByteArray {
        val rdata: ByteArray = when (question.type) {
            TYPE_A -> ByteArray(4)
            TYPE_AAAA -> ByteArray(16)
            else -> return buildEmptyResponse(query, question, RCODE_NXDOMAIN)
        }

        val recordLength = 2 + 2 + 2 + 4 + 2 + rdata.size
        val out = ByteArray(question.endOffset + recordLength)
        System.arraycopy(query, 0, out, 0, question.endOffset)

        applyResponseHeader(out, RCODE_NOERROR, answerCount = 1)

        var p = question.endOffset
        // Name compression pointer back to the question name at offset 12.
        out[p] = 0xC0.toByte()
        out[p + 1] = 0x0C
        p += 2
        writeU16(out, p, question.type)
        p += 2
        writeU16(out, p, question.klass)
        p += 2
        writeU32(out, p, SINKHOLE_TTL)
        p += 4
        writeU16(out, p, rdata.size)
        p += 2
        System.arraycopy(rdata, 0, out, p, rdata.size)
        return out
    }

    /**
     * Sets TC=1 and drops every record.
     *
     * Used when an upstream reply is too large to fit the tunnel MTU. Signalling
     * truncation is the defined behaviour in RFC 1035 section 4.2.1 and lets the
     * client fail fast instead of waiting for a timeout that will never resolve.
     */
    fun buildTruncated(query: ByteArray, question: Question): ByteArray {
        val out = buildEmptyResponse(query, question, RCODE_NOERROR)
        out[2] = (out[2].toInt() or 0x02).toByte()
        return out
    }

    /** Header + question echoed back with the given rcode and no records. */
    fun buildEmptyResponse(query: ByteArray, question: Question, rcode: Int): ByteArray {
        val out = ByteArray(question.endOffset)
        System.arraycopy(query, 0, out, 0, question.endOffset)
        applyResponseHeader(out, rcode, answerCount = 0)
        return out
    }

    /**
     * Turns a copied query header into a response header.
     *
     * Truncating the message at the end of the question drops any EDNS OPT record
     * the client sent. Replying without OPT is valid (RFC 6891 section 7: the client
     * must treat it as a resolver that does not support EDNS) and it keeps the
     * synthesised packet small enough to never need fragmentation.
     */
    private fun applyResponseHeader(out: ByteArray, rcode: Int, answerCount: Int) {
        val recursionDesired = out[2].toInt() and 0x01
        val opcode = (out[2].toInt() shr 3) and 0x0F
        out[2] = (0x80 or (opcode shl 3) or recursionDesired).toByte() // QR=1, AA=0, TC=0
        out[3] = (0x80 or (rcode and 0x0F)).toByte()                   // RA=1, Z=0
        writeU16(out, 4, 1)             // QDCOUNT: only the first question is answered
        writeU16(out, 6, answerCount)   // ANCOUNT
        writeU16(out, 8, 0)             // NSCOUNT
        writeU16(out, 10, 0)            // ARCOUNT
    }
}
