package org.localsend.android.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.localsend.android.model.Device
import java.util.concurrent.TimeUnit

class DeviceScanner(
    private val myIp: String,
    private val myFingerprint: String = "",
    private val onDeviceFound: (Device) -> Unit
) {
    private val client = LocalSendProtocol.createTrustAllOkHttpClientBuilder()
        .connectTimeout(1200, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanJob: Job? = null

    fun scan(scope: CoroutineScope) {
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.IO) {
            val parts = myIp.split(".")
            if (parts.size != 4) return@launch
            val prefix = "${parts[0]}.${parts[1]}.${parts[2]}."

            // Use semaphore to avoid network congestion on Android Wi-Fi
            val semaphore = Semaphore(20)

            val jobs = (1..254).map { i ->
                val targetIp = "$prefix$i"
                if (targetIp == myIp) return@map null

                async {
                    semaphore.withPermit {
                        if (isActive) {
                            checkDevice(targetIp)
                        }
                    }
                }
            }.filterNotNull()

            jobs.awaitAll()
        }
    }

    private fun checkDevice(ip: String) {
        // Try HTTPS first (LocalSend default), then fallback to HTTP
        if (tryProbe(ip, "https")) return
        tryProbe(ip, "http")
    }

    private fun tryProbe(ip: String, scheme: String): Boolean {
        val url = "$scheme://$ip:${LocalSendProtocol.PORT}/api/localsend/v2/info"
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "LocalSendAndroid")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return false
                    val json = gson.fromJson(body, JsonObject::class.java) ?: return false

                    val fingerprint = json.get("fingerprint")?.asString ?: ""
                    if (fingerprint == myFingerprint && myFingerprint.isNotEmpty()) {
                        return false // Ignore self
                    }

                    val alias = json.get("alias")?.asString ?: "Thiết bị $ip"
                    val deviceModel = json.get("deviceModel")?.asString ?: "Generic"
                    val deviceType = json.get("deviceType")?.asString ?: "desktop"
                    val port = json.get("port")?.asInt ?: LocalSendProtocol.PORT
                    val reportedProtocol = json.get("protocol")?.asString ?: scheme

                    val device = Device(
                        ip = ip,
                        port = port,
                        alias = "$alias ($deviceModel)",
                        deviceModel = deviceModel,
                        deviceType = deviceType,
                        protocol = reportedProtocol,
                        fingerprint = fingerprint
                    )

                    mainHandler.post {
                        onDeviceFound(device)
                    }
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    fun stop() {
        scanJob?.cancel()
    }
}
