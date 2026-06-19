// FILE: app/src/main/java/com/example/analogtowifispeakers/ui/theme/VisualizerLayer.kt
package com.example.analogtowifispeakers.ui.theme

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * VisualizerLayer (UI-only)
 * - Pipeline untouched
 * - Continuous redraw (frameTicker)
 * - Modes (swipe): PRO ↔ PARTY ↔ VU ↔ AURA
 * - Upgrades:
 *   ✅ Bars: more spacing, mid-sheen band, vertical highlight
 *   ✅ Peaks: glow dot + halo
 *   ✅ VU: studio backlight + more detail + more swing
 *   ✅ Aura: waveform/energy ribbon (blue→purple) like ref image
 */
@Composable
fun VisualizerLayer(
    levels16: FloatArray? = null,
    bins: FloatArray? = null,
    modifier: Modifier = Modifier
) {
    val swipeThresholdPx = with(LocalDensity.current) { 32.dp.toPx() }
    val scope = rememberCoroutineScope()

    // Always read latest parameters inside render loop
    val latestLevels16 by rememberUpdatedState(levels16)
    val latestBins by rememberUpdatedState(bins)

    // Internal smoothing resolution
    val n = 128
    val drawCount = 64 // spacing
    val smooth = remember { FloatArray(n) { 0f } }
    val peak = remember { FloatArray(n) { 0f } }
    val peakHold = remember { FloatArray(n) { 0f } }

    var agcGain by remember { mutableFloatStateOf(1.0f) }

    // VU
    var vuLevel by remember { mutableFloatStateOf(0f) }

    // Modes
    var modeIndex by remember { mutableIntStateOf(0) }
    val modes = remember { listOf(VizMode.PRO, VizMode.PARTY, VizMode.VU, VizMode.AURA) }
    var currentMode by remember { mutableStateOf(modes[0]) }
    var targetMode by remember { mutableStateOf(modes[0]) }
    val modeT = remember { Animatable(1f) }

    // Continuous redraw ticker
    var frameTicker by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { frameTicker += 1 }
        }
    }

    // Frame update loop (smooth/peak/hold + VU)
    LaunchedEffect(Unit) {
        val smoothUp = 0.42f
        val smoothDown = 0.20f

        val holdSeconds = 0.90f
        val peakDecayPerSecond = 1.05f

        val target = 0.55f
        val gainRise = 0.060f
        val gainFall = 0.030f
        val gainMin = 0.85f
        val gainMax = 10.0f

        val vuAttack = 0.26f
        val vuRelease = 0.060f

        // --- SIGNATURE FIX: VU should return to real 0 at silence ---
        val vuDeadZone = 0.020f

        var lastNanos = 0L

        while (true) {
            withFrameNanos { now ->
                if (lastNanos == 0L) lastNanos = now
                val dt = (now - lastNanos) / 1_000_000_000f
                lastNanos = now

                val l16 = latestLevels16
                val b128 = latestBins

                val src = when {
                    b128 != null -> b128.safeN(n)
                    l16 != null -> binsFromLevels16(l16, n)
                    else -> ZERO_128
                }

                // Energy estimate (RMS-ish of bins)
                var sumSq = 0f
                var m = 0f
                for (i in 0 until n) {
                    val v = src[i].clamp01()
                    sumSq += v * v
                    m = max(m, v)
                }
                val energy = sqrt(sumSq / n.toFloat()).clamp01()

                // Visual-only AGC
                val desired = if (m < 1e-4f) 1.0f else (target / m).coerceIn(gainMin, gainMax)
                val step = if (desired > agcGain) gainRise else gainFall
                agcGain = agcGain + (desired - agcGain) * step

                // --- SIGNATURE FIX: less aggressive VU + deadzone to hit 0 at silence ---
                val energyGated = if (energy < vuDeadZone) 0f else energy
                val vuTarget = (energyGated * 1.45f).coerceIn(0f, 1f) // was 1.85f (too hot)
                val a = if (vuTarget > vuLevel) {
                    vuAttack
                } else {
                    if (vuTarget == 0f) 0.14f else vuRelease
                }
                vuLevel = (vuLevel + (vuTarget - vuLevel) * a).coerceIn(0f, 1f)

                // Smooth + peak hold
                for (i in 0 until n) {
                    val raw = (src[i].clamp01() * agcGain).coerceIn(0f, 1f)
                    val mapped = mapForSpikes(raw)

                    val y = smooth[i]
                    val alpha = if (mapped > y) smoothUp else smoothDown
                    val s = y + (mapped - y) * alpha
                    smooth[i] = s

                    if (s >= peak[i]) {
                        peak[i] = s
                        peakHold[i] = holdSeconds
                    } else {
                        if (peakHold[i] > 0f) {
                            peakHold[i] = max(0f, peakHold[i] - dt)
                        } else {
                            peak[i] = max(s, peak[i] - peakDecayPerSecond * dt)
                        }
                    }
                }
            }
        }
    }

    // Swipe detector: cycle modes
    val gestureMod = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointerId: PointerId = down.id

            var totalDx = 0f
            var isSwiping = false
            var last: PointerInputChange = down

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                if (!change.pressed) break

                val dx = change.position.x - last.position.x
                totalDx += dx
                last = change

                if (!isSwiping && abs(totalDx) >= swipeThresholdPx) isSwiping = true
                if (isSwiping) change.consumePositionChange()
            }

            if (isSwiping) {
                val dir = if (totalDx < 0f) 1 else -1
                val newIndex = (modeIndex + dir).floorMod(modes.size)
                if (newIndex != modeIndex) {
                    modeIndex = newIndex
                    currentMode = targetMode
                    targetMode = modes[modeIndex]
                    scope.launch {
                        modeT.snapTo(0f)
                        modeT.animateTo(1f, tween(320))
                    }
                }
            }
        }
    }

    Canvas(
        modifier = modifier
            .then(gestureMod)
            .fillMaxSize()
    ) {
        // make Canvas depend on ticker
        @Suppress("UNUSED_VARIABLE")
        val _tick = frameTicker

        drawRect(Color.Black)

        val t = modeT.value.coerceIn(0f, 1f)

        // Base grid always subtle
        drawGrid(alphaMul = 1f)

        // Bars background (dim in VU & AURA)
        val bgDim = when {
            currentMode == VizMode.VU || targetMode == VizMode.VU -> 0.55f
            currentMode == VizMode.AURA || targetMode == VizMode.AURA -> 0.28f
            else -> 1.0f
        }

        // Bars layer (always present, but subtle for VU/AURA)
        drawNeonBarsPro(
            smooth = smooth,
            peak = peak,
            drawCount = drawCount,
            paletteA = currentMode.palette,
            paletteB = targetMode.palette,
            blend = t,
            dim = bgDim,
            partyBoost = lerp(currentMode.partyBoost, targetMode.partyBoost, t),
            midSheen = 1.0f,
            tick = frameTicker
        )

        // Aura overlay alpha
        val auraAlpha = when {
            currentMode == VizMode.AURA && targetMode == VizMode.AURA -> 1f
            currentMode == VizMode.AURA -> (1f - t)
            targetMode == VizMode.AURA -> t
            else -> 0f
        }
        if (auraAlpha > 0.001f) {
            drawAuraWave(
                smooth = smooth,
                alpha = auraAlpha,
                tick = frameTicker
            )
        }

        // VU overlay alpha
        val vuAlpha = when {
            currentMode == VizMode.VU && targetMode == VizMode.VU -> 1f
            currentMode == VizMode.VU -> (1f - t)
            targetMode == VizMode.VU -> t
            else -> 0f
        }
        if (vuAlpha > 0.001f) {
            drawVuOverlayStudio(
                level = vuLevel,
                alpha = vuAlpha
            )
        }
    }
}

