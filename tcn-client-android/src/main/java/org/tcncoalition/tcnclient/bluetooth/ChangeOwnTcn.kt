package org.tcncoalition.tcnclient.bluetooth

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.tcncoalition.tcnclient.ChangeOwnTcnReceiver
import org.tcncoalition.tcnclient.TcnConstants
import java.util.concurrent.TimeUnit

class ChangeOwnTcn {
    private var pendingIntent: PendingIntent? = null
    private var alarmManager: AlarmManager? = null

    fun schedule(context: Context, alarmManager: AlarmManager) {
        val intent = Intent(context, ChangeOwnTcnReceiver::class.java)

        pendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        this.alarmManager = alarmManager

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() +
                TimeUnit.MINUTES.toMillis(TcnConstants.TEMPORARY_CONTACT_NUMBER_CHANGE_TIME_INTERVAL_MINUTES),
            pendingIntent
        )
    }

    fun cancel() {
        pendingIntent?.let { alarmManager?.cancel(it) }
    }
}