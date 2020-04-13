package org.tcncoalition.tcnclient.crypto

import java.nio.ByteBuffer

/** Convenience function to read a 32-byte array. */
internal fun read32(buf: ByteBuffer): ByteArray {
    val ret = ByteArray(32)
    buf.get(ret)
    return ret
}