/* ---------------- MODES ---------------- */

private enum class VizMode(val palette: VisualPalette, val partyBoost: Float) {
    PRO(
        palette = VisualPalette(
            left = Color(0xFF25D366),
            midA = Color(0xFFFFD166),
            midB = Color(0xFFFF6B6B),
            right = Color(0xFFB07CFF)
        ),
        partyBoost = 1.00f
    ),
    PARTY(
        palette = VisualPalette(
            left = Color(0xFF1AA6A8),
            midA = Color(0xFF2D5BFF),
            midB = Color(0xFF7C4DFF),
            right = Color(0xFFD5B7FF)
        ),
        partyBoost = 1.45f // more alive
    ),
    VU(
        palette = VisualPalette(
            left = Color(0xFF25D366),
            midA = Color(0xFFFFD166),
            midB = Color(0xFFFF6B6B),
            right = Color(0xFFB07CFF)
        ),
        partyBoost = 0.95f
    ),
    AURA(
        palette = VisualPalette(
            left = Color(0xFF2D5BFF),
            midA = Color(0xFF00C2FF),
            midB = Color(0xFF7C4DFF),
            right = Color(0xFFD5B7FF)
        ),
        partyBoost = 1.05f
    )
}

private data class VisualPalette(
    val left: Color,
    val midA: Color,
    val midB: Color,
    val right: Color
)

