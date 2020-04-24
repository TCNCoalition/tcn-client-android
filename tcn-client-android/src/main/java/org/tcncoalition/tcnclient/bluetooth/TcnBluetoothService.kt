package org.tcncoalition.tcnclient.bluetooth

import android.app.AlarmManager
import android.app.Notification
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import org.tcncoalition.tcnclient.WakeLockManager

class TcnBluetoothService : Service() {

    private var tcnBluetoothManager: TcnBluetoothManager? = null
    private var bluetoothStateListener: BluetoothStateListener? = null
    private val binder: IBinder = LocalBinder()
    private var isStarted = false
//    private lateinit var lockManager: WakeLockManager
    private val changeOwnTcn = ChangeOwnTcn()

//    override fun onCreate() {
//        super.onCreate()
//        lockManager = WakeLockManager(applicationContext.getSystemService())
//    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CHANGE_OWN_TCN -> {
                tcnBluetoothManager?.changeOwnTcn()
                changeOwnTcn.schedule(this, getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        tcnBluetoothManager?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent) = binder

    fun startForegroundNotificationIfNeeded(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(id, notification)
        }
    }

    private fun BluetoothAdapter.supportsAdvertising() =
        isMultipleAdvertisementSupported && bluetoothLeAdvertiser != null

    fun setBluetoothStateListener(bluetoothStateListener: BluetoothStateListener) {
        this.bluetoothStateListener = bluetoothStateListener
    }

    fun startTcnExchange(tcnCallback: TcnBluetoothServiceCallback) {
        if (isStarted) return
        changeOwnTcn.schedule(this, getSystemService(Context.ALARM_SERVICE) as AlarmManager)

        isStarted = true

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (!bluetoothAdapter.supportsAdvertising()) return

        val scanner = bluetoothAdapter.bluetoothLeScanner ?: return
        val advertiser = bluetoothAdapter.bluetoothLeAdvertiser ?: return

        tcnBluetoothManager = TcnBluetoothManager(
            this@TcnBluetoothService,
            scanner,
            advertiser,
            tcnCallback
        )

        tcnBluetoothManager?.start()

        registerReceiver(
            BluetoothStateReceiver() { bluetoothOn ->
                if (bluetoothOn) {
                    tcnBluetoothManager?.start()
                } else {
                    tcnBluetoothManager?.stop()
                }
                bluetoothStateListener?.bluetoothStateChanged(bluetoothOn)
            },
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        )
//        lockManager.acquire()
    }

    fun stopTcnExchange() {
        if (!isStarted) return
        isStarted = false

        tcnBluetoothManager?.stop()
//        lockManager.release()
        changeOwnTcn.cancel()
    }

    inner class LocalBinder : Binder() {
        val service = this@TcnBluetoothService
    }

    companion object {
        const val ACTION_CHANGE_OWN_TCN = "change_own_tcn"

        fun changeOwnTcn(context: Context) {
            val intent = Intent(context, TcnBluetoothService::class.java).apply {
                action = ACTION_CHANGE_OWN_TCN
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
