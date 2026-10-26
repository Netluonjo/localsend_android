package org.localsend.android.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.tls.HeldCertificate
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

object LocalSendProtocol {
    const val PORT = 53317
    const val MULTICAST_GROUP = "224.0.0.167"
    const val PROTOCOL_VERSION = "2.0"

    // Generate self-signed certificate and private key on startup for LocalSend mTLS
    val heldCertificate: HeldCertificate by lazy {
        HeldCertificate.Builder()
            .commonName("LocalSend")
            .addSubjectAlternativeName("localhost")
            .build()
    }

    // SHA-256 fingerprint in lowercase hex (matches official LocalSend specification)
    val certificateFingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256").digest(heldCertificate.certificate.encoded)
        digest.joinToString("") { "%02x".format(it) }
    }

    val trustAllTrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    // Custom KeyManager that explicitly provides our self-signed certificate during client auth (mTLS)
    // This resolves TLSV1_ALERT_CERTIFICATE_REQUIRED on iOS / macOS / Windows servers
    private val customClientKeyManager = object : X509ExtendedKeyManager() {
        private val alias = "localsend_client"

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String {
            return alias
        }

        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String {
            return alias
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> {
            return arrayOf(alias)
        }

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate> {
            return arrayOf(heldCertificate.certificate)
        }

        override fun getPrivateKey(alias: String?): PrivateKey {
            return heldCertificate.keyPair.private
        }
    }

    // TLS SSLContext equipped with our custom KeyManager for mTLS with iOS/Mac/PC
    val clientSslContext: SSLContext by lazy {
        try {
            SSLContext.getInstance("TLS").apply {
                init(arrayOf<KeyManager>(customClientKeyManager), arrayOf<TrustManager>(trustAllTrustManager), SecureRandom())
            }
        } catch (_: Exception) {
            SSLContext.getDefault()
        }
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()

            val prioritizedInterfaces = interfaces
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { intf ->
                    val name = intf.name.lowercase()
                    when {
                        name.startsWith("wlan") -> 3
                        name.startsWith("eth") || name.startsWith("en") -> 2
                        else -> 1
                    }
                }

            for (intf in prioritizedInterfaces) {
                val addresses = intf.inetAddresses.toList()
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                            return host
                        }
                    }
                }
            }

            for (intf in prioritizedInterfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (interfaceAddress in intf.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && broadcast is Inet4Address) {
                        broadcastList.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}

        try {
            broadcastList.add(InetAddress.getByName("255.255.255.255"))
        } catch (_: Exception) {}

        return broadcastList.distinct()
    }

    fun createTrustAllOkHttpClientBuilder(): OkHttpClient.Builder {
        return OkHttpClient.Builder()
            .sslSocketFactory(clientSslContext.socketFactory, trustAllTrustManager)
            .hostnameVerifier { _, _ -> true }
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
            .retryOnConnectionFailure(true)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
    }
}
