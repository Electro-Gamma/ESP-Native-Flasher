package io.espflasher.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * A deliberately bare-bones crash screen: plain android.widget views built in
 * code (no XML inflate, no ViewBinding, no Material components, no custom
 * theme) so that whatever broke the main app can't also break this screen.
 *
 * Shown automatically by [EspFlasherApplication]'s global uncaught-exception
 * handler whenever anything anywhere in the app crashes - this lets you read
 * the exact exception/stack trace on the device itself, with no adb/PC
 * required. Tap "Copy to clipboard" and paste it wherever you need it.
 */
class CrashActivity : Activity() {

    companion object {
        const val EXTRA_TRACE = "trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "No crash details were captured."

        val scrollView = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 64)
        }

        val title = TextView(this).apply {
            text = "ESP Native Flasher crashed - here's why:"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }

        val copyButton = Button(this).apply {
            text = "Copy to clipboard"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ESP Native Flasher crash", trace))
                Toast.makeText(this@CrashActivity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }

        val traceView = TextView(this).apply {
            text = trace
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(0, 24, 0, 0)
        }

        container.addView(title)
        container.addView(copyButton)
        container.addView(traceView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        scrollView.addView(container)
        setContentView(scrollView)
    }
}
