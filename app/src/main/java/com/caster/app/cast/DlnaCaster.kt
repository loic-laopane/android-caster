package com.caster.app.cast

import android.util.Log
import com.caster.app.model.CastDevice
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class DlnaCaster {

    private val executor = Executors.newSingleThreadExecutor()

    fun castUrl(serviceUrl: String, mediaUrl: String, mimeType: String = "video/h264",
                onSuccess: () -> Unit, onError: (String) -> Unit) {
        val device = CastDevice("_stream", "Stream", "", 0,
            com.caster.app.model.DeviceType.DLNA, serviceUrl)
        castUrl(device, mediaUrl, mimeType, onSuccess, onError)
    }

    fun stop(serviceUrl: String) {
        val device = CastDevice("_stream", "Stream", "", 0,
            com.caster.app.model.DeviceType.DLNA, serviceUrl)
        stop(device) {}
    }

    fun castUrl(device: CastDevice, mediaUrl: String, mimeType: String = "video/mp4",
                onSuccess: () -> Unit, onError: (String) -> Unit) {
        executor.submit {
            try {
                val controlUrl = device.serviceUrl.ifEmpty {
                    onError("No AVTransport URL for this device")
                    return@submit
                }

                // Step 1: SetAVTransportURI
                val setUriSoap = buildSetUriSoap(mediaUrl, mimeType)
                val setResult = postSoap(controlUrl, "SetAVTransportURI", setUriSoap)
                if (setResult < 200 || setResult >= 300) {
                    onError("SetAVTransportURI failed: HTTP $setResult")
                    return@submit
                }

                // Step 2: Play
                val playSoap = buildPlaySoap()
                val playResult = postSoap(controlUrl, "Play", playSoap)
                if (playResult < 200 || playResult >= 300) {
                    onError("Play command failed: HTTP $playResult")
                    return@submit
                }

                onSuccess()
            } catch (e: Exception) {
                Log.e(TAG, "DLNA cast error", e)
                onError(e.message ?: "Unknown error")
            }
        }
    }

    fun stop(device: CastDevice, onDone: () -> Unit) {
        executor.submit {
            try {
                val controlUrl = device.serviceUrl.ifEmpty { return@submit }
                val stopSoap = buildStopSoap()
                postSoap(controlUrl, "Stop", stopSoap)
            } catch (e: Exception) {
                Log.w(TAG, "Stop error: ${e.message}")
            } finally {
                onDone()
            }
        }
    }

    private fun postSoap(url: String, action: String, body: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
        conn.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.doOutput = true

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }

        val code = conn.responseCode
        conn.disconnect()
        return code
    }

    private fun buildSetUriSoap(uri: String, mimeType: String): String {
        val metadata = buildDidlMetadata(uri, mimeType)
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <CurrentURI>${escapeXml(uri)}</CurrentURI>
      <CurrentURIMetaData>${escapeXml(metadata)}</CurrentURIMetaData>
    </u:SetAVTransportURI>
  </s:Body>
</s:Envelope>"""
    }

    private fun buildPlaySoap(): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
      <Speed>1</Speed>
    </u:Play>
  </s:Body>
</s:Envelope>"""

    private fun buildStopSoap(): String = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"
    s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:Stop xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <InstanceID>0</InstanceID>
    </u:Stop>
  </s:Body>
</s:Envelope>"""

    private fun buildDidlMetadata(uri: String, mimeType: String): String {
        val title = uri.substringAfterLast('/').substringBefore('?').ifEmpty { "Media" }
        val upnpClass = when {
            mimeType.startsWith("video") -> "object.item.videoItem"
            mimeType.startsWith("audio") -> "object.item.audioItem.musicTrack"
            mimeType.startsWith("image") -> "object.item.imageItem.photo"
            else -> "object.item.videoItem"
        }
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
  <item id="1" parentID="0" restricted="1">
    <dc:title>${escapeXml(title)}</dc:title>
    <upnp:class>$upnpClass</upnp:class>
    <res protocolInfo="http-get:*:$mimeType:*">${escapeXml(uri)}</res>
  </item>
</DIDL-Lite>"""
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        private const val TAG = "DlnaCaster"
    }
}
