package org.localsend.android.network

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object BluetoothShareHelper {

    private const val TAG = "BluetoothShareHelper"
    private const val SHARE_DIR_NAME = "bluetooth_share"

    /**
     * Kiểm tra xem thiết bị có hỗ trợ Bluetooth hay không.
     */
    @Suppress("DEPRECATION")
    fun isBluetoothSupported(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        return adapter != null
    }

    /**
     * Kiểm tra xem Bluetooth hiện tại có đang được bật hay không.
     */
    @Suppress("DEPRECATION")
    fun isBluetoothEnabled(context: Context): Boolean {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter() ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    adapter.isEnabled
                } else {
                    // Không có quyền BLUETOOTH_CONNECT, giả định là bật hoặc để hệ thống xử lý
                    true
                }
            } else {
                adapter.isEnabled
            }
        } catch (e: Exception) {
            Log.w(TAG, "Lỗi kiểm tra trạng thái Bluetooth: ${e.message}")
            true
        }
    }

    /**
     * Mở màn hình Cài đặt Bluetooth của hệ thống.
     */
    fun openBluetoothSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Không thể mở Cài đặt Bluetooth: ${e.message}")
        }
    }

    /**
     * Chuẩn bị tệp tin để chia sẻ qua Bluetooth OPP.
     * Sao chép tệp tạm thời vào cacheDir nội bộ để FileProvider có toàn quyền cấp phép đọc
     * cho ứng dụng Bluetooth hệ thống (tránh lỗi SecurityException Permission Denial trên Android 10+).
     */
    suspend fun prepareShareableUri(context: Context, sourceUri: Uri, originalFileName: String?): Uri? = withContext(Dispatchers.IO) {
        try {
            val shareDir = File(context.cacheDir, SHARE_DIR_NAME).apply { mkdirs() }
            val cleanName = originalFileName?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                ?.takeIf { it.isNotBlank() } ?: "file_${System.currentTimeMillis()}"

            val destFile = File(shareDir, cleanName)

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi sao chép tệp để gửi Bluetooth: ${e.message}", e)
            null
        }
    }

    /**
     * Chuẩn bị một tệp văn bản từ tin nhắn để gửi qua Bluetooth.
     */
    suspend fun prepareTextShareUri(context: Context, text: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val shareDir = File(context.cacheDir, SHARE_DIR_NAME).apply { mkdirs() }
            val destFile = File(shareDir, "tin_nhan_${System.currentTimeMillis()}.txt")
            destFile.writeText(text, Charsets.UTF_8)

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi tạo tệp tin nhắn để gửi Bluetooth: ${e.message}", e)
            null
        }
    }

    /**
     * Khởi chạy Intent gửi tệp qua Bluetooth OPP của hệ thống Android.
     */
    fun launchBluetoothTransfer(
        activity: Activity,
        contentUri: Uri,
        mimeType: String = "*/*"
    ): Boolean {
        return try {
            val safeMime = if (mimeType.isBlank()) "*/*" else mimeType
            val baseIntent = Intent(Intent.ACTION_SEND).apply {
                type = safeMime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val pm = activity.packageManager
            val resolveInfos = pm.queryIntentActivities(baseIntent, PackageManager.MATCH_DEFAULT_ONLY)

            // Tìm activity Bluetooth OPP (thường là com.android.bluetooth.opp.BluetoothOppLauncherActivity)
            val btResolveInfo = resolveInfos.firstOrNull { info ->
                val pkg = info.activityInfo.packageName.lowercase()
                val name = info.activityInfo.name.lowercase()
                pkg.contains("bluetooth") || name.contains("bluetooth")
            }

            val targetIntent = if (btResolveInfo != null) {
                Intent(Intent.ACTION_SEND).apply {
                    type = safeMime
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setClassName(btResolveInfo.activityInfo.packageName, btResolveInfo.activityInfo.name)
                }
            } else {
                // Thử gói com.android.bluetooth trực tiếp
                val oppIntent = Intent(Intent.ACTION_SEND).apply {
                    type = safeMime
                    putExtra(Intent.EXTRA_STREAM, contentUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    `package` = "com.android.bluetooth"
                }

                if (oppIntent.resolveActivity(pm) != null) {
                    oppIntent
                } else {
                    // Fallback: Hộp thoại chia sẻ của hệ thống với tiêu đề Bluetooth
                    Intent.createChooser(baseIntent, "Chọn ứng dụng Bluetooth để gửi tệp").apply {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }

            val targetPkg = targetIntent.`package` ?: targetIntent.component?.packageName
            if (targetPkg != null) {
                activity.grantUriPermission(targetPkg, contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            activity.startActivity(targetIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khi khởi chạy Intent Bluetooth: ${e.message}", e)
            false
        }
    }
}
