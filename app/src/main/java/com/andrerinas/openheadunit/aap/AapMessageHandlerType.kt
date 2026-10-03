package com.andrerinas.openheadunit.aap

import android.content.Context
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

internal class AapMessageHandlerType(
        private val transport: AapTransport,
        recorder: MicRecorder,
        private val aapAudio: AapAudio,
        private val aapVideo: AapVideo,
        settings: Settings,
        context: Context,
        onAaMediaMetadata: ((MediaPlayback.MediaMetaData) -> Unit)? = null,
        onAaPlaybackStatus: ((MediaPlayback.MediaPlaybackStatus) -> Unit)? = null) : AapMessageHandler {

    private val aapControl: AapControl = AapControlGateway(transport, recorder, aapAudio, settings, context)
    // JLY E60 build: now-playing also goes to the cluster (ClusterLink "media").
    private var clusterTrack = MediaPlayback.MediaMetaData.getDefaultInstance()
    private var clusterPlaying = false
    private val mediaPlayback = AapMediaPlayback(
        { meta ->
            onAaMediaMetadata?.invoke(meta)
            clusterTrack = meta
            ClusterLink.publishMedia(meta.song, meta.artist, meta.album, clusterPlaying)
        },
        { status ->
            onAaPlaybackStatus?.invoke(status)
            clusterPlaying = status.state == MediaPlayback.MediaPlaybackStatus.State.PLAYING
            ClusterLink.publishMedia(clusterTrack.song, clusterTrack.artist, clusterTrack.album, clusterPlaying)
        }
    )
    private val aapNavigation = AapNavigation(context, settings)

    private val dispatchMonitor = TransportDispatchMonitor()

    @Throws(AapMessageHandler.HandleException::class)
    override fun handle(message: AapMessage) {
        // Anything slow here is time the socket is not read, audio included. Video has its own
        // thread now, so this should stay near zero; videoQueue is where its backlog shows up.
        val dispatchStartMs = android.os.SystemClock.elapsedRealtime()
        try {
            dispatch(message)
        } finally {
            val finishedMs = android.os.SystemClock.elapsedRealtime()
            dispatchMonitor.onDispatch(
                message.channel, finishedMs - dispatchStartMs, finishedMs,
                transport.videoQueueDepth(), transport.videoShedCount()
            )
                ?.let { AppLog.i("AapTransport: %s", it) }
        }
    }

    private fun dispatch(message: AapMessage) {

        // Every decrypted inbound message passes through here, on every channel, which makes this
        // the one place that can say the link is alive rather than just that the picture is moving.
        // See AapTransport.lastMessageReceivedMs for why that distinction matters. The channel goes
        // with it so the media channels can be measured apart from the link: this fault stops video
        // and audio while leaving control running, and the three are one series here.
        transport.noteMessageReceived(message.channel, message.size)

        val msgType = message.type
        val flags = message.flags

        // 1. Video goes to its own thread (ID_VID), which sends the ack itself once the decode is
        // done. That ack is the phone's flow control and the only bound on the video backlog, so it
        // stays behind the work; what the demux buys is that it is no longer the read thread that
        // waits for it, and audio is read and acked on its own path throughout.
        if (message.channel == Channel.ID_VID) {
            // False means control traffic on the video channel, which falls through to step 5 as
            // it always has. The video thread still sees it either way.
            if (transport.dispatchVideo(message)) {
                return
            }
        }

        // 1b. Instrument cluster video: forwarded to the JLY cluster, never decoded here. Acked at
        // once like any media message, since nothing on this side can fall behind.
        if (message.channel == Channel.ID_CLU && ClusterVideo.isPayload(message)) {
            // never let the cluster path take the phone session down with it
            try { ClusterVideo.onMessage(message) } catch (e: Exception) { AppLog.e("ClusterVideo: frame handling failed", e) }
            if (msgType == 0 || msgType == 1) transport.sendMediaAck(Channel.ID_CLU)
            return
        }

        // 2. Try processing as Audio stream (Speech, System, Media)
        if (message.isAudio) {
            if (aapAudio.process(message)) {
                // Send ACK AFTER processing
                if (msgType == 0 || msgType == 1) {
                    transport.sendMediaAck(message.channel)
                }
                return
            }
        }

        // 3. Media Playback Status (separate channel)
        if (message.channel == Channel.ID_MPB && msgType > 31) {
            mediaPlayback.process(message)
            return
        }

        // 3b. Phone status (JLY E60 build): the active call goes to the cluster (ClusterLink "call").
        if (message.channel == Channel.ID_PHONE && msgType == PHONE_STATUS_MSG) {
            try {
                val status = message.parse(
                    com.andrerinas.openheadunit.aap.protocol.proto.Control.Service.PhoneStatusService.newBuilder()
                ).build()
                val call = status.callsList.firstOrNull()
                if (call == null) {
                    ClusterLink.publishCall(false, 0, "", "", 0)
                } else {
                    ClusterLink.publishCall(true, call.state.number, call.callerId, call.callerNumber, call.callDurationSeconds)
                }
            } catch (e: Exception) {
                AppLog.w("PhoneStatus: could not parse (${e.message})")
            }
            return
        }

        // 4. Navigation (turn-by-turn from any AA nav app)
        // Process only payload messages on NAV channel (>31).
        // Control/handshake messages on NAV channel must pass through to AapControl.
        if (message.channel == Channel.ID_NAV && msgType > 31) {
            if (aapNavigation.process(message)) {
                return
            }
        }

        // 5. Control Message Fallback
        if (msgType in 0..31 || msgType in 32768..32799 || msgType in 65504..65535) {
            try {
                aapControl.execute(message)
            } catch (e: Exception) {
                AppLog.e(e)
                throw AapMessageHandler.HandleException(e)
            }
        } else {
            AppLog.e("Unknown msg_type: %d, flags: %d, channel: %d", msgType, flags, message.channel)
        }
    }

    private companion object {
        /** AA phone-status channel: PHONE_STATUS message id. */
        const val PHONE_STATUS_MSG = 0x8001
    }
}
