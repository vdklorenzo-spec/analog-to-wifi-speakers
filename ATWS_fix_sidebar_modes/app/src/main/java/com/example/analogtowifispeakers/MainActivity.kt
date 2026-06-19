// FILE: app/src/main/java/com/example/analogtowifispeakers/MainActivity.kt
package com.example.analogtowifispeakers

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import com.example.analogtowifispeakers.audio.AacAdtsEncoder
import com.example.analogtowifispeakers.audio.DspEngine
import com.example.analogtowifispeakers.audio.MicAudioSource
import com.example.analogtowifispeakers.ui.matrix.MatrixVisualizerPanel
import com.example.analogtowifispeakers.ui.theme.FrontPanelLayer
import com.example.analogtowifispeakers.ui.theme.PanelLook
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class MainActivity : FragmentActivity() {

    private lateinit var castContext: CastContext
    private lateinit var httpServer: LocalHttpServer

    private var micSource: MicAudioSource? = null
    private var encoder: AacAdtsEncoder? = null

    private val dspEngine = DspEngine()

    @Volatile private var pipelineRunning: Boolean = false
    @Volatile private var autoLoadPending: Boolean = false

    private val pcmBytesIn = AtomicLong(0)
    private val pcmFramesIn = AtomicLong(0)
    private val aacBytesOut = AtomicLong(0)
    private val aacFramesOut = AtomicLong(0)

    // ---- UI level feed (LIVE samples from mic thread) ----
    @Volatile private var lastRms01: Float = 0f
    @Volatile private var lastPeak01: Float = 0f
    @Volatile private var lastLevelUpdateNs: Long = 0L

    // ---- feeder stats (debug via Logcat) ----
    private val pcmDropped = AtomicLong(0)
    @Volatile private var feederRunning: Boolean = false
    private var feederThread: Thread? = null

    // Small queue to avoid blocking mic thread.
    private data class PcmPacket(val bytes: ByteArray, val sizeBytes: Int, val tsNs: Long)
    private val pcmQueue = ArrayBlockingQueue<PcmPacket>(8)

    @Volatile private var lastUrl: String = ""
    @Volatile private var lastAction: String = "No actions yet"

    private val TAG = "AudioBridge"
    private val castConnectedState = mutableStateOf(false)

    // ---- 35s Visual delay buffer (UI thread only) ----
    private data class LevelSample(val tsNs: Long, val rms01: Float, val peak01: Float)
    private val levelLock = Any()
    private val levelRing = ArrayDeque<LevelSample>(1200)

    private val VISUAL_DELAY_NS = 35_000_000_000L // 35s

    private val castListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            lastAction = "Cast session started"
            castConnectedState.value = true
            hookRemoteCallbacks(session)
            maybeAutoLoadToCast()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            lastAction = "Cast session resumed"
            castConnectedState.value = true
            hookRemoteCallbacks(session)
            maybeAutoLoadToCast()
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            lastAction = "Cast session ended ($error)"
            castConnectedState.value = false
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            lastAction = "Cast start failed ($error)"
            castConnectedState.value = false
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            lastAction = "Cast resume failed ($error)"
            castConnectedState.value = false
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            castConnectedState.value = false
        }
    }

    private var remoteCallback: RemoteMediaClient.Callback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        castContext = CastContext.getSharedInstance(this)
        castContext.sessionManager.addSessionManagerListener(castListener, CastSession::class.java)
        castConnectedState.value = (castContext.sessionManager.currentCastSession != null)

        httpServer = LocalHttpServer(port = 9090)
        httpServer.start()

        setContent { Phase7Root() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            castContext.sessionManager.removeSessionManagerListener(castListener, CastSession::class.java)
        } catch (_: Throwable) {}
        stopAll()
        httpServer.stop()
    }

    @Composable
    private fun Phase7Root() {
        val initials = "L"

        // frozen pipeline settings
        val sampleRateHz = 48_000
        val aacBitrateBps = 96_000

        var micGranted by remember { mutableStateOf(isMicGranted()) }
        val micPermLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted -> micGranted = granted }

        var castButtonRef by remember { mutableStateOf<MediaRouteButton?>(null) }

        // ---- UI level values ----
        var delayedLevel01 by remember { mutableStateOf(0f) }
        var delayedPeak01  by remember { mutableStateOf(0f) }

        // Sidebar visibility (for UI only; MUST NOT affect layout)
        var sidebarVisible by remember { mutableStateOf(true) }
        // UI truth for LIVE (comes from Phase7Screen)
        var isLiveUi by remember { mutableStateOf(false) }

        // When LIVE is running, allow a tap anywhere to briefly show the sidebar again.
        // This keeps STOP accessible even after auto-hide.
        LaunchedEffect(sidebarVisible, isLiveUi, pipelineRunning) {
            if (sidebarVisible && isLiveUi && pipelineRunning) {
                // Auto-hide after 8s while live.
                kotlinx.coroutines.delay(8_000)
                sidebarVisible = false
            }
        }

        // Cast master volume bridge (UI only)
        var castVol01 by remember { mutableStateOf(readCastVolume01OrNull() ?: 0.50f) }
        var isVolumeEditing by remember { mutableStateOf(false) }

        // ✅ FrontPanel control state
        var klaarteLevel by remember { mutableIntStateOf(2) }     // 0..4
        var panelLook by remember { mutableStateOf(PanelLook.GOLD) }

        // ---- Matrix demo controls (independent from Cast volume) ----
        var matrixPaletteIndex by remember { mutableIntStateOf(0) }
        var matrixModeIndex by remember { mutableIntStateOf(0) }
        var matrixVolume01 by remember { mutableFloatStateOf(0.55f) }

        // Time ticker for demo bands (makes it move even without audio)
        var demoT by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                kotlinx.coroutines.delay(16)
                demoT = (System.nanoTime() - start) / 1_000_000_000f
            }
        }

        // Demo bands (40 bands) – later replace with real FFT/bands
        val demoBands: List<Float> = remember(demoT, delayedLevel01, pipelineRunning) {
            val base = if (pipelineRunning) delayedLevel01.coerceIn(0f, 1f) else 0.35f
            List(40) { i ->
                val x = i / 39f
                val wave = (sin(demoT * 2.2f + x * 8.0f) * 0.5f + 0.5f)
                val wobble = (sin(demoT * 1.1f + x * 22.0f) * 0.5f + 0.5f) * 0.35f
                val v = (wave * (0.55f + 0.45f * base) + wobble).coerceIn(0f, 1f)
                (v * v).coerceIn(0f, 1f)
            }
        }

        val isCastConnected by castConnectedState

        LaunchedEffect(Unit) {
            var tick = 0
            var lastLog = System.nanoTime()

            while (true) {
                kotlinx.coroutines.delay(50)

                val now = System.nanoTime()

                // Keep cast connected state fresh
                val sessionNow = castContext.sessionManager.currentCastSession
                if ((sessionNow != null) != castConnectedState.value) {
                    castConnectedState.value = (sessionNow != null)
                }

                // Read cast volume occasionally (avoid fighting user while dragging)
                tick++
                if (tick % 12 == 0) { // ~600ms
                    if (!isVolumeEditing) {
                        val v = readCastVolume01OrNull()
                        if (v != null && abs(v - castVol01) > 0.02f) {
                            castVol01 = v
                        }
                    }
                }

                // ---- Build visual ringbuffer on UI thread (no mic-thread work) ----
                val ageMs = (now - lastLevelUpdateNs) / 1_000_000L
                val rms = lastRms01
                val peak = lastPeak01

                // If mic stalls, decay the sample so UI doesn't freeze hard.
                val rmsVal  = if (pipelineRunning && ageMs <= 750L) rms.coerceIn(0f, 1f) else 0f
                val peakVal = if (pipelineRunning && ageMs <= 750L) peak.coerceIn(0f, 1f) else 0f

                val sample = LevelSample(tsNs = now, rms01 = rmsVal, peak01 = peakVal)

                if (pipelineRunning) {
                    synchronized(levelLock) {
                        levelRing.addLast(sample)
                        // Keep ~ (delay + headroom) window
                        val keepBefore = now - (VISUAL_DELAY_NS + 7_000_000_000L)
                        while (levelRing.size > 2 && levelRing.first().tsNs < keepBefore) {
                            levelRing.removeFirst()
                        }
                        // hard cap
                        while (levelRing.size > 1600) {
                            levelRing.removeFirst()
                        }
                    }
                } else {
                    synchronized(levelLock) { levelRing.clear() }
                }

                // ---- Read delayed level ----
                delayedLevel01 = readDelayedLevel01(now)
                delayedPeak01 = readDelayedPeak01(now)

                // Debug log every ~1.5s
                if ((now - lastLog) > 1_500_000_000L) {
                    lastLog = now
                    val q = pcmQueue.size
                    val dropped = pcmDropped.get()
                    Log.d(
                        TAG,
                        "LEVEL age=${ageMs}ms live(rms=${"%.3f".format(lastRms01)} peak=${"%.3f".format(lastPeak01)}) " +
                                "delayed=${"%.3f".format(delayedLevel01)} q=$q/6 dropped=$dropped pipeline=$pipelineRunning liveUi=$isLiveUi sidebar=$sidebarVisible"
                    )
                }
            }
        }

        // Hidden MediaRouteButton hook
        Box {
            AndroidView(
                modifier = Modifier.size(1.dp),
                factory = { ctx ->
                    MediaRouteButton(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(1, 1)
                        CastButtonFactory.setUpMediaRouteButton(this@MainActivity, this)
                        alpha = 0f
                        castButtonRef = this
                    }
                }
            )
        }

        // Root black background
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(isLiveUi, pipelineRunning) {
                    detectTapGestures {
                        if (isLiveUi && pipelineRunning) sidebarVisible = true
                    }
                }
        ) {
            // 1) Matrix visualizer (behind everything)
            MatrixVisualizerPanel(
                modifier = Modifier.fillMaxSize(),
                bands01 = demoBands,
                level01 = delayedLevel01,
                isLive = isLiveUi && pipelineRunning,
                panelLook = panelLook,
                volume01 = matrixVolume01,
                onVolume01Changed = { matrixVolume01 = it.coerceIn(0f, 1f) },
                paletteIndex = matrixPaletteIndex,
                onPaletteNext = { matrixPaletteIndex = (matrixPaletteIndex + 1) % 5 },
                modeIndex = matrixModeIndex,
                onModeNext = { matrixModeIndex = (matrixModeIndex + 1) % 6 },
                showBottomControls = true,
                gridW = 80,
                gridH = 20
            )

            // 2) Front panel overlay (keep as-is, still full size)
            FrontPanelLayer(
                Modifier.fillMaxSize(),
                level01 = delayedLevel01,
                peak01 = delayedPeak01,
                isLive = isLiveUi && pipelineRunning,
                castConnected = isCastConnected,
                sampleRateHz = sampleRateHz,
                bitrateBps = aacBitrateBps,
                castVolume01 = castVol01,
                onCastVolumeEditing = { editing -> isVolumeEditing = editing },
                onSetCastVolume01 = { ui01 ->
                    castVol01 = ui01
                    setCastVolumeFromUi01(ui01)
                },
                // ✅ Wire CLARITY/LOOK
                klaarteLevel = klaarteLevel,
                panelLook = panelLook,
                onKlaarteNext = { klaarteLevel = (klaarteLevel + 1) % 5 },
                onCycleLook = { panelLook = panelLook.next() },
                showTopBrand = true
            )

            // 3) Sidebar overlay (on top)
            if (sidebarVisible) {
                Phase7Screen(
                    initials = initials,
                    level01 = delayedLevel01,
                    castReady = isCastConnected,
                    onCastClick = { castButtonRef?.performClick() },
                    onPlayClick = {
                        if (!isCastConnected) {
                            toast("Kies eerst je Cast speaker.")
                            castButtonRef?.performClick()
                            return@Phase7Screen
                        }
                        if (!micGranted) {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@Phase7Screen
                        }
                        if (!pipelineRunning) {
                            val ok = play()
                            if (!ok) toast("Play failed.")
                        }
                    },
                    onStopClick = {
                        if (pipelineRunning) {
                            stopAll()
                            toast("Stopped.")
                        }
                        // Ensure sidebar is visible again after stop.
                        sidebarVisible = true
                        isLiveUi = false
                    },
                    onSidebarVisibleChanged = { visible -> sidebarVisible = visible },
                    onLiveChanged = { live -> isLiveUi = live }
                )
            }
        }
    }

    private fun readDelayedLevel01(nowNs: Long): Float {
        val target = nowNs - VISUAL_DELAY_NS
        synchronized(levelLock) {
            if (levelRing.isEmpty()) return 0f

            while (levelRing.size >= 2) {
                val b = levelRing.elementAt(1)
                if (b.tsNs <= target) {
                    levelRing.removeFirst()
                } else break
            }

            val best = levelRing.firstOrNull() ?: return 0f
            return best.rms01.coerceIn(0f, 1f)
        }
    }

    private fun readDelayedPeak01(nowNs: Long): Float {
        val target = nowNs - VISUAL_DELAY_NS
        synchronized(levelLock) {
            if (levelRing.isEmpty()) return 0f

            while (levelRing.size >= 2) {
                val b = levelRing.elementAt(1)
                if (b.tsNs <= target) {
                    levelRing.removeFirst()
                } else break
            }

            val best = levelRing.firstOrNull() ?: return 0f
            return best.peak01.coerceIn(0f, 1f)
        }
    }

    private fun play(): Boolean {
        pcmBytesIn.set(0); pcmFramesIn.set(0)
        aacBytesOut.set(0); aacFramesOut.set(0)

        lastRms01 = 0f
        lastPeak01 = 0f
        lastLevelUpdateNs = 0L
        pcmDropped.set(0)

        synchronized(levelLock) { levelRing.clear() }

        autoLoadPending = true

        val ok = startPipeline()
        if (!ok) {
            autoLoadPending = false
            return false
        }

        lastAction = "PLAY: pipeline started, waiting for first AAC frame…"
        toast("Starting stream…")
        return true
    }

    private fun startPipeline(): Boolean {
        if (encoder != null || micSource != null) return true

        val sampleRate = 48_000
        val channels = 1

        val enc = AacAdtsEncoder(sampleRate = sampleRate, channelCount = channels, bitrate = 96_000)
        val okEnc = enc.start(object : AacAdtsEncoder.Callback {
            override fun onAdtsFrame(frame: ByteArray, ptsUs: Long) {
                aacBytesOut.addAndGet(frame.size.toLong())
                aacFramesOut.incrementAndGet()
                httpServer.pushAacFrame(frame)

                if (autoLoadPending) {
                    autoLoadPending = false
                    lastAction = "First AAC frame → autoload"
                    runOnUiThread { maybeAutoLoadToCast() }
                }
            }

            override fun onEncoderError(t: Throwable) {
                Log.e(TAG, "Encoder error", t)
                lastAction = "Encoder error: ${t.javaClass.simpleName}"
                stopAll()
            }
        })
        if (!okEnc) {
            lastAction = "Encoder failed to start"
            return false
        }

        // Start feeder thread BEFORE mic starts
        startEncoderFeeder(enc)

        val src = MicAudioSource(sampleRate = sampleRate, channelCount = channels)
        val okMic = src.start { chunk ->
            // This callback must NEVER block.
            pcmBytesIn.addAndGet(chunk.sizeBytes.toLong())
            pcmFramesIn.incrementAndGet()

            val processed = dspEngine.processInPlace(
                pcm = chunk.data,
                sizeBytes = chunk.sizeBytes,
                channels = channels,
                sampleRate = sampleRate
            )

            // Update UI metering ALWAYS, even if encoder is slow.
            val (rms, peak) = rms01FromPcm16WithPeak(processed, chunk.sizeBytes)
            lastRms01 = rms
            lastPeak01 = max(peak, lastPeak01 * 0.86f) // peak decay
            lastLevelUpdateNs = System.nanoTime()

            // Enqueue for encoder thread (drop if congested)
            val pkt = PcmPacket(processed, chunk.sizeBytes, chunk.timestampNs)
            if (!pcmQueue.offer(pkt)) {
                pcmDropped.incrementAndGet()
                pcmQueue.poll()
                if (!pcmQueue.offer(pkt)) {
                    pcmDropped.incrementAndGet()
                }
            }
        }

        if (!okMic) {
            lastAction = "Mic failed to start"
            stopEncoderFeeder()
            enc.stop()
            return false
        }

        encoder = enc
        micSource = src
        pipelineRunning = true
        return true
    }

    private fun startEncoderFeeder(enc: AacAdtsEncoder) {
        feederRunning = true
        pcmQueue.clear()

        feederThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (feederRunning) {
                try {
                    val pkt = pcmQueue.take()
                    enc.offerPcm(pkt.bytes, pkt.sizeBytes, pkt.tsNs)
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.e(TAG, "Encoder feeder error", t)
                    runOnUiThread { stopAll() }
                    break
                }
            }
        }.apply {
            name = "EncoderFeeder"
            isDaemon = true
            start()
        }
    }

    private fun stopEncoderFeeder() {
        feederRunning = false
        try { feederThread?.interrupt() } catch (_: Throwable) {}
        try { feederThread?.join(300) } catch (_: Throwable) {}
        feederThread = null
        pcmQueue.clear()
    }

    private fun maybeAutoLoadToCast() {
        if (!pipelineRunning) return

        val session = castContext.sessionManager.currentCastSession
        if (session == null) {
            lastAction = "Waiting: connect Cast device"
            autoLoadPending = true
            return
        }

        hookRemoteCallbacks(session)
        val remote = session.remoteMediaClient ?: run {
            lastAction = "Autoload: RemoteMediaClient is null"
            autoLoadPending = true
            return
        }

        val ip = getLanIpv4() ?: run {
            lastAction = "Autoload: LAN IP not found"
            autoLoadPending = true
            return
        }

        val url = "http://$ip:9090/live.aac"
        lastUrl = url
        startLocalPlayback(url)
        Log.d(TAG, "STREAM URL = $url")
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, "Live Audio (AAC)")
            putString(MediaMetadata.KEY_ARTIST, "Audio Bridge v1")
        }

        val mediaInfo = MediaInfo.Builder(url)
            .setContentType("audio/aac")
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setMetadata(md)
            .build()

        val req = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)
            .build()

        lastAction = "Autoload: sending load()"
        toast("Casting…")
        remote.load(req)
    }

    private fun stopAll() {
        autoLoadPending = false
        pipelineRunning = false

        try { castContext.sessionManager.currentCastSession?.remoteMediaClient?.stop() } catch (_: Throwable) {}

        try { micSource?.stop() } catch (_: Throwable) {}
        try { micSource?.release() } catch (_: Throwable) {}
        micSource = null

        stopEncoderFeeder()

        try { encoder?.stop() } catch (_: Throwable) {}
        encoder = null

        synchronized(levelLock) { levelRing.clear() }

        lastAction = "Stopped"
    }

    private fun hookRemoteCallbacks(session: CastSession) {
        val remote = session.remoteMediaClient ?: return
        remoteCallback?.let { try { remote.unregisterCallback(it) } catch (_: Throwable) {} }

        val cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val status = remote.mediaStatus
                val title = status?.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE) ?: "none"
                Log.d(TAG, "Cast status updated: $title")
            }
        }
        remote.registerCallback(cb)
        remoteCallback = cb
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun isMicGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun rms01FromPcm16WithPeak(pcm: ByteArray, sizeBytes: Int): Pair<Float, Float> {
        if (sizeBytes < 2) return 0f to 0f

        var i = 0
        var sumSq = 0.0
        var count = 0
        var peak = 0.0

        val limit = (sizeBytes / 2) * 2
        while (i < limit) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() and 0xFF
            val s16 = ((hi shl 8) or lo).toShort().toInt()
            val s = s16 / 32768.0
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
            sumSq += s * s
            count++
            i += 2
        }

        if (count == 0) return 0f to 0f

        val rms = sqrt(sumSq / count)

        val rms01 = (rms * 1.25).coerceIn(0.0, 1.0).toFloat()
        val peak01 = (peak * 1.10).coerceIn(0.0, 1.0).toFloat()
        return rms01 to peak01
    }

    private fun getLanIpv4(): String? {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    // ---- Cast volume bridge (UI-only) ----

    private fun readCastVolume01OrNull(): Float? {
        val session = castContext.sessionManager.currentCastSession ?: return null
        return try {
            session.volume.toFloat().coerceIn(0f, 1f)
        } catch (_: Throwable) {
            null
        }
    }

    private fun setCastVolumeFromUi01(ui01: Float) {
        val session = castContext.sessionManager.currentCastSession ?: return
        val u = ui01.coerceIn(0f, 1f)

        val gamma = 2.2
        val mapped = u.toDouble().pow(gamma).toFloat().coerceIn(0f, 1f)

        try {
            session.setVolume(mapped.toDouble())
        } catch (_: Throwable) {
            // ignore
        }
    }

            private fun startLocalPlayback(url: String) {
                try {
                    localPlayer?.release()

                    val player = android.media.MediaPlayer()
                    player.setDataSource(url)
                    player.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC)

                    player.setOnPreparedListener {
                        it.start()
                    }

                    player.prepareAsync()
                    localPlayer = player

                } catch (e: Exception) {
                    Log.e(TAG, "Local playback error", e)
                }
            }

}