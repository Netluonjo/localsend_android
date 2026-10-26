package org.localsend.android.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.localsend.android.databinding.ItemDeviceBinding
import org.localsend.android.model.Device

class DeviceAdapter(
    private val onSendClicked: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<Device>()

    fun setDevices(newDevices: List<Device>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    fun addOrUpdateDevice(device: Device) {
        val index = devices.indexOfFirst { it.ip == device.ip }
        if (index != -1) {
            devices[index] = device
            notifyItemChanged(index)
        } else {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    fun clear() {
        devices.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position])
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: Device) {
            binding.tvDeviceName.text = device.alias
            binding.tvDeviceAddress.text = "${device.ip} : ${device.port}"
            binding.btnSendToDevice.setOnClickListener {
                onSendClicked(device)
            }
        }
    }
}
