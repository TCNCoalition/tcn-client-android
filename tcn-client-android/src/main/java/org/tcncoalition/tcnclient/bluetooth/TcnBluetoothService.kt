package org.tcncoalition.tcnclient.bluetooth

import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.LifecycleService

class TcnBluetoothService : LifecycleService() {

    private var tcnBluetoothManager: TcnBluetoothManager? = null
    private var bluetoothStateListener: BluetoothStateListener? = null
    private val binder: IBinder = LocalBinder()
    private var isStarted = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        tcnBluetoothManager?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    fun setForegroundNotification(id: Int, notification: Notification) {
        startForeground(id, notification)
    }

    private fun BluetoothAdapter.supportsAdvertising() =
        isMultipleAdvertisementSupported && bluetoothLeAdvertiser != null

    fun setBluetoothStateListener(bluetoothStateListener: BluetoothStateListener) {
        this.bluetoothStateListener = bluetoothStateListener
    }

    fun startTcnExchange(tcnCallback: TcnBluetoothServiceCallback) {
        if (isStarted) return
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
    }

    fun stopTcnExchange() {
        if (!isStarted) return
        isStarted = false

        tcnBluetoothManager?.stop()
    }

    inner class LocalBinder : Binder() {
        val service = this@TcnBluetoothService
    }
}
