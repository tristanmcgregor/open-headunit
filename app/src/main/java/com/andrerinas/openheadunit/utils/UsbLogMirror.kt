package com.andrerinas.openheadunit.utils

import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * JLY E60 build: copies every log line to openheadunit_log.txt on a plugged-in USB stick (the head
 * unit's "usb1"), so a failed connection can be read on a computer without any export steps.
 * Falls back to the public Download folder when no USB volume is writable.
 *
 * Lines are buffered in memory and appended every few seconds on a background thread; nothing
 * here blocks the caller, and every filesystem error is swallowed.
 */
object UsbLogMirror {
    private const val FILE_NAME = "openheadunit_log.txt"
    private const val FLUSH_MS = 3000L
    private const val MAX_BUFFERED = 20000

    private val lock = Any()
    private val pending = ArrayDeque<String>()
    private val recent = ArrayDeque<String>()       // last lines, served at http://<head unit>:8765/log
    private const val MAX_RECENT = 6000
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var started = false

    // Mount points seen on Android head units for the first USB port, most likely first.
    private val usbRoots = listOf(
        "/storage/usb1", "/mnt/usb1", "/storage/usb0", "/mnt/usb0", "/storage/udisk", "/mnt/udisk",
        "/mnt/usb_storage", "/storage/usbotg", "/mnt/media_rw/usb1"
    )

    fun add(priority: Int, tag: String, msg: String) {
        val level = when (priority) {
            Log.ERROR -> "E"; Log.WARN -> "W"; Log.INFO -> "I"; Log.DEBUG -> "D"; else -> "V"
        }
        val line = synchronized(stamp) { stamp.format(Date()) } + " $level/$tag: $msg"
        synchronized(lock) {
            if (pending.size >= MAX_BUFFERED) pending.pollFirst()
            pending.addLast(line)
            if (recent.size >= MAX_RECENT) recent.pollFirst()
            recent.addLast(line)
        }
        start()
    }

    /** The most recent log lines as plain text. */
    fun recentText(): String = synchronized(lock) { recent.joinToString("\n", postfix = "\n") }

    private fun start() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            // also bring up the cluster link early, so /log answers even if no AA session starts
            try { com.andrerinas.openheadunit.aap.ClusterLink.start() } catch (_: Exception) {}
            Thread({
                while (true) {
                    try { Thread.sleep(FLUSH_MS) } catch (_: InterruptedException) {}
                    flush()
                }
            }, "UsbLogMirror").apply { isDaemon = true }.start()
        }
    }

    private fun targets(): List<File> {
        val found = ArrayList<File>()
        usbRoots.map(::File).filterTo(found) { it.isDirectory && it.canWrite() }
        // Any other mounted removable volume (often /storage/XXXX-XXXX).
        File("/storage").listFiles()?.filterTo(found) {
            it.isDirectory && it.canWrite() && it.name != "emulated" && it.name != "self" && it !in found
        }
        if (found.isEmpty()) {
            @Suppress("DEPRECATION")
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?.takeIf { it.isDirectory || it.mkdirs() }?.let { found.add(it) }
        }
        return found
    }

    private fun flush() {
        val batch: List<String> = synchronized(lock) {
            if (pending.isEmpty()) return
            ArrayList(pending).also { pending.clear() }
        }
        val text = batch.joinToString("\n", postfix = "\n")
        for (dir in targets()) {
            try {
                File(dir, FILE_NAME).appendText(text)
            } catch (_: Exception) {
                // read-only or permission denied: try the next location
            }
        }
    }
}
