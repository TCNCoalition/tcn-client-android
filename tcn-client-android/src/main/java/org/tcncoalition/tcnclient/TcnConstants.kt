package org.tcncoalition.tcnclient

import java.util.*

//  Created by Zsombor Szabo on 10/04/2020.

/** An object that contains the constants defined in the TCN protocol. */
object TcnConstants {

    /** The domain name in reverse dot notation of the TCN coalition. */
    const val DOMAIN_NAME_IN_REVERSE_DOT_NOTATION_STRING = "org.tcn-coalition"

    /** The string representation of the 0xC019 16-bit UUID of the BLE service. */
    private const val UUID_SERVICE_STRING = "0000C019-0000-1000-8000-00805F9B34FB"
    val UUID_SERVICE: UUID = UUID.fromString(UUID_SERVICE_STRING)

    /**
     * The string representation of the 128-bit UUID of the BLE characteristic exposed by the
     * primary peripheral service in connection-oriented mode.
     * */
    private const val UUID_CHARACTERISTIC_STRING = "D61F4F27-3D6B-4B04-9E46-C9D2EA617F62"
    val UUID_CHARACTERISTIC: UUID = UUID.fromString(UUID_CHARACTERISTIC_STRING)
}
