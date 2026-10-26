package org.localsend.android.network

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.localsend.android.model.Device
import org.localsend.android.model.TransferItem
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class SharedFileMeta(
    val uri: Uri,
    val name: String,
    val size: Long
)

class LocalHttpServer(
    private val context: Context,
    private val deviceAlias: String,
    val fingerprint: String,
    private val port: Int = LocalSendProtocol.PORT,
    private val getSharedFile: () -> SharedFileMeta? = { null },
    private val onDeviceRegistered: (Device) -> Unit,
    private val onFileReceived: (TransferItem, File) -> Unit,
    private val onTextReceived: (String, String) -> Unit
) {
    private val tag = "LocalHttpServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()
    private var cachedWebHtml: String? = null

    data class PendingFileMeta(
        val fileId: String,
        val fileName: String,
        val size: Long,
        val fileType: String,
        val token: String,
        val preview: String?
    )

    data class ActiveSession(
        val sessionId: String,
        val senderAlias: String,
        val files: ConcurrentHashMap<String, PendingFileMeta>
    )

    private val sessions = ConcurrentHashMap<String, ActiveSession>()

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(name = "LocalSend-HttpServer") {
            try {
                serverSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                Log.d(tag, "HTTP Server started on port $port")
                while (isRunning) {
                    val client = serverSocket?.accept() ?: break
                    thread { handleClient(client) }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(tag, "Server socket error", e)
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        val clientIp = socket.inetAddress?.hostAddress ?: "Unknown"
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val rawOutput = BufferedOutputStream(socket.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]

            val path = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
            val queryParams = parseQueryParams(fullPath)

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L

            when {
                // ==============================
                // A. WEB SHARE INTERFACE (Safari / Chrome)
                // ==============================
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val html = loadWebShareHtml()
                    sendHtmlResponse(rawOutput, html)
                }

                method == "GET" && path == "/favicon.ico" -> {
                    sendNoContent(rawOutput)
                }

                // Status & active shared file check for Safari Web UI
                method == "GET" && path.startsWith("/api/web/status") -> {
                    val shared = getSharedFile()
                    val statusObj = JsonObject().apply {
                        addProperty("alias", deviceAlias)
                        addProperty("model", Build.MODEL)
                        addProperty("version", LocalSendProtocol.PROTOCOL_VERSION)
                        if (shared != null) {
                            val fileObj = JsonObject().apply {
                                addProperty("name", shared.name)
                                addProperty("size", shared.size)
                                addProperty("formattedSize", formatFileSize(shared.size))
                            }
                            add("sharedFile", fileObj)
                        } else {
                            add("sharedFile", null)
                        }
                    }
                    sendJsonResponse(rawOutput, 200, gson.toJson(statusObj))
                }

                // Download active file from Android to Safari
                method == "GET" && path.startsWith("/api/web/download") -> {
                    val shared = getSharedFile()
                    if (shared != null) {
                        val stream = try {
                            context.contentResolver.openInputStream(shared.uri)
                        } catch (e: Exception) {
                            Log.e(tag, "Error opening shared file stream", e)
                            null
                        }
                        if (stream != null) {
                            stream.use { s ->
                                sendFileResponse(rawOutput, shared.name, shared.size, s)
                            }
                        } else {
                            sendJsonResponse(rawOutput, 404, "{\"error\":\"Không thể mở tệp\"}")
                        }
                    } else {
                        sendJsonResponse(rawOutput, 404, "{\"error\":\"Không có tệp nào đang chia sẻ\"}")
                    }
                }

                // Upload file from Safari to Android
                method == "POST" && path.startsWith("/api/web/upload") -> {
                    val rawName = queryParams["name"] ?: headers["x-file-name"] ?: "safari_${System.currentTimeMillis()}.bin"
                    val fileName = try {
                        URLDecoder.decode(rawName, "UTF-8")
                    } catch (_: Exception) {
                        rawName
                    }

                    val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                        ?: context.filesDir
                    targetDir.mkdirs()

                    val safeFile = getUniqueFile(targetDir, fileName)

                    var remaining = contentLength
                    FileOutputStream(safeFile).use { fos ->
                        val buffer = ByteArray(32768)
                        while (remaining > 0) {
                            val toRead = Math.min(buffer.size.toLong(), remaining).toInt()
                            val read = input.read(buffer, 0, toRead)
                            if (read == -1) break
                            fos.write(buffer, 0, read)
                            remaining -= read
                        }
                    }

                    saveToGalleryIfImage(safeFile)

                    val item = TransferItem(
                        name = safeFile.name,
                        size = safeFile.length(),
                        senderOrReceiver = "Safari ($clientIp)",
                        isIncoming = true,
                        filePath = safeFile.absolutePath
                    )

                    mainHandler.post {
                        onFileReceived(item, safeFile)
                    }

                    val resp = JsonObject().apply {
                        addProperty("status", "ok")
                        addProperty("fileName", safeFile.name)
                        addProperty("size", safeFile.length())
                    }
                    sendJsonResponse(rawOutput, 200, gson.toJson(resp))
                }

                // Send text message / quick note from Safari to Android
                method == "POST" && path.startsWith("/api/web/send-text") -> {
                    val body = readBodyString(input, contentLength.toInt())
                    var textToSend = body
                    try {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        if (json != null && json.has("text")) {
                            textToSend = json.get("text").asString
                        }
                    } catch (_: Exception) {}

                    mainHandler.post {
                        onTextReceived("Safari ($clientIp)", textToSend)
                    }

                    val resp = JsonObject().apply {
                        addProperty("status", "ok")
                    }
                    sendJsonResponse(rawOutput, 200, gson.toJson(resp))
                }

                // ==============================
                // B. LOCALSEND PROTOCOL V2 API
                // ==============================
                // 1. Peer Info
                path.startsWith("/api/localsend/v2/info") -> {
                    val infoObj = JsonObject().apply {
                        addProperty("alias", deviceAlias)
                        addProperty("version", LocalSendProtocol.PROTOCOL_VERSION)
                        addProperty("deviceModel", Build.MODEL)
                        addProperty("deviceType", "mobile")
                        addProperty("fingerprint", fingerprint)
                        addProperty("port", port)
                        addProperty("protocol", "http")
                        addProperty("download", true)
                    }
                    sendJsonResponse(rawOutput, 200, gson.toJson(infoObj))
                }

                // 2. Peer Registration (LocalSend discovery response)
                path.startsWith("/api/localsend/v2/register") -> {
                    val body = readBodyString(input, contentLength.toInt())
                    try {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        if (json != null) {
                            val regAlias = json.get("alias")?.asString ?: "Thiết bị $clientIp"
                            val regModel = json.get("deviceModel")?.asString ?: "Unknown"
                            val regType = json.get("deviceType")?.asString ?: "desktop"
                            val regPort = json.get("port")?.asInt ?: port
                            val regProtocol = json.get("protocol")?.asString ?: "https"
                            val regFingerprint = json.get("fingerprint")?.asString ?: ""

                            val device = Device(
                                ip = clientIp,
                                port = regPort,
                                alias = "$regAlias ($regModel)",
                                deviceModel = regModel,
                                deviceType = regType,
                                protocol = regProtocol,
                                fingerprint = regFingerprint
                            )
                            mainHandler.post {
                                onDeviceRegistered(device)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error handling register body", e)
                    }

                    val respObj = JsonObject().apply {
                        addProperty("alias", deviceAlias)
                        addProperty("version", LocalSendProtocol.PROTOCOL_VERSION)
                        addProperty("deviceModel", Build.MODEL)
                        addProperty("deviceType", "mobile")
                        addProperty("fingerprint", fingerprint)
                        addProperty("port", port)
                        addProperty("protocol", "http")
                        addProperty("download", true)
                    }
                    sendJsonResponse(rawOutput, 200, gson.toJson(respObj))
                }

                // 3. Prepare Upload
                path.startsWith("/api/localsend/v2/prepare-upload") -> {
                    val body = readBodyString(input, contentLength.toInt())
                    val sessionId = UUID.randomUUID().toString()
                    val filesTokenMap = JsonObject()
                    val filesMeta = ConcurrentHashMap<String, PendingFileMeta>()

                    var senderAlias = clientIp
                    try {
                        val json = gson.fromJson(body, JsonObject::class.java)
                        val info = json?.getAsJsonObject("info")
                        if (info != null) {
                            val alias = info.get("alias")?.asString
                            if (!alias.isNullOrBlank()) {
                                senderAlias = alias
                            }
                        }

                        val filesObj = json?.getAsJsonObject("files")
                        if (filesObj != null) {
                            for (entry in filesObj.entrySet()) {
                                val fileId = entry.key
                                val fileData = entry.value.asJsonObject
                                val fileName = fileData.get("fileName")?.asString ?: "file_${System.currentTimeMillis()}"
                                val size = fileData.get("size")?.asLong ?: 0L
                                val fileType = fileData.get("fileType")?.asString ?: "application/octet-stream"
                                val preview = fileData.get("preview")?.asString
                                val token = UUID.randomUUID().toString()

                                filesTokenMap.addProperty(fileId, token)
                                filesMeta[fileId] = PendingFileMeta(
                                    fileId = fileId,
                                    fileName = fileName,
                                    size = size,
                                    fileType = fileType,
                                    token = token,
                                    preview = preview
                                )

                                if (preview != null && fileType.startsWith("text/")) {
                                    mainHandler.post {
                                        onTextReceived(senderAlias, preview)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error parsing prepare-upload", e)
                    }

                    sessions[sessionId] = ActiveSession(
                        sessionId = sessionId,
                        senderAlias = senderAlias,
                        files = filesMeta
                    )

                    val respObj = JsonObject().apply {
                        addProperty("sessionId", sessionId)
                        add("files", filesTokenMap)
                    }
                    sendJsonResponse(rawOutput, 200, gson.toJson(respObj))
                }

                // 4. File / Text Upload (LocalSend P2P)
                path.startsWith("/api/localsend/v2/upload") || path.startsWith("/api/localsend/v2/send-text") -> {
                    if (path.contains("send-text") || headers["content-type"]?.contains("text") == true) {
                        val text = readBodyString(input, contentLength.toInt())
                        sendJsonResponse(rawOutput, 200, "{\"status\":\"ok\"}")
                        mainHandler.post {
                            onTextReceived(clientIp, text)
                        }
                    } else {
                        val sessionId = queryParams["sessionId"]
                        val fileId = queryParams["fileId"]
                        val activeSession = sessionId?.let { sessions[it] }
                        val meta = if (activeSession != null && fileId != null) activeSession.files[fileId] else null

                        val originalName = meta?.fileName
                            ?: headers["x-file-name"]
                            ?: "received_${System.currentTimeMillis()}.bin"

                        val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                            ?: context.filesDir
                        targetDir.mkdirs()

                        val safeFile = getUniqueFile(targetDir, originalName)

                        FileOutputStream(safeFile).use { fos ->
                            val buffer = ByteArray(32768)
                            var remaining = contentLength
                            while (remaining > 0) {
                                val toRead = Math.min(buffer.size.toLong(), remaining).toInt()
                                val read = input.read(buffer, 0, toRead)
                                if (read == -1) break
                                fos.write(buffer, 0, read)
                                remaining -= read
                            }
                        }

                        saveToGalleryIfImage(safeFile)

                        val senderName = activeSession?.senderAlias ?: clientIp
                        val item = TransferItem(
                            name = safeFile.name,
                            size = safeFile.length(),
                            senderOrReceiver = senderName,
                            isIncoming = true,
                            filePath = safeFile.absolutePath
                        )

                        sendJsonResponse(rawOutput, 200, "{\"status\":\"ok\"}")
                        mainHandler.post {
                            onFileReceived(item, safeFile)
                        }
                    }
                }

                // CORS Options preflight
                method == "OPTIONS" -> {
                    sendCorsPreflightResponse(rawOutput)
                }

                else -> {
                    sendJsonResponse(rawOutput, 404, "{\"error\":\"Not Found\"}")
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Client handler exception", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun loadWebShareHtml(): String {
        val template = cachedWebHtml ?: run {
            try {
                context.assets.open("web_share.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                Log.e(tag, "Failed to read web_share.html from assets", e)
                "<!DOCTYPE html><html><body><h1>LocalSend Web Share</h1><p>Thiết bị: $deviceAlias</p></body></html>"
            }
        }.also { cachedWebHtml = it }

        return template
            .replace("{{DEVICE_ALIAS}}", deviceAlias)
            .replace("{{DEVICE_MODEL}}", Build.MODEL)
            .replace("{{PORT}}", port.toString())
    }

    private fun getUniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file

        val nameWithoutExt = name.substringBeforeLast(".")
        val ext = if (name.contains(".")) ".${name.substringAfterLast(".")}" else ""
        var counter = 1
        while (file.exists()) {
            file = File(dir, "${nameWithoutExt}_$counter$ext")
            counter++
        }
        return file
    }

    private fun parseQueryParams(uri: String): Map<String, String> {
        val queryIndex = uri.indexOf('?')
        if (queryIndex == -1) return emptyMap()
        val query = uri.substring(queryIndex + 1)
        val params = mutableMapOf<String, String>()
        for (pair in query.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                params[key] = value
            }
        }
        return params
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readBodyString(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(bytes, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return String(bytes, 0, offset, Charsets.UTF_8)
    }

    private fun sendHtmlResponse(out: OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendJsonResponse(out: OutputStream, code: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun sendNoContent(out: OutputStream) {
        val header = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sendFileResponse(out: OutputStream, fileName: String, fileSize: Long, inputStream: InputStream) {
        val encodedName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val sb = StringBuilder()
        sb.append("HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: application/octet-stream\r\n")
        sb.append("Content-Disposition: attachment; filename=\"$encodedName\"; filename*=UTF-8''$encodedName\r\n")
        if (fileSize > 0) {
            sb.append("Content-Length: $fileSize\r\n")
        }
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))

        val buffer = ByteArray(32768)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            out.write(buffer, 0, bytesRead)
        }
        out.flush()
    }

    private fun sendCorsPreflightResponse(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: *\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(
            "%.2f %s",
            bytes / Math.pow(1024.0, digitGroups.toDouble()),
            units[digitGroups]
        )
    }

    private fun saveToGalleryIfImage(file: File) {
        val name = file.name.lowercase()
        val isImage = name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") ||
                name.endsWith(".webp") || name.endsWith(".heic") || name.endsWith(".gif")
        if (!isImage) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.Images.Media.MIME_TYPE, getMimeType(file.name))
                    put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LocalSend")
                    put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input ->
                            input.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    Log.d(tag, "Successfully saved image to Gallery MediaStore: $uri")
                }
            } else {
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            }
        } catch (e: Exception) {
            Log.e(tag, "Error saving image to Gallery MediaStore", e)
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic" -> "image/heic"
            else -> "image/jpeg"
        }
    }
}