/* ---------------- GRID ---------------- */

private fun DrawScope.drawGrid(alphaMul: Float) {
    val paddingH = size.width * 0.04f
    val paddingV = size.height * 0.06f
    val w = size.width - 2f * paddingH
    val h = size.height - 2f * paddingV

    val strokeH = max(1f, size.minDimension * 0.0012f)

    val lines = 10
    for (i in 0..lines) {
        val y = paddingV + h * (i / lines.toFloat())
        val alpha = (if (i == lines) 0.12f else 0.07f) * alphaMul
        drawLine(
            color = Color.White.copy(alpha = alpha),
            start = Offset(paddingH, y),
            end = Offset(paddingH + w, y),
            strokeWidth = strokeH,
            cap = StrokeCap.Round
        )
    }

    val vLines = 6
    for (i in 0..vLines) {
        val x = paddingH + w * (i / vLines.toFloat())
        val alpha = (if (i == 0 || i == vLines) 0.12f else 0.05f) * alphaMul
        drawLine(
            color = Color.White.copy(alpha = alpha),
            start = Offset(x, paddingV),
            end = Offset(x, paddingV + h),
            strokeWidth = max(1f, size.minDimension * 0.0011f),
            cap = StrokeCap.Round
        )
    }
}

/* ---------------- BARS (PRO LOOK) ---------------- */

private fun DrawScope.drawNeonBarsPro(
    smooth: FloatArray,
    peak: FloatArray,
    drawCount: Int,
    paletteA: VisualPalette,
    paletteB: VisualPalette,
    blend: Float,
    dim: Float,
    partyBoost: Float,
    midSheen: Float,
    tick: Int
) {
    val n = smooth.size
    val step = max(1, n / drawCount)

    val paddingH = size.width * 0.05f
    val paddingV = size.height * 0.08f

    val w = size.width - 2f * paddingH
    val h = size.height - 2f * paddingV

    val baseY = paddingV + h
    val maxSpikeH = h * 0.92f

    // Baseline
    drawLine(
        color = Color.White.copy(alpha = 0.12f * dim),
        start = Offset(paddingH, baseY),
        end = Offset(paddingH + w, baseY),
        strokeWidth = max(1.2f, size.minDimension * 0.0017f),
        cap = StrokeCap.Round
    )

    // Mid sheen band (around 60% height)
    if (midSheen > 0f) {
        val bandY = baseY - maxSpikeH * 0.60f
        val bandH = (maxSpikeH * 0.08f).coerceIn(10f, 48f)
        // gentle breathing
        val breathe = 0.72f + 0.28f * sin(tick * 0.035f)
        val a = (0.05f * dim * midSheen * breathe).coerceIn(0f, 0.09f)

        drawRect(
            color = Color.White.copy(alpha = a),
            topLeft = Offset(paddingH, bandY - bandH * 0.5f),
            size = androidx.compose.ui.geometry.Size(w, bandH)
        )
        // softer outer halo
        drawRect(
            color = Color.White.copy(alpha = a * 0.55f),
            topLeft = Offset(paddingH, bandY - bandH * 0.85f),
            size = androidx.compose.ui.geometry.Size(w, bandH * 1.7f)
        )
    }

    val dx = w / max(1, drawCount - 1).toFloat()

    val coreW = max(1.25f, size.minDimension * 0.0020f)
    val glowW1 = coreW * 2.2f
    val glowW2 = coreW * 3.7f

    for (i in 0 until drawCount) {
        val srcIdx = (i * step).coerceIn(0, n - 1)
        val x = paddingH + i * dx

        val v = (smooth[srcIdx] * partyBoost).clamp01()
        val p = (peak[srcIdx] * partyBoost).clamp01()

        val yTop = (baseY - (v * maxSpikeH)).coerceIn(paddingV, baseY)
        val yPeak = (baseY - (p * maxSpikeH)).coerceIn(paddingV, baseY)

        val nx = (i / max(1f, (drawCount - 1).toFloat())).clamp01()
        val c = lerpColor(
            paletteColorAtX(paletteA, nx),
            paletteColorAtX(paletteB, nx),
            blend
        )

        // Background glow
        val dimGlow = dim * (if (partyBoost > 1.2f) 1.08f else 0.92f)
        drawLine(
            color = c.copy(alpha = 0.06f * dimGlow),
            start = Offset(x, baseY),
            end = Offset(x, yTop),
            strokeWidth = glowW2,
            cap = StrokeCap.Round
        )
        drawLine(
            color = c.copy(alpha = 0.11f * dimGlow),
            start = Offset(x, baseY),
            end = Offset(x, yTop),
            strokeWidth = glowW1,
            cap = StrokeCap.Round
        )

        // Vertical highlight effect (top brighter, bottom darker) using 2-segment core
        val midY = (baseY + yTop) * 0.5f
        val coreAlphaTop = (0.70f + 0.30f * v).coerceIn(0f, 1f) * dim
        val coreAlphaBot = (0.42f + 0.22f * v).coerceIn(0f, 1f) * dim

        drawLine(
            color = c.copy(alpha = coreAlphaBot),
            start = Offset(x, baseY),
            end = Offset(x, midY),
            strokeWidth = coreW,
            cap = StrokeCap.Round
        )
        drawLine(
            color = c.copy(alpha = coreAlphaTop),
            start = Offset(x, midY),
            end = Offset(x, yTop),
            strokeWidth = coreW,
            cap = StrokeCap.Round
        )

        // Peak: glow dot + halo + tiny comet
        val dotR = (coreW * 2.1f).coerceIn(3.0f, 8.0f)
        drawCircle(
            color = c.copy(alpha = 0.18f * dim),
            radius = dotR * 2.2f,
            center = Offset(x, yPeak)
        )
        drawCircle(
            color = c.copy(alpha = 0.42f * dim),
            radius = dotR * 1.35f,
            center = Offset(x, yPeak)
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.22f * dim),
            radius = dotR * 0.70f,
            center = Offset(x, yPeak)
        )
        // small tail
        drawLine(
            color = c.copy(alpha = 0.30f * dim),
            start = Offset(x, yPeak + dotR * 1.6f),
            end = Offset(x, yPeak + dotR * 3.8f),
            strokeWidth = coreW * 0.95f,
            cap = StrokeCap.Round
        )
    }
}

