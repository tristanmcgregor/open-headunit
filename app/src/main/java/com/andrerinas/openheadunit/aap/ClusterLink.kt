package com.andrerinas.openheadunit.aap

import android.util.Base64
import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Pushes the current turn-by-turn state to the JLY instrument cluster over the head unit's
 * Wi-Fi hotspot. The cluster's QML connects with a WebSocket to ws://<hotspot gateway>:8765/nav;
 * a plain `GET /nav` returns the same JSON once, for testing from a browser.
 *
 * Only the server-to-client direction is used, so this is a minimal RFC 6455 text-frame server
 * rather than a library dependency.
 */
object ClusterLink {
    const val PORT = 8765
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private val clients = CopyOnWriteArrayList<Socket>()
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var started = false
    @Volatile private var latest: String = JSONObject().put("type", "nav").put("active", false).toString()
    @Volatile private var latestMedia: String? = null
    @Volatile private var latestCall: String? = null
    @Volatile private var latestSettings: String? = null
    @Volatile private var latestLimit: String? = null
    @Volatile private var latestClusterMap: String? = null
    @Volatile private var latestArt: String? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        pool.execute {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(PORT))
                    AppLog.i("ClusterLink: listening on $PORT")
                    while (true) {
                        val socket = server.accept()
                        pool.execute { handle(socket) }
                    }
                }
            } catch (e: Exception) {
                AppLog.e("ClusterLink: server stopped", e)
                started = false
            }
        }
    }

    fun publish(
        active: Boolean,
        maneuver: Int?,
        actionText: String,
        road: String,
        distanceMeters: Int?,
        timeSeconds: Int?,
        roundaboutExit: Int?,
        turnAngle: Int?,
        turnSide: Int?,
        totalDistanceMeters: Int?,
        totalTimeSeconds: Long?,
        estimatedArrival: String?
    ) {
        start()
        val json = JSONObject()
            .put("type", "nav")
            .put("active", active)
            .put("maneuver", maneuver ?: 0)
            .put("action", actionText)
            .put("road", road)
            .put("distance_m", distanceMeters ?: -1)
            .put("time_s", timeSeconds ?: -1)
            .put("roundabout_exit", roundaboutExit ?: -1)
            .put("turn_angle", turnAngle ?: -1)
            .put("turn_side", turnSide ?: 3)
            .put("total_distance_m", totalDistanceMeters ?: -1)
            .put("total_time_s", totalTimeSeconds ?: -1L)
            .put("eta", estimatedArrival ?: "")
            .toString()
        latest = json
        broadcast(json)
    }

    /** Now playing from Android Auto's media-playback channel. */
    fun publishMedia(title: String, artist: String, album: String, playing: Boolean,
                     durationSeconds: Int = 0, positionSeconds: Int = 0) {
        start()
        val json = JSONObject().put("type", "media").put("title", title).put("artist", artist)
            .put("album", album).put("playing", playing)
            .put("duration", durationSeconds).put("position", positionSeconds).toString()
        latestMedia = json
        broadcast(json)
    }

    /**
     * Album art for the cluster's now-playing card, sent once per track rather than with every
     * position update. Shrunk to a small JPEG here: the card shows it at 48 px.
     */
    fun publishArt(art: ByteArray?) {
        start()
        val url = try {
            art?.takeIf { it.isNotEmpty() }?.let { bytes ->
                val src = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@let null
                val small = android.graphics.Bitmap.createScaledBitmap(src, 128, 128, true)
                val out = java.io.ByteArrayOutputStream()
                small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } ?: ""
        } catch (e: Exception) {
            AppLog.w("ClusterLink: album art not usable (${e.message})")
            ""
        }
        val json = JSONObject().put("type", "mediaart").put("art", url).toString()
        latestArt = json
        broadcast(json)
    }

    /** Current call from Android Auto's phone-status channel (state: AA PhoneStatus_State, 0 = none). */
    fun publishCall(active: Boolean, state: Int, name: String, number: String, seconds: Int) {
        start()
        val json = JSONObject().put("type", "call").put("active", active).put("state", state)
            .put("name", name).put("number", number).put("seconds", seconds).toString()
        latestCall = json
        broadcast(json)
    }

    /** Cluster display settings (CarSettings), also replayed to every cluster that connects. */
    fun publishSettings(json: String) {
        start()
        latestSettings = json
        broadcast(json)
    }

    /** Size of the map the phone draws into the cluster video (the stream minus its margins). */
    fun publishClusterMap(width: Int, height: Int) {
        start()
        val json = JSONObject().put("type", "clustermap").put("width", width).put("height", height).toString()
        latestClusterMap = json
        broadcast(json)
    }

    /** Speed limit of the current road (SpeedLimits), also replayed to every cluster that connects. */
    fun publishLimit(json: String) {
        start()
        latestLimit = json
        broadcast(json)
    }

    /** Short-lived readings (GPS speed, camera countdown): sent to connected clusters, not replayed. */
    fun publishLive(json: String) {
        start()
        broadcast(json)
    }

    private fun broadcast(json: String) {
        val frame = textFrame(json)
        for (c in clients) {
            pool.execute {
                try {
                    synchronized(c) { c.getOutputStream().apply { write(frame); flush() } }
                } catch (e: Exception) {
                    drop(c)
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val headers = readHeaders(input) ?: return socket.close()
            val key = headers["sec-websocket-key"]
            val out = socket.getOutputStream()
            if (key == null) {
                // JLY E60 build: over-the-air update endpoints (/update/..., /dash/...)
                val method = headers[":method"] ?: "GET"
                val path = headers[":path"] ?: "/"
                if (CarUpdate.handle(method, path, headers, input, out) ||
                    CarSettings.handle(method, path, headers, input, out)) {
                    socket.close()
                    return
                }
                // JLY E60 build: GET /log returns the app's recent log for debugging from a phone browser
                val wantsLog = headers[":path"]?.startsWith("/log") == true
                val body = (if (wantsLog) com.andrerinas.openheadunit.utils.UsbLogMirror.recentText() else latest).toByteArray()
                val type = if (wantsLog) "text/plain; charset=utf-8" else "application/json"
                out.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: $type\r\n" +
                        "Access-Control-Allow-Origin: *\r\nContent-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                out.write(body)
                out.flush()
                socket.close()
                return
            }
            val accept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key.trim() + WS_GUID).toByteArray()),
                Base64.NO_WRAP
            )
            out.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
            )
            out.write(textFrame(latest))
            latestMedia?.let { out.write(textFrame(it)) }
            latestArt?.let { out.write(textFrame(it)) }
            latestCall?.let { out.write(textFrame(it)) }
            latestSettings?.let { out.write(textFrame(it)) }
            latestLimit?.let { out.write(textFrame(it)) }
            latestClusterMap?.let { out.write(textFrame(it)) }
            out.flush()
            clients.add(socket)
            AppLog.i("ClusterLink: cluster connected from ${socket.inetAddress.hostAddress}")
            readUntilClose(input, out, socket)
        } catch (e: Exception) {
            AppLog.d("ClusterLink: client ended (${e.message})")
        } finally {
            drop(socket)
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String>? {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || sb.length > 8192) return null
            sb.append(b.toChar())
        }
        val lines = sb.split("\r\n")
        val request = lines.firstOrNull()?.split(" ")
        val path = request?.getOrNull(1) ?: "/"
        return mapOf(":method" to (request?.getOrNull(0) ?: "GET"), ":path" to path) + lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i > 0) line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim() else null
        }.toMap()
    }

    /** Client frames are only pings and close; answer pings, stop on close or EOF. */
    private fun readUntilClose(input: InputStream, out: OutputStream, socket: Socket) {
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return
            val b1 = input.read()
            if (b1 < 0) return
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            val mask = ByteArray(4)
            if ((b1 and 0x80) != 0) {
                var m = 0
                while (m < 4) { val n = input.read(mask, m, 4 - m); if (n < 0) return; m += n }
            }
            val payload = ByteArray(len.toInt())
            var read = 0
            while (read < payload.size) {
                val n = input.read(payload, read, payload.size - read)
                if (n < 0) return
                read += n
            }
            when (b0 and 0x0f) {
                0x8 -> return
                0x9 -> {
                    for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    synchronized(socket) { out.write(frame(0xA, payload)); out.flush() }
                }
            }
        }
    }

    private fun textFrame(text: String) = frame(0x1, text.toByteArray())

    private fun frame(opcode: Int, payload: ByteArray): ByteArray {
        val header = when {
            payload.size < 126 -> byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte())
            payload.size < 65536 -> byteArrayOf(
                (0x80 or opcode).toByte(), 126,
                (payload.size shr 8).toByte(), payload.size.toByte()
            )
            else -> ByteArray(10).also {
                it[0] = (0x80 or opcode).toByte(); it[1] = 127
                for (i in 0 until 8) it[9 - i] = (payload.size.toLong() shr (8 * i)).toByte()
            }
        }
        return header + payload
    }

    private fun drop(socket: Socket) {
        if (clients.remove(socket)) AppLog.i("ClusterLink: cluster disconnected")
        try { socket.close() } catch (_: Exception) {}
    }
}
