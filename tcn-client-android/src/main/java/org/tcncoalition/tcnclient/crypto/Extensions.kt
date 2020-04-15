package org.tcncoalition.tcnclient.crypto

import java.nio.ByteBuffer

/** Convenience function to read a 32-byte array. */
internal fun ByteBuffer.read32(): ByteArray {
    val ret = ByteArray(32)
    get(ret)
    return ret
}

/** Convenience function to read a 64-byte array. */
internal fun ByteBuffer.read64(): ByteArray {
    val ret = ByteArray(64)
    get(ret)
    return ret
}

/** Convenience function to read a short vector with a 1-byte length tag. */
internal fun ByteBuffer.readCompactVec(): ByteArray {
    val len = get().toInt()
    val ret = ByteArray(len)
    get(ret)
    return ret
}
