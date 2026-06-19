Lorenzo Vandekerckhove 
package com.example.analogtowifispeakers

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.mediarouter.app.MediaRouteButton
import com.example.analogtowifispeakers.audio.DspEngine
import com.example.analogtowifispeakers.audio.MicAudioSource
import com.example.analogtowifispeakers.ui.matrix.MatrixVisualizerPanel
import com.example.analogtowifispeakers.ui.theme.FrontPanelLayer
import com.example.analogtowifispeakers.ui.theme.PanelLook
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
import kotlinx.coroutines.delay

class MainActivity : FragmentActivity() {

    private lateinit var castContext: CastContext
    private lateinit var httpServer: LocalHttpServer

    private var micSource: MicAudioSource? = null
    private var ffmpegMuxer: FfmpegHlsMuxer? = null
    private var localPlayer: MediaPlayer? = null
    private val dspEngine = DspEngine()

    @Volatile private var pipelineRunning: Boolean = false
    @Volatile private var autoStartAttempted: Boolean = false
    @Volatile private var castLoadRequested: Boolean = false

    private val pcmBytesIn = AtomicLong(0)
    private val pcmFramesIn = AtomicLong(0)
    private val muxBytesOut = AtomicLong(0)
    private val muxFramesOut = AtomicLong(0)

    @Volatile private var lastRms01: Float = 0f
    @Volatile private var lastPeak01: Float = 0f
    @Volatile private var lastLevelUpdateNs: Long = 0L

    private val pcmDropped = AtomicLong(0)
    @Volatile private var feederRunning: Boolean = false
    private var feederThread: Thread? = null

    private data class PcmPacket(val bytes: ByteArray, val sizeBytes: Int, val tsNs: Long)
    private val pcmQueue = ArrayBlockingQueue<PcmPacket>(6)

    @Volatile private var lastUrl: String = ""
    @Volatile private var lastAction: String = "No actions yet"

    private val TAG = "AudioBridge"
    private val castConnectedState = mutableStateOf(false)

    private data class LevelSample(val tsNs: Long, val rms01: Float, val peak01: Float)
    private val levelLock = Any()
    private val levelRing = ArrayDeque<LevelSample>(1200)

    private val VISUAL_DELAY_NS = 5_000_000_000L

    private val castListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            lastAction = "Cast session started"
            castConnectedState.value = true
            hookRemoteCallbacks(session)

            if (castLoadRequested) {
                maybeAutoLoadToCast()
            }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            lastAction = "Cast session resumed"
            castConnectedState.value = true
            hookRemoteCallbacks(session)

