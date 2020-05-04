package org.tcncoalition.tcnclient.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.tcncoalition.tcnclient.TcnClient
import org.tcncoalition.tcnclient.TcnConstants.WAKELOCK_DURATION
import org.tcncoalition.tcnclient.TcnConstants.WAKELOCK_TAG

class ChangeOwnTcnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            val pm = it.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            wl.acquire(WAKELOCK_DURATION.toLong())

            TcnClient.tcnManager?.changeOwnTcn()

            wl.release()
        }
    }

    companion object {
        private const val REQUEST_CODE = 42

        fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ChangeOwnTcnReceiver::class.java)
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)

            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}