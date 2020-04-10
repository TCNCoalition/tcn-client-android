package org.tcncoalition.tcnclient.bluetooth

//  Created by Zsombor SZABO on 10/04/2020.

/** TCN Bluetooth Service callbacks. */
abstract class TcnBluetoothServiceCallback {

    /** Callback whenever the service needs a new TCN for sharing. */
    fun onTcnGenerate(): ByteArray {
        return ByteArray(0)
    }

    /** Callback whenever the service finds a TCN. */
    fun onTcnFind(tcn: ByteArray) {}

    ///** Callback whenever the service finds a TCN. */
    fun onHandleError(error: Error) {}

}
