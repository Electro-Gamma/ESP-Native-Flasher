package io.espflasher.app

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Installs a process-wide uncaught-exception handler so any crash - anywhere
 * in the app, not just MainActivity.onCreate - lands on [CrashActivity]
 * instead of the bare "keeps stopping" system dialog. This exists purely as
 * a diagnostic aid for builds where adb/logcat access isn't available; it
 * doesn't change any app behavior otherwise.
 */
class EspFlasherApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                val intent = Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_TRACE, trace)
                }
                startActivity(intent)
            } catch (inner: Throwable) {
                // If even showing the crash screen fails, fall back to the
                // platform's default handler so the OS still logs something.
                previousHandler?.uncaughtException(thread, throwable)
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }
}
