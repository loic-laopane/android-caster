package com.caster.app.discovery

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.caster.app.model.CastDevice
import com.caster.app.model.ConnectionType
import com.caster.app.model.DeviceType

class BluetoothDiscovery(
    private val context: Context,
    private val listener: BluetoothListener
) {
    interface BluetoothListener {
        fun onVehicleFound(device: CastDevice)
        fun onDiscoveryFinished()
        fun onBluetoothUnavailable(reason: String)
    }

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            try {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        @Suppress("DEPRECATION")
                        val btDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        btDevice?.let { handleDevice(it) }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        listener.onDiscoveryFinished()
                    }
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "BroadcastReceiver SecurityException: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "BroadcastReceiver error: ${e.message}")
            }
        }
    }

    fun start() {
        val bt = adapter
        if (bt == null) {
            listener.onBluetoothUnavailable("Bluetooth non disponible sur cet appareil")
            return
        }
        if (!bt.isEnabled) {
            listener.onBluetoothUnavailable("Bluetooth désactivé — activez-le dans les paramètres")
            return
        }

        // Expose already-paired devices immediately
        // getBondedDevices() requires BLUETOOTH_CONNECT on API 31+ — guard it
        try {
            bt.bondedDevices?.forEach { handleDevice(it) }
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices denied — BLUETOOTH_CONNECT not granted yet")
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            context.registerReceiver(receiver, filter)
            registered = true
            bt.startDiscovery() // requires BLUETOOTH_SCAN on API 31+
            Log.d(TAG, "Bluetooth discovery started")
        } catch (e: SecurityException) {
            listener.onBluetoothUnavailable(
                "Permission Bluetooth refusee (BLUETOOTH_SCAN/CONNECT manquante). " +
                "Verifiez les permissions dans les reglages."
            )
        }
    }

    fun stop() {
        try {
            adapter?.cancelDiscovery()
        } catch (e: SecurityException) { /* ignore */ }
        if (registered) {
            try { context.unregisterReceiver(receiver) } catch (e: Exception) { /* ignore */ }
            registered = false
        }
    }

    private fun handleDevice(bt: BluetoothDevice) {
        val name = try { bt.name } catch (e: SecurityException) { null } ?: return
        val address = try { bt.address } catch (e: SecurityException) { return }

        val deviceClass = try { bt.bluetoothClass } catch (e: SecurityException) { null }
        val majorClass = deviceClass?.majorDeviceClass ?: -1

        val isCarAudio = majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        val isCarName = isCarBrandName(name)

        if (!isCarAudio && !isCarName) return

        val aaSupported = isAndroidAutoCapable(name, deviceClass)
        val device = CastDevice(
            id = "bt:$address",
            name = name,
            host = address,
            port = 5277,
            type = DeviceType.VEHICLE,
            connectionType = ConnectionType.BLUETOOTH,
            bluetoothAddress = address,
            androidAutoSupported = aaSupported,
            manufacturer = guessManufacturer(name)
        )
        listener.onVehicleFound(device)
        Log.d(TAG, "Vehicle found: $name [$address] AA=$aaSupported")
    }

    private fun isCarBrandName(name: String): Boolean {
        val lower = name.toLowerCase()
        return CAR_KEYWORDS.any { lower.contains(it) }
    }

    private fun isAndroidAutoCapable(name: String, cls: BluetoothClass?): Boolean {
        val lower = name.toLowerCase()
        // Known AA-compatible keywords in device names
        val aaKeywords = listOf("android auto", "aa wireless", "aaWireless", "motorola ma1",
            "cplay2air", "carlinkit", "ottocast", "sportage", "kia", "hyundai", "ioniq")
        if (aaKeywords.any { lower.contains(it.toLowerCase()) }) return true
        // Car audio class devices are often AA-capable on modern cars
        return cls?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
    }

    private fun guessManufacturer(name: String): String {
        val lower = name.toLowerCase()
        return CAR_MANUFACTURERS.firstOrNull { lower.contains(it.toLowerCase()) } ?: ""
    }

    companion object {
        private const val TAG = "BluetoothDiscovery"

        val CAR_KEYWORDS = listOf(
            "kia", "sportage", "hyundai", "ioniq", "tucson", "toyota", "honda",
            "bmw", "mercedes", "benz", "audi", "volkswagen", " vw ", "ford", "renault",
            "peugeot", "citroen", "nissan", "mazda", "subaru", "mitsubishi", "lexus",
            "volvo", "seat", "skoda", "dacia", "opel", "chevrolet", "cadillac",
            // Head unit / infotainment brands
            "android auto", "carplay", "uvo", "sync", "idrive", "mmb", "navi",
            "infotainment", "head unit",
            // Wireless AA dongles
            "aaWireless", "cplay2air", "carlinkit", "ottocast", "motorola ma1", "aa dongle"
        )

        val CAR_MANUFACTURERS = listOf(
            "Kia", "Hyundai", "Toyota", "Honda", "BMW", "Mercedes-Benz", "Audi",
            "Volkswagen", "Ford", "Renault", "Peugeot", "Citroën", "Nissan",
            "Mazda", "Subaru", "Mitsubishi", "Lexus", "Volvo", "Seat", "Škoda",
            "Dacia", "Opel", "Chevrolet", "Cadillac"
        )
    }
}
