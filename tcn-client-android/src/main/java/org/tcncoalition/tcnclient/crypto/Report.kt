package org.tcncoalition.tcnclient.crypto

import cafe.cryptography.ed25519.Ed25519PublicKey
import cafe.cryptography.ed25519.Ed25519Signature
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Describes the intended type of the contents of a memo field. */
@ExperimentalUnsignedTypes
enum class MemoType(val t: UByte) {
    /** The CoEpi symptom self-report format, version 1 (TBD) */
    CoEpiV1(0.toUByte()),

    /** The CovidWatch test data format, version 1 (TBD) */
    CovidWatchV1(1.toUByte()),

    /** Reserved for future use. */
    Reserved(0xff.toUByte());

    companion object Reader {
        /** Converts a byte into a [MemoType]. */
        fun fromByte(t: Byte): MemoType {
            return when (t.toInt()) {
                0 -> CoEpiV1
                1 -> CovidWatchV1
                else -> throw UnknownMemoType(t)
            }
        }
    }
}

/** A report of potential exposure. */
@ExperimentalUnsignedTypes
class Report(
    internal val rvk: Ed25519PublicKey,
    private val tckBytes: ByteArray,
    private val j1: KeyIndex,
    private val j2: KeyIndex,
    val memoType: MemoType,
    val memoData: ByteArray
) {
    init {
        require(tckBytes.size == 32) { "tckBytes must be 32 bytes, was ${tckBytes.size}" }
        if (j1.short == 0.toShort()) throw InvalidReportIndex()
    }

    internal fun sizeHint(): Int {
        return 32 + 32 + 2 + 2 + 1 + 1 + memoData.size
    }

    companion object Reader {
        /** Reads a [Report] from [bytes]. */
        fun fromByteArray(bytes: ByteArray): Report {
            return fromByteBuffer(ByteBuffer.wrap(bytes))
        }

        /**
         * Reads a [Report] from [buf].
         *
         * The order of [buf] will be set too [ByteOrder.LITTLE_ENDIAN].
         */
        internal fun fromByteBuffer(buf: ByteBuffer): Report {
            buf.order(ByteOrder.LITTLE_ENDIAN)

            val rvk = read32(buf)
            val tckBytes = read32(buf)
            val j1 = KeyIndex(buf.short)
            val j2 = KeyIndex(buf.short)
            val memoType = MemoType.fromByte(buf.get())
            val memoData = readCompactVec(buf)

            return Report(
                Ed25519PublicKey.fromByteArray(rvk),
                tckBytes,
                j1,
                j2,
                memoType,
                memoData
            )
        }
    }

    /** Serializes a [Report] into a [ByteArray]. */
    fun toByteArray(): ByteArray {
        val memoLen = memoData.size.toByte()
        if (memoLen.toInt() != memoData.size) throw OversizeMemo(memoData.size)

        val buf = ByteBuffer.allocate(sizeHint())
        buf.order(ByteOrder.LITTLE_ENDIAN)

        buf.put(rvk.toByteArray())
        buf.put(tckBytes)
        buf.putShort(j1.short)
        buf.putShort(j2.short)
        buf.put(memoType.t.toByte())
        buf.put(memoLen)
        buf.put(memoData)

        return buf.array()
    }

    class TemporaryContactNumberIterator(
        private var tck: TemporaryContactKey,
        private val end: KeyIndex
    ) :
        Iterator<TemporaryContactNumber> {
        override fun hasNext(): Boolean {
            return tck.index.uShort < end.uShort
        }

        override fun next(): TemporaryContactNumber {
            val tcn = tck.temporaryContactNumber
            tck = tck.ratchet()!! // We do not ratchet past end <= UShort.MAX_VALUE.
            return tcn
        }
    }

    /** An iterator over all temporary contact numbers included in the report. */
    val temporaryContactNumbers: Iterator<TemporaryContactNumber>
        get() = TemporaryContactNumberIterator(
            TemporaryContactKey(
                j1.dec(),
                rvk,
                tckBytes
            ).ratchet()!!, // j1 - 1 < j1 <= UShort.MAX_VALUE
            j2
        )
}


/**
 * Creates a report of potential exposure.
 *
 * # Inputs
 *
 * - [memoType], [memoData]: the type and data for the report's memo field.
 * - `[j1] > 0`: the ratchet index of the first temporary contact number in the report.
 * - [j2]: the ratchet index of the last temporary contact number other users should check.
 *
 * # Notes
 *
 * Creating a report reveals *all* temporary contact numbers subsequent to [j1],
 * not just up to [j2], which is included for convenience.
 *
 * The [memoData] must be less than 256 bytes long.
 *
 * Reports are unlinkable from each other **only up to the memo field**. In
 * other words, adding the same high-entropy data to the memo fields of multiple
 * reports will cause them to be linkable.
 */
@ExperimentalUnsignedTypes
fun ReportAuthorizationKey.createReport(
    memoType: MemoType,
    memoData: ByteArray,
    j1: UShort,
    j2: UShort
): SignedReport {
    // Ensure that j1 is at least 1.
    val j1Coerced = if (j1 == 0.toUShort()) {
        1.toUShort()
    } else {
        j1
    }

    // Recompute tck_{j1 - 1}. This requires recomputing j1 - 1 hashes, but
    // creating reports is done infrequently and it means we don't force the
    // caller to have saved all intermediate hashes.
    var tck = tck0;
    for (i in 0 until j1Coerced.toInt() - 1) {
        tck = tck.ratchet()!!
    }

    val report = Report(
        rvk,
        tck.tckBytes,
        KeyIndex(j1Coerced.toShort()),
        KeyIndex(j2.toShort()),
        memoType,
        memoData
    )

    return SignedReport(report, rak.expand().sign(report.toByteArray(), rvk))
}

@ExperimentalUnsignedTypes
class SignedReport(private val report: Report, private val signature: Ed25519Signature) {
    companion object Reader {
        /** Reads a [SignedReport] from [bytes]. */
        fun fromByteArray(bytes: ByteArray): SignedReport {
            val buf = ByteBuffer.wrap(bytes)
            val report = Report.fromByteBuffer(buf.slice())
            val signature = read64(buf)
            return SignedReport(report, Ed25519Signature.fromByteArray(signature))
        }
    }

    /** Serializes a [SignedReport] into a [ByteArray]. */
    fun toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(report.sizeHint() + 64)
        buf.put(report.toByteArray())
        buf.put(signature.toByteArray())
        return buf.array()
    }

    /** Verifies the source integrity of this report, producing `true` if successful. */
    fun verify(): Boolean {
        return report.rvk.verify(report.toByteArray(), signature)
    }
}
