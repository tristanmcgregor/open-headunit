package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.video.VideoFragmentAssembler
import com.andrerinas.openheadunit.utils.AppLog
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * The instrument-cluster video sink (channel [Channel.ID_CLU]).
 *
 * The phone renders a navigation-only picture for this display and sends it as H.264. Nothing is
 * decoded here: complete access units are forwarded to the JLY cluster over the head unit's
 * hotspot, where a native QML item decodes and draws them.
 *
 * Wire format on TCP [PORT], head unit -> cluster: repeated [u32 big-endian length][Annex-B access
 * unit]. A cluster that connects mid-stream first gets the stored SPS/PPS and every access unit
 * since the last IDR, so its decoder starts from a keyframe without asking the phone for one.
 */
object ClusterVideo {
    const val ENABLED = true
    const val PORT = 8766

    // Announced to the phone in ServiceDiscoveryResponse. The full 800x480 frame fills the
    // band between the cluster's hairlines; its edges sit behind the two dials.
    const val WIDTH = 800
    const val HEIGHT = 480
    const val MARGIN_WIDTH = 0
    const val MARGIN_HEIGHT = 0
    const val DENSITY = 160
    const val DISPLAY_ID = 1          // logical display id, distinct from the main display (0)
    const val DISPLAY_TYPE_CLUSTER = 1

    private const val MAX_AU_BYTES = 1 shl 20
    private const val MAX_GOP_BYTES = 6 shl 20

    private val assembler = VideoFragmentAssembler()
    private val unit = ByteArrayOutputStream(64 * 1024)
    private var assembling = false

    // Access units from the last IDR onwards, replayed to a cluster that connects late.
    private val gop = ArrayList<ByteArray>()
    private var gopBytes = 0
    private var codecConfig: ByteArray? = null

    private val io = Executors.newSingleThreadExecutor()
    private val outbox = LinkedBlockingQueue<ByteArray>(120)
    @Volatile private var client: Socket? = null
    @Volatile private var started = false

    /** Called on the transport read thread for every type 0/1 message on [Channel.ID_CLU]. */
    @Synchronized
    fun onMessage(message: AapMessage) {
        startServer()
        val buf = message.data
        val len = message.size
        val decision = assembler.onMessage(
            flags = message.flags.toInt(),
            payloadStartsAt10 = startsWithStartCode(buf, VideoFragmentAssembler.OFFSET_TIMESTAMP_INDICATION, len),
            payloadStartsAt2 = startsWithStartCode(buf, VideoFragmentAssembler.OFFSET_MEDIA_INDICATION, len)
        )
        when (val action = decision.action) {
            is VideoFragmentAssembler.Action.DecodeWhole ->
                emit(buf.copyOfRange(action.payloadOffset, len))
            is VideoFragmentAssembler.Action.BeginAssembly -> {
                unit.reset()
                unit.write(buf, action.payloadOffset, len - action.payloadOffset)
                assembling = true
            }
            VideoFragmentAssembler.Action.Append ->
                if (assembling && unit.size() + len <= MAX_AU_BYTES) unit.write(buf, 0, len) else assembling = false
            VideoFragmentAssembler.Action.AppendAndDecode -> {
                if (assembling && unit.size() + len <= MAX_AU_BYTES) {
                    unit.write(buf, 0, len)
                    emit(unit.toByteArray())
                }
                assembling = false
            }
            is VideoFragmentAssembler.Action.Discard -> assembling = false
        }
    }

    /** Picture (including middle/last fragments, which carry no type header) vs control traffic. */
    fun isPayload(message: AapMessage): Boolean = VideoFragmentAssembler.isPayload(
        flags = message.flags.toInt(),
        payloadStartsAt10 = startsWithStartCode(message.data, VideoFragmentAssembler.OFFSET_TIMESTAMP_INDICATION, message.size),
        payloadStartsAt2 = startsWithStartCode(message.data, VideoFragmentAssembler.OFFSET_MEDIA_INDICATION, message.size)
    )

    /** Send the focus the phone expects before it starts encoding for this display. */
    fun focusNotification(): AapMessage = AapMessage(
        Channel.ID_CLU,
        Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE,
        Media.VideoFocusNotification.newBuilder()
            .setMode(Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED)
            .setUnsolicited(true)
            .build()
    )

    @Synchronized
    fun reset() {
        assembler.reset()
        unit.reset()
        assembling = false
        gop.clear()
        gopBytes = 0
        codecConfig = null
    }

    private fun emit(au: ByteArray) {
        val types = nalTypes(au)
        when {
            types.all { it == 7 || it == 8 } -> codecConfig = au          // SPS/PPS only
            5 in types -> { gop.clear(); gopBytes = 0; gop.add(au); gopBytes += au.size }
            gopBytes + au.size <= MAX_GOP_BYTES -> { gop.add(au); gopBytes += au.size }
        }
        if (client != null && !outbox.offer(au)) {
            AppLog.w("ClusterVideo: cluster is behind, dropping its connection to resync")
            dropClient()
        }
    }

    private fun startServer() {
        if (started) return
        started = true
        Thread({
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(PORT))
                    AppLog.i("ClusterVideo: listening on $PORT")
                    while (true) {
                        val s = server.accept()
                        s.tcpNoDelay = true
                        dropClient()
                        val backlog = synchronized(this) { listOfNotNull(codecConfig) + gop.toList() }
                        outbox.clear()
                        backlog.forEach { outbox.offer(it) }
                        client = s
                        AppLog.i("ClusterVideo: cluster connected from ${s.inetAddress.hostAddress}, replaying ${backlog.size} units")
                        io.execute { pump(s) }
                    }
                }
            } catch (e: Exception) {
                AppLog.e("ClusterVideo: server stopped", e)
                started = false
            }
        }, "ClusterVideoServer").apply { isDaemon = true }.start()
    }

    private fun pump(s: Socket) {
        try {
            val out = DataOutputStream(s.getOutputStream().buffered(256 * 1024))
            while (client === s) {
                val au = outbox.take()
                if (au.isEmpty()) continue        // wake-up marker from dropClient
                out.writeInt(au.size)
                out.write(au)
                if (outbox.isEmpty()) out.flush()
            }
        } catch (e: Exception) {
            AppLog.d("ClusterVideo: cluster link ended (${e.message})")
        } finally {
            if (client === s) dropClient()
        }
    }

    private fun dropClient() {
        val c = client ?: return
        client = null
        outbox.clear()
        outbox.offer(ByteArray(0)) // wake the pump so it notices
        try { c.close() } catch (_: Exception) {}
        AppLog.i("ClusterVideo: cluster disconnected")
    }

    private fun startsWithStartCode(buf: ByteArray, offset: Int, len: Int): Boolean {
        if (len < offset + 4) return false
        return buf[offset].toInt() == 0 && buf[offset + 1].toInt() == 0 &&
            (buf[offset + 2].toInt() == 1 || (buf[offset + 2].toInt() == 0 && buf[offset + 3].toInt() == 1))
    }

    /** H.264 NAL unit types present in an Annex-B access unit. */
    private fun nalTypes(au: ByteArray): Set<Int> {
        val types = HashSet<Int>()
        var i = 0
        while (i + 3 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                types.add(au[i + 3].toInt() and 0x1f)
                i += 3
            } else i++
        }
        return types
    }
}
