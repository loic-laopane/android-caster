package com.caster.app.discovery

import android.util.Log
import com.caster.app.model.CastDevice
import com.caster.app.model.DeviceType
import java.net.*
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DeviceDiscovery(private val listener: DiscoveryListener) {

    interface DiscoveryListener {
        fun onDeviceFound(device: CastDevice)
        fun onDeviceLost(deviceId: String)
        fun onDiscoveryError(message: String)
    }

    private val executor = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private val devices = ConcurrentHashMap<String, CastDevice>()

    fun start() {
        if (running.compareAndSet(false, true)) {
            executor.submit { discoverChromecasts() }
            executor.submit { discoverDlnaDevices() }
        }
    }

    fun stop() {
        running.set(false)
    }

    fun getDevices(): List<CastDevice> = devices.values.toList()

    private fun discoverChromecasts() {
        // mDNS query for _googlecast._tcp.local
        try {
            val group = InetAddress.getByName("224.0.0.251")
            val socket = MulticastSocket(5353)
            socket.joinGroup(group)
            socket.soTimeout = 2000

            val query = buildMdnsQuery("_googlecast._tcp.local")
            val packet = DatagramPacket(query, query.size, group, 5353)

            var retries = 0
            while (running.get() && retries < 5) {
                try {
                    socket.send(packet)
                    val buf = ByteArray(4096)
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)
                    parseMdnsResponse(response.data, response.length, response.address.hostAddress)
                } catch (e: SocketTimeoutException) {
                    retries++
                    Thread.sleep(2000)
                }
            }
            socket.leaveGroup(group)
            socket.close()
        } catch (e: IOException) {
            Log.w(TAG, "Chromecast discovery: ${e.message}")
        }
    }

    private fun discoverDlnaDevices() {
        // SSDP M-SEARCH for UPnP media renderers
        try {
            val group = InetAddress.getByName("239.255.255.250")
            val socket = MulticastSocket()
            socket.soTimeout = 3000

            val targets = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:device:MediaRenderer:2",
                "ssdp:all"
            )

            for (target in targets) {
                if (!running.get()) break
                val search = buildSsdpSearch(target)
                val packet = DatagramPacket(search.toByteArray(), search.length, group, 1900)
                socket.send(packet)
            }

            val deadline = System.currentTimeMillis() + 5000
            while (running.get() && System.currentTimeMillis() < deadline) {
                try {
                    val buf = ByteArray(8192)
                    val response = DatagramPacket(buf, buf.size)
                    socket.receive(response)
                    val data = String(response.data, 0, response.length)
                    parseSsdpResponse(data, response.address.hostAddress)
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
            socket.close()
        } catch (e: IOException) {
            Log.w(TAG, "DLNA discovery: ${e.message}")
        }
    }

    private fun buildSsdpSearch(target: String): String {
        return "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: $target\r\n\r\n"
    }

    private fun parseSsdpResponse(data: String, remoteHost: String) {
        if (!data.startsWith("HTTP/1.1 200") && !data.startsWith("NOTIFY")) return

        val headers = data.lines().associate { line ->
            val idx = line.indexOf(':')
            if (idx > 0) line.substring(0, idx).toUpperCase() to line.substring(idx + 1).trim()
            else "" to ""
        }

        val location = headers["LOCATION"] ?: return
        val usn = headers["USN"] ?: remoteHost

        executor.submit {
            fetchDlnaDeviceDescription(location, usn, remoteHost)
        }
    }

    private fun fetchDlnaDeviceDescription(location: String, usn: String, host: String) {
        try {
            val url = URL(location)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            val xml = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val name = extractXmlValue(xml, "friendlyName") ?: "Unknown Device"
            val manufacturer = extractXmlValue(xml, "manufacturer") ?: ""
            val model = extractXmlValue(xml, "modelName") ?: ""
            val port = url.port.takeIf { it > 0 } ?: 80
            val deviceId = "dlna:$host:$port"

            if (!devices.containsKey(deviceId)) {
                val device = CastDevice(
                    id = deviceId,
                    name = name,
                    host = host,
                    port = port,
                    type = DeviceType.DLNA,
                    serviceUrl = findAvTransportUrl(xml, url),
                    manufacturer = manufacturer,
                    modelName = model
                )
                devices[deviceId] = device
                listener.onDeviceFound(device)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch device description from $location: ${e.message}")
        }
    }

    private fun findAvTransportUrl(xml: String, baseUrl: URL): String {
        val serviceType = "urn:schemas-upnp-org:service:AVTransport"
        val idx = xml.indexOf(serviceType)
        if (idx < 0) return ""
        val controlStart = xml.indexOf("<controlURL>", idx)
        val controlEnd = xml.indexOf("</controlURL>", controlStart)
        if (controlStart < 0 || controlEnd < 0) return ""
        val path = xml.substring(controlStart + 12, controlEnd).trim()
        return if (path.startsWith("http")) path
        else "${baseUrl.protocol}://${baseUrl.host}:${baseUrl.port}$path"
    }

    private fun extractXmlValue(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf(close, start)
        if (end < 0) return null
        return xml.substring(start + open.length, end).trim()
            .replace("<![CDATA[", "").replace("]]>", "")
    }

    private fun buildMdnsQuery(serviceName: String): ByteArray {
        val buf = mutableListOf<Byte>()
        // Transaction ID: 0
        buf.add(0); buf.add(0)
        // Flags: standard query
        buf.add(0); buf.add(0)
        // QDCOUNT: 1
        buf.add(0); buf.add(1)
        // ANCOUNT, NSCOUNT, ARCOUNT: 0
        repeat(6) { buf.add(0) }
        // QNAME
        for (label in serviceName.split(".")) {
            buf.add(label.length.toByte())
            label.forEach { buf.add(it.toInt().toByte()) }
        }
        buf.add(0) // null label
        // QTYPE: PTR (12)
        buf.add(0); buf.add(12)
        // QCLASS: IN (1) with QU bit
        buf.add(0x80.toByte()); buf.add(1)
        return buf.toByteArray()
    }

    private fun parseMdnsResponse(data: ByteArray, length: Int, host: String) {
        // Minimal mDNS parser - look for TXT records with "fn=" (friendly name)
        val text = String(data, 0, length)
        val deviceId = "cast:$host"
        if (!devices.containsKey(deviceId)) {
            val device = CastDevice(
                id = deviceId,
                name = extractMdnsFriendlyName(data, length) ?: "Chromecast ($host)",
                host = host,
                port = 8009,
                type = DeviceType.CHROMECAST
            )
            devices[deviceId] = device
            listener.onDeviceFound(device)
        }
    }

    private fun extractMdnsFriendlyName(data: ByteArray, length: Int): String? {
        val text = String(data, 0, length, Charsets.ISO_8859_1)
        val fnIdx = text.indexOf("fn=")
        if (fnIdx < 0) return null
        var end = fnIdx + 3
        while (end < length && data[end] != 0.toByte() && data[end] != 1.toByte()) end++
        return if (end > fnIdx + 3) text.substring(fnIdx + 3, end) else null
    }

    companion object {
        private const val TAG = "DeviceDiscovery"
    }
}
