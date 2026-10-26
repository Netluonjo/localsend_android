package org.localsend.android.network

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.localsend.android.model.Device
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.UUID

sealed class SendResult {
    object Success : SendResult()
    object Declined : SendResult()
    data class Error(val message: String) : SendResult()
}

class ClientSender(
    private val senderAlias: String,
    private val senderFingerprint: String
) {
    private val tag = "ClientSender"
    private val client = LocalSendProtocol.createTrustAllOkHttpClientBuilder().build()
    private val gson = Gson()

    suspend fun sendText(device: Device, text: String): SendResult = withContext(Dispatchers.IO) {
        val fileId = UUID.randomUUID().toString()
        val textBytes = text.toByteArray(Charsets.UTF_8)
        var lastError: String? = null

        val schemes = if (device.protocol == "http") listOf("http", "https") else listOf("https", "http")

        for (scheme in schemes) {
            val prepareUrl = "$scheme://${device.ip}:${device.port}/api/localsend/v2/prepare-upload"
            val prepareBody = JsonObject().apply {
                add("info", JsonObject().apply {
                    addProperty("alias", senderAlias)
                    addProperty("version", LocalSendProtocol.PROTOCOL_VERSION)
                    addProperty("deviceModel", Build.MODEL)
                    addProperty("deviceType", "mobile")
                    addProperty("fingerprint", senderFingerprint)
                    addProperty("port", LocalSendProtocol.PORT)
                    addProperty("protocol", scheme)
                    addProperty("download", true)
                })
                val filesObj = JsonObject()
                val fileMeta = JsonObject().apply {
                    addProperty("id", fileId)
                    addProperty("fileName", "text_${System.currentTimeMillis()}.txt")
                    addProperty("size", textBytes.size.toLong())
                    addProperty("fileType", "text/plain")
                    addProperty("preview", text)
                }
                filesObj.add(fileId, fileMeta)
                add("files", filesObj)
            }

            try {
                val reqBody = gson.toJson(prepareBody).toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(prepareUrl)
                    .header("Connection", "close")
                    .header("Accept", "application/json")
                    .post(reqBody)
                    .build()

                val (code, respBody) = client.newCall(request).execute().use { response ->
                    Pair(response.code, response.body?.string() ?: "")
                }

                when (code) {
                    200 -> {
                        val respJson = gson.fromJson(respBody, JsonObject::class.java)
                        val sessionId = respJson?.get("sessionId")?.asString
                        val filesMap = respJson?.getAsJsonObject("files")
                        val token = filesMap?.get(fileId)?.asString

                        // If receiver already extracted preview directly and no token was provided, transmission is complete
                        if (token.isNullOrBlank() || sessionId.isNullOrBlank()) {
                            return@withContext SendResult.Success
                        }

                        val uploadUrl = "$scheme://${device.ip}:${device.port}/api/localsend/v2/upload?sessionId=$sessionId&fileId=$fileId&token=$token"
                        val uploadReq = Request.Builder()
                            .url(uploadUrl)
                            .header("Connection", "close")
                            .post(textBytes.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                            .build()

                        client.newCall(uploadReq).execute().use { uploadResp ->
                            if (uploadResp.isSuccessful) {
                                return@withContext SendResult.Success
                            } else {
                                lastError = "Lỗi khi gửi nội dung (HTTP ${uploadResp.code})"
                            }
                        }
                    }
                    204 -> {
                        // 204 No Content: LocalSend protocol indicates receiver received text via preview completely!
                        return@withContext SendResult.Success
                    }
                    403 -> {
                        Log.w(tag, "Recipient declined the transfer request")
                        return@withContext SendResult.Declined
                    }
                    401 -> return@withContext SendResult.Error("iPhone yêu cầu mã PIN xác nhận")
                    409 -> return@withContext SendResult.Error("iPhone đang bận phiên truyền khác")
                    else -> {
                        lastError = "Thiết bị phản hồi mã lỗi HTTP $code"
                        Log.w(tag, "prepare-upload ($scheme) returned $code: $respBody")
                    }
                }
            } catch (e: Exception) {
                lastError = when (e) {
                    is SocketTimeoutException -> "Hết thời gian chờ (iPhone chưa bấm Chấp nhận hoặc đang khóa màn hình)"
                    is ConnectException -> "Không thể kết nối (Hãy mở app LocalSend trên iPhone và mở khóa màn hình)"
                    else -> "Lỗi kết nối: ${e.message ?: e.javaClass.simpleName}"
                }
                Log.w(tag, "Error sending text to ${device.ip} via $scheme: ${e.message}", e)
            }
        }

        // Fallback for simple/legacy servers: POST /api/localsend/v2/send-text
        for (scheme in schemes) {
            try {
                val fallbackUrl = "$scheme://${device.ip}:${device.port}/api/localsend/v2/send-text"
                val fallbackReq = Request.Builder()
                    .url(fallbackUrl)
                    .header("Connection", "close")
                    .post(text.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(fallbackReq).execute().use { response ->
                    if (response.isSuccessful) return@withContext SendResult.Success
                }
            } catch (_: Exception) {}
        }

        SendResult.Error(lastError ?: "Không thể kết nối hoặc thiết bị không phản hồi")
    }

    suspend fun sendFile(
        contentResolver: ContentResolver,
        device: Device,
        uri: Uri
    ): SendResult = withContext(Dispatchers.IO) {
        val fileName = getFileName(contentResolver, uri)
        val fileSize = getFileSize(contentResolver, uri)
        val fileId = UUID.randomUUID().toString()
        val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
        val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
        var lastError: String? = null

        val schemes = if (device.protocol == "http") listOf("http", "https") else listOf("https", "http")

        for (scheme in schemes) {
            val prepareUrl = "$scheme://${device.ip}:${device.port}/api/localsend/v2/prepare-upload"
            val prepareBody = JsonObject().apply {
                add("info", JsonObject().apply {
                    addProperty("alias", senderAlias)
                    addProperty("version", LocalSendProtocol.PROTOCOL_VERSION)
                    addProperty("deviceModel", Build.MODEL)
                    addProperty("deviceType", "mobile")
                    addProperty("fingerprint", senderFingerprint)
                    addProperty("port", LocalSendProtocol.PORT)
                    addProperty("protocol", scheme)
                    addProperty("download", true)
                })
                val filesObj = JsonObject()
                val fileMeta = JsonObject().apply {
                    addProperty("id", fileId)
                    addProperty("fileName", fileName)
                    addProperty("size", fileSize)
                    addProperty("fileType", mimeType)
                }
                filesObj.add(fileId, fileMeta)
                add("files", filesObj)
            }

            var sessionId: String? = null
            var token: String? = null

            try {
                val reqBody = gson.toJson(prepareBody).toRequestBody("application/json; charset=utf-8".toMediaType())
                val prepareReq = Request.Builder()
                    .url(prepareUrl)
                    .header("Connection", "close")
                    .header("Accept", "application/json")
                    .post(reqBody)
                    .build()

                val (code, respBody) = client.newCall(prepareReq).execute().use { response ->
                    Pair(response.code, response.body?.string() ?: "")
                }

                when (code) {
                    200 -> {
                        val respJson = gson.fromJson(respBody, JsonObject::class.java)
                        sessionId = respJson?.get("sessionId")?.asString
                        token = respJson?.getAsJsonObject("files")?.get(fileId)?.asString
                    }
                    204 -> {
                        // 204 No Content: Receiver accepted or already has the file without requiring stream
                        return@withContext SendResult.Success
                    }
                    403 -> return@withContext SendResult.Declined
                    401 -> return@withContext SendResult.Error("iPhone yêu cầu mã PIN xác thực")
                    409 -> return@withContext SendResult.Error("iPhone đang bận phiên truyền khác")
                    else -> {
                        lastError = "iPhone trả về mã lỗi HTTP $code"
                        Log.w(tag, "prepare-upload ($scheme) returned $code: $respBody")
                    }
                }
            } catch (e: Exception) {
                lastError = when (e) {
                    is SocketTimeoutException -> "Hết thời gian chờ (iPhone chưa bấm 'Chấp nhận' hoặc màn hình bị khóa)"
                    is ConnectException -> "Không thể kết nối (Hãy mở app LocalSend trên iPhone và kết nối chung Wi-Fi)"
                    else -> "Lỗi kết nối: ${e.message ?: e.javaClass.simpleName}"
                }
                Log.w(tag, "prepare-upload error on $scheme: ${e.message}", e)
            }

            if (sessionId != null && token != null) {
                val uploadUrl = "$scheme://${device.ip}:${device.port}/api/localsend/v2/upload?sessionId=$sessionId&fileId=$fileId&token=$token"

                val requestBody = object : RequestBody() {
                    override fun contentType(): MediaType = mediaType
                    override fun contentLength(): Long = if (fileSize > 0) fileSize else -1L

                    override fun writeTo(sink: okio.BufferedSink) {
                        contentResolver.openInputStream(uri)?.use { input: InputStream ->
                            val buffer = ByteArray(32768)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                }

                val uploadReq = Request.Builder()
                    .url(uploadUrl)
                    .header("Connection", "close")
                    .post(requestBody)
                    .build()

                try {
                    client.newCall(uploadReq).execute().use { response ->
                        if (response.isSuccessful) {
                            return@withContext SendResult.Success
                        } else {
                            lastError = "iPhone từ chối tải tệp lên (HTTP ${response.code})"
                        }
                    }
                } catch (e: Exception) {
                    lastError = "Lỗi khi truyền dữ liệu: ${e.message ?: e.javaClass.simpleName}"
                    Log.e(tag, "Upload binary stream error: ${e.message}", e)
                }
            }
        }

        // Fallback upload
        for (scheme in schemes) {
            try {
                val uploadUrl = "$scheme://${device.ip}:${device.port}/api/localsend/v2/upload"
                val requestBody = object : RequestBody() {
                    override fun contentType(): MediaType = mediaType
                    override fun contentLength(): Long = if (fileSize > 0) fileSize else -1L

                    override fun writeTo(sink: okio.BufferedSink) {
                        contentResolver.openInputStream(uri)?.use { input: InputStream ->
                            val buffer = ByteArray(32768)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                sink.write(buffer, 0, read)
                            }
                        }
                    }
                }

                val req = Request.Builder()
                    .url(uploadUrl)
                    .header("Connection", "close")
                    .addHeader("X-File-Name", fileName)
                    .post(requestBody)
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return@withContext SendResult.Success
                }
            } catch (_: Exception) {}
        }

        SendResult.Error(lastError ?: "Gửi tệp thất bại hoặc người nhận đã ngắt kết nối")
    }

    private fun getFileName(contentResolver: ContentResolver, uri: Uri): String {
        var name = "file_${System.currentTimeMillis()}"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    val str = cursor.getString(nameIndex)
                    if (!str.isNullOrBlank()) name = str
                }
            }
        } catch (_: Exception) {}
        return name
    }

    private fun getFileSize(contentResolver: ContentResolver, uri: Uri): Long {
        var size = -1L
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        } catch (_: Exception) {}

        if (size <= 0) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    val len = afd.length
                    if (len > 0) size = len
                }
            } catch (_: Exception) {}
        }

        if (size <= 0) {
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    var count = 0L
                    val buf = ByteArray(65536)
                    var r: Int
                    while (stream.read(buf).also { r = it } != -1) {
                        count += r
                    }
                    if (count > 0) size = count
                }
            } catch (_: Exception) {}
        }

        return if (size < 0) 0L else size
    }
}
