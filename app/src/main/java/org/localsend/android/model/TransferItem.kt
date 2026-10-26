package org.localsend.android.model

data class TransferItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val size: Long,
    val senderOrReceiver: String,
    val isIncoming: Boolean,
    val status: String = "Hoàn tất",
    val timestamp: Long = System.currentTimeMillis(),
    val filePath: String? = null
)
