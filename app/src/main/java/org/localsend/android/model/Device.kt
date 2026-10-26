package org.localsend.android.model

data class Device(
    val ip: String,
    val port: Int = 53317,
    val alias: String,
    val deviceModel: String = "Unknown",
    val deviceType: String = "mobile",
    val protocol: String = "http",
    val fingerprint: String = "",
    val lastSeen: Long = System.currentTimeMillis()
)
