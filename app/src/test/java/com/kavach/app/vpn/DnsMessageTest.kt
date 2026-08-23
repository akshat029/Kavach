package com.kavach.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Wire-format tests for the DNS codec.
 *
 * These byte offsets are the contract with every resolver on the device, so they are
 * asserted literally rather than by round-tripping through the same code.
 */
class DnsMessageTest {

    private fun query(name: String, type: Int, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8)
        out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)   // flags: standard query, recursion desired
        out.write(0x00); out.write(0x01)   // QDCOUNT = 1
        out.write(0x00); out.write(0x00)   // ANCOUNT
        out.write(0x00); out.write(0x00)   // NSCOUNT
        out.write(0x00); out.write(0x00)   // ARCOUNT
        if (name.isNotEmpty()) {
            name.split('.').forEach { label ->
                out.write(label.length)
                out.write(label.toByteArray(Charsets.US_ASCII))
            }
        }
        out.write(0x00)                    // root label
        out.write(type shr 8); out.write(type and 0xFF)
        out.write(0x00); out.write(0x01)   // class IN
        return out.toByteArray()
    }

    private fun u16(b: ByteArray, i: Int) =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    @Test
    fun `parses a normal question`() {
        val q = DnsMessage.parseQuestion(query("ads.example.com", DnsMessage.TYPE_A))
        assertNotNull(q)
        assertEquals("ads.example.com", q!!.name)
        assertEquals(DnsMessage.TYPE_A, q.type)
        assertEquals(1, q.klass)
        // 12 header + (4 + 8 + 4 + 1) name + 4 type/class
        assertEquals(33, q.endOffset)
    }

    @Test
    fun `lowercases the name`() {
        val q = DnsMessage.parseQuestion(query("Ads.EXAMPLE.Com", DnsMessage.TYPE_A))
        assertEquals("ads.example.com", q!!.name)
    }

    @Test
    fun `parses a root query`() {
        val q = DnsMessage.parseQuestion(query("", DnsMessage.TYPE_NS))
        assertNotNull(q)
        assertEquals("", q!!.name)
        assertEquals(17, q.endOffset)
    }

    @Test
    fun `rejects a compression pointer inside the question`() {
        val bytes = query("example.com", DnsMessage.TYPE_A)
        bytes[12] = 0xC0.toByte()   // turn the first label length into a pointer
        assertNull(DnsMessage.parseQuestion(bytes))
    }

    @Test
    fun `rejects a truncated message`() {
        val bytes = query("example.com", DnsMessage.TYPE_A)
        assertNull(DnsMessage.parseQuestion(bytes.copyOf(20)))
        assertNull(DnsMessage.parseQuestion(ByteArray(4)))
    }

    @Test
    fun `rejects a message with no questions`() {
        val bytes = query("example.com", DnsMessage.TYPE_A)
        bytes[4] = 0; bytes[5] = 0   // QDCOUNT = 0
        assertNull(DnsMessage.parseQuestion(bytes))
    }

    @Test
    fun `distinguishes a query from a response`() {
        val q = query("example.com", DnsMessage.TYPE_A)
        assertTrue(DnsMessage.isQuery(q))
        q[2] = (q[2].toInt() or 0x80).toByte()
        assertTrue(!DnsMessage.isQuery(q))
    }

    @Test
    fun `sinkholes an A record to the unspecified address`() {
        val q = query("ads.example.com", DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuestion(q)!!
        val reply = DnsMessage.buildSinkhole(q, question)

        assertEquals(question.endOffset + 16, reply.size)
        assertEquals(0x1234, u16(reply, 0))                    // id echoed
        assertEquals(0x80, reply[2].toInt() and 0x80)          // QR = 1
        assertEquals(0x01, reply[2].toInt() and 0x01)          // RD preserved
        assertEquals(0, reply[3].toInt() and 0x0F)             // RCODE = NOERROR
        assertEquals(0x80, reply[3].toInt() and 0x80)          // RA = 1
        assertEquals(1, u16(reply, 4))                         // QDCOUNT
        assertEquals(1, u16(reply, 6))                         // ANCOUNT
        assertEquals(0, u16(reply, 8))
        assertEquals(0, u16(reply, 10))

        var p = question.endOffset
        assertEquals(0xC0, reply[p].toInt() and 0xFF)          // compression pointer
        assertEquals(0x0C, reply[p + 1].toInt())               // to offset 12
        p += 2
        assertEquals(DnsMessage.TYPE_A, u16(reply, p)); p += 2
        assertEquals(1, u16(reply, p)); p += 2
        p += 4                                                 // TTL
        assertEquals(4, u16(reply, p)); p += 2
        assertArrayEquals(ByteArray(4), reply.copyOfRange(p, p + 4))
    }

    @Test
    fun `sinkholes an AAAA record to the unspecified address`() {
        val q = query("ads.example.com", DnsMessage.TYPE_AAAA)
        val question = DnsMessage.parseQuestion(q)!!
        val reply = DnsMessage.buildSinkhole(q, question)

        assertEquals(question.endOffset + 28, reply.size)
        assertEquals(1, u16(reply, 6))
        val rdataStart = reply.size - 16
        assertEquals(16, u16(reply, rdataStart - 2))
        assertArrayEquals(ByteArray(16), reply.copyOfRange(rdataStart, reply.size))
    }

    @Test
    fun `sinkholes a non-address record as NXDOMAIN`() {
        val q = query("ads.example.com", DnsMessage.TYPE_TXT)
        val question = DnsMessage.parseQuestion(q)!!
        val reply = DnsMessage.buildSinkhole(q, question)

        assertEquals(question.endOffset, reply.size)
        assertEquals(DnsMessage.RCODE_NXDOMAIN, reply[3].toInt() and 0x0F)
        assertEquals(0, u16(reply, 6))
    }

    @Test
    fun `builds a refused response`() {
        val q = query("blocked.example.com", DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuestion(q)!!
        val reply = DnsMessage.buildEmptyResponse(q, question, DnsMessage.RCODE_REFUSED)

        assertEquals(question.endOffset, reply.size)
        assertEquals(DnsMessage.RCODE_REFUSED, reply[3].toInt() and 0x0F)
        assertEquals(0x80, reply[2].toInt() and 0x80)
    }

    @Test
    fun `truncation sets the TC bit and clears every record`() {
        val q = query("big.example.com", DnsMessage.TYPE_A)
        val question = DnsMessage.parseQuestion(q)!!
        val reply = DnsMessage.buildTruncated(q, question)

        assertEquals(0x02, reply[2].toInt() and 0x02)          // TC = 1
        assertEquals(0x80, reply[2].toInt() and 0x80)          // still a response
        assertEquals(DnsMessage.RCODE_NOERROR, reply[3].toInt() and 0x0F)
        assertEquals(0, u16(reply, 6))
        assertEquals(question.endOffset, reply.size)
    }

    @Test
    fun `drops the client EDNS OPT record from a synthesised answer`() {
        // A query carrying an OPT record in the additional section must still produce
        // a reply that stops at the end of the question.
        val base = query("ads.example.com", DnsMessage.TYPE_A)
        val withOpt = base + byteArrayOf(
            0x00,                                   // root name
            0x00, 0x29,                             // TYPE = OPT (41)
            0x10, 0x00,                             // UDP payload size 4096
            0x00, 0x00, 0x00, 0x00,                 // extended rcode + flags
            0x00, 0x00,                             // RDLENGTH
        )
        withOpt[11] = 0x01                          // ARCOUNT = 1

        val question = DnsMessage.parseQuestion(withOpt)!!
        assertEquals(33, question.endOffset)

        val reply = DnsMessage.buildSinkhole(withOpt, question)
        assertEquals(0, u16(reply, 10))             // ARCOUNT zeroed
        assertEquals(question.endOffset + 16, reply.size)
    }

    @Test
    fun `type names cover the record types the log displays`() {
        assertEquals("A", DnsMessage.typeName(DnsMessage.TYPE_A))
        assertEquals("AAAA", DnsMessage.typeName(DnsMessage.TYPE_AAAA))
        assertEquals("HTTPS", DnsMessage.typeName(DnsMessage.TYPE_HTTPS))
        assertEquals("TYPE999", DnsMessage.typeName(999))
    }
}
