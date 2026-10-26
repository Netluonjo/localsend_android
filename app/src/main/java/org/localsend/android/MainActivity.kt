package org.localsend.android

import android.Manifest
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.localsend.android.databinding.ActivityMainBinding
import org.localsend.android.databinding.DialogImagePreviewBinding
import org.localsend.android.model.Device
import org.localsend.android.model.TransferItem
import org.localsend.android.network.*
import org.localsend.android.ui.DeviceAdapter
import org.localsend.android.ui.HistoryAdapter
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var httpServer: LocalHttpServer? = null
    private var udpDiscoveryService: UdpDiscoveryService? = null
    private var deviceScanner: DeviceScanner? = null

    private val deviceAlias = "LocalSend (${Build.MODEL})"
    private val fingerprint = LocalSendProtocol.certificateFingerprint
    private var localIp = "127.0.0.1"

    private val clientSender by lazy { ClientSender(deviceAlias, fingerprint) }

    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private var selectedFileUri: Uri? = null
    private var selectedFileName: String? = null
    private var selectedFileSize: Long = 0L
    private var pendingTextToSend: String? = null

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { onFileSelected(it) }
    }

    private val bluetoothFilePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            onFileSelected(it)
            triggerBluetoothShare()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        startBluetoothSendWorkflow()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        localIp = LocalSendProtocol.getLocalIpAddress()
        setupHeader()
        setupAdapters()
        setupNavigation()
        setupActions()
        startServer()
        startUdpDiscovery()
    }

    override fun onResume() {
        super.onResume()
        localIp = LocalSendProtocol.getLocalIpAddress()
        setupHeader()
        udpDiscoveryService?.sendAnnouncement(announce = true)
    }

    private fun setupHeader() {
        binding.tvDeviceAlias.text = deviceAlias
        binding.tvIpAddress.text = "IP: $localIp : ${LocalSendProtocol.PORT}"
        binding.tvWifiName.text = "IP Cục Bộ: $localIp"
        val webUrl = "http://$localIp:${LocalSendProtocol.PORT}"
        binding.tvWebShareUrl.text = webUrl
    }

    private fun setupAdapters() {
        deviceAdapter = DeviceAdapter { device ->
            sendToDevice(device)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter

        historyAdapter = HistoryAdapter { item ->
            onHistoryItemClicked(item)
        }
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = historyAdapter
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_receive -> {
                    binding.layoutReceiveView.visibility = View.VISIBLE
                    binding.layoutSendView.visibility = View.GONE
                    binding.layoutHistoryView.visibility = View.GONE
                    true
                }
                R.id.nav_send -> {
                    binding.layoutReceiveView.visibility = View.GONE
                    binding.layoutSendView.visibility = View.VISIBLE
                    binding.layoutHistoryView.visibility = View.GONE
                    startDeviceScan()
                    true
                }
                R.id.nav_history -> {
                    binding.layoutReceiveView.visibility = View.GONE
                    binding.layoutSendView.visibility = View.GONE
                    binding.layoutHistoryView.visibility = View.VISIBLE
                    true
                }
                else -> false
            }
        }
    }

    private fun setupActions() {
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch("*/*")
        }

        binding.btnSelectMedia.setOnClickListener {
            filePickerLauncher.launch("image/*")
        }

        binding.btnSendText.setOnClickListener {
            showSendTextDialog()
        }

        binding.btnClearSelected.setOnClickListener {
            clearSelection()
        }

        binding.btnRefreshDevices.setOnClickListener {
            startDeviceScan()
        }

        binding.btnCopyWebUrl.setOnClickListener {
            val url = "http://$localIp:${LocalSendProtocol.PORT}"
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("LocalSend Web URL", url)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Đã sao chép link Web: $url", Toast.LENGTH_SHORT).show()
        }

        binding.btnShareBluetooth.setOnClickListener {
            if (selectedFileUri != null || pendingTextToSend != null) {
                triggerBluetoothShare()
            } else {
                bluetoothFilePickerLauncher.launch("*/*")
            }
        }

        binding.btnSendSelectedViaBluetooth.setOnClickListener {
            triggerBluetoothShare()
        }

        binding.btnOpenBluetoothSettings.setOnClickListener {
            BluetoothShareHelper.openBluetoothSettings(this)
        }
    }

    private fun triggerBluetoothShare() {
        if (!BluetoothShareHelper.isBluetoothSupported(this)) {
            Toast.makeText(this, "Thiết bị không hỗ trợ tính năng Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!BluetoothShareHelper.isBluetoothEnabled(this)) {
            Toast.makeText(this, "Bluetooth đang tắt. Vui lòng bật Bluetooth để gửi tệp!", Toast.LENGTH_LONG).show()
            BluetoothShareHelper.openBluetoothSettings(this)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }

        startBluetoothSendWorkflow()
    }

    private fun startBluetoothSendWorkflow() {
        val uri = selectedFileUri
        val text = pendingTextToSend

        if (uri == null && text == null) {
            Toast.makeText(this, "Vui lòng chọn Tệp hoặc nhập Văn bản trước khi gửi qua Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Đang chuẩn bị chia sẻ qua Bluetooth...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val shareUri: Uri?
            val mimeType: String
            val itemName: String
            val itemSize: Long

            if (text != null) {
                shareUri = BluetoothShareHelper.prepareTextShareUri(this@MainActivity, text)
                mimeType = "text/plain"
                itemName = "Tin nhắn: $text"
                itemSize = text.toByteArray(Charsets.UTF_8).size.toLong()
            } else {
                shareUri = BluetoothShareHelper.prepareShareableUri(this@MainActivity, uri!!, selectedFileName)
                val resolvedMime = contentResolver.getType(uri)
                mimeType = resolvedMime ?: if (isImageFile(selectedFileName ?: "")) "image/*" else "*/*"
                itemName = selectedFileName ?: "Tệp tin"
                itemSize = selectedFileSize
            }

            if (shareUri != null) {
                val launched = BluetoothShareHelper.launchBluetoothTransfer(
                    this@MainActivity,
                    shareUri,
                    mimeType
                )
                if (launched) {
                    historyAdapter.addItem(
                        TransferItem(
                            name = itemName,
                            size = itemSize,
                            senderOrReceiver = "Bluetooth",
                            isIncoming = false,
                            status = "Đã chuyển sang Bluetooth"
                        )
                    )
                    Toast.makeText(this@MainActivity, "Đã mở hộp thoại Bluetooth. Hãy chọn thiết bị nhận!", Toast.LENGTH_LONG).show()
                    clearSelection()
                } else {
                    Toast.makeText(this@MainActivity, "Không thể mở ứng dụng Bluetooth để gửi tệp!", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this@MainActivity, "Lỗi khi chuẩn bị dữ liệu gửi qua Bluetooth!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getActiveSharedFile(): SharedFileMeta? {
        val uri = selectedFileUri
        val name = selectedFileName
        return if (uri != null && name != null) {
            SharedFileMeta(uri, name, selectedFileSize)
        } else {
            null
        }
    }

    private fun startServer() {
        httpServer = LocalHttpServer(
            context = this,
            deviceAlias = deviceAlias,
            fingerprint = fingerprint,
            port = LocalSendProtocol.PORT,
            getSharedFile = { getActiveSharedFile() },
            onDeviceRegistered = { device ->
                runOnUiThread {
                    binding.tvNoDevices.visibility = View.GONE
                    deviceAdapter.addOrUpdateDevice(device)
                }
            },
            onFileReceived = { item, file ->
                runOnUiThread {
                    historyAdapter.addItem(item)
                    if (isImageFile(file.name)) {
                        Toast.makeText(this, "Đã nhận ảnh: ${item.name}", Toast.LENGTH_SHORT).show()
                        showImagePreviewDialog(file)
                    } else {
                        Toast.makeText(this, "Đã nhận file: ${item.name}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onTextReceived = { senderName, text ->
                runOnUiThread {
                    showReceivedTextDialog(senderName, text)
                }
            }
        )
        httpServer?.start()
    }

    private fun startUdpDiscovery() {
        udpDiscoveryService = UdpDiscoveryService(
            context = this,
            deviceAlias = deviceAlias,
            fingerprint = fingerprint,
            port = LocalSendProtocol.PORT,
            onDeviceFound = { device ->
                runOnUiThread {
                    binding.tvNoDevices.visibility = View.GONE
                    deviceAdapter.addOrUpdateDevice(device)
                }
            }
        )
        udpDiscoveryService?.start()
    }

    private fun startDeviceScan() {
        localIp = LocalSendProtocol.getLocalIpAddress()
        setupHeader()

        deviceAdapter.clear()
        binding.tvNoDevices.visibility = View.VISIBLE
        binding.tvNoDevices.text = "Đang quét mạng Wi-Fi (Multicast + Subnet)..."

        udpDiscoveryService?.sendAnnouncement(announce = true)

        deviceScanner?.stop()
        deviceScanner = DeviceScanner(
            myIp = localIp,
            myFingerprint = fingerprint
        ) { device ->
            binding.tvNoDevices.visibility = View.GONE
            deviceAdapter.addOrUpdateDevice(device)
        }
        deviceScanner?.scan(lifecycleScope)
    }

    private fun onFileSelected(uri: Uri) {
        selectedFileUri = uri
        pendingTextToSend = null

        var name = "Tệp đã chọn"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                val str = cursor.getString(nameIndex)
                if (!str.isNullOrBlank()) name = str
            }
            if (sizeIndex != -1) {
                size = cursor.getLong(sizeIndex)
            }
        }

        if (size <= 0) {
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    if (afd.length > 0) size = afd.length
                }
            } catch (_: Exception) {}
        }

        selectedFileName = name
        selectedFileSize = size

        val sizeMb = String.format(Locale.getDefault(), "%.2f MB", size / (1024.0 * 1024.0))
        binding.cardSelectedItem.visibility = View.VISIBLE
        binding.tvSelectedItemName.text = "Đã chọn: $name ($sizeMb)"
    }

    private fun showSendTextDialog() {
        val input = EditText(this)
        input.hint = "Nhập văn bản cần chia sẻ..."
        input.setPadding(40, 30, 40, 30)

        AlertDialog.Builder(this)
            .setTitle("Gửi Văn Bản / Tin Nhắn")
            .setView(input)
            .setPositiveButton("Xong") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotEmpty()) {
                    pendingTextToSend = text
                    selectedFileUri = null
                    binding.cardSelectedItem.visibility = View.VISIBLE
                    binding.tvSelectedItemName.text = "Đã chọn văn bản: \"$text\""
                }
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun clearSelection() {
        selectedFileUri = null
        selectedFileName = null
        pendingTextToSend = null
        binding.cardSelectedItem.visibility = View.GONE
    }

    private fun sendToDevice(device: Device) {
        val uri = selectedFileUri
        val text = pendingTextToSend

        if (uri == null && text == null) {
            Toast.makeText(this, "Vui lòng chọn Tệp hoặc nhập Văn bản trước khi gửi!", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Đang gửi yêu cầu tới ${device.alias}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = if (text != null) {
                clientSender.sendText(device, text)
            } else {
                clientSender.sendFile(contentResolver, device, uri!!)
            }

            when (result) {
                is SendResult.Success -> {
                    val item = TransferItem(
                        name = if (text != null) "Văn bản: $text" else (selectedFileName ?: "File"),
                        size = if (text != null) text.toByteArray().size.toLong() else selectedFileSize,
                        senderOrReceiver = device.alias,
                        isIncoming = false
                    )
                    historyAdapter.addItem(item)
                    Toast.makeText(this@MainActivity, "Gửi thành công!", Toast.LENGTH_SHORT).show()
                    clearSelection()
                }
                is SendResult.Declined -> {
                    Toast.makeText(this@MainActivity, "Người nhận đã TỪ CHỐI yêu cầu nhận file!", Toast.LENGTH_LONG).show()
                }
                is SendResult.Error -> {
                    Toast.makeText(this@MainActivity, "Gửi thất bại: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showReceivedTextDialog(senderName: String, text: String) {
        AlertDialog.Builder(this)
            .setTitle("Nhận được tin nhắn mới!")
            .setMessage("Từ: $senderName\n\nNội dung:\n$text")
            .setPositiveButton("Sao chép") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("LocalSend Text", text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Đã sao chép vào bộ nhớ tạm!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Đóng", null)
            .show()

        historyAdapter.addItem(
            TransferItem(
                name = "Tin nhắn: $text",
                size = text.toByteArray().size.toLong(),
                senderOrReceiver = senderName,
                isIncoming = true
            )
        )
    }

    private fun onHistoryItemClicked(item: TransferItem) {
        val path = item.filePath
        if (path != null) {
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this, "Tệp không còn tồn tại trên máy!", Toast.LENGTH_SHORT).show()
                return
            }
            if (isImageFile(file.name)) {
                showImagePreviewDialog(file)
            } else {
                openFileWithDefaultApp(file)
            }
        } else if (item.name.startsWith("Tin nhắn:") || item.name.startsWith("Văn bản:")) {
            val text = item.name.substringAfter(":").trim()
            showReceivedTextDialog(item.senderOrReceiver, text)
        }
    }

    private fun isImageFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".gif")
    }

    private fun showImagePreviewDialog(file: File) {
        val dialog = Dialog(this)
        val previewBinding = DialogImagePreviewBinding.inflate(layoutInflater)
        dialog.setContentView(previewBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        previewBinding.tvPreviewTitle.text = file.name
        val sizeMb = String.format(Locale.getDefault(), "%.2f MB", file.length() / (1024.0 * 1024.0))
        previewBinding.tvPreviewSubtitle.text = "$sizeMb • Đã lưu vào máy"

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                loadSampledBitmap(file.absolutePath, 1080, 1080)
            }
            if (bitmap != null) {
                previewBinding.imgFullPreview.setImageBitmap(bitmap)
            }
        }

        previewBinding.btnClosePreview.setOnClickListener {
            dialog.dismiss()
        }

        previewBinding.btnOpenInGallery.setOnClickListener {
            dialog.dismiss()
            openFileWithDefaultApp(file)
        }

        previewBinding.btnShareImage.setOnClickListener {
            dialog.dismiss()
            shareFile(file)
        }

        dialog.show()
    }

    private fun openFileWithDefaultApp(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val mimeType = contentResolver.getType(uri) ?: if (isImageFile(file.name)) "image/*" else "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Mở tệp"))
        } catch (e: Exception) {
            Toast.makeText(this, "Không có ứng dụng nào để mở tệp này: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFile(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (isImageFile(file.name)) "image/*" else "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Chia sẻ tệp"))
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi khi chia sẻ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSampledBitmap(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(path, options)

            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        udpDiscoveryService?.stop()
        httpServer?.stop()
        deviceScanner?.stop()
    }
}
