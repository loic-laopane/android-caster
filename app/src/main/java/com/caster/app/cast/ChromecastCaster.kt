package com.caster.app.cast

import android.util.Log
import com.caster.app.model.CastDevice
import java.io.*
import java.net.Socket
import java.util.concurrent.Executors
import javax.net.ssl.*
import java.security.SecureRandom
import java.security.cert.X509Certificate

class ChromecastCaster {

    private val executor = Executors.newSingleThreadExecutor()
    private var socket: SSLSocket? = null

    fun connect(device: CastDevice, onConnected: () -> Unit, onError: (String) -> Unit) {
        executor.submit {
            try {
                // Chromecast uses self-signed TLS on port 8009
                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, arrayOf(TrustAllCerts), SecureRandom())
                val factory = sslContext.socketFactory
                socket = factory.createSocket(device.host, device.port) as SSLSocket
                socket?.startHandshake()
                Log.d(TAG, "Connected to ${device.name}")
                onConnected()
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                onError("Cannot connect to ${device.name}: ${e.message}")
            }
        }
    }

    fun launchApp(receiverAppId: String = DEFAULT_RECEIVER_APP_ID) {
        sendCastMessage(buildLaunchMessage(receiverAppId))
    }

    fun castMedia(mediaUrl: String, mimeType: String = "video/mp4", title: String = "") {
        sendCastMessage(buildLoadMessage(mediaUrl, mimeType, title))
    }

    fun disconnect() {
        try {
            socket?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Close error: ${e.message}")
        }
        socket = null
    }

    private fun sendCastMessage(json: String) {
        executor.submit {
            try {
                val s = socket ?: return@submit
                val payload = json.toByteArray(Charsets.UTF_8)
                // CASTV2 framing: 4-byte big-endian length prefix
                val out = DataOutputStream(s.outputStream)
                out.writeInt(payload.size)
                out.write(payload)
                out.flush()
            } catch (e: IOException) {
                Log.e(TAG, "Send error: ${e.message}")
            }
        }
    }

    private fun buildLaunchMessage(appId: String): String {
        return """{"type":"LAUNCH","appId":"$appId","requestId":1}"""
    }

    private fun buildLoadMessage(url: String, mimeType: String, title: String): String {
        val escapedUrl = url.replace("\"", "\\\"")
        val escapedTitle = title.replace("\"", "\\\"")
        return """{"type":"LOAD","requestId":2,"media":{"contentId":"$escapedUrl","contentType":"$mimeType","streamType":"BUFFERED","metadata":{"metadataType":0,"title":"$escapedTitle"}}}"""
    }

    private object TrustAllCerts : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val TAG = "ChromecastCaster"
        const val DEFAULT_RECEIVER_APP_ID = "CC1AD845"
    }
}
