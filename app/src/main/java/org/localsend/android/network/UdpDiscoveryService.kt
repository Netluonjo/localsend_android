package org.localsend.android.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.localsend.android.model.Device
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import kotlin.concurrent.thread

class UdpDiscoveryService(
    private val context: Context,
    private val deviceAlias: String,
    private val fingerprint: String,
    private val port: Int = LocalSendProtocol.PORT,
    private val onDeviceFound: (Device) -> Unit
) {
    private val tag = "UdpDiscoveryService"
    private var multicastSocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var isRunning = false
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = LocalSendProtocol.createTrustAllOkHttpClientBuilder().build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        if (isRunning) return
        isRunning = true

        acquireMulticastLock()

        thread(name = "LocalSend-UdpReceiver") {
            listenForPackets()
        }

        // Broadcast initial announcement so other devices instantly discover us
        sendAnnouncement(announce = true)
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("LocalSendMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(tag, "MulticastLock acquired")
        } catch (e: Exception) {
            Log.e(tag, "Could not acquire MulticastLock", e)
        }
    }

    private fun listenForPackets() {
        try {
            val socket = MulticastSocket(LocalSendProtocol.PORT).apply {
                reuseAddress = true
                timeToLive = 255
            }
            multicastSocket = socket

            val group = InetAddress.getByName(LocalSendProtocol.MULTICAST_GROUP)
            try {
                socket.joinGroup(group)
                Log.d(tag, "Joined multicast group ${LocalSendProtocol.MULTICAST_GROUP}")
            } catch (e: Exception) {
                Log.w(tag, "Failed to join multicast group, broadcast only", e)
            }

            val buffer = ByteArray(4096)
            while (isRunning) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    val senderIp = packet.address.hostAddress ?: continue
                    val data = String(packet.data, packet.offset, packet.length, Charsets.UTF_8).trim()
                    handleReceivedPacket(senderIp, data)
                } catch (e: Exception) {
                    if (!isRunning) break
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "UDP listen error", e)
        } finally {
            closeSocket()
        }
    }

    private fun handleReceivedPacket(senderIp: String, jsonString: String) {
        try {
            val json = gson.fromJson(jsonString, JsonObject::class.java) ?: return
            val packetFingerprint = json.get("fingerprint")?.asString ?: ""

            // Ignore our own broadcast packets
            if (packetFingerprint == fingerprint || senderIp == LocalSendProtocol.getLocalIpAddress()) {
                return
            }

            val alias = json.get("alias")?.asString ?: "Thiết bị $senderIp"
            val deviceModel = json.get("deviceModel")?.asString ?: "Android"
            val deviceType = json.get("deviceType")?.asString ?: "mobile"
            val targetPort = json.get("port")?.asInt ?: LocalSendProtocol.PORT
            val protocol = json.get("protocol")?.asString ?: "https"
            val isAnnounce = json.get("announce")?.asBoolean ?: false

            val device = Device(
                ip = senderIp,
                port = targetPort,
                alias = "$alias ($deviceModel)",
                deviceModel = deviceModel,
                deviceType = deviceType,
                protocol = protocol,
                fingerprint = packetFingerprint
            )

            mainHandler.post {
                onDeviceFound(device)
            }

            // If the other device asked for an announcement, respond back with HTTP register
            if (isAnnounce) {
                scope.launch {
                    registerWithDevice(device)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error parsing UDP packet from $senderIp", e)
        }
    }

    /**
     * Send HTTP POST /api/localsend/v2/register to the peer device
     */
    private fun registerWithDevice(device: Device) {
        try {
            val url = "${device.protocol}://${device.ip}:${device.port}/api/localsend/v2/register"
            val bodyObj = JsonObject().apply {
                addProperty("alias", deviceAlias)
                addProperty("version", LocalSendProtocol.PROTOCOL_VERSION)
                addProperty("deviceModel", Build.MODEL)
                addProperty("deviceType", "mobile")
                addProperty("fingerprint", fingerprint)
                addProperty("port", port)
                addProperty("protocol", "http")
                addProperty("download", true)
            }

            val body = gson.toJson(bodyObj).toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(url).post(body).build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(tag, "Successfully registered with ${device.ip}")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to register with ${device.ip}: ${e.message}")
        }
    }

    /**
     * Broadcasts an announcement UDP packet to multicast group and subnet broadcasts
     */
    fun sendAnnouncement(announce: Boolean = true) {
        scope.launch {
            try {
                val announceObj = JsonObject().apply {
                    addProperty("alias", deviceAlias)
                    addProperty("version", LocalSendProtocol.PROTOCOL_VERSION)
                    addProperty("deviceModel", Build.MODEL)
                    addProperty("deviceType", "mobile")
                    addProperty("fingerprint", fingerprint)
                    addProperty("port", port)
                    addProperty("protocol", "http")
                    addProperty("download", true)
                    addProperty("announce", announce)
                }

                val payload = gson.toJson(announceObj).toByteArray(Charsets.UTF_8)
                val multicastAddress = InetAddress.getByName(LocalSendProtocol.MULTICAST_GROUP)
                val targetAddresses = (LocalSendProtocol.getBroadcastAddresses() + multicastAddress).distinct()

                val senderSocket = MulticastSocket().apply {
                    timeToLive = 255
                    broadcast = true
                }

                // Send 3 times with 150ms delay to overcome packet drop on Wi-Fi
                repeat(3) {
                    for (target in targetAddresses) {
                        try {
                            val packet = DatagramPacket(payload, payload.size, target, LocalSendProtocol.PORT)
                            senderSocket.send(packet)
                        } catch (_: Exception) {}
                    }
                    delay(150)
                }
                senderSocket.close()
                Log.d(tag, "Announcement broadcast completed (announce=$announce)")
            } catch (e: Exception) {
                Log.e(tag, "Error sending announcement", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        scope.cancel()
        closeSocket()
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(tag, "MulticastLock released")
            }
        } catch (_: Exception) {}
    }

    private fun closeSocket() {
        try {
            multicastSocket?.close()
        } catch (_: Exception) {}
        multicastSocket = null
    }
}