/* ---------------- AURA MODE ---------------- */

private fun DrawScope.drawAuraWave(
    smooth: FloatArray,
    alpha: Float,
    tick: Int
) {
    val paddingH = size.width * 0.06f
    val centerY = size.height * 0.52f
    val w = size.width - 2f * paddingH

    val samples = 256
    val step = max(1, smooth.size / 64) // use coarse energy
    val tmp = FloatArray(64)
    run {
        var k = 0
        for (i in 0 until 64) {
            val idx = (i * step).coerceIn(0, smooth.size - 1)
            tmp[k++] = smooth[idx].clamp01()
        }
    }

    fun sampleAt(x: Float): Float {
        val xf = x.coerceIn(0f, 1f) * (tmp.size - 1)
        val i0 = xf.toInt().coerceIn(0, tmp.size - 1)
        val i1 = (i0 + 1).coerceIn(0, tmp.size - 1)
        val t = (xf - i0).coerceIn(0f, 1f)
        return (tmp[i0] + (tmp[i1] - tmp[i0]) * t).clamp01()
    }

    val maxAmp = (size.height * 0.18f).coerceIn(70f, 260f)
    val phase = tick * 0.03f

    val topPath = Path()
    val botPath = Path()

    for (i in 0 until samples) {
        val nx = i / (samples - 1).toFloat()
        val x = paddingH + nx * w

        val base = sampleAt(nx)
        val shaped = base.pow(0.62f)

        // "plasma hairs": deterministic wobble tied to signal
        val wobble =
            0.72f +
                    0.28f * sin(phase + nx * 10.0f) +
                    0.16f * sin(phase * 1.35f + nx * 22.0f)

        val amp = (shaped * wobble).coerceIn(0f, 1.15f) * maxAmp

        val yTop = centerY - amp
        val yBot = centerY + amp

        if (i == 0) {
            topPath.moveTo(x, yTop)
            botPath.moveTo(x, yBot)
        } else {
            topPath.lineTo(x, yTop)
            botPath.lineTo(x, yBot)
        }
    }

    // Colors like reference: blue → purple
    fun auraColor(nx: Float): Color {
        val left = Color(0xFF2D5BFF) // blue
        val mid = Color(0xFF00C2FF)  // cyan-ish
        val right = Color(0xFF7C4DFF) // purple
        return when {
            nx < 0.45f -> lerpColor(left, mid, (nx / 0.45f))
            else -> lerpColor(mid, right, ((nx - 0.45f) / 0.55f))
        }
    }

    // Draw glow layers (cheap but effective)
    val strokeCore = max(2.2f, size.minDimension * 0.0024f)
    val strokeGlow1 = strokeCore * 2.6f
    val strokeGlow2 = strokeCore * 5.2f

    // Background mist
    drawRect(
        color = Color(0xFF7C4DFF).copy(alpha = 0.05f * alpha),
        topLeft = Offset(0f, centerY - maxAmp * 1.10f),
        size = androidx.compose.ui.geometry.Size(size.width, maxAmp * 2.20f)
    )

    // Layered strokes: top & bottom
    fun drawAuraPath(path: Path, sign: Int) {
        drawPath(
            path = path,
            color = Color(0xFF2D5BFF).copy(alpha = 0.10f * alpha),
            style = Stroke(width = strokeGlow2, cap = StrokeCap.Round)
        )
        drawPath(
            path = path,
            color = Color(0xFF7C4DFF).copy(alpha = 0.12f * alpha),
            style = Stroke(width = strokeGlow1, cap = StrokeCap.Round)
        )
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.05f * alpha),
            style = Stroke(width = strokeGlow1 * 0.55f, cap = StrokeCap.Round)
        )
        drawPath(
            path = path,
            color = Color(0xFFB07CFF).copy(alpha = 0.42f * alpha),
            style = Stroke(width = strokeCore, cap = StrokeCap.Round)
        )

        // Hair spikes: every few samples
        val hairEvery = 6
        val hairLen = maxAmp * 0.32f
        for (i in 0 until samples step hairEvery) {
            val nx = i / (samples - 1).toFloat()
            val x = paddingH + nx * w
            val base = sampleAt(nx).pow(0.60f)
            val jitter = 0.55f + 0.45f * sin(phase * 1.6f + nx * 34f)
            val len = (base * jitter).coerceIn(0f, 1f) * hairLen

            val y0 = if (sign < 0) {
                centerY - (base * maxAmp)
            } else {
                centerY + (base * maxAmp)
            }
            val y1 = y0 + sign * len

            val c = auraColor(nx)
            drawLine(
                color = c.copy(alpha = 0.20f * alpha),
                start = Offset(x, y0),
                end = Offset(x, y1),
                strokeWidth = strokeCore * 0.85f,
                cap = StrokeCap.Round
            )
        }
    }

    drawAuraPath(topPath, sign = -1)
    drawAuraPath(botPath, sign = +1)

    // Soft center core line
    drawLine(
        color = Color.White.copy(alpha = 0.08f * alpha),
        start = Offset(paddingH, centerY),
        end = Offset(paddingH + w, centerY),
        strokeWidth = strokeCore * 0.9f,
        cap = StrokeCap.Round
    )
}

