package org.tcncoalition.tcnclient.bluetooth

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import org.tcncoalition.tcnclient.TcnConstants
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

class TcnBluetoothService(
    private val ctx: Context,
    private val scanner: BluetoothLeScanner,
    private val advertiser: BluetoothLeAdvertiser,
    private val tcnCallback: TcnBluetoothServiceCallback
) {

    companion object {
        private const val TAG = "TcnBluetoothService"
    }

    var bluetoothGattServer: BluetoothGattServer? = null

    private var isStarted: Boolean = false

    private var handler = Handler()

    private var generatedTcn = ByteArray(0)

    private var tcnAdvertisingQueue = ArrayList<ByteArray>()

    private var inRangeBleAddressToTcnMap: MutableMap<String, ByteArray> = mutableMapOf()

    private var generateOwnTcnTimer: Timer? = null

    private var advertiseNextTcnTimer: Timer? = null

    fun start() {
        if (isStarted) return
        isStarted = true

        changeOwnTcn()
        runChangeOwnTcnTimer()

        startScan()
        startAdvertising()
        // Create the local GATTServer and open it once.
        initBleGattServer(
            (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager),
            TcnConstants.UUID_SERVICE
        )

        runAdvertiseNextTcnTimer()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false

        stopScan()
        stopAdvertising()
        bluetoothGattServer?.clearServices()
        bluetoothGattServer?.close()
        bluetoothGattServer = null

        generateOwnTcnTimer?.cancel()
        generateOwnTcnTimer = null
        advertiseNextTcnTimer?.cancel()
        advertiseNextTcnTimer = null

        tcnAdvertisingQueue.clear()
        inRangeBleAddressToTcnMap.clear()
    }

    private fun runChangeOwnTcnTimer() {
        generateOwnTcnTimer?.cancel()
        generateOwnTcnTimer = Timer()
        generateOwnTcnTimer?.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    changeOwnTcn()
                }
            },
            TimeUnit.MINUTES.toMillis(TcnConstants.TEMPORARY_CONTACT_NUMBER_CHANGE_TIME_INTERVAL_MINUTES),
            TimeUnit.MINUTES.toMillis(TcnConstants.TEMPORARY_CONTACT_NUMBER_CHANGE_TIME_INTERVAL_MINUTES)
        )
    }

    private fun runAdvertiseNextTcnTimer() {
        advertiseNextTcnTimer?.cancel()
        advertiseNextTcnTimer = Timer()
        advertiseNextTcnTimer?.scheduleAtFixedRate(
            object : TimerTask() {
                override fun run() {
                    // No need to update advertised TCN if there aren't more than 1 enqueued.
                    if (tcnAdvertisingQueue.size > 1) {
                        val firstTCN = tcnAdvertisingQueue.first()
                        tcnAdvertisingQueue.removeAt(0)
                        tcnAdvertisingQueue.add(firstTCN)
                        stopAdvertising()
                        startAdvertising()
                    }
                }
            },
            TimeUnit.SECONDS.toMillis(10),
            TimeUnit.SECONDS.toMillis(10)
        )
    }

    fun changeOwnTcn() {
        // Remove current TCN from the advertising queue.
        dequeueFromAdvertising(generatedTcn)
        val tcn = tcnCallback.generateTcn()
        Log.i(TAG, "Did generate TCN=${Base64.encodeToString(tcn, Base64.DEFAULT)}")
        generatedTcn = tcn
        // Enqueue new TCN to the head of the advertising queue so it will be advertised next.
        enqueueForAdvertising(tcn, true)
        // Force restart advertising with new TCN
        stopAdvertising()
        startAdvertising()
    }

    private fun dequeueFromAdvertising(tcn: ByteArray?) {
        val tcn = tcn ?: return
        tcnAdvertisingQueue.remove(tcn)
        Log.i(TAG, "Dequeued TCN=${Base64.encodeToString(tcn, Base64.DEFAULT)} from advertising")
    }

    private fun enqueueForAdvertising(tcn: ByteArray?, atHead: Boolean = false) {
        val tcn = tcn ?: return
        if (atHead) {
            tcnAdvertisingQueue.add(0, tcn)
        } else {
            tcnAdvertisingQueue.add(tcn)
        }
        Log.i(TAG, "Enqueued TCN=${Base64.encodeToString(tcn, Base64.DEFAULT)} for advertising")
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun startScan() {
        // Use try catch to handle DeadObject exception
        try {
            // Do not use scan filters because we wan't to keep track of every device in range
            // that writes our characteristic, and not just the ones that are advertising the TCN service.
            // (Android bridging case)
//            val scanFilters = arrayOf(TcnConstants.UUID_SERVICE).map {
//                ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
//            }

            val scanSettings = ScanSettings.Builder().apply {
                // Low latency is important for older Android devices to be able to discover nearby
                // devices.
                setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                setReportDelay(TimeUnit.SECONDS.toMillis(10))
                @RequiresApi
                if (Build.VERSION_CODES.M == Build.VERSION.SDK_INT) {
                    setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                }
            }.build()

            scanner.startScan(null, scanSettings, scanCallback)
            Log.i(TAG, "Started scan")
        } catch (exception: Exception) {
            Log.e(TAG, "Start scan failed: $exception")
        }

        // Bug workaround: Restart periodically so the Bluetooth daemon won't get into a broken
        // state on old Android devices.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isStarted) {
                Log.i(TAG, "Restarting scan...")
                stopScan()
                startScan()
            }
        }, TimeUnit.SECONDS.toMillis(30))
    }

    private fun stopScan() {
        // Use try catch to handle DeadObject exception
        try {
            scanner.stopScan(scanCallback)
            Log.i(TAG, "Stopped scan")
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
            Log.d(TAG, "onBatchScanResults")

            val results = results ?: listOf<ScanResult>()

            // Remove TCNs from our advertising queue that we received from devices which are now
            // out of range.
            val currentInRangeAddresses = results.map { it.device.address }
            val addressesToRemove: MutableList<String> = mutableListOf()
            inRangeBleAddressToTcnMap.keys.forEach {
                if (!currentInRangeAddresses.contains(it)) {
                    addressesToRemove.add(it)
                }
            }
            addressesToRemove.forEach {
                val tcn = inRangeBleAddressToTcnMap[it]
                dequeueFromAdvertising(tcn)
                inRangeBleAddressToTcnMap.remove(it)
            }

            // Search for a TCN in the service data of the advertisement
            results.forEach for_each@{
                Log.d(TAG, "result=$it")

                val scanRecord = it.scanRecord ?: return@for_each
                val tcn = scanRecord.serviceData[
                        ParcelUuid(TcnConstants.UUID_SERVICE)] ?: return@for_each

                Log.d(TAG, "Did find TCN=${Base64.encodeToString(tcn, Base64.DEFAULT)}")
                tcnCallback.onTcnFound(tcn)
            }
        }
    }

    private fun startAdvertising() {
        // Use try catch to handle DeadObject exception
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(TcnConstants.UUID_SERVICE))
                .addServiceData(
                    ParcelUuid(TcnConstants.UUID_SERVICE),
                    tcnAdvertisingQueue.first() + generatedTcn.sliceArray(0..3)
                )
                .build()

            advertiser.startAdvertising(settings, data, advertisingCallback)
            Log.i(TAG, "Started advertising")
        } catch (exception: Exception) {
            Log.e(TAG, "Start advertising failed: $exception")
        }
    }

    private fun initBleGattServer(
        bluetoothManager: BluetoothManager,
        serviceUUID: UUID?
    ) {
        bluetoothGattServer = bluetoothManager.openGattServer(ctx,
            object : BluetoothGattServerCallback() {
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

                            if (value == null || value.size != TcnConstants.TEMPORARY_CONTACT_NUMBER_LENGTH) {
                                result = BluetoothGatt.GATT_FAILURE
                                return
                            }

                            Log.d(
                                TAG,
                                "Did find TCN=${Base64.encodeToString(
                                    value,
                                    Base64.DEFAULT
                                )} from device=${device?.address}"
                            )
                            tcnCallback.onTcnFound(value)
                            // TCNs received through characteristic writes come from iOS apps in the
                            // background.
                            // Act as a bridge and advertise these TCNs so iOS apps can discover
                            // each other while in the background.
                            if (device != null && inRangeBleAddressToTcnMap[device.address] == null) {
                                inRangeBleAddressToTcnMap[device.address] = value
                                enqueueForAdvertising(value)
                            }
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
            })

        val service = BluetoothGattService(
            serviceUUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        service.addCharacteristic(
            BluetoothGattCharacteristic(
                TcnConstants.UUID_CHARACTERISTIC,
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
            )
        )

        bluetoothGattServer?.clearServices()
        bluetoothGattServer?.addService(service)
    }

    private val advertisingCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.w(TAG, "onStartSuccess settingsInEffect=$settingsInEffect")
            super.onStartSuccess(settingsInEffect)
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "onStartFailure errorCode=$errorCode")
            super.onStartFailure(errorCode)
        }
    }

    private fun stopAdvertising() {
        try {
            advertiser.stopAdvertising(advertisingCallback)
            Log.i(TAG, "Stopped advertising")
        } catch (exception: Exception) {
            Log.e(TAG, "Stop advertising failed: $exception")
        }
    }
}