            if (castLoadRequested) {
                maybeAutoLoadToCast()
            }
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            lastAction = "Cast session ended ($error)"
            castConnectedState.value = false
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            lastAction = "Cast start failed ($error)"
            castConnectedState.value = false
            castLoadRequested = false
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            lastAction = "Cast resume failed ($error)"
            castConnectedState.value = false
            castLoadRequested = false
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
        Log.d("MainActivityFF", "FFmpeg MainActivity onCreate reached")

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
        } catch (_: Throwable) {
        }
        stopAll()
        httpServer.stop()
    }

    @Composable
    private fun Phase7Root() {
        val sampleRateHz = 48_000
        val hlsBitrateBps = 96_000

        var micGranted by remember { mutableStateOf(isMicGranted()) }
        val micPermLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted -> micGranted = granted }

        var castButtonRef by remember { mutableStateOf<MediaRouteButton?>(null) }

        var delayedLevel01 by remember { mutableStateOf(0f) }
        var delayedPeak01 by remember { mutableStateOf(0f) }
        var liveStreamLevel01 by remember { mutableStateOf(0f) }

        var isLiveUi by remember { mutableStateOf(false) }

        var pipelineRunningUi by remember { mutableStateOf(pipelineRunning) }
        var liveClockStartMs by remember { mutableStateOf<Long?>(null) }
        var liveClockText by remember { mutableStateOf("00:00") }

        LaunchedEffect(micGranted) {
            Log.d(
                "PIPELINE",
                "LaunchedEffect(micGranted) called, micGranted=$micGranted autoStartAttempted=$autoStartAttempted pipelineRunning=$pipelineRunning"
            )

            if (!micGranted) {
                if (!autoStartAttempted) {
                    Log.d("PIPELINE", "Requesting RECORD_AUDIO permission")
                    micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                return@LaunchedEffect
            }

            if (!pipelineRunning && !autoStartAttempted) {
                autoStartAttempted = true
                Log.d("PIPELINE", "Auto-start attempt -> startLocalMonitoring()")
                val ok = startLocalMonitoring()
                Log.d("PIPELINE", "Auto-start result = $ok")
                if (!ok) {
                    toast("Auto start failed.")
                } else {
                    isLiveUi = true
                }
            }
        }

        LaunchedEffect(isLiveUi, pipelineRunningUi) {
            if (isLiveUi && pipelineRunningUi) {
                if (liveClockStartMs == null) {
                    liveClockStartMs = SystemClock.elapsedRealtime()
                }

                while (isLiveUi && pipelineRunningUi) {
                    val startedAt = liveClockStartMs ?: SystemClock.elapsedRealtime().also {
                        liveClockStartMs = it
                    }

                    val elapsedSec =
                        ((SystemClock.elapsedRealtime() - startedAt) / 1000L).coerceAtLeast(0L)

                    val minutes = elapsedSec / 60L
                    val seconds = elapsedSec % 60L

                    liveClockText = String.format("%02d:%02d", minutes, seconds)

                    delay(250)
                }
            } else {
                liveClockStartMs = null
                liveClockText = "00:00"
            }
        }

        var castVol01 by remember { mutableStateOf(readCastVolume01OrNull() ?: 0.50f) }
        var isVolumeEditing by remember { mutableStateOf(false) }

        var klaarteLevel by remember { mutableIntStateOf(2) }
        var panelLook by remember { mutableStateOf(PanelLook.GOLD) }

        var matrixPaletteIndex by remember { mutableIntStateOf(0) }
        var matrixModeIndex by remember { mutableIntStateOf(0) }
        var matrixVolume01 by remember { mutableFloatStateOf(0.55f) }

        var demoT by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (true) {
                delay(16)
                demoT = (System.nanoTime() - start) / 1_000_000_000f
            }
        }

        val demoBands: List<Float> = remember(demoT, delayedLevel01, pipelineRunningUi) {
            val base = if (pipelineRunningUi) delayedLevel01.coerceIn(0f, 1f) else 0.35f
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
                delay(50)

                if (pipelineRunningUi != pipelineRunning) {
                    pipelineRunningUi = pipelineRunning
                }

                val now = System.nanoTime()

                val sessionNow = castContext.sessionManager.currentCastSession
                if ((sessionNow != null) != castConnectedState.value) {
                    castConnectedState.value = (sessionNow != null)
                }

                tick++
                if (tick % 12 == 0) {
                    if (!isVolumeEditing) {
                        val v = readCastVolume01OrNull()
                        if (v != null && abs(v - castVol01) > 0.02f) {
                            castVol01 = v
                        }
                    }
                }

                val ageMs = (now - lastLevelUpdateNs) / 1_000_000L
                val rms = lastRms01
                val peak = lastPeak01

                val rmsVal = if (pipelineRunningUi && ageMs <= 750L) rms.coerceIn(0f, 1f) else 0f
                val peakVal = if (pipelineRunningUi && ageMs <= 750L) peak.coerceIn(0f, 1f) else 0f

                val sample = LevelSample(tsNs = now, rms01 = rmsVal, peak01 = peakVal)

                if (pipelineRunningUi) {
                    synchronized(levelLock) {
                        levelRing.addLast(sample)
                        val keepBefore = now - (VISUAL_DELAY_NS + 7_000_000_000L)
                        while (levelRing.size > 2 && levelRing.first().tsNs < keepBefore) {
                            levelRing.removeFirst()
                        }
                        while (levelRing.size > 1600) {
                            levelRing.removeFirst()
                        }
                    }
                } else {
                    synchronized(levelLock) { levelRing.clear() }
                }

                delayedLevel01 = readDelayedLevel01(now)
                delayedPeak01 = readDelayedPeak01(now)
                liveStreamLevel01 = rmsVal

                if ((now - lastLog) > 1_500_000_000L) {
                    lastLog = now
                    val q = pcmQueue.size
                    val dropped = pcmDropped.get()
                    Log.d(
                        TAG,
                        "LEVEL age=${ageMs}ms live(rms=${"%.3f".format(lastRms01)} peak=${"%.3f".format(lastPeak01)}) " +
                                "delayed=${"%.3f".format(delayedLevel01)} q=$q/6 dropped=$dropped pipeline=$pipelineRunningUi liveUi=$isLiveUi"
                    )
                }
            }
        }

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

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            MatrixVisualizerPanel(
                modifier = Modifier.fillMaxSize(),
                bands01 = demoBands,
                level01 = delayedLevel01,
                isLive = isLiveUi && pipelineRunningUi,
                panelLook = panelLook,
                volume01 = matrixVolume01,
                onVolume01Changed = { matrixVolume01 = it.coerceIn(0f, 1f) },
                paletteIndex = matrixPaletteIndex,
                onPaletteNext = { matrixPaletteIndex = (matrixPaletteIndex + 1) % 5 },
                modeIndex = matrixModeIndex,
                onModeNext = { matrixModeIndex = (matrixModeIndex + 1) % 11 },
                showBottomControls = true,
                gridW = 80,
                gridH = 20
            )

            FrontPanelLayer(
                Modifier.fillMaxSize(),
                level01 = delayedLevel01,
                peak01 = delayedPeak01,
                isLive = isLiveUi && pipelineRunningUi,
                castConnected = isCastConnected,
                sampleRateHz = sampleRateHz,
                bitrateBps = hlsBitrateBps,
                castVolume01 = castVol01,
                onCastVolumeEditing = { editing -> isVolumeEditing = editing },
                onSetCastVolume01 = { ui01 ->
                    castVol01 = ui01
                    setCastVolumeFromUi01(ui01)
                },
                klaarteLevel = klaarteLevel,
                panelLook = panelLook,
                onKlaarteNext = { klaarteLevel = (klaarteLevel + 1) % 5 },
                onCycleLook = { panelLook = panelLook.next() },
                showTopBrand = true,
                streamRunning = pipelineRunningUi,
                streamLatencyText = liveClockText,
                streamDisplayText = compactStreamDisplay(),
                streamVuTop01 = liveStreamLevel01,
                streamVuBottom01 = liveStreamLevel01,
                onStreamPlayClick = {
                    Log.d("PIPELINE", "FrontPanel PLAY clicked, micGranted=$micGranted pipelineRunning=$pipelineRunning")
                    if (!micGranted) {
                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (!pipelineRunning) {
                        val ok = startLocalMonitoring()
                        Log.d("PIPELINE", "FrontPanel PLAY result = $ok")
                        if (!ok) {
                            toast("Start failed.")
                        } else {
                            isLiveUi = true
                        }
                    }
                },
                onStreamStopClick = {
                    Log.d("PIPELINE", "FrontPanel STOP clicked")
                    if (pipelineRunning) {
                        stopAll()
                        toast("Stopped.")
                    }
                    isLiveUi = false
                    autoStartAttempted = false
                },
                onStreamCastClick = {
                    Log.d("PIPELINE", "FrontPanel CAST clicked")
                    castLoadRequested = true
                    castButtonRef?.let { button ->
                        button.post { button.performClick() }
                    }
                }
            )
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
                } else {
                    break
                }
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
                } else {
                    break
                }
            }

            val best = levelRing.firstOrNull() ?: return 0f
            return best.peak01.coerceIn(0f, 1f)
        }
    }

    private fun startLocalMonitoring(): Boolean {
        Log.d("PIPELINE", "startLocalMonitoring called")

        pcmBytesIn.set(0)
        pcmFramesIn.set(0)
        muxBytesOut.set(0)
        muxFramesOut.set(0)

        lastRms01 = 0f
        lastPeak01 = 0f
        lastLevelUpdateNs = 0L
        pcmDropped.set(0)

        synchronized(levelLock) { levelRing.clear() }

        val ok = startPipeline()
        Log.d("PIPELINE", "startPipeline result = $ok")
        if (!ok) {
            return false
        }

        val ip = getLanIpv4()
        if (ip != null) {
            val url = "http://$ip:9090/live.m3u8"
            lastUrl = url
            Log.d(TAG, "STREAM URL = $url")
            startLocalPlayback(url)
        } else {
            Log.w(TAG, "Local playback skipped: LAN IP not found")
        }

        lastAction = "Local stream started"
        toast("Local stream started")
        return true
    }

    private fun startPipeline(): Boolean {
        Log.d("PIPELINE", "startPipeline called")
        Log.d("PIPELINE", "Before start: ffmpegMuxer=${ffmpegMuxer != null} micSource=${micSource != null}")

        if (ffmpegMuxer != null || micSource != null) return true

        val sampleRate = 48_000
        val channels = 1
        val bitrate = 96_000

        val muxer = FfmpegHlsMuxer(
            context = this,
            sampleRate = sampleRate,
            channelCount = channels,
            bitrate = bitrate,
            onFatalError = { t ->
                Log.e(TAG, "FFmpeg muxer error", t)
                lastAction = "FFmpeg error: ${t.javaClass.simpleName}"
                runOnUiThread { stopAll() }
            }
        )

        Log.d("PIPELINE", "Calling muxer.start()")
        val okMuxer = muxer.start()
        Log.d("PIPELINE", "muxer.start() result = $okMuxer")

        if (!okMuxer) {
            lastAction = "FFmpeg muxer failed to start"
            return false
        }

        httpServer.setHlsRootDir(muxer.outputDir)
        Log.d("PIPELINE", "HLS root dir assigned = ${muxer.outputDir.absolutePath}")

        startMuxerFeeder(muxer)

        val src = MicAudioSource(sampleRate = sampleRate, channelCount = channels)
        Log.d("PIPELINE", "Calling micSource.start()")
        val okMic = src.start { chunk ->
            pcmBytesIn.addAndGet(chunk.sizeBytes.toLong())
            pcmFramesIn.incrementAndGet()

            val processed = dspEngine.processInPlace(
                pcm = chunk.data,
                sizeBytes = chunk.sizeBytes,
                channels = channels,
                sampleRate = sampleRate
            )

            val (rms, peak) = rms01FromPcm16WithPeak(processed, chunk.sizeBytes)
            lastRms01 = rms
            lastPeak01 = max(peak, lastPeak01 * 0.86f)
            lastLevelUpdateNs = System.nanoTime()

            val pkt = PcmPacket(processed, chunk.sizeBytes, chunk.timestampNs)
            if (!pcmQueue.offer(pkt)) {
                pcmDropped.incrementAndGet()
                pcmQueue.poll()
                if (!pcmQueue.offer(pkt)) {
                    pcmDropped.incrementAndGet()
                }
            }
        }
        Log.d("PIPELINE", "micSource.start() result = $okMic")

        if (!okMic) {
            lastAction = "Mic failed to start"
            stopMuxerFeeder()
            muxer.stop()
            return false
        }

        ffmpegMuxer = muxer
        micSource = src
        pipelineRunning = true
        Log.d("PIPELINE", "Pipeline fully started")
        return true
    }

    private fun startMuxerFeeder(muxer: FfmpegHlsMuxer) {
        Log.d("PIPELINE", "startMuxerFeeder called")
        feederRunning = true
        pcmQueue.clear()

        feederThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (feederRunning) {
                try {
                    val pkt = pcmQueue.take()
                    if (muxer.offerPcm(pkt.bytes, pkt.sizeBytes)) {
                        muxBytesOut.addAndGet(pkt.sizeBytes.toLong())
                        muxFramesOut.incrementAndGet()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    Log.e(TAG, "Muxer feeder error", t)
                    runOnUiThread { stopAll() }
                    break
                }
            }
        }.apply {
            name = "MuxerFeeder"
            isDaemon = true
            start()
        }
    }

    private fun stopMuxerFeeder() {
        feederRunning = false
        try { feederThread?.interrupt() } catch (_: Throwable) {}
        try { feederThread?.join(300) } catch (_: Throwable) {}
        feederThread = null
        pcmQueue.clear()
    }

    private fun maybeAutoLoadToCast() {
        if (!pipelineRunning) {
            toast("Lokale stream is nog niet gestart.")
            return
        }

        val session = castContext.sessionManager.currentCastSession
        if (session == null) {
            lastAction = "Waiting: connect Cast device"
            toast("Kies eerst een Cast speaker.")
            return
        }

        hookRemoteCallbacks(session)

        val ip = getLanIpv4() ?: run {
            lastAction = "Cast failed: LAN IP not found"
            toast("LAN IP niet gevonden.")
            return
        }

        val url = "http://$ip:9090/live.m3u8"

        lastUrl = url
        Log.d(TAG, "STREAM URL = $url")

        Thread {
            try {
                Log.d(TAG, "Warmup before casting (browser style)")
                Thread.sleep(2500)
            } catch (_: Exception) {
            }

            runOnUiThread {
                lastAction = "CastStreamLoader.loadLiveHls"
                toast("Casting…")

                val ok = CastStreamLoader.loadLiveHls(
                    context = this@MainActivity,
                    url = url,
                    title = "Analog to WiFi Speakers"
                )

                if (ok) {
                    lastAction = "Cast LOAD requested"
                    castLoadRequested = false
                } else {
                    lastAction = "Cast LOAD failed"
                    toast("Cast LOAD failed")
                }
            }
        }.start()
    }

    private fun stopAll() {
        castLoadRequested = false
        pipelineRunning = false

        try { castContext.sessionManager.currentCastSession?.remoteMediaClient?.stop() } catch (_: Throwable) {}

        try { localPlayer?.stop() } catch (_: Throwable) {}
        try { localPlayer?.release() } catch (_: Throwable) {}
        localPlayer = null

        try { micSource?.stop() } catch (_: Throwable) {}
        try { micSource?.release() } catch (_: Throwable) {}
        micSource = null

        stopMuxerFeeder()

        try { ffmpegMuxer?.stop() } catch (_: Throwable) {}
        ffmpegMuxer = null
        httpServer.setHlsRootDir(null)

        synchronized(levelLock) { levelRing.clear() }

        lastAction = "Stopped"
    }

    private fun hookRemoteCallbacks(session: CastSession) {
        val remote = session.remoteMediaClient ?: return
        remoteCallback?.let {
            try { remote.unregisterCallback(it) } catch (_: Throwable) {}
        }

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

    private fun startLocalPlayback(url: String) {
        try {
            try { localPlayer?.stop() } catch (_: Throwable) {}
            try { localPlayer?.release() } catch (_: Throwable) {}
            localPlayer = null

            val player = MediaPlayer()

            player.setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )

            player.setVolume(1.0f, 1.0f)

            player.setOnPreparedListener {
                Log.d(TAG, "Local player prepared → start()")
                it.start()
            }

            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Local player error: what=$what extra=$extra")
                false
            }

            player.setOnInfoListener { _, what, extra ->
                Log.d(TAG, "Local player info: what=$what extra=$extra")
                false
            }

            player.setOnCompletionListener {
                Log.w(TAG, "Local player completed unexpectedly")
            }

            player.setDataSource(url)
            localPlayer = player

            Log.d(TAG, "Local player prepareAsync: $url")
            player.prepareAsync()

        } catch (e: Exception) {
            Log.e(TAG, "Local playback error", e)
        }
    }

    private fun compactStreamDisplay(): String {
        val url = lastUrl.ifBlank {
            val ip = getLanIpv4() ?: return "--"
            "http://$ip:9090/audio.m3u8"
        }

        val raw = url
            .removePrefix("http://")
            .removePrefix("https://")
            .removeSuffix("/audio.m3u8")
            .removeSuffix("/live.m3u8")
            .trim()

        return raw.ifBlank { "--" }
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

        val noiseFloor = 0.030
        val gated = rms - noiseFloor
        if (gated <= 0.0) return 0f to 0f

        val k = 14.0
        val vu = kotlin.math.ln1p(k * gated) / kotlin.math.ln1p(k)

        val rms01 = (vu * 1.10).coerceIn(0.0, 1.0).toFloat()
        val peak01 = peak.coerceIn(0.0, 1.0).toFloat()

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
}