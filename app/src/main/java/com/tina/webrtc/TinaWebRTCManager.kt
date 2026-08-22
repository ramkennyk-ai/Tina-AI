package com.tina.webrtc

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.*

/**
 * TINA's call manager — same shape as Talksy's WebRTCManager (RTDB signaling,
 * network-adaptive Opus, ICE timeout/disconnect handling), extended to add
 * video capture/tracks/renderers for video calls.
 *
 * Room creator = caller, same convention as Talksy: pass isCaller from
 * outside based on however your matchmaking decides that (e.g. alphabetical
 * UID comparison, like Talksy does).
 */
class TinaWebRTCManager(
    private val context: Context,
    private val roomId: String,
    val isCaller: Boolean,
    private val eglBase: EglBase = EglBase.create(),
    private val onCallEnded: () -> Unit,
    private val onCallConnected: () -> Unit = {}
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var localAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var localVideoTrack: VideoTrack? = null
    private var localVideoSource: VideoSource? = null

    var localRenderer: SurfaceViewRenderer? = null
    var remoteRenderer: SurfaceViewRenderer? = null

    private var captionDataChannel: DataChannel? = null
    private var onCaptionReceived: ((String) -> Unit)? = null

    /** Register a callback for translated captions arriving from the peer. */
    fun setOnCaptionReceived(callback: (String) -> Unit) {
        onCaptionReceived = callback
    }

    /** Send a (already-translated) caption string to the peer over the data channel. */
    fun sendCaption(text: String) {
        val buffer = DataChannel.Buffer(
            java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)),
            false
        )
        captionDataChannel?.send(buffer)
    }

    private val signalingManager = RealtimeDBSignalingManager(context, roomId)

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var isRemoteDescriptionSet = false

    private val handler = Handler(Looper.getMainLooper())
    private var iceTimeoutRunnable: Runnable? = null
    private var disconnectRunnable: Runnable? = null
    private val ICE_TIMEOUT_MS = 30000L

    private val networkType: NetworkType get() = detectNetworkType()
    private enum class NetworkType { WIFI, FOUR_G, OTHER }
    private data class OpusConfig(val bitrate: Int, val useDtx: Boolean, val useFec: Boolean, val label: String)

    // ─── ICE Servers — reuse your existing TURN droplet from Talksy ───────
    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:YOUR_DROPLET_IP:3478")
            .setUsername("talksy").setPassword("YOUR_PASSWORD").createIceServer(),
        PeerConnection.IceServer.builder("turn:YOUR_DROPLET_IP:3478?transport=tcp")
            .setUsername("talksy").setPassword("YOUR_PASSWORD").createIceServer(),
    )

    // ─── Init ───────────────────────────────────────────────────────────

    fun init(localRenderer: SurfaceViewRenderer, remoteRenderer: SurfaceViewRenderer) {
        this.localRenderer = localRenderer
        this.remoteRenderer = remoteRenderer
        localRenderer.init(eglBase.eglBaseContext, null)
        remoteRenderer.init(eglBase.eglBaseContext, null)

        initializePeerConnectionFactory()
        createPeerConnection()
        createCaptionChannelIfCaller()
        addLocalAudioTrack()
        addLocalVideoTrack()
        listenForHangUp()
        startIceTimeout()

        Log.d("TinaWebRTC", "Network: ${networkType.name}, isCaller: $isCaller")

        if (isCaller) createOffer() else waitForOffer()
    }

    private fun detectNetworkType(): NetworkType {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkType.OTHER
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.FOUR_G
            else -> NetworkType.OTHER
        }
    }

    private fun getOpusConfig(): OpusConfig = when (networkType) {
        NetworkType.WIFI -> OpusConfig(48000, useDtx = true, useFec = false, label = "WiFi-48kbps")
        NetworkType.FOUR_G -> OpusConfig(24000, useDtx = true, useFec = true, label = "4G-24kbps+FEC")
        NetworkType.OTHER -> OpusConfig(16000, useDtx = true, useFec = true, label = "Other-16kbps+FEC")
    }

    private fun initializePeerConnectionFactory() {
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    // ─── Peer Connection ────────────────────────────────────────────────

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            iceCandidatePoolSize = 5
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    signalingManager.sendIceCandidate(
                        isCaller = isCaller,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.sdp
                    )
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                    Log.d("TinaWebRTC", "ICE STATE: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            Log.d("TinaWebRTC", "CALL CONNECTED")
                            cancelIceTimeout()
                            disconnectRunnable?.let { handler.removeCallbacks(it) }
                            handler.post { onCallConnected() }
                        }
                        PeerConnection.IceConnectionState.COMPLETED -> cancelIceTimeout()
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e("TinaWebRTC", "CALL FAILED")
                            handler.post { onCallEnded() }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            Log.w("TinaWebRTC", "DISCONNECTED")
                            disconnectRunnable = Runnable {
                                val current = peerConnection?.iceConnectionState()
                                if (current == PeerConnection.IceConnectionState.DISCONNECTED) {
                                    onCallEnded()
                                }
                            }
                            handler.postDelayed(disconnectRunnable!!, 15000)
                        }
                        PeerConnection.IceConnectionState.CLOSED -> handler.post { onCallEnded() }
                        else -> {}
                    }
                }

                override fun onTrack(transceiver: RtpTransceiver?) {
                    val track = transceiver?.receiver?.track()
                    if (track is VideoTrack) track.addSink(remoteRenderer)
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {
                    stream?.videoTracks?.firstOrNull()?.addSink(remoteRenderer)
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {
                    // Callee side receives the channel the caller created
                    channel?.let { attachCaptionChannel(it) }
                }
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            }
        )
    }

    // ─── Caption data channel ───────────────────────────────────────────

    /** Only the caller creates the channel; the callee receives it via onDataChannel. */
    private fun createCaptionChannelIfCaller() {
        if (!isCaller) return
        val init = DataChannel.Init().apply {
            ordered = true // captions should arrive in order; latency cost is negligible at text size
        }
        peerConnection?.createDataChannel("captions", init)?.let { attachCaptionChannel(it) }
    }

    private fun attachCaptionChannel(channel: DataChannel) {
        captionDataChannel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                Log.d("TinaWebRTC", "Caption channel state: ${channel.state()}")
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                onCaptionReceived?.invoke(String(bytes, Charsets.UTF_8))
            }
        })
    }

    // ─── Audio Track — same AEC/NS/AGC config as Talksy ───────────────────

    private fun addLocalAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            optional.add(MediaConstraints.KeyValuePair("googOpusDtx", "true"))
            optional.add(MediaConstraints.KeyValuePair("googOpusFec", "true"))
            optional.add(MediaConstraints.KeyValuePair("googOpusStereo", "false"))
        }

        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track_local", audioSource)
        localAudioTrack?.setEnabled(true)
        peerConnection?.addTrack(localAudioTrack, listOf("local_stream"))
    }

    // ─── Video Track — new for TINA ────────────────────────────────────

    private fun addLocalVideoTrack() {
        videoCapturer = createCameraCapturer() ?: run {
            Log.e("TinaWebRTC", "No camera available")
            return
        }

        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        localVideoSource = peerConnectionFactory?.createVideoSource(false)
        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("video_track_local", localVideoSource)
        localVideoTrack?.addSink(localRenderer)
        peerConnection?.addTrack(localVideoTrack, listOf("local_stream"))
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        for (name in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(name)) enumerator.createCapturer(name, null)?.let { return it }
        }
        for (name in enumerator.deviceNames) {
            enumerator.createCapturer(name, null)?.let { return it }
        }
        return null
    }

    fun switchCamera() = videoCapturer?.switchCamera(null)

    fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    // ─── ICE Timeout ────────────────────────────────────────────────────

    private fun startIceTimeout() {
        iceTimeoutRunnable = Runnable {
            val state = peerConnection?.iceConnectionState()
            if (state != PeerConnection.IceConnectionState.CONNECTED &&
                state != PeerConnection.IceConnectionState.COMPLETED) {
                Log.e("TinaWebRTC", "ICE timeout")
                onCallEnded()
            }
        }
        handler.postDelayed(iceTimeoutRunnable!!, ICE_TIMEOUT_MS)
    }

    private fun cancelIceTimeout() {
        iceTimeoutRunnable?.let { handler.removeCallbacks(it) }
        iceTimeoutRunnable = null
    }

    // ─── SDP Opus optimization — same as Talksy ────────────────────────

    private fun optimizeSdpForOpus(sdpDescription: String): String {
        val config = getOpusConfig()
        Log.d("TinaWebRTC", "Opus: ${config.label}")

        val lines = sdpDescription.split("\r\n").toMutableList()
        val payloadType = findOpusPayloadType(lines)
        if (payloadType == -1) return sdpDescription

        val fec = if (config.useFec) "1" else "0"
        val dtx = if (config.useDtx) "1" else "0"
        val fmtpLine = "a=fmtp:$payloadType minptime=10;useinbandfec=$fec;usedtx=$dtx;" +
            "maxaveragebitrate=${config.bitrate};stereo=0;sprop-stereo=0"

        val fmtpIndex = lines.indexOfFirst { it.startsWith("a=fmtp:$payloadType") }
        if (fmtpIndex != -1) {
            lines[fmtpIndex] = fmtpLine
        } else {
            val rtpmapIndex = lines.indexOfFirst { it.startsWith("a=rtpmap:$payloadType") }
            if (rtpmapIndex != -1) lines.add(rtpmapIndex + 1, fmtpLine)
        }
        return lines.joinToString("\r\n")
    }

    private fun findOpusPayloadType(lines: List<String>): Int {
        for (line in lines) {
            if (line.startsWith("a=rtpmap:") && line.contains("opus/48000")) {
                return line.substringAfter("a=rtpmap:").substringBefore(" ").toIntOrNull() ?: -1
            }
        }
        return -1
    }

    // ─── Offer ──────────────────────────────────────────────────────────

    private fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                val newSdp = SessionDescription(sdp.type, optimizeSdpForOpus(sdp.description))
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signalingManager.sendOffer(
                            sdp = newSdp.description,
                            onSuccess = {
                                listenForAnswer()
                                listenForRemoteCandidates()
                            },
                            onFailure = { Log.e("TinaWebRTC", "Send offer failed") }
                        )
                    }
                    override fun onSetFailure(p0: String?) { Log.e("TinaWebRTC", "Set local failed: $p0") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, newSdp)
            }
            override fun onCreateFailure(error: String?) { Log.e("TinaWebRTC", "Create offer failed: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    // ─── Answer ─────────────────────────────────────────────────────────

    private fun listenForAnswer() {
        signalingManager.listenForAnswer { sdp ->
            val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    isRemoteDescriptionSet = true
                    pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingIceCandidates.clear()
                }
                override fun onSetFailure(p0: String?) { Log.e("TinaWebRTC", "Set answer failed: $p0") }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, answer)
        }
    }

    private fun waitForOffer() {
        signalingManager.listenForOffer { sdp ->
            val offer = SessionDescription(SessionDescription.Type.OFFER, sdp)
            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    isRemoteDescriptionSet = true
                    pendingIceCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingIceCandidates.clear()
                    createAnswer()
                    listenForRemoteCandidates()
                }
                override fun onSetFailure(p0: String?) { Log.e("TinaWebRTC", "Set offer failed: $p0") }
                override fun onCreateSuccess(p0: SessionDescription?) {}
                override fun onCreateFailure(p0: String?) {}
            }, offer)
        }
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return
                val newSdp = SessionDescription(sdp.type, optimizeSdpForOpus(sdp.description))
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        signalingManager.sendAnswer(
                            sdp = newSdp.description,
                            onSuccess = {},
                            onFailure = { Log.e("TinaWebRTC", "Send answer failed") }
                        )
                    }
                    override fun onSetFailure(p0: String?) { Log.e("TinaWebRTC", "Set local answer: $p0") }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, newSdp)
            }
            override fun onCreateFailure(error: String?) { Log.e("TinaWebRTC", "Create answer failed: $error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    // ─── ICE Candidates ─────────────────────────────────────────────────

    private fun listenForRemoteCandidates() {
        signalingManager.listenForIceCandidates(isCaller) { sdpMid, sdpMLineIndex, candidate ->
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
            if (isRemoteDescriptionSet) {
                peerConnection?.addIceCandidate(iceCandidate)
            } else {
                pendingIceCandidates.add(iceCandidate)
            }
        }
    }

    // ─── Hang up / mic ──────────────────────────────────────────────────

    private fun listenForHangUp() {
        signalingManager.listenForHangUp { handler.post { onCallEnded() } }
    }

    fun hangUp() {
        signalingManager.markRoomEnded()
        cleanup()
    }

    fun setMicEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    // ─── Cleanup ────────────────────────────────────────────────────────

    fun cleanup() {
        cancelIceTimeout()
        disconnectRunnable?.let { handler.removeCallbacks(it) }
        signalingManager.cleanup()

        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localVideoSource?.dispose()

        localAudioTrack?.dispose()
        audioSource?.dispose()

        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()

        localRenderer?.release()
        remoteRenderer?.release()

        localAudioTrack = null
        audioSource = null
        localVideoTrack = null
        localVideoSource = null
        videoCapturer = null
        peerConnection = null
        peerConnectionFactory = null
    }
}
