package com.example.analogtowifispeakers

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SpectrumAnalyzer — FORENSIC "PROOF OF CALL" BUILD
 *
 * Doel:
 * - 100% bewijzen dat offerPcm16le() in de draaiende app wordt aangeroepen.
 * - Zelfs met STILTE moet levels16 bewegen via een test-pulse patroon.
 *
 * Pipeline untouched. Pure analyzer.
 */
class SpectrumAnalyzer(
    private val sampleRate: Int,
    val fftSize: Int = 1024,
    private val bands: Int = 16,
    private val minFreqHz: Float = 35f,
    private val maxFreqHz: Float = 16000f
) {
    private val lock = Any()

    // ---- Public output (compat) ----
    val latestLevels16: FloatArray
        get() = snapshot01()
    fun latestLevels16(): FloatArray = snapshot01()

    val latestBins128: FloatArray
        get() = snapshotBins128()
    fun latestBins128(): FloatArray = snapshotBins128()

    // ---- HARD PROOF DIAGNOSTICS ----
    @Volatile var debugOffers: Long = 0L
        private set
    @Volatile var debugLastByteCount: Int = 0
        private set
    @Volatile var debugTestPhase: Int = 0
        private set
    @Volatile var debugLastChunkRms: Float = 0f
        private set

    // ---- Window + FFT buffers ----
    private val window = FloatArray(fftSize) { i ->
        val ang = (2.0 * PI * i.toDouble() / (fftSize - 1).toDouble())
        (0.5 - 0.5 * cos(ang)).toFloat()
    }
    private val windowSum: Float = window.sum().coerceAtLeast(1e-6f)

    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)

    // ---- Band edges ----
    private val bandStarts = IntArray(bands)
    private val bandEnds = IntArray(bands)

    // ---- Output buffers ----
    private val smoothed01 = FloatArray(bands) // 0..1
    private val latestDb = FloatArray(bands)

    private val bins128Cache = FloatArray(128)

    // ---- Frame assembly ----
    private val floatFrame = FloatArray(fftSize)
    private var frameFill = 0

    // ---- Cache for band mapping ----
    private var cachedNyquist: Float = sampleRate.toFloat() * 0.5f
    private var cachedMin: Float = minFreqHz
    private var cachedMax: Float = maxFreqHz

    init {
        require(isPowerOfTwo(fftSize)) { "fftSize must be power-of-two (got $fftSize)" }
        require(sampleRate > 0) { "sampleRate must be > 0" }
        require(bands == 16) { "Project expects 16 bands (got $bands)" }
        computeLogBandBinRanges()
    }

    /** PCM16 mono little-endian bytes */
    fun offerPcm16le(pcmBytes: ByteArray, byteCount: Int = pcmBytes.size) {
        debugOffers += 1
        debugLastByteCount = byteCount.coerceAtLeast(0)

        // === PROOF-OF-CALL TEST PULSE ===
        // Elke call: roteer een piek door de 16 bands.
        // Dit MOET beweging geven in de visualizer, zelfs bij stilte.
        injectTestPulse()

        // === Optional: RMS meten (extra bewijs) ===
        debugLastChunkRms = chunkRmsFast(pcmBytes, byteCount)

        // === FFT frame assembly (niet de focus nu) ===
        val n = min(byteCount, pcmBytes.size)
        var idx = 0
        while (idx + 1 < n) {
            val lo = pcmBytes[idx].toInt() and 0xFF
            val hi = pcmBytes[idx + 1].toInt()
            val s = ((hi shl 8) or lo).toShort()
            floatFrame[frameFill++] = s.toInt() / 32768f

            if (frameFill >= fftSize) {
                analyzeFloatFrame(floatFrame)
                frameFill = 0
            }
            idx += 2
        }
    }

    /** PCM16 mono (ShortArray) */
    fun offerPcm16le(pcm16le: ShortArray, sampleCount: Int = pcm16le.size) {
        debugOffers += 1
        debugLastByteCount = (sampleCount * 2).coerceAtLeast(0)
        injectTestPulse()

        val n = min(sampleCount, pcm16le.size)
        var i = 0
        while (i < n) {
            val remaining = fftSize - frameFill
            val take = min(remaining, n - i)
            for (j in 0 until take) {
                floatFrame[frameFill + j] = pcm16le[i + j].toInt() / 32768f
            }
            frameFill += take
            i += take
            if (frameFill >= fftSize) {
                analyzeFloatFrame(floatFrame)
                frameFill = 0
            }
        }
    }

    private fun injectTestPulse() {
        val phase = (debugTestPhase + 1) % 16
        debugTestPhase = phase

        synchronized(lock) {
            // zachte decay + één band popt omhoog
            for (b in 0 until 16) {
                smoothed01[b] = (smoothed01[b] * 0.82f).coerceIn(0f, 1f)
            }
            smoothed01[phase] = max(smoothed01[phase], 0.85f)

            // mids kleine boost volgens spec
            for (b in 6..9) {
                smoothed01[b] = (smoothed01[b] * 1.06f).coerceIn(0f, 1f)
            }
        }
    }

    private fun snapshot01(): FloatArray {
        synchronized(lock) { return smoothed01.copyOf() }
    }

    private fun snapshotBins128(): FloatArray {
        synchronized(lock) {
            val last = bands - 1
            for (i in 0 until 128) {
                val x = (i / 127f) * last.toFloat()
                val i0 = x.toInt().coerceIn(0, last)
                val i1 = (i0 + 1).coerceIn(0, last)
                val t = (x - i0.toFloat()).coerceIn(0f, 1f)
                val v = smoothed01[i0] + (smoothed01[i1] - smoothed01[i0]) * t
                bins128Cache[i] = v.coerceIn(0f, 1f)
            }
            return bins128Cache.copyOf()
        }
    }

    private fun chunkRmsFast(pcmBytes: ByteArray, byteCount: Int): Float {
        val n = min(byteCount, pcmBytes.size)
        if (n < 4) return 0f
        var idx = 0
        var sumSq = 0.0
        var count = 0
        while (idx + 1 < n) {
            val lo = pcmBytes[idx].toInt() and 0xFF
            val hi = pcmBytes[idx + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toInt()
            val s = sample / 32768.0
            sumSq += s * s
            count++
            idx += 8 // sample 1 op 4
        }
        if (count <= 0) return 0f
        return sqrt(sumSq / count).toFloat()
    }

    // ======= FFT path (later premium) =======
    private fun analyzeFloatFrame(frame: FloatArray) {
        val n = fftSize
        val sr = sampleRate
        val half = n / 2
        val eps = 1e-12f

        val nyquist = sr.toFloat() * 0.5f
        val fMin = min(max(minFreqHz, 1f), nyquist - 1f)
        val fMax = min(max(maxFreqHz, fMin + 1f), nyquist)
        if (cachedNyquist != nyquist || cachedMin != fMin || cachedMax != fMax) {
            synchronized(lock) {
                cachedNyquist = nyquist
                cachedMin = fMin
                cachedMax = fMax
                computeLogBandBinRanges()
            }
        }

        for (i in 0 until n) {
            re[i] = frame[i] * window[i]
            im[i] = 0f
        }

        fftInPlace(re, im)

        for (b in 0 until bands) {
            var start = bandStarts[b].coerceIn(1, half - 1)
            var end = bandEnds[b].coerceIn(start + 1, half)

            var sumPower = 0.0
            val cnt = (end - start).coerceAtLeast(1)

            for (k in start until end) {
                val r = re[k]
                val ii = im[k]
                sumPower += (r * r + ii * ii).toDouble()
            }

            val meanPower = sumPower / cnt.toDouble()
            val rmsBin = sqrt(meanPower).toFloat()
            val rmsAmp = (2f * rmsBin / windowSum).coerceAtLeast(eps)
            val db = 20f * log10(rmsAmp)
            latestDb[b] = db
        }

        val dbMin = -110f
        val dbMax = -18f

        synchronized(lock) {
            for (b in 0 until bands) {
                val norm01 = ((latestDb[b] - dbMin) / (dbMax - dbMin)).coerceIn(0f, 1f)
                val y = smoothed01[b]
                val a = if (norm01 > y) 0.25f else 0.10f
                smoothed01[b] = y + (norm01 - y) * a
            }
        }
    }

    private fun computeLogBandBinRanges() {
        val n = fftSize
        val sr = sampleRate.toFloat()
        val half = n / 2

        val nyquist = sr * 0.5f
        val fMin = min(max(cachedMin, 1f), nyquist - 1f)
        val fMax = min(max(cachedMax, fMin + 1f), nyquist)

        val ratio = fMax / fMin
        val lnRatio = ln(ratio)

        for (b in 0 until bands) {
            val f0 = fMin * exp(lnRatio * (b.toFloat() / bands.toFloat()))
            val f1 = fMin * exp(lnRatio * ((b + 1).toFloat() / bands.toFloat()))

            val k0 = freqToBin(f0, sr, n).coerceIn(1, half - 1)
            val k1 = freqToBin(f1, sr, n).coerceIn(k0 + 1, half)

            bandStarts[b] = k0
            bandEnds[b] = k1
        }

        for (b in 1 until bands) {
            if (bandStarts[b] < bandEnds[b - 1]) bandStarts[b] = min(bandEnds[b - 1], half - 1)
            if (bandEnds[b] <= bandStarts[b]) bandEnds[b] = min(bandStarts[b] + 1, half)
        }
    }

    private fun freqToBin(freqHz: Float, sampleRate: Float, n: Int): Int {
        return (freqHz * n.toFloat() / sampleRate).roundToInt()
    }

    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = (-2.0 * PI / len.toDouble())
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()

            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val halfLen = len shr 1

                for (k in 0 until halfLen) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + halfLen]
                    val vIm = im[i + k + halfLen]

                    val tRe = vRe * wRe - vIm * wIm
                    val tIm = vRe * wIm + vIm * wRe

                    re[i + k] = uRe + tRe
                    im[i + k] = uIm + tIm
                    re[i + k + halfLen] = uRe - tRe
                    im[i + k + halfLen] = uIm - tIm

                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    val nextWIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                    wIm = nextWIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    private fun isPowerOfTwo(x: Int): Boolean = x > 0 && (x and (x - 1)) == 0
}