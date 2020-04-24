package org.tcncoalition.tcnclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import org.tcncoalition.tcnclient.bluetooth.TcnBluetoothService

class ChangeOwnTcnReceiver : BroadcastReceiver() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onReceive(context: Context?, intent: Intent?) {
        // We need this receiver to trigger Application class
        Log.i("ChangeOwnTcnReceiver", "onReceive")
        context?.let { TcnBluetoothService.changeOwnTcn(it) }
    }
}