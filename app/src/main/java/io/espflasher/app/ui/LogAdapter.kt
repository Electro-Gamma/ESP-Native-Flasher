package io.espflasher.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.espflasher.app.R
import io.espflasher.app.flashing.LogEntry
import io.espflasher.app.flashing.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders the console log, color-coding each line by [LogLevel]. */
class LogAdapter(private var fontSizeSp: Float = 12f) : ListAdapter<LogEntry, LogAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LogEntry>() {
            override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem === newItem
            override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry) = oldItem == newItem
        }
        private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun setFontSize(sp: Float) {
        fontSizeSp = sp
        notifyDataSetChanged()
    }

    class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false) as TextView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = getItem(position)
        val time = TIME_FORMAT.format(Date(entry.timestampMs))
        holder.textView.text = "[$time] ${entry.message}"
        holder.textView.setTextColor(colorFor(holder.textView, entry.level))
        holder.textView.textSize = fontSizeSp
    }

    /**
     * Sourced from colors.xml's `log_*`/`console_background` entries rather
     * than duplicating the same hex values here - those were previously
     * defined but never actually referenced by this adapter (it hardcoded
     * its own copies via [android.graphics.Color.parseColor] instead), and
     * the console's background separately drifted to a theme-dependent
     * `?attr/colorSurfaceVariant` (pale in light mode) instead of a fixed
     * color these values were designed against - the combination made
     * INFO/RESPONSE lines nearly invisible. `console_background` is fixed
     * light now (esp_surface_variant_light, regardless of app theme), and
     * log_info/log_response were correspondingly darkened to match -
     * log_command/log_success/log_warning/log_error/log_debug are
     * unchanged. Both this adapter and the console's background (see
     * activity_main.xml) read from the same colors.xml entries, so a future
     * palette change only has to happen in one place.
     */
    private fun colorFor(view: TextView, level: LogLevel): Int {
        val res = when (level) {
            LogLevel.COMMAND -> R.color.log_command
            LogLevel.RESPONSE -> R.color.log_response
            LogLevel.INFO -> R.color.log_info
            LogLevel.SUCCESS -> R.color.log_success
            LogLevel.WARNING -> R.color.log_warning
            LogLevel.ERROR -> R.color.log_error
            LogLevel.DEBUG -> R.color.log_debug
        }
        return ContextCompat.getColor(view.context, res)
    }
}
