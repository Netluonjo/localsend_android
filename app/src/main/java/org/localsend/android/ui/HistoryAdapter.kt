package org.localsend.android.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.localsend.android.R
import org.localsend.android.databinding.ItemHistoryBinding
import org.localsend.android.model.TransferItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class HistoryAdapter(
    private val onItemClick: (TransferItem) -> Unit = {}
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<TransferItem>()
    private val dateFormat = SimpleDateFormat("HH:mm - dd/MM", Locale.getDefault())
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun addItem(item: TransferItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(private val binding: ItemHistoryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var currentJob: Job? = null

        fun bind(item: TransferItem) {
            currentJob?.cancel()
            binding.tvHistoryFileName.text = item.name
            val sizeStr = formatFileSize(item.size)
            val direction = if (item.isIncoming) "Nhận từ" else "Gửi tới"
            val time = dateFormat.format(Date(item.timestamp))

            val isImage = isImageFile(item.name)
            val isText = item.name.startsWith("Tin nhắn:") || item.name.startsWith("Văn bản:")

            val actionHint = when {
                isImage -> "Chạm để xem ảnh"
                isText -> "Chạm để xem & sao chép"
                else -> "Chạm để mở"
            }
            binding.tvHistoryDetails.text = "$sizeStr • $direction ${item.senderOrReceiver} • $actionHint • $time"

            if (isImage && item.filePath != null) {
                val file = File(item.filePath)
                if (file.exists()) {
                    binding.imgHistoryStatus.setImageResource(android.R.drawable.stat_sys_download_done)
                    currentJob = adapterScope.launch {
                        val bitmap = withContext(Dispatchers.IO) {
                            loadSampledBitmap(item.filePath, 120, 120)
                        }
                        if (bitmap != null) {
                            binding.imgHistoryStatus.setImageBitmap(bitmap)
                        }
                    }
                } else {
                    binding.imgHistoryStatus.setImageResource(android.R.drawable.ic_menu_gallery)
                }
            } else if (isText) {
                binding.imgHistoryStatus.setImageResource(android.R.drawable.ic_menu_send)
            } else {
                binding.imgHistoryStatus.setImageResource(android.R.drawable.stat_sys_download_done)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }

        private fun isImageFile(name: String): Boolean {
            val lower = name.lowercase()
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
                    lower.endsWith(".webp") || lower.endsWith(".heic") || lower.endsWith(".gif")
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

        private fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
        }
    }
}
