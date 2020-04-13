package org.tcncoalition.tcnclient.crypto

import cafe.cryptography.ed25519.Ed25519PrivateKey
import cafe.cryptography.ed25519.Ed25519PublicKey
import org.tcncoalition.tcnclient.TcnConstants.H_TCK_DOMAIN_SEPARATOR
import org.tcncoalition.tcnclient.TcnConstants.H_TCN_DOMAIN_SEPARATOR
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/** Authorizes publication of a report of potential exposure. */
class ReportAuthorizationKey(internal val rak: Ed25519PrivateKey) {
    internal val rvk: Ed25519PublicKey = this.rak.derivePublic()

    /** Generates a new ReportAuthorizationKey. */
    constructor(random: SecureRandom) : this(Ed25519PrivateKey.generate(random))

    companion object Reader {
        /** Reads a [ReportAuthorizationKey] from [bytes]. */
        fun fromByteArray(bytes: ByteArray): ReportAuthorizationKey {
            val buf = ByteBuffer.wrap(bytes)
            val rak = Ed25519PrivateKey.fromByteArray(read32(buf))
            return ReportAuthorizationKey(rak)
        }
    }

    /** Serializes a [ReportAuthorizationKey] into a [ByteArray]. */
    fun toByteArray(): ByteArray {
        return rak.toByteArray()
    }

    /** The initial temporary contact key derived from this report authorization key. */
    val initialTemporaryContactKey: TemporaryContactKey by lazy {
        tck0.ratchet()!!
    }

    /** This is internal because tck0 shouldn't be used to generate a tcn. */
    internal val tck0: TemporaryContactKey
        get() {
            val h = MessageDigest.getInstance("SHA-256")
            h.update(H_TCK_DOMAIN_SEPARATOR)
            h.update(rak.toByteArray())
            return TemporaryContactKey(KeyIndex(0), rvk, h.digest())
        }
}

/** A pseudorandom 128-bit value broadcast to nearby devices over Bluetooth. */
class TemporaryContactNumber internal constructor(val bytes: ByteArray) {
    init {
        require(bytes.size == 16) { "TCN must be 16 bytes, was ${bytes.size}" }
    }
}

/**
 * The index of a specific [TemporaryContactKey].
 *
 * Represents a value between zero and `2^16 - 1`.
 */
class KeyIndex(internal val short: Short) {
    @ExperimentalUnsignedTypes
    internal val uShort = short.toUShort()

    internal val bytes: ByteArray
        get() {
            val buf = ByteBuffer.allocate(2)
            buf.order(ByteOrder.LITTLE_ENDIAN)
            buf.putShort(short)
            return buf.array()
        }

    /** Returns `null` if the increment would cause the index to wrap around to zero. */
    fun checkedInc(): KeyIndex? {
        // We are representing the index internally as a Short, so we rely on
        // wrapping behaviour of Short.inc() to reach half of the index space.
        val nextIndex = short.inc()

        // If we arrive back at zero, we have wrapped the equivalent UShort.
        return if (nextIndex != 0.toShort()) {
            KeyIndex(nextIndex)
        } else {
            null
        }
    }

    internal fun dec() = KeyIndex(short.dec())
}

/** A ratcheting key used to derive temporary contact numbers. */
class TemporaryContactKey(
    internal val index: KeyIndex,
    private val rvk: Ed25519PublicKey,
    internal val tckBytes: ByteArray
) {
    init {
        require(tckBytes.size == 32) { "tckBytes must be 32 bytes, was ${tckBytes.size}" }
    }

    companion object Reader {
        /** Reads a [TemporaryContactKey] from [bytes]. */
        fun fromByteArray(bytes: ByteArray): TemporaryContactKey {
            val buf = ByteBuffer.wrap(bytes)
            buf.order(ByteOrder.LITTLE_ENDIAN)

            val index = KeyIndex(buf.short)
            val rvk = Ed25519PublicKey.fromByteArray(read32(buf))
            val tckBytes = read32(buf)

            return TemporaryContactKey(index, rvk, tckBytes)
        }
    }

    /** Serializes a [TemporaryContactKey] into a [ByteArray]. */
    fun toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(2 + 32 + 32)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        buf.putShort(index.short)
        buf.put(rvk.toByteArray())
        buf.put(tckBytes)

        return buf.array()
    }

    private var ratcheted = false

    /** The temporary contact number derived from this key. */
    val temporaryContactNumber: TemporaryContactNumber by lazy {
        val h = MessageDigest.getInstance("SHA-256")
        h.update(H_TCN_DOMAIN_SEPARATOR)
        h.update(index.bytes)
        h.update(tckBytes)
        TemporaryContactNumber(h.digest().sliceArray(0..15))
    }

    /**
     * Ratchets the key forward, producing a new key for a new temporary contact number, or `null`
     * if the report authorization key should be rotated.
     */
    fun ratchet(): TemporaryContactKey? {
        // Emulate a consuming method.
        if (ratcheted) throw IllegalStateException("key has already been ratcheted")
        ratcheted = true

        return index.checkedInc()?.let { nextIndex ->
            val h = MessageDigest.getInstance("SHA-256")
            h.update(H_TCK_DOMAIN_SEPARATOR)
            h.update(rvk.toByteArray())
            h.update(tckBytes)
            TemporaryContactKey(nextIndex, rvk, h.digest())
        }
    }
}
