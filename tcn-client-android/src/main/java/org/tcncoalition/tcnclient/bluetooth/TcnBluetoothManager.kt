package org.tcncoalition.tcnclient.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import org.tcncoalition.tcnclient.TcnConstants
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.TimeUnit

class TcnBluetoothManager(
    private val context: Context,
    private val scanner: BluetoothLeScanner,
    private val advertiser: BluetoothLeAdvertiser,
    private val tcnCallback: TcnBluetoothServiceCallback
) {

    var bluetoothGattServer: BluetoothGattServer? = null

    private var isStarted: Boolean = false

    private var handler = Handler()

    private var generatedTcn = ByteArray(0)

    private var tcnAdvertisingQueue = ArrayList<ByteArray>()

    private var inRangeBleAddressToTcnMap: MutableMap<String, ByteArray> = mutableMapOf()

    private var estimatedDistanceToRemoteDeviceAddressMap: MutableMap<String, Double> =
        mutableMapOf()

    private var generateOwnTcnTimer: Timer? = null

    private var advertiseNextTcnTimer: Timer? = null

    fun start() {
        if (isStarted) return
        isStarted = true

        startScan()
        // Create the local GATTServer and open it once.
        initBleGattServer(
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager),
            TcnConstants.UUID_SERVICE
        )

        changeOwnTcn() // This starts advertising also
        runChangeOwnTcnTimer()
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
        estimatedDistanceToRemoteDeviceAddressMap.clear()
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
            TimeUnit.SECONDS.toMillis(20),
            TimeUnit.SECONDS.toMillis(20)
        )
    }

    private fun changeOwnTcn() {
        // Remove current TCN from the advertising queue.
        dequeueFromAdvertising(generatedTcn)
        val tcn = tcnCallback.generateTcn()
        Log.i(TAG, "Did generate TCN=${Base64.encodeToString(tcn, Base64.NO_WRAP)}")
        generatedTcn = tcn
        // Enqueue new TCN to the head of the advertising queue so it will be advertised next.
        enqueueForAdvertising(tcn, true)
        // Force restart advertising with new TCN
        stopAdvertising()
        startAdvertising()
    }

    private fun dequeueFromAdvertising(tcn: ByteArray?) {
        tcn ?: return
        tcnAdvertisingQueue.remove(tcn)
        Log.i(TAG, "Dequeued TCN=${Base64.encodeToString(tcn, Base64.NO_WRAP)} from advertising")
    }

    private fun enqueueForAdvertising(tcn: ByteArray?, atHead: Boolean = false) {
        tcn ?: return
        if (atHead) {
            tcnAdvertisingQueue.add(0, tcn)
        } else {
            tcnAdvertisingQueue.add(tcn)
        }
        Log.i(TAG, "Enqueued TCN=${Base64.encodeToString(tcn, Base64.NO_WRAP)} for advertising")
    }

    private fun startScan() {
        // Use try catch to handle DeadObject exception
        try {
            // Do not use scan filters because we wan't to keep track of every device in range
            // that writes our characteristic, and not just the ones that are advertising the TCN service.
            // (Android bridging case)
            // iOS in the background advertises differently and Android can not discover it,
            // if it scans using filters for a specific service.
//            val scanFilters = arrayOf(TcnConstants.UUID_SERVICE).map {
//                ScanFilter.Builder().setServiceUuid(ParcelUuid(it)).build()
//            }

            val scanSettings = ScanSettings.Builder().apply {
                // Low latency is important for older Android devices to be able to discover nearby
                // devices.
                setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
                // Report delay plays an important role in keeping track of the devices nearby:
                // If a 30 sec batch scan result doesn't include devices from the previous result,
                // then we assume those out of range.
                setReportDelay(TimeUnit.SECONDS.toMillis(30))
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
        }, TimeUnit.SECONDS.toMillis(90)) // This should be at least 2x of the scan settings report delay
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

            Log.d(TAG, "onBatchScanResults: ${results?.size}")

            // Search for a TCN in the service data of the advertisement
            results?.forEach for_each@{
                Log.d(TAG, "result=$it")

                val scanRecord = it.scanRecord ?: return@for_each

                val tcnServiceData = scanRecord.serviceData[
                        ParcelUuid(TcnConstants.UUID_SERVICE)]

                val hintIsAndroid = (tcnServiceData != null)

                // Update estimated distance
                val estimatedDistanceMeters = getEstimatedDistanceMeters(
                    it.rssi,
                    getMeasuredRSSIAtOneMeter(scanRecord.txPowerLevel, hintIsAndroid)
                )
                estimatedDistanceToRemoteDeviceAddressMap[it.device.address] =
                    estimatedDistanceMeters

                tcnServiceData ?: return@for_each
                if (tcnServiceData.size < TcnConstants.TEMPORARY_CONTACT_NUMBER_LENGTH) return@for_each
                val tcn =
                    tcnServiceData.sliceArray(0 until TcnConstants.TEMPORARY_CONTACT_NUMBER_LENGTH)

                Log.i(
                    TAG,
                    "Did find TCN=${Base64.encodeToString(
                        tcn,
                        Base64.NO_WRAP
                    )} from device=${it.device.address}\""
                )
                tcnCallback.onTcnFound(tcn, estimatedDistanceToRemoteDeviceAddressMap[it.device.address])
            }

            // Remove TCNs from our advertising queue that we received from devices which are now
            // out of range.
            var currentInRangeAddresses = results?.mapNotNull { it.device.address }
            if (currentInRangeAddresses == null) {
                currentInRangeAddresses = arrayListOf()
            }
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

            // Notify the API user that TCNs which are left in the list are still in range and
            // we have just found them again so it can track the duration of the contact.
            inRangeBleAddressToTcnMap.forEach {
                Log.i(
                    TAG,
                    "Did find TCN=${Base64.encodeToString(
                        it.value,
                        Base64.NO_WRAP
                    )} from device=${it.key}"
                )
                tcnCallback.onTcnFound(it.value, estimatedDistanceToRemoteDeviceAddressMap[it.key])
            }
        }
    }

    private fun startAdvertising() {
        // Use try catch to handle DeadObject exception
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(TcnConstants.UUID_SERVICE))
                .addServiceData(
                    ParcelUuid(TcnConstants.UUID_SERVICE),
                    // Attach the first 4 bytes of our TCN to work around the problem of iOS
                    // devices writing a new TCN to us whenever we rotate the TCN (every 10 sec).
                    // iOS devices use the last 4 bytes to identify the Android devices and write
                    // only once a TCN to them.
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
        bluetoothGattServer = bluetoothManager.openGattServer(context,
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

                            Log.i(
                                TAG,
                                "Did find TCN=${Base64.encodeToString(
                                    value,
                                    Base64.NO_WRAP
                                )} from device=${device?.address}"
                            )
                            tcnCallback.onTcnFound(value, estimatedDistanceToRemoteDeviceAddressMap[device?.address])
                            // TCNs received through characteristic writes come from iOS apps in the
                            // background.
                            // We act as a bridge and advertise these TCNs so iOS apps can discover
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

    companion object {
        private const val TAG = "TcnBluetoothService"
    }
}
