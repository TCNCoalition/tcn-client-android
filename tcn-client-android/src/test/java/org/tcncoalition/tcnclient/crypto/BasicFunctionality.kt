package org.tcncoalition.tcnclient.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

class BasicFunctionality {
    @ExperimentalUnsignedTypes
    @Test
    fun generateTemporaryContactNumbersAndReportThem() {
        // Generate a report authorization key.  This key represents the capability
        // to publish a report about a collection of derived temporary contact numbers.
        val rak = ReportAuthorizationKey(SecureRandom.getInstanceStrong())

        // Use the temporary contact key ratchet mechanism to compute a list
        // of temporary contact numbers.
        var tck = rak.initialTemporaryContactKey // tck <- tck_1
        val tcns = (0..100).map {
            val tcn = tck.temporaryContactNumber
            tck = tck.ratchet()!!
            tcn
        }

        // Prepare a report about a subset of the temporary contact numbers.
        val signedReport = rak.createReport(
            MemoType.CoEpiV1,
            "symptom data".toByteArray(Charsets.UTF_8),
            20.toUShort(),
            90.toUShort()
        )

        // Verify the source integrity of the report...
        val report = signedReport.verify()

        // ...allowing the disclosed TCNs to be recomputed.
        val recomputedTcns = report.temporaryContactNumbers.asSequence().toList()

        // Check that the recomputed TCNs match the originals.
        // The slice is offset by 1 because tcn_0 is not included.
        assertEquals(tcns.slice(20 - 1 until 90 - 1), recomputedTcns)
    }

    @ExperimentalUnsignedTypes
    @Test
    fun basicReadWriteRoundTrip() {
        val rak = ReportAuthorizationKey(SecureRandom.getInstanceStrong())

        val rakBuf1 = rak.toByteArray()
        val rakBuf2 = ReportAuthorizationKey.fromByteArray(rakBuf1).toByteArray()
        assertArrayEquals(rakBuf1, rakBuf2)

        val tck = rak.initialTemporaryContactKey

        val tckBuf1 = tck.toByteArray()
        val tckBuf2 = TemporaryContactKey.fromByteArray(tckBuf1).toByteArray()
        assertArrayEquals(tckBuf1, tckBuf2)

        val signedReport = rak.createReport(
            MemoType.CoEpiV1,
            "symptom data".toByteArray(Charsets.UTF_8),
            20.toUShort(),
            100.toUShort()
        )

        val signedBuf1 = signedReport.toByteArray()
        val signedBuf2 = SignedReport.fromByteArray(signedBuf1).toByteArray()
        assertArrayEquals(signedBuf1, signedBuf2)

        val report = signedReport.verify()

        val reportBuf1 = report.toByteArray()
        val reportBuf2 = Report.fromByteArray(reportBuf1).toByteArray()
        assertArrayEquals(reportBuf1, reportBuf2)
    }
}