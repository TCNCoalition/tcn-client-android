package org.tcncoalition.tcnclient.bluetooth

import android.app.Notification
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import java.util.UUID

class TcnBluetoothService : LifecycleService() {

    private var tcnAdvertiser: TcnAdvertiser? = null
    private var tcnScanner: TcnScanner? = null
    private val binder: IBinder = LocalBinder()
    private lateinit var tcnCallback: TcnBluetoothServiceCallback

    private var tcnExchanging = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        tcnAdvertiser?.stopAdvertiser()
        tcnScanner?.stopScanning()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    private fun BluetoothAdapter.supportsAdvertising() =
        isMultipleAdvertisementSupported && bluetoothLeAdvertiser != null

    fun setForegroundNotification(notification: Notification) {
        startForeground(NOTIFICATION_ID, notification)
    }

    fun setTcnCallback(tcnCallback: TcnBluetoothServiceCallback) {
        this.tcnCallback = tcnCallback
    }

    fun startTcnExchange() {
        if (tcnExchanging) return
        tcnExchanging = true

        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        bluetoothAdapter.supportsAdvertising()
        // advertise CEN
        tcnAdvertiser = TcnAdvertiser(
            this@TcnBluetoothService,
            bluetoothAdapter.bluetoothLeAdvertiser,
            TEMPORARY_CONTACT_NUMBER_SERVICE,
            TEMPORARY_CONTACT_NUMBER_IDENTIFIER_CHARACTERISTIC,
            tcnCallback
        )

        // scan CENs
        tcnScanner = TcnScanner(
            this@TcnBluetoothService,
            bluetoothAdapter.bluetoothLeScanner,
            TEMPORARY_CONTACT_NUMBER_SERVICE,
            tcnCallback
        )

        tcnAdvertiser?.startAdvertiser(TEMPORARY_CONTACT_NUMBER_SERVICE)
        tcnScanner?.startScanning(arrayOf(TEMPORARY_CONTACT_NUMBER_SERVICE), 10)
    }

    fun updateCen() {
        tcnAdvertiser?.updateCEN()
    }

    fun stopTcnExchange() {
        if (!tcnExchanging) return
        tcnExchanging = false
        tcnAdvertiser?.stopAdvertiser()
        tcnScanner?.stopScanning()
    }

    inner class LocalBinder : Binder() {
        val service = this@TcnBluetoothService
    }

    companion object {
        const val NOTIFICATION_ID = 0

        // The string representation of the UUID for the primary peripheral service
        val TEMPORARY_CONTACT_NUMBER_SERVICE: UUID = UUID.fromString("0000C019-0000-1000-8000-00805F9B34FB")

        // The string representation of the UUID for the temporary contact number characteristic
        val TEMPORARY_CONTACT_NUMBER_IDENTIFIER_CHARACTERISTIC: UUID =
            UUID.fromString("D61F4F27-3D6B-4B04-9E46-C9D2EA617F62")
    }
}
