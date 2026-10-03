package com.andrerinas.openheadunit.aap

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.ref.WeakReference
import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * JLY E60 build: over-the-air updates, as described in updater/PROTOCOL.md.
 *
 * The phone updater app pushes GitHub releases here over the car Wi-Fi (POST /update/...); the
 * cluster pulls dash bundles from here (GET /dash/...). Both arrive on ClusterLink's port.
 * An upload is accepted only with a valid release signature (E60_UPDATE_PUBKEY), so nothing
 * secret is built into the app and nobody on the car Wi-Fi can push their own update.
 *
 * An uploaded APK is only staged. The install confirmation is shown when the app's own home
 * screen is in front, never over a projection, because the system dialog would cover Android Auto.
 */
object CarUpdate {
    private const val MAX_UPLOAD = 96L shl 20
    private const val ACTION_INSTALL_RESULT = "com.andrerinas.openheadunit.E60_INSTALL_RESULT"

    private lateinit var app: Context
    private lateinit var dir: File
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var home: WeakReference<Activity>? = null
    @Volatile private var promptedRelease = 0

    fun init(application: Application) {
        app = application
        dir = File(application.filesDir, "carupdate").apply { mkdirs() }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity is MainActivity) {
                    home = WeakReference(activity)
                    offerInstall(activity)
                }
            }
            override fun onActivityPaused(activity: Activity) {
                if (home?.get() === activity) home = null
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        // a staged APK at or below the running release was installed (or superseded): drop it
        if (pendingApkRelease() in 1..BuildConfig.E60_RELEASE) clearPendingApk()
    }

    private val ready get() = this::dir.isInitialized

    /** Handles /update/... and /dash/...; returns false for any other path. */
    fun handle(method: String, path: String, headers: Map<String, String>, input: InputStream, out: OutputStream): Boolean {
        val route = path.substringBefore('?')
        if (!route.startsWith("/update/") && !route.startsWith("/dash/")) return false
        if (!ready) { respond(out, 503, "text/plain", "not ready"); return true }
        val query = parseQuery(path.substringAfter('?', ""))
        try {
            when {
                method == "GET" && route == "/update/status" -> respond(out, 200, "application/json", status())
                method == "POST" && route == "/update/dash" -> upload(Kind.DASH, query, headers, input, out)
                method == "POST" && route == "/update/apk" -> upload(Kind.APK, query, headers, input, out)
                method == "POST" && route == "/update/speedlimits" -> upload(Kind.SPEEDLIMITS, query, headers, input, out)
                method == "GET" && route == "/dash/manifest.txt" -> {
                    query["have"]?.toIntOrNull()?.let { noteClusterRelease(it) }
                    val meta = dashMeta()
                    if (meta == null) respond(out, 404, "text/plain", "no dash bundle")
                    else respond(out, 200, "text/plain",
                        "release=${meta.release}\nsha256=${meta.sha256}\nsize=${meta.size}\n")
                }
                method == "GET" && route == "/dash/bundle.tar.gz" -> {
                    val f = File(dir, "dash.tar.gz")
                    if (!f.isFile) respond(out, 404, "text/plain", "no dash bundle")
                    else sendFile(out, f, "application/gzip")
                }
                else -> respond(out, 404, "text/plain", "unknown")
            }
        } catch (e: Exception) {
            AppLog.e("CarUpdate: ${method} ${route} failed", e)
            try { respond(out, 500, "text/plain", e.message ?: "error") } catch (_: Exception) {}
        }
        return true
    }

    private enum class Kind { DASH, APK, SPEEDLIMITS }

    private class Meta(val release: Int, val sha256: String, val size: Long)

    private fun status(): String = JSONObject()
        .put("service", "e60-update")
        .put("apkRelease", BuildConfig.E60_RELEASE)
        .put("apkVersionCode", BuildConfig.VERSION_CODE)
        .put("pendingApkRelease", pendingApkRelease())
        .put("dashRelease", dashMeta()?.release ?: 0)
        .put("speedLimitsRelease", readInt("speedlimits.txt"))
        .put("clusterDashRelease", readInt("cluster.txt"))
        .toString()

    private fun upload(kind: Kind, query: Map<String, String>, headers: Map<String, String>, input: InputStream, out: OutputStream) {
        val length = headers["content-length"]?.toLongOrNull() ?: -1
        // A refusal reads the body first: answering mid-upload and closing would reach the
        // phone as a broken connection instead of the reason.
        fun refuse(code: Int, why: String) {
            if (length in 1..MAX_UPLOAD) discard(input, length)
            respond(out, code, "text/plain", why)
        }
        val release = query["release"]?.toIntOrNull() ?: 0
        val sha = query["sha256"]?.lowercase() ?: ""
        if (release <= 0 || sha.length != 64 || length <= 0 || length > MAX_UPLOAD)
            return refuse(400, "need release, sha256 and Content-Length")
        // The release tool signs "<kind>\n<release>\n<sha256>\n"; the body is then held to that sha256.
        if (!signatureValid(kind, release, sha, headers["x-update-signature"] ?: "")) {
            AppLog.w("CarUpdate: ${kind} release $release refused, signature does not verify")
            return refuse(401, "bad signature")
        }
        val current = when (kind) {
            Kind.DASH -> dashMeta()?.release ?: 0
            Kind.APK -> maxOf(BuildConfig.E60_RELEASE, pendingApkRelease())
            Kind.SPEEDLIMITS -> readInt("speedlimits.txt")
        }
        if (release <= current) return refuse(409, "already have $current")

        val tmp = File(dir, "upload.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        tmp.outputStream().use { f ->
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                f.write(buf, 0, n)
                digest.update(buf, 0, n)
                left -= n
            }
            if (left > 0) { tmp.delete(); return respond(out, 400, "text/plain", "body ended early") }
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (got != sha) {
            tmp.delete()
            AppLog.w("CarUpdate: ${kind} release $release checksum mismatch")
            return respond(out, 400, "text/plain", "sha256 mismatch")
        }
        when (kind) {
            Kind.DASH -> {
                tmp.renameTo(File(dir, "dash.tar.gz"))
                File(dir, "dash.txt").writeText("$release\n$sha\n$length\n")
            }
            Kind.APK -> {
                tmp.renameTo(File(dir, "pending.apk"))
                File(dir, "pending.txt").writeText("$release\n")
                promptedRelease = 0
                home?.get()?.let { a -> main.post { offerInstall(a) } }
            }
            Kind.SPEEDLIMITS -> {
                // shipped gzip-compressed; unpacked once here so the matcher can map it
                val bin = File(dir, "speedlimits.bin.tmp")
                try {
                    java.util.zip.GZIPInputStream(tmp.inputStream()).use { gz -> bin.outputStream().use { gz.copyTo(it) } }
                } catch (e: Exception) {
                    tmp.delete(); bin.delete()
                    return respond(out, 400, "text/plain", "not a gzip speed-limit file")
                }
                tmp.delete()
                bin.renameTo(speedLimitFile())
                File(dir, "speedlimits.txt").writeText("$release\n")
                SpeedLimits.reload()
            }
        }
        AppLog.i("CarUpdate: ${kind} release $release received ($length bytes)")
        respond(out, 200, "text/plain", "ok")
    }

    private fun signatureValid(kind: Kind, release: Int, sha: String, signature: String): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(
            X509EncodedKeySpec(Base64.decode(BuildConfig.E60_UPDATE_PUBKEY, Base64.DEFAULT)))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update("${kind.name.lowercase()}\n$release\n$sha\n".toByteArray())
            verify(Base64.decode(signature, Base64.DEFAULT))
        }
    } catch (e: Exception) {
        false
    }

    /** The installed speed-limit data (see updater/speedlimits.py for the format). */
    fun speedLimitFile(): File = File(dir, "speedlimits.bin")

    private fun dashMeta(): Meta? {
        val lines = File(dir, "dash.txt").takeIf { it.isFile && File(dir, "dash.tar.gz").isFile }
            ?.readLines() ?: return null
        val release = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        return Meta(release, lines.getOrNull(1)?.trim() ?: return null, lines.getOrNull(2)?.trim()?.toLongOrNull() ?: 0)
    }

    private fun noteClusterRelease(release: Int) {
        if (readInt("cluster.txt") != release) {
            File(dir, "cluster.txt").writeText("$release\n")
            AppLog.i("CarUpdate: cluster reports dash release $release")
        }
    }

    private fun pendingApkRelease(): Int =
        if (File(dir, "pending.apk").isFile) readInt("pending.txt") else 0

    private fun clearPendingApk() {
        File(dir, "pending.apk").delete()
        File(dir, "pending.txt").delete()
    }

    private fun readInt(name: String): Int =
        try { File(dir, name).readText().trim().lines().first().toInt() } catch (_: Exception) { 0 }

    // ---- APK install ----

    /** Asks the system to install a staged APK; the user confirms on the head unit's screen. */
    private fun offerInstall(activity: Activity) {
        val release = pendingApkRelease()
        if (release <= BuildConfig.E60_RELEASE || release == promptedRelease) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            // Android needs "install unknown apps" allowed for this app once
            Toast.makeText(activity, "Allow Open Headunit to install updates, then come back", Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")))
            } catch (_: Exception) {}
            return
        }
        promptedRelease = release
        AppLog.i("CarUpdate: installing staged APK release $release")
        Thread({
            try {
                val apk = File(dir, "pending.apk")
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("update.apk", 0, apk.length()).use { sink ->
                        apk.inputStream().use { it.copyTo(sink) }
                        session.fsync(sink)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    val result = PendingIntent.getBroadcast(app, 0,
                        Intent(ACTION_INSTALL_RESULT).setPackage(app.packageName), flags)
                    session.commit(result.intentSender)
                }
            } catch (e: Exception) {
                AppLog.e("CarUpdate: APK install failed to start", e)
                promptedRelease = 0
            }
        }, "CarUpdateInstall").start()
    }

    /** Receives the install session's status; the confirmation screen comes through here. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> AppLog.i("CarUpdate: APK installed")
                else -> {
                    AppLog.w("CarUpdate: APK install ended with status $status: " +
                        intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
                    promptedRelease = 0
                }
            }
        }
    }

    // ---- HTTP helpers ----

    private fun discard(input: InputStream, length: Long) {
        val buf = ByteArray(64 * 1024)
        var left = length
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) return
            left -= n
        }
    }

    private fun parseQuery(q: String): Map<String, String> = q.split('&').mapNotNull {
        val i = it.indexOf('=')
        if (i > 0) Uri.decode(it.substring(0, i)) to Uri.decode(it.substring(i + 1)) else null
    }.toMap()

    private fun respond(out: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray()
        out.write(("HTTP/1.1 $code ${reason(code)}\r\nContent-Type: $type\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun sendFile(out: OutputStream, f: File, type: String) {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
            "Content-Length: ${f.length()}\r\nConnection: close\r\n\r\n").toByteArray())
        f.inputStream().use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"
        409 -> "Conflict"; 503 -> "Service Unavailable"; else -> "Error"
    }
}
