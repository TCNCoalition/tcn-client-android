package org.tcncoalition.tcnclient.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresApi
import org.tcncoalition.tcnclient.TcnConstants
import java.util.concurrent.TimeUnit

/**
 * TcnBluetoothService
 *
 * A Bluetooth service that implements the TCN protocol.
 *
 * @param context The Android Context this object is constructed in.
 *
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class TcnBluetoothService(
    private val context: Context,
    var tcnBluetoothServiceCallback: TcnBluetoothServiceCallback
) {

    companion object {
        private const val TAG = "TcnBluetoothService"
    }

    private var isStarted: Boolean = false

    private var handler = Handler()

    fun start() {
        if (isStarted) {
            return
        }
        isStarted = true

        startScanner()
        startAdvertiser()
        Log.i(TAG, "Service started")
    }

    fun stop() {
        if (!isStarted) {
            return
        }
        isStarted = false

        stopScanner()
        stopAdvertiser()
        Log.i(TAG, "Service stopped")
    }

    private fun startScanner() {
        // Use try catch to catch DeadObject exception
        try {
            val bluetoothLeScanner =
                BluetoothAdapter.getDefaultAdapter().bluetoothLeScanner ?: return
            scanner = bluetoothLeScanner

            val scanFilters = arrayOf(TcnConstants.UUID_SERVICE).map {
                ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .build()

            bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback)

            Log.i(TAG, "Started scanning")
        } catch (exception: Exception) {
            Log.e(TAG, "Start scan failed: $exception")
        }

        // Bug workaround: Restart periodically so the Bluetooth daemon won't get into a broken
        // state on old Android devices.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isStarted) {
                Log.i(TAG, "Restarting scan...")
                stopScanner()
                startScanner()
            }
        }, TimeUnit.SECONDS.toMillis(10))
    }

    private fun stopScanner() {
        // Use try catch to catch DeadObject exception
        try {
            scanner?.stopScan(scanCallback)
            scanner = null
            Log.i(TAG, "Stopped scanning")
        } catch (exception: Exception) {
            Log.e(TAG, "Stop scan failed: $exception")
        }
    }

    private var scanCallback = object : ScanCallback() {

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "onScanFailed errorCode=$errorCode")
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            super.onBatchScanResults(results)
            Log.i(TAG, "onBatchScanResults")
            results?.forEach {
                Log.i(TAG, "result=$it")
                val scanRecord = it.scanRecord ?: return
                val tcn = scanRecord.serviceData[ParcelUuid(TcnConstants.UUID_SERVICE)]
                    ?: return
                tcnBluetoothServiceCallback.onTcnFind(tcn)
            }
        }
    }

    private var scanner: BluetoothLeScanner? = null


    private fun startAdvertiser() {
        // Use try catch to catch DeadObject exception
        try {
            val bluetoothLeAdvertiser =
                BluetoothAdapter.getDefaultAdapter().bluetoothLeAdvertiser ?: return
            advertiser = bluetoothLeAdvertiser

            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).let { bluetoothManager ->
                val server =
                    bluetoothManager.openGattServer(context, bluetoothGattServerCallback) ?: return
                bluetoothGattServer = server

                val service = BluetoothGattService(
                    TcnConstants.UUID_SERVICE,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
                )
                service.addCharacteristic(
                    BluetoothGattCharacteristic(
                        TcnConstants.UUID_CHARACTERISTIC,
                        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                        BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
                    )
                )

                server.clearServices()
                server.addService(service)
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build()
            advertiseSettings = settings

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(TcnConstants.UUID_SERVICE))
                .addServiceData(
                    ParcelUuid(TcnConstants.UUID_SERVICE),
                    tcnBluetoothServiceCallback.onTcnGenerate()
                )
                .build()

            bluetoothLeAdvertiser.startAdvertising(settings, data, advertisingCallback)

            Log.i(TAG, "Started advertising")
        } catch (exception: java.lang.Exception) {
            Log.e(TAG, "Start advertising failed: $exception")
        }
    }

    private fun stopAdvertiser() {
        // Use try catch to catch DeadObject exception
        try {
            bluetoothGattServer?.apply {
                clearServices()
                close()
            }
            bluetoothGattServer = null
            advertiser?.stopAdvertising(advertisingCallback)
            advertiser = null
        } catch (exception: java.lang.Exception) {
            Log.e(TAG, "Stop advertising failed: $exception")
        }

    }

    private var advertiseSettings: AdvertiseSettings? = null

    private var advertiser: BluetoothLeAdvertiser? = null

    private var bluetoothGattServer: BluetoothGattServer? = null

    private val advertisingCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "onStartSuccess settingsInEffect=$settingsInEffect")
            advertiseSettings = settingsInEffect
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "onStartFailure errorCode=$errorCode")
            // TODO
            //tcnBluetoothServiceCallback.onHandleError(errorCode)
        }
    }

    private var bluetoothGattServerCallback: BluetoothGattServerCallback =
        object : BluetoothGattServerCallback() {

            override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
                super.onServiceAdded(status, service)
                Log.i(TAG, "onServiceAdded status=$status service=$service")
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice?,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic?,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray?
            ) {
                var result = BluetoothGatt.GATT_SUCCESS
                try {
                    if (characteristic?.uuid == TcnConstants.UUID_CHARACTERISTIC) {
                        if (offset != 0) {
                            result = BluetoothGatt.GATT_INVALID_OFFSET
                            return
                        }

                        if (value == null || value.size != 16) {
                            result = BluetoothGatt.GATT_FAILURE
                            return
                        }

                        tcnBluetoothServiceCallback.onTcnFind(value)
                    } else {
                        result = BluetoothGatt.GATT_FAILURE
                    }
                } catch (exception: Exception) {
                    result = BluetoothGatt.GATT_FAILURE
                } finally {
                    Log.i(
                        TAG,
                        "onCharacteristicWriteRequest result=$result device=$device requestId=$requestId characteristic=$characteristic preparedWrite=$preparedWrite responseNeeded=$responseNeeded offset=$offset value=$value"
                    )
                    if (responseNeeded) {
                        bluetoothGattServer?.sendResponse(
                            device,
                            requestId,
                            result,
                            offset,
                            null
                        )
                    }
                }
            }
        }

}
