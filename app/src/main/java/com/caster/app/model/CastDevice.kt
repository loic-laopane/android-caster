package com.caster.app.model

enum class DeviceType { CHROMECAST, DLNA, AIRPLAY, VEHICLE, UNKNOWN }
enum class ConnectionType { BLUETOOTH, WIFI, WIFI_DIRECT, USB, UNKNOWN }

data class CastDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val type: DeviceType,
    val serviceUrl: String = "",
    val manufacturer: String = "",
    val modelName: String = "",
    val bluetoothAddress: String = "",
    val connectionType: ConnectionType = ConnectionType.UNKNOWN,
    val androidAutoSupported: Boolean = false
) {
    val displayName: String
        get() = if (manufacturer.isNotEmpty()) "$name ($manufacturer)" else name

    val typeLabel: String
        get() = when (type) {
            DeviceType.CHROMECAST -> "Chromecast"
            DeviceType.DLNA -> "DLNA/UPnP"
            DeviceType.AIRPLAY -> "AirPlay"
            DeviceType.VEHICLE -> when (connectionType) {
                ConnectionType.BLUETOOTH -> "Véhicule · Bluetooth"
                ConnectionType.WIFI -> "Véhicule · Wi-Fi"
                ConnectionType.WIFI_DIRECT -> "Android Auto Dongle"
                else -> "Véhicule"
            }
            DeviceType.UNKNOWN -> "Unknown"
        }
}
