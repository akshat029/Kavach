package com.kavach.app.vpn

/** Big-endian byte helpers shared by the packet and DNS codecs. */
internal object Bytes {

    fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

    fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun writeU16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    fun writeU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    /**
     * Internet checksum accumulator (RFC 1071).
     *
     * Kept as a class rather than a function so the IPv4 header checksum, the IPv4
     * UDP pseudo-header checksum and the IPv6 UDP pseudo-header checksum can all
     * share one implementation. Getting this wrong produces packets the kernel
     * silently drops, which is very hard to debug from the app side, so it is
     * covered directly by unit tests in PacketsTest.
     */
    class Checksum {
        private var sum: Long = 0

        fun addWord(value: Int): Checksum {
            sum += (value and 0xFFFF).toLong()
            return this
        }

        fun addBytes(data: ByteArray, offset: Int, length: Int): Checksum {
            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
                i += 2
            }
            if (i < end) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
            return this
        }

        fun fold(): Int {
            var s = sum
            while ((s ushr 16) != 0L) s = (s and 0xFFFF) + (s ushr 16)
            return (s.inv() and 0xFFFF).toInt()
        }
    }
}
