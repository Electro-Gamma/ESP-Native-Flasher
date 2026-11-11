package io.espflasher.app.monitor

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.espflasher.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One serial-monitor line per row; optionally prefixed with the time it arrived. */
class MonitorAdapter : ListAdapter<MonitorLine, MonitorAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<MonitorLine>() {
            override fun areItemsTheSame(a: MonitorLine, b: MonitorLine) = a.id == b.id
            override fun areContentsTheSame(a: MonitorLine, b: MonitorLine) = a == b
        }
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private var showTimestamp = false
    private var fontSizeSp = 12f

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    fun setShowTimestamp(value: Boolean) {
        if (showTimestamp == value) return
        showTimestamp = value
        notifyDataSetChanged()
    }

    fun setFontSize(sp: Float) {
        if (fontSizeSp == sp) return
        fontSizeSp = sp
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_monitor_line, parent, false) as TextView)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = getItem(position)
        holder.textView.textSize = fontSizeSp
        holder.textView.text =
            if (showTimestamp) "${TIME_FORMAT.format(Date(line.timeMs))} -> ${line.text}" else line.text
    }
}
