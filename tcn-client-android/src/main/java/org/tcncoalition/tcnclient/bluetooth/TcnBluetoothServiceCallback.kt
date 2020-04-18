package org.tcncoalition.tcnclient.bluetooth

interface TcnBluetoothServiceCallback {

    /** Callback whenever the service needs a new TCN for sharing. */
    fun generateTcn(): ByteArray

    /** Callback whenever the service finds a TCN. */
    fun onTcnFound(tcn: ByteArray, estimatedDistance: Double? = null)
}