/* ---------------- VU (STUDIO) ---------------- */

private fun DrawScope.drawVuOverlayStudio(level: Float, alpha: Float) {
    val w = size.width
    val h = size.height

    val padH = w * 0.10f
    val top = h * 0.22f

    val metersW = w - 2f * padH
    val metersH = h * 0.40f
    val gap = metersW * 0.06f
    val singleW = (metersW - gap) / 2f

    val leftRect = Rect(padH, top, padH + singleW, top + metersH)
    val rightRect = Rect(padH + singleW + gap, top, padH + singleW + gap + singleW, top + metersH)

    // Studio backlight behind meters
    val glowCx = w * 0.5f
    val glowCy = top + metersH * 0.55f
    val glowR = min(w, h) * 0.46f
    drawCircle(
        color = Color(0xFFFFD166).copy(alpha = 0.06f * alpha),
        radius = glowR,
        center = Offset(glowCx, glowCy)
    )
    drawCircle(
        color = Color(0xFFFF6B6B).copy(alpha = 0.03f * alpha),
        radius = glowR * 0.72f,
        center = Offset(glowCx, glowCy)
    )

    fun drawMeterFrame(r: Rect) {
        val frameR = 18f
        drawRoundRect(
            color = Color(0xFF0F0F0F).copy(alpha = 0.92f * alpha),
            topLeft = Offset(r.left, r.top),
            size = androidx.compose.ui.geometry.Size(r.width, r.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(frameR, frameR)
        )
        drawRoundRect(
            color = Color(0xFF1B1B1B).copy(alpha = 0.92f * alpha),
            topLeft = Offset(r.left + 10f, r.top + 10f),
            size = androidx.compose.ui.geometry.Size(r.width - 20f, r.height - 20f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(frameR - 6f, frameR - 6f)
        )
        drawRoundRect(
            color = Color(0xFF242424).copy(alpha = 0.92f * alpha),
            topLeft = Offset(r.left + 16f, r.top + 16f),
            size = androidx.compose.ui.geometry.Size(r.width - 32f, r.height - 32f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(frameR - 10f, frameR - 10f)
        )
        drawRoundRect(
            color = Color.White.copy(alpha = 0.085f * alpha),
            topLeft = Offset(r.left + 18f, r.top + 18f),
            size = androidx.compose.ui.geometry.Size(r.width - 36f, (r.height - 36f) * 0.44f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(frameR - 11f, frameR - 11f)
        )
        val s = 3.2f
        val screwC = Color.White.copy(alpha = 0.08f * alpha)
        drawCircle(screwC, s, Offset(r.left + 22f, r.top + 22f))
        drawCircle(screwC, s, Offset(r.right - 22f, r.top + 22f))
        drawCircle(screwC, s, Offset(r.left + 22f, r.bottom - 22f))
        drawCircle(screwC, s, Offset(r.right - 22f, r.bottom - 22f))
    }

    fun drawScaleAndNeedle(r: Rect, level01: Float) {
        val inner = Rect(r.left + 22f, r.top + 22f, r.right - 22f, r.bottom - 22f)

        val cx = (inner.left + inner.right) * 0.5f
        val cy = inner.bottom - inner.height * 0.12f
        val radius = min(inner.width, inner.height) * 0.47f

        // scale arc
        drawArc(
            color = Color(0xFFFFD166).copy(alpha = 0.30f * alpha),
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f),
            style = Stroke(width = max(2.2f, size.minDimension * 0.0019f), cap = StrokeCap.Round)
        )

        // ticks
        val ticks = 13
        for (i in 0 until ticks) {
            val tt = i / (ticks - 1).toFloat()
            val ang = Math.toRadians((200f + 140f * tt).toDouble())
            val cosA = cos(ang).toFloat()
            val sinA = sin(ang).toFloat()

            val x0 = cx + cosA * (radius * 0.82f)
            val y0 = cy + sinA * (radius * 0.82f)
            val x1 = cx + cosA * (radius * 0.98f)
            val y1 = cy + sinA * (radius * 0.98f)

            val isRedZone = tt > 0.84f
            val tickCol = if (isRedZone) Color(0xFFFF6B6B) else Color(0xFFFFD166)
            val a = if (isRedZone) 0.58f else 0.42f

            drawLine(
                color = tickCol.copy(alpha = a * alpha),
                start = Offset(x0, y0),
                end = Offset(x1, y1),
                strokeWidth = if (i % 2 == 0) 2.8f else 2.0f,
                cap = StrokeCap.Round
            )
        }

        // needle mapping (more lively but still “VU-ish”)
        val shaped = level01.coerceIn(0f, 1f).pow(0.46f)
        val needleAng = Math.toRadians((200f + 140f * shaped).toDouble())
        val nx = cx + cos(needleAng).toFloat() * (radius * 0.86f)
        val ny = cy + sin(needleAng).toFloat() * (radius * 0.86f)

        // needle shadow
        drawLine(
            color = Color.Black.copy(alpha = 0.35f * alpha),
            start = Offset(cx + 2.2f, cy + 2.2f),
            end = Offset(nx + 2.2f, ny + 2.2f),
            strokeWidth = 4.2f,
            cap = StrokeCap.Round
        )
        // --- SIGNATURE FIX: needle must be RED (not white) ---
        val signatureRed = Color(0xFFFF3B30)
        drawLine(
            color = signatureRed.copy(alpha = 0.90f * alpha),
            start = Offset(cx, cy),
            end = Offset(nx, ny),
            strokeWidth = 3.3f,
            cap = StrokeCap.Round
        )
        // hub (keep neutral)
        drawCircle(
            color = Color(0xFF101010).copy(alpha = 0.96f * alpha),
            radius = 11f,
            center = Offset(cx, cy)
        )
        drawCircle(
            color = Color(0xFFF2F2F2).copy(alpha = 0.75f * alpha),
            radius = 5.6f,
            center = Offset(cx, cy)
        )

        // subtle separator
        drawLine(
            color = Color.White.copy(alpha = 0.07f * alpha),
            start = Offset(inner.left, inner.bottom - 26f),
            end = Offset(inner.right, inner.bottom - 26f),
            strokeWidth = 1.6f,
            cap = StrokeCap.Round
        )

        // labels via nativeCanvas (safe)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb((alpha * 255f * 0.60f).roundToInt(), 230, 200, 120)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (min(size.width, size.height) * 0.016f).coerceIn(11f, 18f)
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb((alpha * 255f * 0.45f).roundToInt(), 210, 210, 210)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = (min(size.width, size.height) * 0.0125f).coerceIn(10f, 16f)
        }

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText("VU", cx, inner.top + 22f, labelPaint)
            canvas.nativeCanvas.drawText("-20   -10    -7    -5    -3    0    +3", cx, inner.bottom - 10f, smallPaint)
        }
    }

    drawMeterFrame(leftRect)
    drawMeterFrame(rightRect)
    drawScaleAndNeedle(leftRect, level)
    drawScaleAndNeedle(rightRect, level)

    // "vdk" brand (dark gray / black)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb((alpha * 255f * 0.62f).roundToInt(), 40, 40, 40)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = (min(size.width, size.height) * 0.030f).coerceIn(18f, 34f)
    }
    val brandY = (leftRect.bottom + 52f).coerceAtMost(size.height - 24f)
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText("vdk", size.width * 0.5f, brandY, paint)
    }
}

