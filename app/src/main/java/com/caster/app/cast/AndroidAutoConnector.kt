package com.caster.app.cast

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import com.caster.app.model.CastDevice
import com.caster.app.model.ConnectionType
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class AndroidAutoConnector(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    /** True if the Google Android Auto app is installed. */
    fun isAndroidAutoInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("com.google.android.projection.gearhead", 0)
        true
    } catch (e: Exception) { false }

    /**
     * Probe whether the vehicle's Android Auto port (TCP 5277) is reachable.
     * Works for wireless AA (built-in) and some dongles on the same WiFi.
     */
    fun probeAndroidAutoPort(
        host: String,
        onReachable: (String) -> Unit,
        onUnreachable: (String) -> Unit
    ) {
        executor.submit {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, 5277), 3000)
                socket.close()
                Log.d(TAG, "AA port 5277 reachable on $host")
                onReachable(host)
            } catch (e: Exception) {
                onUnreachable("Port 5277 injoignable sur $host: ${e.message}")
            }
        }
    }

    /**
     * Scan the current WiFi subnet for Android Auto dongles listening on port 5277.
     * Returns found IPs via callback.
     */
    fun scanSubnetForAndroidAuto(
        onDeviceFound: (String, String) -> Unit,
        onScanDone: () -> Unit
    ) {
        executor.submit {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val ip = wm.connectionInfo.ipAddress
                if (ip == 0) { onScanDone(); return@submit }

                // Build subnet base (e.g. 192.168.1.)
                val b4 = (ip shr 24) and 0xFF
                val b3 = (ip shr 16) and 0xFF
                val b2 = (ip shr 8) and 0xFF
                val subnet = "$b4.$b3.$b2."

                val scanExecutor = java.util.concurrent.Executors.newFixedThreadPool(32)
                val latch = java.util.concurrent.CountDownLatch(254)

                for (i in 1..254) {
                    val host = "$subnet$i"
                    scanExecutor.submit {
                        try {
                            val s = Socket()
                            s.connect(InetSocketAddress(host, 5277), 400)
                            s.close()
                            val label = resolveVehicleLabel(host)
                            onDeviceFound(host, label)
                            Log.d(TAG, "Found AA device at $host")
                        } catch (e: Exception) {
                            // not reachable on this host
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
                scanExecutor.shutdown()
            } catch (e: Exception) {
                Log.w(TAG, "Subnet scan error: ${e.message}")
            } finally {
                onScanDone()
            }
        }
    }

    /**
     * Return a human-readable label for a host found on port 5277.
     * Tries reverse DNS, falls back to the IP.
     */
    private fun resolveVehicleLabel(host: String): String = try {
        val resolved = java.net.InetAddress.getByName(host).canonicalHostName
        if (resolved != host) "Android Auto ($resolved)" else "Android Auto ($host)"
    } catch (e: Exception) {
        "Android Auto ($host)"
    }

    /**
     * Build a user-visible connection guide for the selected device.
     */
    fun connectionGuide(device: CastDevice): String {
        val aaInstalled = isAndroidAutoInstalled()
        val aaStatus = if (aaInstalled) "✓ Android Auto est installé" else "⚠ Android Auto n'est pas installé"

        fun nl(sb: StringBuilder, s: String) { sb.append(s).append('\n') }
        return when (device.connectionType) {
            ConnectionType.BLUETOOTH -> buildString {
                nl(this, "$aaStatus\n")
                nl(this, "--- Connexion via Bluetooth ---")
                nl(this, "Vehicule : ${device.name}")
                nl(this, "")
                nl(this, "1. Activez le Bluetooth du vehicule.")
                nl(this, "2. Connectez votre telephone a ${device.name}.")
                if (isKiaSportage(device.name)) {
                    nl(this, "")
                    nl(this, "--- Kia Sportage 2024 ---")
                    nl(this, "Filaire : cable USB-C sur le port USB-A de la console.")
                    nl(this, "Sans fil : Reglages > Generale > Connexion > Android Auto > Wireless.")
                    nl(this, "1er demarrage sans fil : acceptez sur l'ecran de la voiture.")
                } else {
                    nl(this, "")
                    nl(this, "3. Acceptez la connexion Android Auto sur l'ecran de la voiture.")
                    nl(this, "4. Mode sans fil : activez-le dans les reglages du vehicule.")
                }
            }
            ConnectionType.WIFI, ConnectionType.WIFI_DIRECT -> buildString {
                nl(this, "$aaStatus\n")
                nl(this, "--- Dongle Android Auto Wireless ---")
                nl(this, "Appareil detecte : ${device.name} (${device.host})")
                nl(this, "")
                nl(this, "1. Connectez le telephone au Wi-Fi du dongle.")
                nl(this, "2. Lancez Android Auto - connexion automatique.")
                nl(this, "")
                nl(this, "Dongles : AAWireless, Motorola MA1, CarLinKit, OttoCast, Cplay2air")
            }
            else -> buildString {
                nl(this, aaStatus)
                nl(this, "Connectez le vehicule via Bluetooth ou USB.")
            }
        }
    }

    /** Launch the Android Auto app if installed. */
    fun launchAndroidAuto(): Boolean {
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage("com.google.android.projection.gearhead")
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }

    private fun isKiaSportage(name: String): Boolean {
        val lower = name.toLowerCase()
        return lower.contains("kia") || lower.contains("sportage") || lower.contains("uvo")
    }

    companion object {
        private const val TAG = "AndroidAutoConnector"
    }
}
