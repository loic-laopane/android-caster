package com.caster.app.model

enum class DeviceType { CHROMECAST, DLNA, AIRPLAY, UNKNOWN }

data class CastDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: DeviceType,
    val serviceUrl: String = "",
    val manufacturer: String = "",
    val modelName: String = ""
) {
    val displayName: String
        get() = if (manufacturer.isNotEmpty()) "$name ($manufacturer)" else name

    val typeLabel: String
        get() = when (type) {
            DeviceType.CHROMECAST -> "Chromecast"
            DeviceType.DLNA -> "DLNA/UPnP"
            DeviceType.AIRPLAY -> "AirPlay"
            DeviceType.UNKNOWN -> "Unknown"
        }
}