/* ---------------- HELPERS ---------------- */

private val ZERO_128 = FloatArray(128) { 0f }

private fun paletteColorAtX(p: VisualPalette, nx: Float): Color {
    val t = nx.coerceIn(0f, 1f)
    return when {
        t < 0.33f -> lerpColor(p.left, p.midA, (t / 0.33f))
        t < 0.66f -> lerpColor(p.midA, p.midB, ((t - 0.33f) / 0.33f))
        else -> lerpColor(p.midB, p.right, ((t - 0.66f) / 0.34f))
    }
}

private fun mapForSpikes(xIn: Float): Float {
    val x = xIn.coerceIn(0f, 1f)

    val floor = 0.006f
    var v = ((x - floor) / (1f - floor)).coerceIn(0f, 1f)

    v = v.pow(0.66f)

    val knee = 0.82f
    if (v > knee) {
        val over = (v - knee) / (1f - knee)
        val compressed = 1f - (1f / (1f + 2.8f * over))
        v = knee + compressed * (1f - knee)
    }

    val headroom = 0.96f
    return (v * headroom).coerceIn(0f, headroom)
}

private fun binsFromLevels16(levels16: FloatArray, n: Int): FloatArray {
    val safe = levels16.safe16()
    val out = FloatArray(n)
    val last = 15
    for (i in 0 until n) {
        val x = (i / (n - 1).toFloat()) * last.toFloat()
        val i0 = x.toInt().coerceIn(0, last)
        val i1 = (i0 + 1).coerceIn(0, last)
        val t = (x - i0.toFloat()).coerceIn(0f, 1f)
        val v = safe[i0] + (safe[i1] - safe[i0]) * t
        out[i] = v.coerceIn(0f, 1f)
    }
    return out
}

private fun FloatArray.safe16(): FloatArray {
    if (size == 16) return this
    val out = FloatArray(16) { 0f }
    val n = min(16, size)
    for (i in 0 until n) out[i] = this[i]
    return out
}

private fun FloatArray.safeN(n: Int): FloatArray {
    if (size == n) return this
    val out = FloatArray(n) { 0f }
    val m = min(n, size)
    for (i in 0 until m) out[i] = this[i]
    return out
}

private fun Float.clamp01(): Float = when {
    this < 0f -> 0f
    this > 1f -> 1f
    else -> this
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * u,
        green = a.green + (b.green - a.green) * u,
        blue = a.blue + (b.blue - a.blue) * u,
        alpha = a.alpha + (b.alpha - a.alpha) * u
    )
}

private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m