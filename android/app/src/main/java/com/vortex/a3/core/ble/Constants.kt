package com.vortex.a3.core.ble

import java.util.UUID

object Ble {
    const val V1_VERSION: Byte = 0x01


    val VORTEX_SERVICE_UUID: UUID = UUID.fromString("53ffc983-45f6-4891-a826-094ac749c063")

    val PAIRING_CONTROL_UUID: UUID = UUID.fromString("b68f4442-adad-4d7c-a944-95c57b5094c7")

    val RECONNECT_CONTROL_UUID: UUID = UUID.fromString("bd2e76f0-d216-4f3b-9704-70cc272c3072")

    val CAPABILITY_UUID: UUID = UUID.fromString("e78510a3-b39e-459f-8957-864cdb301282")

    val AUDIO_SIGNAL_UUID: UUID = UUID.fromString("c2e1c97f-3a4b-4d7e-9f0c-1e6a8b3d9c5f")

    const val ADV_PAYLOAD_LEN: Int = 10

    const val LOCAL_NAME: String = "Vortex Android"
}
