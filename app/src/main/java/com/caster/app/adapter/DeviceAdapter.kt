package com.caster.app.adapter

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.caster.app.model.CastDevice
import com.caster.app.model.DeviceType

class DeviceAdapter(private val context: Context) : BaseAdapter() {

    private val devices = mutableListOf<CastDevice>()
    private var selectedId: String? = null

    fun updateDevices(list: List<CastDevice>) {
        devices.clear()
        devices.addAll(list)
        notifyDataSetChanged()
    }

    fun addDevice(device: CastDevice) {
        val idx = devices.indexOfFirst { it.id == device.id }
        if (idx >= 0) devices[idx] = device else devices.add(device)
        notifyDataSetChanged()
    }

    fun removeDevice(id: String) {
        devices.removeAll { it.id == id }
        notifyDataSetChanged()
    }

    fun setSelected(id: String?) {
        selectedId = id
        notifyDataSetChanged()
    }

    fun getSelectedDevice(): CastDevice? = devices.find { it.id == selectedId }

    override fun getCount() = devices.size
    override fun getItem(position: Int) = devices[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val device = devices[position]
        val isSelected = device.id == selectedId

        val dp = context.resources.displayMetrics.density
        val p = (12 * dp).toInt()

        val layout = (convertView as? LinearLayout) ?: LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        layout.setPadding(p, p, p, p)
        layout.removeAllViews()
        layout.setBackgroundColor(if (isSelected) 0xFF1C3A6E.toInt() else 0xFF111428.toInt())

        val nameView = TextView(context).apply {
            text = device.displayName
            textSize = 16f
            setTextColor(if (isSelected) 0xFF00D4FF.toInt() else 0xFFFFFFFF.toInt())
        }

        val infoView = TextView(context).apply {
            text = "${device.typeLabel} · ${device.host}"
            textSize = 13f
            setTextColor(if (isSelected) 0xFF90CAF9.toInt() else 0xFFB8C0E8.toInt())
        }

        layout.addView(nameView)
        layout.addView(infoView)

        return layout
    }
}
