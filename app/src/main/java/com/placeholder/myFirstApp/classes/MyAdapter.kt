package com.placeholder.myFirstApp.classes

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.placeholder.myFirstApp.R

class MyAdapter(
    private val devices: List<PlayerDevice>,
    private val onConnectClick: (PlayerDevice) -> Unit,
    private val onRemoveClick: (PlayerDevice) -> Unit
) : RecyclerView.Adapter<MyAdapter.DeviceViewHolder>() {

    inner class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.device_name)
        val status: TextView = view.findViewById(R.id.device_status)
        val connectButton: Button = view.findViewById(R.id.button_connect)
        val removeButton: Button = view.findViewById(R.id.button_remove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]

        // Always reset background color first, then set based on current device state
        holder.itemView.setBackgroundColor(
            if (device.isConnected) Color.GREEN else Color.TRANSPARENT
        )
        
        holder.name.text = device.name
        holder.status.text = device.status

        // set button click listeners
        holder.connectButton.setOnClickListener { onConnectClick(device) }
        holder.removeButton.setOnClickListener { onRemoveClick(device) }
    }

    override fun getItemCount() = devices.size

    // Modified method to find current position when updating
    fun updateDeviceConnectionStatus(deviceAddress: String, isConnected: Boolean) {
        val position = devices.indexOfFirst { it.address == deviceAddress }
        if (position != -1) {
            devices[position].isConnected = isConnected
            notifyItemChanged(position)
        }
    }
    
    // Method to update status for a device
    fun updateDeviceStatus(deviceAddress: String, status: String) {
        val position = devices.indexOfFirst { it.address == deviceAddress }
        if (position != -1) {
            devices[position].status = status
            notifyItemChanged(position)
        }
    }
}
