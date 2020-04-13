package org.tcncoalition.tcnclient.crypto

import java.nio.ByteBuffer

/** Convenience function to read a 32-byte array. */
internal fun read32(buf: ByteBuffer): ByteArray {
    val ret = ByteArray(32)
    buf.get(ret)
    return ret
}

/** Convenience function to read a 64-byte array. */
internal fun read64(buf: ByteBuffer): ByteArray {
    val ret = ByteArray(64)
    buf.get(ret)
    return ret
}

/** Convenience function to read a short vector with a 1-byte length tag. */
internal fun readCompactVec(buf: ByteBuffer): ByteArray {
    val len = buf.get().toInt()
    val ret = ByteArray(len)
    buf.get(ret)
    return ret
}
