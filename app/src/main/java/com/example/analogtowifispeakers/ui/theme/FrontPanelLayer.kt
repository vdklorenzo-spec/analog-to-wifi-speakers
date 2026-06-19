// FILE: app/src/main/java/com/example/analogtowifispeakers/ui/theme/FrontPanelLayer.kt
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.consumeDownChange
import androidx.compose.ui.input.pointer.consumePositionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class PanelLook(val label: String) {
    GOLD("GOLD"),
    SILVER("SILVER"),
    BRONZE("BRONZE"),
    CRYSTAL("CRYSTAL");

    fun next(): PanelLook = when (this) {
        GOLD -> SILVER
        SILVER -> BRONZE
        BRONZE -> CRYSTAL
        CRYSTAL -> GOLD
    }
}

@Composable
fun FrontPanelLayer(
    modifier: Modifier = Modifier,
    level01: Float,
    peak01: Float,
    isLive: Boolean,
    castConnected: Boolean,
    sampleRateHz: Int,
    bitrateBps: Int,
    castVolume01: Float,
    onCastVolumeEditing: (Boolean) -> Unit,
    onSetCastVolume01: (Float) -> Unit,
    klaarteLevel: Int = 2,                 // 0..4
    panelLook: PanelLook = PanelLook.GOLD,
    onKlaarteNext: () -> Unit = {},
    onCycleLook: () -> Unit = {},
    showTopBrand: Boolean = true,
    streamRunning: Boolean = false,
    streamLatencyText: String = "00:00",
    streamDisplayText: String = "--",
    streamVuTop01: Float = 0f,
    streamVuBottom01: Float = 0f,
    onStreamPlayClick: () -> Unit = {},
    onStreamStopClick: () -> Unit = {},
    onStreamCastClick: () -> Unit = {},
) {
    val swipeThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }

    // Slides:
// 1 CLASSIC_VU
// 2 LED_METERS
// 3 WAVEFORM
// 4 MATRIX
// 5 LASERS
// 6 STROBE_TUBES
    val modes = remember {
        listOf(
            PanelMode.CLASSIC_VU,
            PanelMode.LED_METERS,
            PanelMode.WAVEFORM,
            PanelMode.MATRIX,
            PanelMode.LASERS,
            PanelMode.STROBE_TUBES
        )
    }
    var modeIndex by remember { mutableIntStateOf(0) }
    var currentMode by remember { mutableStateOf(modes[0]) }
    var targetMode by remember { mutableStateOf(modes[0]) }
    val modeT = remember { Animatable(1f) }
    var modeAnimKey by remember { mutableIntStateOf(0) }

    // IMPORTANT:
    // We animate from currentMode -> targetMode, but we MUST commit the target at the end.
    // Otherwise the UI shows the new slide while the controls still act like the old one.
    LaunchedEffect(modeAnimKey) {
        modeT.snapTo(0f)
        modeT.animateTo(1f, tween(320))
        // Commit so taps on slide 4/5 don't behave like slide 1/2/3.
        currentMode = targetMode
    }

    val dimmer01 by remember(klaarteLevel) {
        mutableStateOf(
            when (klaarteLevel.coerceIn(0, 4)) {
                0 -> 0.38f
                1 -> 0.52f
                2 -> 0.66f
                3 -> 0.80f
                else -> 0.92f
            }
        )
    }

    var ballistics by remember { mutableStateOf(Ballistics.MED) }

    // --- VU smoothing (Slide 1: Classic VU only) ---
    // Classic meters must REST fully left when there's no real audio energy.
    // In practice the pipeline can be "live" while the input is just noise, so we gate it here.
    val vuActiveThreshold = 0.06f
    val vuLive = isLive && max(level01, peak01) >= vuActiveThreshold

    val vuDeadZone = 0.02f
    val vuRawLevel = if (vuLive) level01.coerceIn(0f, 1f) else 0f
    val vuGatedLevel = if (vuRawLevel < vuDeadZone) 0f else vuRawLevel
    val vuTargetLevel = vuGatedLevel

    val vuAttack = 0.12f
    val vuRelease = 0.03f
    var vuSmoothLevel by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(vuTargetLevel, vuLive) {
        if (!vuLive) {
            vuSmoothLevel = 0f
        } else {
            val a = if (vuTargetLevel > vuSmoothLevel) vuAttack else vuRelease
            vuSmoothLevel = (vuSmoothLevel + (vuTargetLevel - vuSmoothLevel) * a).coerceIn(0f, 1f)
        }
    }

    // --- General smoothing (used by other slides) ---
    val vuDeadZoneGeneral = 0.02f
    val rawLevel = if (isLive) level01.coerceIn(0f, 1f) else 0f
    val gatedLevel = if (rawLevel < vuDeadZoneGeneral) 0f else rawLevel
    val targetLevel = gatedLevel

    val attack = 0.12f
    val release = 0.03f
    var smoothLevel by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(targetLevel, isLive) {
        if (!isLive) {
            smoothLevel = 0f
        } else {
            val a = if (targetLevel > smoothLevel) attack else release
            smoothLevel = (smoothLevel + (targetLevel - smoothLevel) * a).coerceIn(0f, 1f)
        }
    }
    // Frame ticker
    var frameTicker by remember { mutableIntStateOf(0) }
    var nowNanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                nowNanos = it
                frameTicker++
            }
        }
    }

    // Peak hold for LED mode
    var peakHold by remember { mutableFloatStateOf(0f) }
    var peakHoldUntil by remember { mutableLongStateOf(0L) }
    LaunchedEffect(nowNanos, isLive, smoothLevel) {
        val t = nowNanos
        val lvl = if (isLive) smoothLevel.coerceIn(0f, 1f) else 0f
        if (lvl > peakHold + 0.012f) {
            peakHold = lvl
            peakHoldUntil = t + 900_000_000L
        } else if (t > peakHoldUntil) {
            peakHold = (peakHold - 0.018f).coerceAtLeast(0f)
        }
        if (!isLive) {
            peakHold = 0f
            peakHoldUntil = 0L
        }
    }

    // Volume pulse feedback
    val volPulse = remember { Animatable(0f) }
    var volPulseKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(volPulseKey) {
        volPulse.snapTo(1f)
        volPulse.animateTo(0f, tween(220))
    }

    // Matrix slide state (local)
    var matrixPaletteIndex by remember { mutableIntStateOf(0) }
    var matrixModeIndex by remember { mutableIntStateOf(0) }

    // Laser slide state (local)
    var laserPaletteIndex by remember { mutableIntStateOf(0) }
    var laserRigIndex by remember { mutableIntStateOf(0) }

    // ✅ IMPORTANT FIX: pointerInput MUST NOT steal taps/drags unless it handles them.
    val unifiedInput = Modifier.pointerInput(
        modeIndex,
        castConnected,
        castVolume01,
        klaarteLevel,
        panelLook,
        currentMode,
        targetMode,
        matrixPaletteIndex,
        matrixModeIndex,
        laserPaletteIndex,
        laserRigIndex,
        streamRunning,
        streamLatencyText,
        streamDisplayText
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val pointerId: PointerId = down.id

            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@awaitEachGesture

            val minDim = min(w, h)

            // --- same chassis math as drawDeviceChassis (for correct hit areas) ---
            val pad = minDim * 0.045f
            val outer = Rect(pad, pad, w - pad, h - pad)

            val bezel = (minDim * 0.020f).coerceIn(10f, 18f)
            val inner = Rect(
                outer.left + bezel,
                outer.top + bezel,
                outer.right - bezel,
                outer.bottom - bezel
            )

            val facePad = (minDim * 0.012f).coerceIn(6f, 12f)
            val face = Rect(
                inner.left + facePad,
                inner.top + facePad,
                inner.right - facePad,
                inner.bottom - facePad
            )

            val stripArea = Rect(
                face.left,
                face.top + face.height * 0.74f,
                face.right,
                face.bottom
            )

            val y = stripArea.top + stripArea.height * 0.55f
            val spacing = w * 0.18f
            val xMid = w * 0.50f
            val xL = xMid - spacing
            val xR = xMid + spacing

            val btnR = (minDim * 0.052f).coerceIn(26f, 46f)
            val btnHitR = btnR * 1.55f

            // ✅ EXACT strip rect as drawControlStripPro
            val strip = Rect(
                face.left + face.width * 0.10f,
                y - btnR * 1.20f,
                face.right - face.width * 0.10f,
                y + btnR * 1.20f
            )

            // VOL rect (same as drawControlStripPro)
            val volW = (minDim * 0.44f).coerceIn(290f, w * 0.70f)
            val volH = (minDim * 0.085f).coerceIn(44f, 72f)
            val volRect = Rect(
                xMid - volW * 0.5f,
                y - volH * 0.5f,
                xMid + volW * 0.5f,
                y + volH * 0.5f
            )

            fun dist2(px: Float, py: Float, cx: Float, cy: Float): Float {
                val dx = px - cx
                val dy = py - cy
                return dx * dx + dy * dy
            }

            val pX = down.position.x
            val pY = down.position.y

            val inControlStrip = (pX in strip.left..strip.right) && (pY in strip.top..strip.bottom)

            val dL = dist2(pX, pY, xL, y)
            val dR = dist2(pX, pY, xR, y)

            val hitLeft = inControlStrip && dL <= btnHitR * btnHitR
            val hitRight = inControlStrip && dR <= btnHitR * btnHitR
            val hitVol =
                inControlStrip && (pX in volRect.left..volRect.right) && (pY in volRect.top..volRect.bottom)

            val activeMode =
                if (modeT.value < 1f) targetMode else currentMode

            val isMatrixNow = (activeMode == PanelMode.MATRIX)
            val isLaserNow = (activeMode == PanelMode.LASERS)
            val isClassicNow = (activeMode == PanelMode.CLASSIC_VU)

            val streamGap = btnR * 0.36f
            val streamH = btnR * 1.75f
            val stream = Rect(
                strip.left,
                strip.top - streamGap - streamH,
                strip.right,
                strip.top - streamGap
            )

            val streamButtonR = btnR * 0.68f
            val streamButtonsGap = streamButtonR * 0.55f
            val streamCastX = stream.right - streamButtonR * 1.30f
            val streamStopX = streamCastX - (streamButtonR * 2f + streamButtonsGap)
            val streamPlayX = streamStopX - (streamButtonR * 2f + streamButtonsGap)
            val streamBtnY = stream.center.y
            val streamHitR = streamButtonR * 1.28f

            val inStream =
                isClassicNow && (pX in stream.left..stream.right) && (pY in stream.top..stream.bottom)
            val hitStreamPlay =
                inStream && dist2(pX, pY, streamPlayX, streamBtnY) <= streamHitR * streamHitR
            val hitStreamStop =
                inStream && dist2(pX, pY, streamStopX, streamBtnY) <= streamHitR * streamHitR
            val hitStreamCast =
                inStream && dist2(pX, pY, streamCastX, streamBtnY) <= streamHitR * streamHitR

            // ✅ Edge tap zones inside the strip (for quick prev/next)
            val edgeW = (btnR * 1.15f).coerceIn(28f, 70f)
            val hitPrevEdge = inControlStrip && pX <= (strip.left + edgeW)
            val hitNextEdge = inControlStrip && pX >= (strip.right - edgeW)

            // -------- 0) Handle STREAM BAR buttons --------
            if (hitStreamPlay) {
                down.consumeDownChange()
                onStreamPlayClick()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!ch.pressed) break
                    ch.consumePositionChange()
                }
                return@awaitEachGesture
            }

            if (hitStreamStop) {
                down.consumeDownChange()
                onStreamStopClick()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!ch.pressed) break
                    ch.consumePositionChange()
                }
                return@awaitEachGesture
            }

            if (hitStreamCast) {
                down.consumeDownChange()
                onStreamCastClick()
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!ch.pressed) break
                    ch.consumePositionChange()
                }
                return@awaitEachGesture
            }

            // -------- 1) Handle LEFT button --------
            if (hitLeft && !hitVol) {
                down.consumeDownChange() // ✅ only consume when we handle the tap
                when {
                    isMatrixNow -> matrixPaletteIndex =
                        (matrixPaletteIndex + 1) % MatrixPalette.entries.size

                    isLaserNow -> laserPaletteIndex =
                        (laserPaletteIndex + 1) % PanelLaserPalette.entries.size

                    else -> onKlaarteNext()
                }
                // swallow the rest of gesture
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!ch.pressed) break
                    ch.consumePositionChange()
                }
                return@awaitEachGesture
            }

            // -------- 2) Handle RIGHT button --------
            if (hitRight && !hitVol) {
                down.consumeDownChange()
                when {
                    isMatrixNow -> matrixModeIndex = (matrixModeIndex + 1) % MatrixMode.entries.size
                    isLaserNow -> laserRigIndex = (laserRigIndex + 1) % PanelLaserRig.entries.size
                    else -> onCycleLook()
                }
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!ch.pressed) break
                    ch.consumePositionChange()
                }
                return@awaitEachGesture
            }

            // -------- 3) Handle VOL dual-action --------
            if (hitVol) {
                down.consumeDownChange()
                onCastVolumeEditing(true)

                val centerX = volRect.center.x
                val deadZone = volRect.width * 0.06f
                val step = 0.04f

                fun applyStep(dir: Int) {
                    if (!castConnected) return
                    val base = castVolume01.coerceIn(0f, 1f)
                    val next = (base + dir * step).coerceIn(0f, 1f)
                    if (next != base) {
                        onSetCastVolume01(next)
                        volPulseKey += 1
                    }
                }

                val dir =
                    if (pX < centerX - deadZone) -1
                    else if (pX > centerX + deadZone) +1
                    else 0

                if (dir != 0) applyStep(dir)

                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!ch.pressed) break
                    ch.consumePositionChange()
                }

                onCastVolumeEditing(false)
                return@awaitEachGesture
            }

            // -------- 4) Handle edge taps (prev/next page) --------
            if (hitPrevEdge || hitNextEdge) {
                down.consumeDownChange()
                val dir = if (hitNextEdge) 1 else -1
                val newIndex = (modeIndex + dir).floorMod(modes.size)
                if (newIndex != modeIndex) {
                    modeIndex = newIndex
                    currentMode = targetMode
                    targetMode = modes[modeIndex]
                    modeAnimKey += 1
                }
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                    if (!ch.pressed) break
                    ch.consumePositionChange()
                }
                return@awaitEachGesture
            }

            // -------- 5) Swipe switching (works everywhere EXCEPT when you started on a control) --------
            // ✅ do NOT consume the down -> parent can still show sidebar / manage timer as before
            var totalDx = 0f
            var totalDy = 0f
            var isSwiping = false
            var lastX = down.position.x
            var lastY = down.position.y

            while (true) {
                val ev = awaitPointerEvent()
                val ch = ev.changes.firstOrNull { it.id == pointerId } ?: break
                if (!ch.pressed) break

                val dx = ch.position.x - lastX
                val dy = ch.position.y - lastY
                totalDx += dx
                totalDy += dy
                lastX = ch.position.x
                lastY = ch.position.y

                val horizontalEnough =
                    abs(totalDx) >= swipeThresholdPx && abs(totalDx) > abs(totalDy) * 1.25f
                if (!isSwiping && horizontalEnough) isSwiping = true

                if (isSwiping) {
                    // ✅ now we consume move to avoid weird nested drags
                    ch.consumePositionChange()
                }
            }

            if (isSwiping) {
                val dir = if (totalDx < 0f) 1 else -1
                val newIndex = (modeIndex + dir).floorMod(modes.size)
                if (newIndex != modeIndex) {
                    modeIndex = newIndex
                    currentMode = targetMode
                    targetMode = modes[modeIndex]
                    modeAnimKey += 1
                }
            }

            // If it wasn't a swipe, we consumed nothing:
            // ✅ tap goes to parent -> sidebar behavior stays correct
        }
    }

    Canvas(
        modifier = modifier
            .then(unifiedInput)
            .fillMaxSize()
    ) {
        @Suppress("UNUSED_VARIABLE")
        val _tick = frameTicker

        val timeSec = nowNanos / 1_000_000_000f

        val device = drawDeviceChassis(
            dimmer01 = dimmer01,
            timeSec = timeSec,
            look = panelLook
        )

        val t = modeT.value.coerceIn(0f, 1f)
        val alphaA = (1f - t).coerceIn(0f, 1f)
        val alphaB = t.coerceIn(0f, 1f)

        if (showTopBrand) drawTopBrand(device.topArea, dimmer01, look = panelLook, alpha = 1f)

        val peakForUi = if (isLive) peak01.coerceIn(0f, 1f) else 0f

        val palette = MatrixPalette.fromIndex(matrixPaletteIndex)
        val mMode = MatrixMode.fromIndex(matrixModeIndex)

        val lPal = PanelLaserPalette.fromIndex(laserPaletteIndex)
        val lRig = PanelLaserRig.fromIndex(laserRigIndex)

        when (currentMode) {
            PanelMode.CLASSIC_VU -> drawClassicVuCentered(
                device,
                vuSmoothLevel,
                vuLive,
                ballistics,
                dimmer01,
                timeSec,
                panelLook,
                alphaA
            )

            PanelMode.LED_METERS -> drawLedDotsPro(
                device,
                peakForUi,
                peakHold,
                isLive,
                dimmer01,
                timeSec,
                look = panelLook,
                alpha = alphaA
            )

            PanelMode.WAVEFORM -> drawWaveformPro(
                device,
                peakForUi,
                isLive,
                dimmer01,
                timeSec,
                look = panelLook,
                alpha = alphaA
            )

            PanelMode.MATRIX -> drawMatrixLedPro(
                device,
                peakForUi,
                isLive,
                dimmer01,
                timeSec,
                look = panelLook,
                alpha = alphaA,
                palette = palette,
                mode = mMode
            )

            PanelMode.LASERS -> drawLaserShowPro(
                device = device,
                level = peakForUi,
                isLive = isLive,
                dimmer01 = dimmer01,
                timeSec = timeSec,
                look = panelLook,
                alpha = alphaA,
                palette = lPal,
                rig = lRig
            )

            PanelMode.STROBE_TUBES -> drawLaserShowPro(
                device = device,
                level = peakForUi,
                isLive = isLive,
                dimmer01 = dimmer01,
                timeSec = timeSec,
                look = panelLook,
                alpha = alphaA,
                palette = lPal,
                rig = lRig
            )
        }
        when (targetMode) {

            PanelMode.CLASSIC_VU -> drawClassicVuCentered(
                device,
                vuSmoothLevel,
                vuLive,
                ballistics,
                dimmer01,
                timeSec,
                panelLook,
                alphaB
            )

            PanelMode.LED_METERS -> drawLedDotsPro(
                device,
                peakForUi,
                peakHold,
                isLive,
                dimmer01,
                timeSec,
                look = panelLook,
                alpha = alphaB
            )

            PanelMode.WAVEFORM -> drawWaveformPro(
                device,
                peakForUi,
                isLive,
                dimmer01,
                timeSec,
                look = panelLook,
                alpha = alphaB
            )

            PanelMode.MATRIX -> drawMatrixLedPro(
                device,
                peakForUi,
                isLive,
                dimmer01,
                timeSec,
                look = panelLook,
                alpha = alphaB,
                palette = palette,
                mode = mMode
            )

            PanelMode.LASERS -> drawLaserShowPro(
                device = device,
                level = peakForUi,
                isLive = isLive,
                dimmer01 = dimmer01,
                timeSec = timeSec,
                look = panelLook,
                alpha = alphaB,
                palette = lPal,
                rig = lRig
            )

            PanelMode.STROBE_TUBES -> drawLaserShowPro(
                device = device,
                level = peakForUi,
                isLive = isLive,
                dimmer01 = dimmer01,
                timeSec = timeSec,
                look = panelLook,
                alpha = alphaB,
                palette = lPal,
                rig = lRig
            )
        }

        if (targetMode == PanelMode.CLASSIC_VU) {
            drawStreamBarPro(
                device = device,
                look = panelLook,
                dimmer01 = dimmer01,
                isRunning = streamRunning,
                latencyText = streamLatencyText,
                streamText = streamDisplayText,
                vuTop01 = streamVuTop01,
                vuBottom01 = streamVuBottom01
            )
        }

        drawControlStripPro(
            device = device,
            look = panelLook,
            dimmer01 = dimmer01,
            clarityLevel = klaarteLevel,
            castConnected = castConnected,
            castVolume01 = castVolume01,
            volPulse01 = volPulse.value,

            isMatrixMode = (targetMode == PanelMode.MATRIX),
            matrixPaletteLabel = palette.label,
            matrixModeLabel = mMode.label,

            isLaserMode = (targetMode == PanelMode.LASERS),
            laserPaletteLabel = lPal.label,
            laserRigLabel = lRig.label
        )
    }
}

/* ----------------- Enums ----------------- */

private enum class PanelMode { CLASSIC_VU, LED_METERS, WAVEFORM, MATRIX, LASERS, STROBE_TUBES }

private enum class Ballistics(val label: String, val curve: Float) {
    SLOW("SLOW", 0.62f),
    MED("MED", 0.54f),
    FAST("FAST", 0.48f)
}

/* ----------------- Device Frame Model ----------------- */

private data class DeviceRects(
    val outer: Rect,
    val inner: Rect,
    val face: Rect,
    val topArea: Rect,
    val meterArea: Rect,
    val stripArea: Rect
)

private fun PanelLook.accentDark(): Color = when (this) {
    PanelLook.GOLD -> Color(0xFF6B5A2E)
    PanelLook.SILVER -> Color(0xFF4D5158)
    PanelLook.BRONZE -> Color(0xFF6B3E2A)
    PanelLook.CRYSTAL -> Color(0xFF4D6A74)
}

private fun PanelLook.accentWarm(): Color = when (this) {
    PanelLook.GOLD -> Color(0xFFCDBB86)
    PanelLook.SILVER -> Color(0xFFC7CDD6)
    PanelLook.BRONZE -> Color(0xFFCC8D63)
    PanelLook.CRYSTAL -> Color(0xFF9FE8FF)
}

private fun PanelLook.accentHi(): Color = when (this) {
    PanelLook.GOLD -> Color(0xFFF1E6C8)
    PanelLook.SILVER -> Color(0xFFF2F4F7)
    PanelLook.BRONZE -> Color(0xFFFFE4D3)
    PanelLook.CRYSTAL -> Color(0xFFE6FBFF)
}

private fun PanelLook.buttonText(): Color = when (this) {
    PanelLook.GOLD -> Color(0xFFF1E6C8)
    PanelLook.SILVER -> Color(0xFFF2F4F7)
    PanelLook.BRONZE -> Color(0xFFFFE4D3)
    PanelLook.CRYSTAL -> Color(0xFFDDF8FF)
}

private fun DrawScope.drawDeviceChassis(
    dimmer01: Float,
    timeSec: Float,
    look: PanelLook
): DeviceRects {
    val w = size.width
    val h = size.height

    val pad = size.minDimension * 0.045f
    val outer = Rect(pad, pad, w - pad, h - pad)

    val bezel = (size.minDimension * 0.020f).coerceIn(10f, 18f)
    val inner =
        Rect(outer.left + bezel, outer.top + bezel, outer.right - bezel, outer.bottom - bezel)

    val facePad = (size.minDimension * 0.012f).coerceIn(6f, 12f)
    val face = Rect(
        inner.left + facePad,
        inner.top + facePad,
        inner.right - facePad,
        inner.bottom - facePad
    )

    val topArea = Rect(face.left, face.top, face.right, face.top + face.height * 0.12f)
    val stripArea = Rect(face.left, face.top + face.height * 0.74f, face.right, face.bottom)
    val meterArea =
        Rect(face.left, face.top + face.height * 0.14f, face.right, face.top + face.height * 0.72f)

    val rOuter = (size.minDimension * 0.030f).coerceIn(14f, 24f)
    drawRoundRect(
        color = Color(0xFF040404),
        topLeft = Offset(outer.left, outer.top),
        size = Size(outer.width, outer.height),
        cornerRadius = CornerRadius(rOuter, rOuter)
    )

    val aDark = look.accentDark()
    val aWarm = look.accentWarm()
    val aHi = look.accentHi()

    val shimmer = (0.5f - 0.5f * cos(timeSec * 0.35f)).coerceIn(0f, 1f)
    val hiA = (0.08f + 0.10f * dimmer01) * (0.65f + 0.35f * shimmer)

    val bezelBrush = Brush.linearGradient(
        colors = listOf(
            aHi.copy(alpha = hiA),
            aWarm.copy(alpha = 0.42f),
            aDark.copy(alpha = 0.92f)
        ),
        start = Offset(outer.left, outer.top),
        end = Offset(outer.right, outer.bottom)
    )

    drawRoundRect(
        brush = bezelBrush,
        topLeft = Offset(inner.left - bezel, inner.top - bezel),
        size = Size(inner.width + bezel * 2f, inner.height + bezel * 2f),
        cornerRadius = CornerRadius(rOuter, rOuter),
        style = Stroke(width = bezel)
    )

    drawRoundRect(
        color = aHi.copy(alpha = (0.09f + 0.09f * dimmer01).coerceIn(0.08f, 0.20f)),
        topLeft = Offset(inner.left, inner.top),
        size = Size(inner.width, inner.height),
        cornerRadius = CornerRadius((rOuter - bezel * 0.8f).coerceAtLeast(10f)),
        style = Stroke(width = max(1.2f, size.minDimension * 0.0014f))
    )

    val faceBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF0A0A0A), Color(0xFF060606)),
        startY = face.top,
        endY = face.bottom
    )
    drawRoundRect(
        brush = faceBrush,
        topLeft = Offset(face.left, face.top),
        size = Size(face.width, face.height),
        cornerRadius = CornerRadius((rOuter - bezel).coerceAtLeast(10f))
    )

    drawFaceplateReflectionsAndHairline(face, dimmer01, timeSec, look)

    return DeviceRects(
        outer = outer,
        inner = inner,
        face = face,
        topArea = topArea,
        meterArea = meterArea,
        stripArea = stripArea
    )
}

private fun DrawScope.drawFaceplateReflectionsAndHairline(
    face: Rect,
    dimmer01: Float,
    timeSec: Float,
    look: PanelLook
) {
    val aBase = (0.03f + 0.03f * dimmer01).coerceIn(0.02f, 0.07f)

    val diag = Path().apply {
        moveTo(face.left + face.width * 0.12f, face.top + face.height * 0.18f)
        lineTo(face.right - face.width * 0.10f, face.top + face.height * 0.30f)
    }
    drawPath(
        path = diag,
        color = look.accentHi().copy(alpha = aBase),
        style = Stroke(
            width = max(2.0f, size.minDimension * 0.0020f),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.cornerPathEffect(10f)
        )
    )

    val glintT = (0.5f - 0.5f * cos(timeSec * 0.55f)).coerceIn(0f, 1f)
    val gx = face.left + face.width * (0.30f + 0.40f * glintT)
    val gy = face.top + face.height * (0.61f - 0.03f * glintT)
    drawCircle(
        color = look.accentHi()
            .copy(alpha = (0.010f + 0.020f * dimmer01) * (0.55f + 0.45f * glintT)),
        radius = max(1.1f, size.minDimension * 0.0010f),
        center = Offset(gx, gy)
    )
}

/* ----------------- Top Brand ----------------- */

private fun DrawScope.drawTopBrand(topArea: Rect, dimmer01: Float, look: PanelLook, alpha: Float) {
    val r = topArea
    val cr = (size.minDimension * 0.014f).coerceIn(10f, 18f)

    drawRoundRect(
        color = Color(0xFF050505).copy(alpha = 0.92f * alpha),
        topLeft = Offset(r.left, r.top),
        size = Size(r.width, r.height),
        cornerRadius = CornerRadius(cr, cr)
    )

    val textSize = (min(size.width, size.height) * 0.037f).coerceIn(20f, 32f)
    val baseA = ((0.22f + 0.16f * dimmer01) * alpha).coerceIn(0.18f, 0.42f)

    val hi = look.accentHi()
    val warm = look.accentWarm()
    val dark = look.accentDark()

    drawIntoCanvas { canvas ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            isFakeBoldText = true
            this.textSize = textSize
            letterSpacing = 0.03f

            shader = android.graphics.LinearGradient(
                r.center.x, r.top, r.center.x, r.bottom,
                intArrayOf(
                    android.graphics.Color.argb(
                        (baseA * 0.65f * 255f).roundToInt(),
                        (hi.red * 255).roundToInt(),
                        (hi.green * 255).roundToInt(),
                        (hi.blue * 255).roundToInt()
                    ),
                    android.graphics.Color.argb(
                        (baseA * 1.00f * 255f).roundToInt(),
                        (warm.red * 255).roundToInt(),
                        (warm.green * 255).roundToInt(),
                        (warm.blue * 255).roundToInt()
                    ),
                    android.graphics.Color.argb(
                        (baseA * 0.75f * 255f).roundToInt(),
                        (dark.red * 255).roundToInt(),
                        (dark.green * 255).roundToInt(),
                        (dark.blue * 255).roundToInt()
                    )
                ),
                floatArrayOf(0.0f, 0.55f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        canvas.nativeCanvas.drawText("vdk", r.center.x, r.center.y + textSize * 0.34f, paint)
    }
}

/* ----------------- Shared Window (thin) ----------------- */

private fun DrawScope.drawHiFiWindowThin(
    r: Rect,
    look: PanelLook,
    alpha: Float,
    frameBoost: Float = 1.0f
) {
    val cr = (size.minDimension * 0.018f).coerceIn(10f, 18f)

    drawRoundRect(
        color = Color(0xFF060606).copy(alpha = 0.96f * alpha),
        topLeft = Offset(r.left, r.top),
        size = Size(r.width, r.height),
        cornerRadius = CornerRadius(cr, cr)
    )

    val rimW = ((size.minDimension * 0.0050f) * frameBoost).coerceIn(2.4f, 5.4f)
    val rimBrush = Brush.linearGradient(
        colors = listOf(
            look.accentHi().copy(alpha = 0.16f * alpha),
            look.accentWarm().copy(alpha = 0.24f * alpha),
            look.accentDark().copy(alpha = 0.60f * alpha)
        ),
        start = Offset(r.left, r.top),
        end = Offset(r.right, r.bottom)
    )
    drawRoundRect(
        brush = rimBrush,
        topLeft = Offset(r.left + rimW * 0.5f, r.top + rimW * 0.5f),
        size = Size(r.width - rimW, r.height - rimW),
        cornerRadius = CornerRadius((cr - rimW).coerceAtLeast(6f), (cr - rimW).coerceAtLeast(6f)),
        style = Stroke(width = rimW)
    )

    val inset = rimW + (size.minDimension * 0.0074f).coerceIn(4.6f, 8.4f)
    drawRoundRect(
        color = Color(0xFF0C0C0C).copy(alpha = 0.92f * alpha),
        topLeft = Offset(r.left + inset, r.top + inset),
        size = Size(r.width - inset * 2f, r.height - inset * 2f),
        cornerRadius = CornerRadius((cr - inset).coerceAtLeast(6f))
    )

    drawRoundRect(
        color = Color.White.copy(alpha = 0.048f * alpha),
        topLeft = Offset(r.left + inset, r.top + inset),
        size = Size(r.width - inset * 2f, (r.height - inset * 2f) * 0.22f),
        cornerRadius = CornerRadius((cr - inset).coerceAtLeast(6f))
    )
}

/* ----------------- Slide 1: Classic VU ----------------- */

private fun DrawScope.drawClassicVuCentered(
    device: DeviceRects,
    level: Float,
    isLive: Boolean,
    ballistics: Ballistics,
    dimmer01: Float,
    timeSec: Float,
    look: PanelLook,
    alpha: Float
) {
    if (alpha <= 0.001f) return

    val area = device.meterArea

    val insetX = area.width * 0.060f
    val insetY = area.height * 0.085f
    val zone = Rect(
        area.left + insetX,
        area.top + insetY,
        area.right - insetX,
        area.bottom - insetY
    )

    val w = zone.width
    val h = zone.height

    val gap = (w * 0.060f).coerceIn(16f, 34f)
    val meterW = (w - gap) / 2f
    val meterH = (h * 0.78f).coerceIn(120f, h)

    val pairW = meterW * 2f + gap
    val startX = zone.center.x - pairW * 0.5f
    val top = zone.top + (h - meterH) * 0.10f

    val leftRect = Rect(startX, top, startX + meterW, top + meterH)
    val rightRect = Rect(startX + meterW + gap, top, startX + meterW + gap + meterW, top + meterH)

    drawHiFiWindowThin(leftRect, look, alpha)
    drawHiFiWindowThin(rightRect, look, alpha)

    val lvl = if (isLive) level.coerceIn(0f, 1f) else 0f
    val shaped = lvl.pow(ballistics.curve).coerceIn(0f, 1f)

    drawVuFaceSubtleCurve(leftRect, shaped, alpha, dimmer01, look)
    drawVuFaceSubtleCurve(rightRect, shaped, alpha, dimmer01, look)
}

private fun DrawScope.drawVuFaceSubtleCurve(
    r: Rect,
    level01: Float,
    alpha: Float,
    dimmer01: Float,
    look: PanelLook
) {
    val inset = (size.minDimension * 0.020f).coerceIn(12f, 20f)
    val inner = Rect(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset)

    val hi = look.accentHi()
    val warm = look.accentWarm()
    val dark = look.accentDark()
    val signatureRed = Color(0xFFFF3B30)

    clipRect(inner.left, inner.top, inner.right, inner.bottom) {

        val plateBrush = Brush.verticalGradient(
            colors = listOf(
                hi.copy(alpha = (0.30f + 0.06f * dimmer01) * alpha),
                warm.copy(alpha = (0.56f + 0.08f * dimmer01) * alpha),
                dark.copy(alpha = 0.72f * alpha)
            ),
            startY = inner.top,
            endY = inner.bottom
        )
        val cr = (size.minDimension * 0.010f).coerceIn(6f, 10f)
        drawRoundRect(
            brush = plateBrush,
            topLeft = Offset(inner.left, inner.top),
            size = Size(inner.width, inner.height),
            cornerRadius = CornerRadius(cr, cr)
        )

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = (0.06f + 0.06f * dimmer01) * alpha),
                    Color.Transparent
                ),
                startY = inner.top,
                endY = inner.top + inner.height * 0.30f
            ),
            topLeft = Offset(inner.left, inner.top),
            size = Size(inner.width, inner.height),
            cornerRadius = CornerRadius(cr, cr)
        )

        val leftX = inner.left + inner.width * 0.08f
        val rightX = inner.right - inner.width * 0.08f
        val span = rightX - leftX

        val baseY = inner.top + inner.height * 0.20f
        val curve = inner.height * 0.030f

        fun scaleY(t: Float): Float {
            val x = (t - 0.5f)
            return baseY + curve * (x * x * 4f - 1f) * 0.55f
        }

        val steps = 48
        var prev = Offset(leftX, scaleY(0f))
        for (i in 1..steps) {
            val t = i / steps.toFloat()
            val x = leftX + span * t
            val p = Offset(x, scaleY(t))
            drawLine(
                color = hi.copy(alpha = 0.22f * alpha),
                start = prev,
                end = p,
                strokeWidth = max(1.6f, size.minDimension * 0.0012f),
                cap = StrokeCap.Round
            )
            prev = p
        }

        val minor = 25
        val major = 11
        val tickCol = Color(0xFF1B160B).copy(alpha = 0.80f * alpha)
        val hotCol = signatureRed.copy(alpha = 0.92f * alpha)

        for (i in 0 until minor) {
            val t = i / (minor - 1).toFloat()
            val x = leftX + span * t
            val y0 = scaleY(t)

            val isMajor = (i % ((minor - 1) / (major - 1)).coerceAtLeast(1) == 0)
            val len = if (isMajor) inner.height * 0.12f else inner.height * 0.07f
            val inHotZone = t > 0.82f
            val c = if (inHotZone) hotCol else tickCol

            drawLine(
                color = c,
                start = Offset(x, y0),
                end = Offset(x, y0 + len),
                strokeWidth = if (isMajor) 2.2f else 1.2f,
                cap = StrokeCap.Butt
            )
        }

        drawIntoCanvas { canvas ->
            val pSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = (min(size.width, size.height) * 0.0125f).coerceIn(9f, 12.5f)
                color = android.graphics.Color.argb((170f * alpha).roundToInt(), 35, 28, 16)
                letterSpacing = 0.06f
            }
            val titleY = inner.top + inner.height * 0.47f
            canvas.nativeCanvas.drawText("POWER OUTPUT", inner.center.x, titleY, pSmall)
        }

        val pivot = Offset(inner.center.x, inner.bottom - inner.height * 0.16f)

        val angMin = 180f
        val angMax = 360f
        val ang = angMin + (angMax - angMin) * level01.coerceIn(0f, 1f)
        val rad = (ang * PI.toFloat() / 180f)

        val needleLen = min(inner.width, inner.height) * 0.62f
        val tip = Offset(
            pivot.x + kotlin.math.cos(rad) * needleLen,
            pivot.y + kotlin.math.sin(rad) * needleLen
        )

        drawLine(
            color = Color.Black.copy(alpha = 0.24f * alpha),
            start = Offset(pivot.x + 1.8f, pivot.y + 1.8f),
            end = Offset(tip.x + 1.8f, tip.y + 1.8f),
            strokeWidth = 3.4f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = signatureRed.copy(alpha = 0.92f * alpha),
            start = pivot,
            end = tip,
            strokeWidth = 2.7f,
            cap = StrokeCap.Round
        )

        val hubR = max(7.8f, size.minDimension * 0.0054f)
        drawCircle(
            color = Color(0xFF0A0A0A).copy(alpha = 0.95f * alpha),
            radius = hubR,
            center = pivot
        )
        drawCircle(
            color = warm.copy(alpha = 0.32f * alpha),
            radius = hubR * 0.45f,
            center = pivot
        )

        val glowY = inner.bottom - inner.height * 0.10f
        val glowH = inner.height * 0.08f
        val glowA = (0.10f + 0.16f * dimmer01) * alpha
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    hi.copy(alpha = 0.00f),
                    hi.copy(alpha = glowA * 0.55f),
                    hi.copy(alpha = glowA),
                    hi.copy(alpha = glowA * 0.45f),
                    hi.copy(alpha = 0.00f)
                ),
                startY = glowY,
                endY = glowY + glowH
            ),
            topLeft = Offset(inner.left + inner.width * 0.06f, glowY),
            size = Size(inner.width * 0.88f, glowH),
            cornerRadius = CornerRadius(cr, cr)
        )
    }
}

/* ----------------- Slide 2: LED Dots ----------------- */

private fun DrawScope.drawLedDotsPro(
    device: DeviceRects,
    level: Float,
    peak: Float,
    isLive: Boolean,
    dimmer01: Float,
    timeSec: Float,
    look: PanelLook,
    alpha: Float
) {
    if (alpha <= 0.001f) return

    val area = device.meterArea

    val panelW = area.width * 0.88f
    val panelH = area.height * 0.86f
    val panelLeft = area.center.x - panelW * 0.5f
    val panelTop = area.top + (area.height - panelH) * 0.12f
    val panel = Rect(panelLeft, panelTop, panelLeft + panelW, panelTop + panelH)

    drawRoundRect(
        color = Color(0xFF060606).copy(alpha = 0.96f * alpha),
        topLeft = Offset(panel.left, panel.top),
        size = Size(panel.width, panel.height),
        cornerRadius = CornerRadius((size.minDimension * 0.018f).coerceIn(10f, 18f))
    )

    val inset = (size.minDimension * 0.016f).coerceIn(10f, 16f)
    val inner =
        Rect(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

    drawDoubleCenterRailsPro(inner, dimmer01, timeSec, look, alpha)

    val lvl = if (isLive) level.coerceIn(0f, 1f) else 0f
    val pk = if (isLive) peak.coerceIn(0f, 1f) else 0f

    val segments = 26
    val dotGap = (inner.height * 0.010f).coerceIn(3f, 8f)
    val step = (inner.height - dotGap * (segments - 1)) / segments.toFloat()

    val railGap = (inner.width * 0.015f).coerceIn(7f, 12f)
    val railW = (inner.width * 0.050f).coerceIn(13f, 24f)
    val railsTotalW = railW * 2f + railGap

    val colsPerSide = 7

    val bandPad = (inner.width * 0.024f).coerceIn(8f, 14f)
    val leftBand = Rect(
        inner.left + bandPad,
        inner.top,
        inner.center.x - railsTotalW * 0.5f - bandPad,
        inner.bottom
    )
    val rightBand = Rect(
        inner.center.x + railsTotalW * 0.5f + bandPad,
        inner.top,
        inner.right - bandPad,
        inner.bottom
    )

    val ledGreen = Color(0xFF25D366)
    val ledYellow = Color(0xFFFFD166)
    val ledRed = Color(0xFFFF3B30)
    val peakHi = Color.White

    fun colorForIndex(iFromBottom: Int): Color {
        val gEnd = (segments * 0.64f).roundToInt().coerceAtLeast(1)
        val yEnd = (segments * 0.86f).roundToInt().coerceAtLeast(gEnd + 1)
        return when {
            iFromBottom <= gEnd -> ledGreen
            iFromBottom <= yEnd -> ledYellow
            else -> ledRed
        }
    }

    val envelope = floatArrayOf(0.86f, 0.94f, 1.04f, 1.14f, 1.04f, 0.94f, 0.86f)
    val radiusScale = floatArrayOf(0.86f, 0.98f, 1.12f, 1.32f, 1.12f, 0.98f, 0.86f)

    fun columnXsPacked(band: Rect): FloatArray {
        val usableW = band.width
        val gap = (usableW / (colsPerSide + 1.55f)).coerceAtLeast(size.minDimension * 0.010f)
        val totalSpan = gap * (colsPerSide - 1)
        val startX = band.center.x - totalSpan * 0.5f
        return FloatArray(colsPerSide) { i -> startX + i * gap }
    }

    val leftXs = columnXsPacked(leftBand)
    val rightXs = columnXsPacked(rightBand)

    fun drawDotColumn(x: Float, value01: Float, peak01: Float, dotR: Float) {
        val v = value01.coerceIn(0f, 1f).pow(0.62f)
        val lit = (v * segments).roundToInt().coerceIn(0, segments)

        val p = peak01.coerceIn(0f, 1f)
        val peakIndex = (p.pow(0.62f) * segments).roundToInt().coerceIn(0, segments)

        for (i in 1..segments) {
            val y = inner.bottom - i * step - (i - 1) * dotGap + step * 0.5f
            val on = i <= lit
            val c = colorForIndex(i)
            val aOn = 0.90f * alpha
            val aOff = 0.13f * alpha
            val a = if (on) aOn else aOff
            drawCircle(color = c.copy(alpha = a), radius = dotR, center = Offset(x, y))
        }

        if (peakIndex >= 1) {
            val y = inner.bottom - peakIndex * step - (peakIndex - 1) * dotGap + step * 0.5f
            drawCircle(peakHi.copy(alpha = 0.66f * alpha), dotR * 1.02f, Offset(x, y))
            drawCircle(peakHi.copy(alpha = 0.16f * alpha), dotR * 1.55f, Offset(x, y))
        }
    }

    val baseDotR = min(step, inner.width * 0.024f) * 0.48f
    for (i in 0 until colsPerSide) {
        val dotR = baseDotR * radiusScale[i]
        val g = envelope[i]
        drawDotColumn(leftXs[i], lvl * g, pk, dotR)
        drawDotColumn(rightXs[i], lvl * g, pk, dotR)
    }
}

private fun DrawScope.drawDoubleCenterRailsPro(
    inner: Rect,
    dimmer01: Float,
    timeSec: Float,
    look: PanelLook,
    alpha: Float
) {
    val railGap = (inner.width * 0.015f).coerceIn(7f, 12f)
    val railW = (inner.width * 0.050f).coerceIn(13f, 24f)
    val railH = (inner.height * 0.94f).coerceAtLeast(10f)
    val marginY = (inner.height * 0.03f).coerceIn(6f, 12f)
    val top = inner.top + marginY

    val totalW = railW * 2f + railGap
    val startX = inner.center.x - totalW * 0.5f

    val hi = look.accentHi()
    val warm = look.accentWarm()
    val dark = look.accentDark()

    fun drawOneRail(x: Float, phaseOffset: Float) {
        val r = Rect(x, top, x + railW, top + railH)
        val cr = min(railW, railH) * 0.35f

        val rim = (size.minDimension * 0.0032f).coerceIn(1.6f, 3.8f)
        val rimBrush = Brush.linearGradient(
            colors = listOf(
                hi.copy(alpha = 0.14f * alpha),
                warm.copy(alpha = 0.26f * alpha),
                dark.copy(alpha = 0.58f * alpha)
            ),
            start = Offset(r.left, r.top),
            end = Offset(r.right, r.bottom)
        )
        drawRoundRect(
            brush = rimBrush,
            topLeft = Offset(r.left, r.top),
            size = Size(r.width, r.height),
            cornerRadius = CornerRadius(cr, cr),
            style = Stroke(width = rim)
        )

        val slotInset = rim * 1.75f
        val slot =
            Rect(r.left + slotInset, r.top + slotInset, r.right - slotInset, r.bottom - slotInset)
        val slotCr = (cr - slotInset).coerceAtLeast(5f)

        drawRoundRect(
            color = Color(0xFF070707).copy(alpha = 0.92f * alpha),
            topLeft = Offset(slot.left, slot.top),
            size = Size(slot.width, slot.height),
            cornerRadius = CornerRadius(slotCr, slotCr)
        )
        drawRoundRect(
            color = hi.copy(alpha = (0.040f + 0.070f * dimmer01) * alpha),
            topLeft = Offset(slot.left + 1.6f, slot.top + 1.6f),
            size = Size(slot.width - 3.2f, slot.height - 3.2f),
            cornerRadius = CornerRadius(slotCr, slotCr),
            style = Stroke(width = max(0.85f, size.minDimension * 0.00082f))
        )

        val period = 8.8f
        val phase = (timeSec / period) * (2f * PI.toFloat()) + phaseOffset
        val travel01 = (0.5f - 0.5f * cos(phase)).coerceIn(0f, 1f)

        val bandH = (slot.height * 0.26f).coerceIn(10f, slot.height)
        val bandTop =
            (slot.top + travel01 * (slot.height - bandH)).coerceIn(slot.top, slot.bottom - bandH)

        val glowA = ((0.10f + 0.24f * dimmer01) * alpha).coerceIn(0.05f, 0.38f)
        val brush = Brush.verticalGradient(
            colors = listOf(
                hi.copy(alpha = 0.00f),
                hi.copy(alpha = glowA * 0.55f),
                hi.copy(alpha = glowA),
                hi.copy(alpha = glowA * 0.55f),
                hi.copy(alpha = 0.00f),
            ),
            startY = bandTop,
            endY = bandTop + bandH
        )

        drawRoundRect(
            brush = brush,
            topLeft = Offset(slot.left, slot.top),
            size = Size(slot.width, slot.height),
            cornerRadius = CornerRadius(slotCr, slotCr)
        )
    }

    drawOneRail(startX, phaseOffset = 0.0f)
    drawOneRail(startX + railW + railGap, phaseOffset = 0.55f)
}

/* ----------------- Slide 3: Waveform ----------------- */

private fun DrawScope.drawWaveformPro(
    device: DeviceRects,
    level: Float,
    isLive: Boolean,
    dimmer01: Float,
    timeSec: Float,
    look: PanelLook,
    alpha: Float
) {
    if (alpha <= 0.001f) return

    val area = device.meterArea
    val w = area.width
    val h = area.height

    val panelW = w * 0.86f
    val panelH = h * 0.84f
    val panel = Rect(
        area.center.x - panelW * 0.5f,
        area.top + (h - panelH) * 0.12f,
        area.center.x + panelW * 0.5f,
        area.top + (h - panelH) * 0.12f + panelH
    )
    drawRoundRect(
        color = Color(0xFF060606).copy(alpha = 0.96f * alpha),
        topLeft = Offset(panel.left, panel.top),
        size = Size(panel.width, panel.height),
        cornerRadius = CornerRadius((size.minDimension * 0.018f).coerceIn(10f, 18f))
    )

    val inset = (size.minDimension * 0.016f).coerceIn(10f, 16f)
    val inner =
        Rect(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

    drawRoundRect(
        color = Color(0xFF070707).copy(alpha = 0.92f * alpha),
        topLeft = Offset(inner.left, inner.top),
        size = Size(inner.width, inner.height),
        cornerRadius = CornerRadius((size.minDimension * 0.010f).coerceIn(6f, 10f))
    )

    val lvl = if (isLive) level.coerceIn(0f, 1f) else 0f
    val midY = inner.center.y

    val leftX = inner.left + inner.width * 0.06f
    val rightX = inner.right - inner.width * 0.06f
    val span = rightX - leftX

    fun envelope(t: Float): Float {
        val x = (t - 0.5f) / 0.25f
        val g = exp(-x * x)
        return (0.50f + 0.60f * g).coerceIn(0.50f, 1.15f)
    }

    val ampBase = (inner.height * 0.42f) * (0.10f + 0.90f * lvl.pow(0.85f))

    val phase = timeSec * 0.85f
    val f1 = 1.05f
    val f2 = 2.35f
    val f3 = 3.90f

    fun wave(t: Float): Float {
        val a = sin((t * 2f * PI.toFloat() * f1) + phase).toFloat()
        val b = sin((t * 2f * PI.toFloat() * f2) + phase * 0.77f).toFloat()
        val c = sin((t * 2f * PI.toFloat() * f3) + phase * 0.49f).toFloat()
        val mix = 0.56f * a + 0.30f * b + 0.14f * c
        return mix * envelope(t)
    }

    val hi = look.accentHi()
    val warm = look.accentWarm()
    val dark = look.accentDark()
    val hot = lerpColor(warm, hi, 0.70f)

    val slices = 140
    val dx = span / (slices - 1).toFloat()

    for (i in 0 until slices) {
        val t = i / (slices - 1).toFloat()
        val x = leftX + dx * i

        val y = wave(t)
        val amp = abs(y)

        val lineH = (amp * ampBase).coerceAtLeast(inner.height * 0.02f)
        val top = (midY - lineH).coerceIn(inner.top, inner.bottom)
        val bot = (midY + lineH).coerceIn(inner.top, inner.bottom)

        val dist = abs(t - 0.5f) / 0.5f
        val c = when {
            dist < 0.20f -> hi
            dist < 0.55f -> warm
            dist < 0.85f -> dark
            else -> hot
        }

        val a = ((0.10f + 0.34f * (1f - dist)) * (0.35f + 0.65f * dimmer01) * alpha).coerceIn(
            0.06f,
            0.55f
        )
        val sw = (size.minDimension * (0.00095f + 0.00065f * (1f - dist))).coerceIn(0.9f, 2.2f)

        drawLine(
            color = c.copy(alpha = a),
            start = Offset(x, top),
            end = Offset(x, bot),
            strokeWidth = sw,
            cap = StrokeCap.Round
        )
    }

    drawLine(
        color = Color.White.copy(alpha = 0.040f * alpha),
        start = Offset(leftX, midY),
        end = Offset(rightX, midY),
        strokeWidth = max(1.0f, size.minDimension * 0.0010f),
        cap = StrokeCap.Butt
    )
}

/* ----------------- Slide 4: Matrix ----------------- */

private enum class MatrixMode(val label: String) {
    SPECTRUM("SPECTR"),
    MIRROR("MIRROR"),
    WAVE("WAVE"),
    BLOCKS("BLOCKS"),
    PULSE("PULSE"),
    TEXT("TEXT"),
    SPRING("SPRNG"),
    RAIN("RAIN"),
    STAIR("STAIR"),
    FIRE("FIRE"),
    NEEDLE("NEEDL");

    companion object {
        fun fromIndex(i: Int): MatrixMode {
            val all = entries
            return all[(i % all.size + all.size) % all.size]
        }
    }
}

private enum class MatrixPalette(val label: String) {
    AMBER("AMBER"),
    MAGENTA("MAGENTA"),
    CYAN("CYAN"),
    CLASSIC("CLASSIC"),
    MONO("MONO");

    companion object {
        fun fromIndex(i: Int): MatrixPalette {
            val all = entries
            return all[(i % all.size + all.size) % all.size]
        }
    }
}

/* ----------------- Slide 5: Lasers (panel-local types) ----------------- */

private enum class PanelLaserRig(val label: String) {
    FAN("FAN"),
    DUAL_FAN("DUAL"),
    SCANNER("SCAN"),
    FULL_SHOW("SHOW"),
    LIGHT_FLOOR("FLOOR");

    companion object {
        fun fromIndex(i: Int): PanelLaserRig {
            val all = entries
            return all[(i % all.size + all.size) % all.size]
        }
    }
}

private enum class PanelLaserPalette(val label: String) {
    // COLOR knop = kleurpalet (geen DMX preset bank)
    GREEN("GRN"),
    CYAN("CYAN"),
    MAGENTA("MAG"),
    RED("RED"),
    WHITE("WHT");

    companion object {
        fun fromIndex(i: Int): PanelLaserPalette {
            val all = entries
            return all[(i % all.size + all.size) % all.size]
        }
    }
}

private data class PanelLaserBeam(
    val origin: Offset,
    val end: Offset,
    val depth01: Float,
    val color: Color,
    val intensity01: Float
)

private fun DrawScope.drawLaserShowPro(
    device: DeviceRects,
    level: Float,
    isLive: Boolean,
    dimmer01: Float,
    timeSec: Float,
    look: PanelLook,
    alpha: Float,
    palette: PanelLaserPalette,
    rig: PanelLaserRig
) {
    if (alpha <= 0.001f) return

    val area = device.meterArea

    // --- Panel window (sources live here) ---
    val panelW = area.width * 0.90f
    val panelH = area.height * 0.86f
    val panel = Rect(
        area.center.x - panelW * 0.5f,
        area.top + (area.height - panelH) * 0.12f,
        area.center.x + panelW * 0.5f,
        area.top + (area.height - panelH) * 0.12f + panelH
    )

    drawHiFiWindowThin(panel, look, alpha, frameBoost = 1.10f)

    val inset = (size.minDimension * 0.017f).coerceIn(10f, 16f)
    val inner =
        Rect(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

    val cr = (size.minDimension * 0.010f).coerceIn(6f, 10f)
    drawRoundRect(
        color = Color(0xFF000000).copy(alpha = 0.965f * alpha),
        topLeft = Offset(inner.left, inner.top),
        size = Size(inner.width, inner.height),
        cornerRadius = CornerRadius(cr)
    )

    // --- Audio energy ---
    val lvl = if (isLive) level.coerceIn(0f, 1f) else 0f
    val bass = lvl.pow(0.70f)          // stronger response
    val mid = lvl.pow(0.92f)
    val high = lvl.pow(1.18f)

    // --- DMX preset colors (fixtures have fixed colors; COLOR button selects presets, not colors) ---
    val green = Color(0xFF00FF66)
    val red = Color(0xFFFF2A2A)
    val cyan = Color(0xFF00E5FF)
    val magenta = Color(0xFFFF3ED6)

    // base set used by most presets (no rainbow per beam)
    data class Duo(val a: Color, val b: Color, val c: Color)

    val duo = when (palette) {

        PanelLaserPalette.GREEN ->
            Duo(
                a = green,
                b = Color(0xFF66FF66),
                c = Color(0xFF9AFF6A)
            )

        PanelLaserPalette.CYAN ->
            Duo(
                a = cyan,
                b = Color(0xFF9FFFFF),
                c = Color.White
            )

        PanelLaserPalette.MAGENTA ->
            Duo(
                a = magenta,
                b = Color(0xFFFFB3FF),
                c = Color.White
            )

        PanelLaserPalette.RED ->
            Duo(
                a = red,
                b = Color(0xFFFF8080),
                c = Color(0xFFFFB3B3)
            )

        PanelLaserPalette.WHITE ->
            Duo(
                a = Color.White,
                b = Color(0xFFE0F2FF),
                c = Color(0xFFCFE8FF)
            )
    }

    // --- CLUB HAZE / SCREEN WASH (makes beams feel "volumetric" instead of thin lines) ---
    // 1) Ambient haze tint inside the window (very subtle, depth cue)
    // 2) Viewer flash/wash on energetic peaks (like looking into a laser in a club)
    val washColor = when (palette) {
        PanelLaserPalette.GREEN -> green
        PanelLaserPalette.CYAN -> cyan
        PanelLaserPalette.MAGENTA -> magenta
        PanelLaserPalette.RED -> red
        PanelLaserPalette.WHITE -> Color.White
    }

    // Soft ambient haze always present (only when live)
    val hazeAmbientA =
        (0.010f + 0.060f * smooth01((lvl - 0.08f) / 0.40f)) *
                (0.35f + 0.65f * dimmer01) * alpha

    if (isLive && hazeAmbientA > 0.003f) {
        // radial glow = "smoke curtain" feel across the whole window
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    washColor.copy(alpha = hazeAmbientA * 1.10f),
                    washColor.copy(alpha = hazeAmbientA * 0.55f),
                    washColor.copy(alpha = 0f),
                ),
                center = inner.center,
                radius = max(inner.width, inner.height) * 0.72f
            ),
            topLeft = Offset(inner.left, inner.top),
            size = Size(inner.width, inner.height),
            cornerRadius = CornerRadius(cr, cr)
        )
    }


    // strobe rate rises on peaks; kept bounded to avoid "cartoon jitter"
    val strobeHz = lerp(4.0f, 9.5f, smooth01((lvl - 0.55f) / 0.45f))
    fun strobeOn(phase: Float = 0f, duty: Float = 0.35f): Boolean {
        val x = (sin((timeSec + phase) * (2f * PI.toFloat()) * strobeHz) * 0.5f + 0.5f)
        return x > (1f - duty)
    }
    // Viewer flash (short wash on peaks / strobe bursts)
    val peakFlash01 =
        smooth01((lvl - 0.38f) / 0.34f) * (0.55f + 0.45f * high)

    val flashGate =
        if (lvl > 0.62f) (strobeOn(phase = 0.09f, duty = 0.14f)) else (lvl > 0.78f)

    val flashA =
        if (isLive && flashGate) (0.02f + 0.14f * peakFlash01) * (0.35f + 0.65f * dimmer01) * alpha
        else 0f

    if (flashA > 0.004f) {
        drawRoundRect(
            brush = Brush.radialGradient(
                colors = listOf(
                    washColor.copy(alpha = flashA),
                    washColor.copy(alpha = flashA * 0.55f),
                    washColor.copy(alpha = 0f)
                ),
                center = Offset(inner.center.x, inner.top + inner.height * 0.42f),
                radius = max(inner.width, inner.height) * 0.95f
            ),
            topLeft = Offset(inner.left, inner.top),
            size = Size(inner.width, inner.height),
            cornerRadius = CornerRadius(cr, cr)
        )
    }


    // --- DMX-ish shutter / strobe timing (not always on) ---
    // base: subtle gating so beams breathe even without peaks
    val breathe = 0.55f + 0.45f * sin(timeSec * 1.15f)
    val peakGate = smooth01((lvl - 0.18f) / 0.28f)
    val liveGate = smooth01(0.35f * breathe + 0.65f * peakGate)
    // Occasional blackout punches (very short) for "club" feel.
    val punchRate = 0.70f
    val punch = (sin(timeSec * (2f * PI.toFloat()) * punchRate) * 0.5f + 0.5f)
    val blackout = (punch > 0.985f) && (lvl > 0.45f)   // rare + only when energetic

    // --- 3D-ish projection for forward/back depth feel ---
    // We treat the screen as a projection plane; beams can go "behind" (negative forward).
    // This is a stylized projection (not physically exact), tuned for stage-laser feel.
    data class V3(val x: Float, val y: Float, val z: Float)

    fun dirFromYawPitch(yaw: Float, pitch: Float, forwardBias: Float): V3 {
        val cy = cos(yaw);
        val sy = sin(yaw)
        val cp = cos(pitch);
        val sp = sin(pitch)
        // y is up in rig space; z is forward (towards viewer).
        val x = sy * cp
        val y = -sp
        val z = cy * cp
        // bias forward/back (allows deliberate "behind" cones)
        return V3(x, y, z * forwardBias)
    }

    fun project(origin: Offset, d: V3, reachPx: Float): Pair<Offset, Float> {
        // forward factor: -1..1 (behind..towards)
        val f = d.z.coerceIn(0f, 1.2f)

        // reach scaling: forward beams travel longer
        val reach = reachPx * lerp(0.95f, 1.65f, f)

        // pseudo perspective: forward beams spread more across screen
        val persp = lerp(1.10f, 1.80f, f)

        val end = Offset(
            origin.x + d.x * reach * persp,
            origin.y + d.y * reach * persp
        )

        // depth01 for rendering (0 = behind, 1 = towards)
        val depth01 = (f / 1.2f).coerceIn(0f, 1f)
        return end to depth01
    }

    // --- Fixture positions (sources MUST stay inside the inner window) ---
    fun inInner(nx: Float, ny: Float): Offset =
        Offset(inner.left + nx * inner.width, inner.top + ny * inner.height)

    // 5-laser line slightly above center (your spec)
    val yLine = 0.42f
    val lineXs = floatArrayOf(0.18f, 0.34f, 0.50f, 0.66f, 0.82f)
    val lineOrigins = lineXs.map { inInner(it, yLine) }

    // Additional fixture for other shapes (still within the window)
    val center = inInner(0.50f, 0.70f)

    // --- Global rig motion (coherent, slow, "mechanical") ---
    // These are intentionally slow and smooth; individual beams derive from this.
    val rigYaw = sin(timeSec * 0.35f) * lerp(0.45f, 0.95f, bass)      // left-right
    val rigPitch = sin(timeSec * 0.27f + 1.2f) * lerp(0.30f, 0.75f, mid) // up-down

    // Forward-only: no behind beams (your final decision)
    val forwardBias = 1.15f

    // --- Beam list ---
    val beams = ArrayList<PanelLaserBeam>(220)

    // Utility to add a beam + optional haze samples
    fun addBeam(
        origin: Offset,
        yaw: Float,
        pitch: Float,
        color: Color,
        baseIntensity: Float,
        reach: Float
    ) {
        if (blackout) return

        // shutter gating
        val shutter = liveGate
        if (shutter <= 0.02f) return

        val d = dirFromYawPitch(yaw, pitch, forwardBias)
        val (end, depth01) = project(origin, d, reach)

        // intensity: forward a bit stronger, behind dimmer
        val forwardBoost = lerp(0.72f, 1.15f, depth01)
        val intensity01 = (baseIntensity * shutter * forwardBoost).coerceIn(0f, 1f)

        // skip if basically dark
        if (intensity01 <= 0.01f) return

        beams += PanelLaserBeam(origin, end, depth01, color, intensity01)
    }

    // --- Scene builders (5 rigs) ---
    val reach = size.minDimension * 1.35f  // beams can use whole display

    // COLOR = kleurpalet (rig blijft gekozen via SHAPE knop)
    val effectiveRig: PanelLaserRig = rig

    // Fan density depends on the effective scene (so FINAL looks richer)
    val fanCount = when (effectiveRig) {
        PanelLaserRig.FULL_SHOW -> 90
        else -> 60
    }

    when (effectiveRig) {
        PanelLaserRig.DUAL_FAN -> {
            // Premium symmetric scanner bar:
            // now with musical mirrored group behavior:
            // - sometimes outer pair + inner pair + center
            // - sometimes center trio + outer pair
            // - smooth scanner motion
            // - stronger viewer-directed hits
            // - color movement can happen per head, per pair, or globally

            val peakBurst01 = smooth01((lvl - 0.56f) / 0.24f)
            val blindPunch = lvl > 0.72f && strobeOn(phase = 0.05f, duty = 0.10f)

            val baseYaw = rigYaw * lerp(0.62f, 0.96f, bass)
            val basePitch = rigPitch * lerp(0.10f, 0.22f, mid)

            val spreadInner = lerp(0.06f, 0.14f, smooth01((lvl - 0.06f) / 0.62f))
            val spreadOuter = lerp(0.14f, 0.28f, smooth01((lvl - 0.08f) / 0.66f))

            val verticalSweep =
                sin(timeSec * 0.42f) * lerp(0.020f, 0.060f, mid)
            val horizontalSweep =
                sin(timeSec * 0.30f + 0.8f) * lerp(0.020f, 0.070f, bass)

            val beamsPerFixture = 5
            val half = (beamsPerFixture - 1) * 0.5f
            val fillBeamsPerGap = 4

            // Musical group choreography:
            // 0 = all together
            // 1 = mirrored pairs + center
            // 2 = center trio vs outer pair
            val groupMode = ((floor(timeSec * lerp(0.22f, 0.48f, bass)).toInt()) % 3 + 3) % 3

            // Color choreography:
            // 0 = all same color family
            // 1 = mirrored pairs share colors
            // 2 = each head gets a related tone
            val colorMode = ((floor(timeSec * lerp(0.28f, 0.58f, mid)).toInt()) % 3 + 3) % 3

            val globalColor = when {
                lvl > 0.78f -> duo.c
                lvl > 0.52f -> duo.a
                else -> duo.b
            }

            lineOrigins.forEachIndexed { idx, o ->
                val pos = idx - 2 // -2, -1, 0, 1, 2
                val absPos = abs(pos).toFloat()

                val headPhase = idx * 0.23f

                // ----- mirrored musical grouping -----
                val groupGain = when (groupMode) {
                    0 -> {
                        when (idx) {
                            2 -> 1.00f
                            1, 3 -> 0.92f
                            else -> 0.84f
                        }
                    }

                    1 -> {
                        val outerBeat = 0.5f + 0.5f * sin(timeSec * 1.55f + 0.4f)
                        val innerBeat = 0.5f + 0.5f * sin(timeSec * 1.55f + 2.1f)
                        val centerBeat = 0.5f + 0.5f * sin(timeSec * 1.55f + 3.5f)

                        when (idx) {
                            0, 4 -> lerp(0.48f, 1.00f, outerBeat)
                            1, 3 -> lerp(0.48f, 1.00f, innerBeat)
                            else -> lerp(0.56f, 1.00f, centerBeat)
                        }
                    }

                    else -> {
                        val trioBeat = 0.5f + 0.5f * sin(timeSec * 1.35f + 0.8f)
                        val edgeBeat = 0.5f + 0.5f * sin(timeSec * 1.35f + 3.1f)

                        when (idx) {
                            1, 2, 3 -> lerp(0.52f, 1.00f, trioBeat)
                            else -> lerp(0.40f, 0.92f, edgeBeat)
                        }
                    }
                }

                // ----- mirrored color behavior -----
                val headColor = when (colorMode) {
                    0 -> {
                        globalColor
                    }

                    1 -> {
                        when (idx) {
                            0, 4 -> duo.b
                            1, 3 -> duo.a
                            else -> duo.c
                        }
                    }

                    else -> {
                        when (idx) {
                            0, 4 -> duo.b
                            1, 3 -> if (
                                strobeOn(
                                    phase = 0.06f + idx * 0.03f,
                                    duty = 0.22f
                                )
                            ) duo.c else duo.a

                            else -> if (lvl > 0.70f) duo.c else duo.a
                        }
                    }
                }

                val headEnergyBase = when (idx) {
                    2 -> lerp(0.72f, 1.00f, bass)
                    1, 3 -> lerp(0.56f, 0.88f, bass)
                    else -> lerp(0.42f, 0.72f, bass)
                }

                val headEnergy = headEnergyBase * groupGain

                val localYawBase =
                    baseYaw +
                            horizontalSweep * (0.60f - 0.16f * absPos) +
                            pos * lerp(0.08f, 0.16f, bass) +
                            sin(timeSec * 0.34f + headPhase) * lerp(0.010f, 0.034f, mid)

                val localPitchBase =
                    basePitch +
                            verticalSweep +
                            cos(timeSec * 0.46f + headPhase) * lerp(0.008f, 0.028f, mid) -
                            absPos * 0.008f

                val localSpread = lerp(spreadInner, spreadOuter, (absPos / 2f).coerceIn(0f, 1f))

                // Main scanner rays - smoother and more elegant
                for (k in 0 until beamsPerFixture) {
                    val off = ((k - half) / half).coerceIn(-1f, 1f)

                    val yaw = localYawBase + off * localSpread
                    val pitch = localPitchBase + off * 0.030f

                    val centerWeight = 1f - abs(off)
                    val beamIntensity =
                        headEnergy * (0.76f + 0.18f * centerWeight) * (0.88f + 0.12f * peakBurst01)

                    val localReach =
                        if (blindPunch && abs(off) < 0.14f) reach * 1.32f
                        else reach * (1.02f + 0.14f * centerWeight)

                    addBeam(
                        origin = o,
                        yaw = yaw,
                        pitch = pitch,
                        color = headColor,
                        baseIntensity = beamIntensity,
                        reach = localReach
                    )
                }

                // Fill rays between mains for smoother laser sheets
                for (g in 0 until (beamsPerFixture - 1)) {
                    val t0 = g / half - 1f
                    val t1 = (g + 1f) / half - 1f

                    for (m in 1..fillBeamsPerGap) {
                        val u = m / (fillBeamsPerGap + 1f)
                        val off = lerp(t0, t1, u).coerceIn(-1f, 1f)

                        val yaw = localYawBase + off * localSpread
                        val pitch = localPitchBase + off * 0.050f

                        val fillIntensity =
                            headEnergy * (0.14f + 0.10f * peakBurst01) * (1f - 0.18f * abs(off))

                        addBeam(
                            origin = o,
                            yaw = yaw,
                            pitch = pitch,
                            color = headColor,
                            baseIntensity = fillIntensity,
                            reach = reach * 1.08f
                        )
                    }
                }

                // Viewer-directed hit on strong peaks
                if (blindPunch) {
                    addBeam(
                        origin = o,
                        yaw = localYawBase,
                        pitch = localPitchBase - 0.008f,
                        color = headColor,
                        baseIntensity = 1.08f,
                        reach = reach * 1.40f
                    )

                    // Broader glass-hit / viewer hit
                    val viewerA =
                        (0.08f + 0.16f * peakBurst01) * (0.42f + 0.58f * dimmer01) * alpha
                    val viewerCenter = Offset(
                        lerp(o.x, inner.center.x, 0.20f),
                        lerp(o.y, inner.center.y, 0.34f)
                    )

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                headColor.copy(alpha = viewerA),
                                headColor.copy(alpha = viewerA * 0.55f),
                                headColor.copy(alpha = 0f)
                            ),
                            center = viewerCenter,
                            radius = size.minDimension * 0.085f
                        ),
                        radius = size.minDimension * 0.060f,
                        center = viewerCenter
                    )
                }
            }

            // Global center haze hit on strong moments
            if (blindPunch) {
                val fogColor = if (lvl > 0.78f) duo.c else duo.a
                val fogA = (0.14f + 0.22f * peakBurst01) * (0.42f + 0.58f * dimmer01) * alpha

                drawRoundRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            fogColor.copy(alpha = fogA),
                            fogColor.copy(alpha = fogA * 0.58f),
                            fogColor.copy(alpha = 0f)
                        ),
                        center = Offset(inner.center.x, inner.center.y),
                        radius = max(inner.width, inner.height) * 1.04f
                    ),
                    topLeft = Offset(inner.left, inner.top),
                    size = Size(inner.width, inner.height),
                    cornerRadius = CornerRadius(cr, cr)
                )
            }
        }

        PanelLaserRig.FAN -> {
            val origin = center
            val spread = lerp(0.75f, 1.70f, bass)
            val baseYaw = rigYaw * 1.25f
            val basePitch = rigPitch * 0.70f

            for (i in 0 until fanCount) {
                val u = i / (fanCount - 1f)
                val yaw = baseYaw + (u - 0.5f) * spread
                val pitch = basePitch + sin(timeSec * 0.22f) * 0.22f
                val col = if (i % 3 == 0) duo.a else if (i % 3 == 1) duo.b else duo.c
                val intensity = lerp(0.18f, 0.82f, lvl) * lerp(0.75f, 1.0f, bass)
                addBeam(origin, yaw, pitch, col, intensity, reach)
            }

            if (lvl > 0.55f && strobeOn(phase = 0.18f, duty = 0.20f)) {
                addBeam(origin, baseYaw, basePitch - 0.05f, duo.a, 1.0f, reach * 1.15f)
            }
        }

        PanelLaserRig.LIGHT_FLOOR -> {
            drawLightFloor(
                inner = inner,
                color = washColor,
                lvl = lvl,
                timeSec = timeSec
            )
        }

        PanelLaserRig.SCANNER -> {
            val origin = center
            val baseYaw = rigYaw * 0.85f
            val basePitch = rigPitch * 0.55f

            val ring = 54
            val cone = lerp(0.25f, 0.75f, bass)
            val swirl = timeSec * lerp(0.35f, 0.95f, mid)

            for (i in 0 until ring) {
                val a = (i / ring.toFloat()) * (2f * PI.toFloat()) + swirl
                val yaw = baseYaw + cos(a) * cone
                val pitch = basePitch + sin(a) * cone * 0.75f
                val col = if (i % 2 == 0) duo.a else duo.c
                val intensity = lerp(0.16f, 0.78f, lvl)
                addBeam(origin, yaw, pitch, col, intensity, reach)
            }

            val masterOn = lvl > 0.50f && strobeOn(phase = 0.12f, duty = 0.14f)
            if (masterOn) addBeam(origin, baseYaw, basePitch, duo.b, 1.0f, reach * 1.30f)
        }

        PanelLaserRig.FULL_SHOW -> {
            run {
                val origin = center
                val spread = lerp(0.85f, 1.90f, bass)
                val baseYaw = rigYaw * 1.10f
                val basePitch = rigPitch * 0.62f

                val n = 64
                for (i in 0 until n) {
                    val u = i / (n - 1f)
                    val yaw = baseYaw + (u - 0.5f) * spread
                    val pitch = basePitch + sin(timeSec * 0.20f) * 0.20f
                    val col = if (i % 3 == 0) duo.a else if (i % 3 == 1) duo.b else duo.c
                    val intensity = lerp(0.12f, 0.70f, lvl)
                    addBeam(origin, yaw, pitch, col, intensity, reach)
                }
            }

            if (lvl > 0.55f && strobeOn(phase = 0.05f, duty = 0.24f)) {
                val origins = listOf(lineOrigins.first(), lineOrigins.last())
                origins.forEachIndexed { i, o ->
                    val sign = if (i == 0) 1f else -1f
                    val yaw = rigYaw + sign * 0.95f
                    val pitch = rigPitch * 0.55f - 0.10f
                    addBeam(o, yaw, pitch, if (i == 0) duo.a else duo.b, 0.95f, reach * 1.10f)
                }
            }

            run {
                val origin = center
                val ring = 32
                val cone = lerp(0.20f, 0.55f, bass)
                val swirl = timeSec * 0.75f
                for (i in 0 until ring) {
                    val a = (i / ring.toFloat()) * (2f * PI.toFloat()) + swirl
                    val yaw = rigYaw * 0.70f + cos(a) * cone
                    val pitch = rigPitch * 0.45f + sin(a) * cone * 0.70f
                    addBeam(origin, yaw, pitch, duo.c, lerp(0.10f, 0.55f, lvl), reach)
                }
            }

            val key = lvl > 0.62f && strobeOn(phase = 0.18f, duty = 0.18f)
            if (key) {
                addBeam(
                    center,
                    rigYaw * 0.35f,
                    rigPitch * 0.20f - 0.10f,
                    duo.b,
                    1.0f,
                    reach * 1.35f
                )
            }
        }
    }

    // --- Render beams across the FULL DISPLAY (not clipped to the window) ---
    // (Your rule: sources in the window, beams can use the entire screen)
    val pad = size.minDimension * 0.08f
    clipRect(-pad, -pad, size.width + pad, size.height + pad) {
        beams.forEach { b ->
            val a = (b.intensity01 * alpha).coerceIn(0f, 1f)

            // OLED beam style: thin core + subtle halo
            val coreW = (0.95f + 0.85f * b.depth01) * (0.70f + 0.30f * b.intensity01)
            val haloW1 = coreW * 2.4f
            val haloW2 = coreW * 1.55f

            drawLine(
                color = b.color.copy(alpha = (a * 0.10f).coerceIn(0f, 0.14f)),
                start = b.origin,
                end = b.end,
                strokeWidth = haloW1,
                cap = StrokeCap.Round
            )
            drawLine(
                color = b.color.copy(alpha = (a * 0.30f).coerceIn(0f, 0.34f)),
                start = b.origin,
                end = b.end,
                strokeWidth = haloW2,
                cap = StrokeCap.Round
            )
            drawLine(
                color = b.color.copy(alpha = a),
                start = b.origin,
                end = b.end,
                strokeWidth = coreW,
                cap = StrokeCap.Round
            )

            val haze = (0.10f + 0.40f * b.intensity01) * (0.35f + 0.65f * dimmer01)
            if (haze > 0.06f) {
                val n = (10 + (18 * b.intensity01).roundToInt()).coerceIn(10, 28)
                for (i in 0 until n) {
                    val t = (i + 1f) / (n + 1f)
                    val px = lerp(b.origin.x, b.end.x, t)
                    val py = lerp(b.origin.y, b.end.y, t)

                    val d = lerp(0.45f, 1.0f, b.depth01)
                    val jitter = (sin(timeSec * 0.65f + t * 8.0f) * 0.5f + 0.5f) * 0.65f + 0.35f
                    val rr = (0.55f + 0.95f * d) * (0.85f + 0.15f * jitter)
                    val aa = (a * haze * (0.10f + 0.22f * t) * d).coerceIn(0f, 0.14f)

                    drawCircle(
                        color = b.color.copy(alpha = aa),
                        radius = rr,
                        center = Offset(px, py)
                    )
                }
            }
        }
    }

    // --- Draw fixture "heads" inside the window (only when active) ---
    // Heads should not stay lit constantly: show lens glow only when this fixture actually emits.
    fun drawHead(o: Offset, col: Color, strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0.02f) return
        val r = (size.minDimension * 0.0045f).coerceIn(2.2f, 4.8f)
        val glow = (0.10f + 0.55f * s) * alpha
        drawCircle(
            col.copy(alpha = (glow * 0.25f).coerceIn(0f, 0.22f)),
            radius = r * 3.2f,
            center = o
        )
        drawCircle(
            col.copy(alpha = (glow * 0.55f).coerceIn(0f, 0.55f)),
            radius = r * 1.6f,
            center = o
        )
        drawCircle(col.copy(alpha = (glow).coerceIn(0f, 0.90f)), radius = r, center = o)
    }

    // Aggregate per-origin activity (cheap approximation)
    val byOrigin = HashMap<Long, Float>()
    fun keyOf(o: Offset): Long =
        ((o.x.toInt().toLong() and 0xFFFF) shl 32) or (o.y.toInt().toLong() and 0xFFFF)
    beams.forEach { b ->
        val k = keyOf(b.origin)
        val cur = byOrigin[k] ?: 0f
        byOrigin[k] = max(cur, b.intensity01)
    }

    // Draw heads for the current rig origins only
    when (rig) {
        PanelLaserRig.DUAL_FAN -> {
            lineOrigins.forEachIndexed { idx, o ->
                val col = if (idx % 2 == 0) duo.a else duo.b
                val s = byOrigin[keyOf(o)] ?: 0f
                drawHead(o, col, s)
            }
        }

        PanelLaserRig.FAN, PanelLaserRig.SCANNER, PanelLaserRig.FULL_SHOW -> {
            val s = byOrigin[keyOf(center)] ?: 0f
            drawHead(center, duo.a, s)
        }

        PanelLaserRig.LIGHT_FLOOR -> {
            // no fixture head for floor mode
        }
    }

    // Optional: panel flash strobe (your idea), kept subtle and rare.
    val windowFlash = (lvl > 0.60f) && strobeOn(phase = 0.0f, duty = 0.08f)
    if (windowFlash && !blackout) {
        drawRoundRect(
            color = Color.White.copy(alpha = 0.18f * alpha),
            topLeft = Offset(inner.left, inner.top),
            size = Size(inner.width, inner.height),
            cornerRadius = CornerRadius(cr)
        )
    }
}

private fun laserPickTrio(
    palette: PanelLaserPalette,
    t: Float,
    bass: Float,
    mid: Float,
    high: Float
): List<Color> {
    // Compatibility helper (presets are handled elsewhere).
    val green = Color(0xFF00FF66)
    val red = Color(0xFFFF2A2A)
    val cyan = Color(0xFF00E5FF)
    val magenta = Color(0xFFFF3ED6)
    return when (palette) {
        PanelLaserPalette.GREEN -> listOf(green, cyan, magenta)
        PanelLaserPalette.CYAN -> listOf(cyan, green, magenta)
        PanelLaserPalette.MAGENTA -> listOf(magenta, cyan, red)
        PanelLaserPalette.RED -> listOf(red, green, cyan)
        PanelLaserPalette.WHITE -> listOf(Color.White, cyan, magenta)
    }
}

private fun DrawScope.drawLightFloor(
    inner: Rect,
    color: Color,
    lvl: Float,
    timeSec: Float
) {
    val pulse = 0.56f + 0.44f * lvl

    // Tunnel center
    val cx = inner.center.x
    val cy = inner.top + inner.height * 0.46f

    // Subtiele centrale haze
    val fogA = 0.035f + 0.08f * lvl
    drawRoundRect(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = fogA * 0.85f),
                color.copy(alpha = fogA * 0.35f),
                color.copy(alpha = 0f)
            ),
            center = Offset(cx, cy),
            radius = inner.width * 0.70f
        ),
        topLeft = Offset(inner.left, inner.top),
        size = Size(inner.width, inner.height),
        cornerRadius = CornerRadius(10f, 10f)
    )

    // ===== TUNNEL RINGS =====
    // Nieuwe kleurimpuls loopt van buiten naar binnen.
    val ringCount = 12
    val travelSpeed = 0.95f + 1.10f * lvl
    val bassAccent = when {
        color.red > color.green && color.red > color.blue -> Color(0xFFFFB38A)     // red -> warm amber/peach
        color.green > color.red && color.green > color.blue -> Color(0xFF8AFFF0)   // green -> mint/cyan
        color.blue > color.red && color.blue > color.green -> Color(0xFFE6F2FF)    // blue/cyan -> icy white-blue
        else -> Color(0xFFFFB3FF)                                                   // magenta/white -> soft pink/violet
    }

    val bassPulse = smooth01((lvl - 0.24f) / 0.28f)
    for (i in 0 until ringCount) {
        val u = i / (ringCount - 1f).toFloat()
        val p = u * u

        val w = lerp(inner.width * 0.94f, inner.width * 0.14f, p)
        val h = lerp(inner.height * 0.80f, inner.height * 0.16f, p)

        val yDrift = sin(timeSec * 0.9f + u * 2.8f) * inner.height * 0.010f

        val left = cx - w * 0.5f
        val top = cy - h * 0.5f + yDrift
        val right = cx + w * 0.5f
        val bottom = cy + h * 0.5f + yDrift

        // Golf komt van buitenste ring naar binnenste ring
        val wave = (sin((timeSec * travelSpeed - u * 1.55f) * (2f * PI.toFloat())) * 0.5f + 0.5f)
        val musicBoost = (0.18f + 0.82f * lvl)
        val ringBoost = (wave * musicBoost).coerceIn(0f, 1f)

        // Geen regenboog: gewoon basiskleur + lichtere toon die naar binnen reist
        val inwardBassWave =
            (sin((timeSec * (0.70f + bassPulse * 1.15f) - u * 1.45f) * (2f * PI.toFloat())) * 0.5f + 0.5f)

        val bassTravel = (inwardBassWave * bassPulse).coerceIn(10f, 26f)

        val ringBaseColor = lerpColor(color, Color.White, 0.10f + 0.26f * ringBoost)
        val ringColor = lerpColor(ringBaseColor, bassAccent, 0.18f + 0.46f * bassTravel)

        val alpha =
            ((0.07f + 0.26f * pulse) * (0.55f + 0.45f * ringBoost) * (1f - 0.14f * u))
                .coerceIn(0f, 1f)

        val stroke =
            (lerp(3.0f, 1.0f, p) * (0.82f + 0.34f * ringBoost))
                .coerceIn(0.8f, 4.2f)

        drawRoundRect(
            color = ringColor.copy(alpha = alpha),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(12f, 12f),
            style = Stroke(width = stroke)
        )
    }

    // ===== ZIJLIJNEN / RAILS =====
    val railCount = 8
    val targetY = cy
    val targetLeft = cx - inner.width * 0.06f
    val targetRight = cx + inner.width * 0.06f

    for (i in 0 until railCount) {
        val t = i / (railCount - 1f).toFloat()

        val leftStart = Offset(
            x = lerp(inner.left + inner.width * 0.05f, inner.left + inner.width * 0.28f, t),
            y = inner.bottom - inner.height * (0.05f + 0.08f * t)
        )
        val rightStart = Offset(
            x = lerp(inner.right - inner.width * 0.05f, inner.right - inner.width * 0.28f, t),
            y = inner.bottom - inner.height * (0.05f + 0.08f * t)
        )

        val railWave = (sin((timeSec * 2.1f - t * 1.2f) * PI.toFloat()) * 0.5f + 0.5f)
        val railBoost = (0.60f + 0.40f * railWave) * (0.65f + 0.35f * lvl)
        val railColor = lerpColor(color, Color.White, 0.04f + 0.10f * railWave)

        drawLine(
            color = railColor.copy(alpha = (0.08f + 0.18f * railBoost).coerceIn(0f, 1f)),
            start = leftStart,
            end = Offset(targetLeft, targetY),
            strokeWidth = lerp(2.4f, 1.0f, t)
        )

        drawLine(
            color = railColor.copy(alpha = (0.08f + 0.18f * railBoost).coerceIn(0f, 1f)),
            start = rightStart,
            end = Offset(targetRight, targetY),
            strokeWidth = lerp(2.4f, 1.0f, t)
        )
    }

    // ===== DIEPE CORE LINE =====
    // Cirkel vervangen door een "computerstem"-streepje
    val coreWave = (sin(timeSec * 5.2f) * 0.5f + 0.5f)
    val coreWidth = lerp(
        inner.width * 0.035f,
        inner.width * 0.11f,
        (0.30f + 0.70f * lvl * coreWave).coerceIn(0f, 1f)
    )
    val coreY = cy + sin(timeSec * 2.4f) * inner.height * 0.004f

    val coreColor = lerpColor(color, Color.White, 0.18f + 0.24f * lvl)
    val coreAlpha = (0.24f + 0.42f * lvl).coerceIn(0f, 1f)

    // glow
    drawLine(
        color = coreColor.copy(alpha = coreAlpha * 0.22f),
        start = Offset(cx - coreWidth * 0.5f, coreY),
        end = Offset(cx + coreWidth * 0.5f, coreY),
        strokeWidth = 7.0f
    )

    // main line
    drawLine(
        color = coreColor.copy(alpha = coreAlpha),
        start = Offset(cx - coreWidth * 0.5f, coreY),
        end = Offset(cx + coreWidth * 0.5f, coreY),
        strokeWidth = 2.2f
    )

    // micro center peak, heel subtiel
    val notchH = inner.height * (0.008f + 0.016f * lvl)
    drawLine(
        color = coreColor.copy(alpha = (coreAlpha * 0.78f).coerceIn(0f, 1f)),
        start = Offset(cx, coreY - notchH),
        end = Offset(cx, coreY + notchH),
        strokeWidth = 1.2f
    )
}

/* ----------------- Slide 4: Matrix draw (same) ----------------- */

private fun DrawScope.drawMatrixLedPro(
    device: DeviceRects,
    level: Float,
    isLive: Boolean,
    dimmer01: Float,
    timeSec: Float,
    look: PanelLook,
    alpha: Float,
    palette: MatrixPalette,
    mode: MatrixMode
) {
    if (alpha <= 0.001f) return

    val area = device.meterArea

    val panelW = area.width * 0.90f
    val panelH = area.height * 0.86f
    val panel = Rect(
        area.center.x - panelW * 0.5f,
        area.top + (area.height - panelH) * 0.12f,
        area.center.x + panelW * 0.5f,
        area.top + (area.height - panelH) * 0.12f + panelH
    )

    drawHiFiWindowThin(panel, look, alpha, frameBoost = 1.18f)

    val inset = (size.minDimension * 0.017f).coerceIn(10f, 16f)
    val inner =
        Rect(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

    drawRoundRect(
        color = Color(0xFF070707).copy(alpha = 0.94f * alpha),
        topLeft = Offset(inner.left, inner.top),
        size = Size(inner.width, inner.height),
        cornerRadius = CornerRadius((size.minDimension * 0.010f).coerceIn(6f, 10f))
    )

    val gridW = 80
    val gridH = 20

    val cellW = inner.width / gridW.toFloat()
    val cellH = inner.height / gridH.toFloat()
    val cell = min(cellW, cellH)

    val drawW = cell * gridW
    val drawH = cell * gridH
    val ox = inner.left + (inner.width - drawW) * 0.5f
    val oy = inner.top + (inner.height - drawH) * 0.5f

    val r = cell * 0.18f
    val core = cell * 0.72f
    val pad = (cell - core) * 0.5f

    val lvl = if (isLive) level.coerceIn(0f, 1f) else 0f

    fun pseudoBand(x: Int): Float {
        val tt = x / (gridW - 1f)
        val a = (0.55f + 0.45f * sin((tt * 2f * PI.toFloat()) + timeSec * 1.25f)).coerceIn(0f, 1f)
        val b = (0.55f + 0.45f * sin((tt * 5f * PI.toFloat()) + timeSec * 0.85f)).coerceIn(0f, 1f)
        val shape = (0.65f * a + 0.35f * b)
        val energy = (0.12f + 0.88f * lvl.pow(0.80f))
        return (shape * energy).coerceIn(0f, 1f)
    }

    fun colorFor(v: Float, y: Int): Color {
        val tY = (y / (gridH - 1f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return when (palette) {
            MatrixPalette.AMBER ->
                lerpColor(
                    Color(0xFF8A3B00),
                    Color(0xFFFFD06A),
                    (0.25f + 0.75f * v) * (0.55f + 0.45f * tY)
                )

            MatrixPalette.MAGENTA ->
                lerpColor(
                    Color(0xFF7A1BFF),
                    Color(0xFFFF4FD8),
                    (0.30f + 0.70f * v) * (0.55f + 0.45f * tY)
                )

            MatrixPalette.CYAN ->
                lerpColor(
                    Color(0xFF00A8FF),
                    Color(0xFF6CFFF5),
                    (0.30f + 0.70f * v) * (0.55f + 0.45f * tY)
                )

            MatrixPalette.CLASSIC -> {
                when {
                    tY < 0.60f -> lerpColor(
                        Color(0xFF19C463),
                        Color(0xFF9CFF7A),
                        (tY / 0.60f) * (0.35f + 0.65f * v)
                    )

                    tY < 0.85f -> lerpColor(
                        Color(0xFFFFD54F),
                        Color(0xFFFFB300),
                        ((tY - 0.60f) / 0.25f) * (0.35f + 0.65f * v)
                    )

                    else -> lerpColor(
                        Color(0xFFFF6A6A),
                        Color(0xFFFF2A2A),
                        ((tY - 0.85f) / 0.15f) * (0.35f + 0.65f * v)
                    )
                }
            }

            MatrixPalette.MONO -> Color.White
        }
    }


    val textWord = if (isLive) "LIVE" else "VDK"
    val textGrid: Array<FloatArray> = buildText5x7Grid(
        w = gridW,
        h = gridH,
        text = textWord,
        blink = isLive,
        timeSec = timeSec
    )

    fun intensityAt(x: Int, y: Int): Float {
        val a = pseudoBand(x)

        return when (mode) {
            MatrixMode.SPECTRUM -> {
                val height = (a * gridH).roundToInt().coerceIn(0, gridH)
                if (y < height) 1f else 0f
            }

            MatrixMode.MIRROR -> {
                val mid = (gridH - 1) * 0.5f
                val half = (a * (gridH * 0.5f)).coerceIn(0f, gridH.toFloat())
                val dy = abs(y - mid)
                if (dy <= half) 1f else 0f
            }

            MatrixMode.WAVE -> {
                val tt = x / (gridW - 1f)
                val wobble = sin(tt * 2f * PI.toFloat() * 2.2f + timeSec * 2.3f) * 0.35f
                val yy = ((gridH - 1) * 0.5f + (a - 0.5f + wobble) * (gridH * 0.42f))
                    .roundToInt()
                    .coerceIn(0, gridH - 1)
                when (y) {
                    yy -> 1f
                    yy - 1, yy + 1 -> 0.55f
                    else -> 0f
                }
            }

            MatrixMode.BLOCKS -> {
                val seed = sin((x * 17.13f + y * 9.77f) + timeSec * 1.05f) * 43758.5453f
                val f = fract(seed)
                val p = (0.06f + 0.24f * a).coerceIn(0.04f, 0.32f)
                if (f < p) (0.65f + 0.35f * a) else 0f
            }

            MatrixMode.PULSE -> {
                // Horizontal sinus trace with amplitude driven by overall level.
                val mid = (gridH - 1) * 0.5f
                val amp = (0.15f + 0.85f * lvl) * (gridH * 0.42f)
                val phase = (x / gridW.toFloat()) * (PI.toFloat() * 2f)
                val yy = (mid + sin(phase * 2.0f + timeSec * 3.0f) * amp).roundToInt()
                    .coerceIn(0, gridH - 1)
                when (y) {
                    yy -> 1f
                    yy - 1, yy + 1 -> 0.45f
                    else -> 0f
                }
            }

            MatrixMode.TEXT -> {
                if (y in 0 until gridH && x in 0 until gridW) textGrid[y][x] else 0f
            }

            MatrixMode.SPRING -> {
                // Spectrum with a subtle overshoot/bounce feeling.
                val bounce = sin(timeSec * 6.5f + x * 0.18f) * (0.10f + 0.22f * a)
                val height = ((a + bounce) * gridH).roundToInt().coerceIn(0, gridH)
                if (y < height) 1f else 0f
            }

            MatrixMode.RAIN -> {
                // Falling droplets per column with a short trail.
                val fx = fract(sin((x * 91.7f + timeSec * 1.35f)) * 43758.5453f)
                val drop = (fx * (gridH - 1)).roundToInt().coerceIn(0, gridH - 1)
                val trail = (2 + (lvl * 3f)).roundToInt().coerceIn(2, 5)
                val dy = drop - y
                when {
                    dy == 0 -> 1f
                    dy in 1..trail -> (0.75f - 0.12f * dy).coerceIn(0.12f, 0.75f)
                    else -> 0f
                }
            }

            MatrixMode.STAIR -> {
                // Quantized "step" spectrum with a travelling phase.
                val shift = ((timeSec * 10.0f).toInt() % gridW + gridW) % gridW
                val a2 = pseudoBand((x + shift) % gridW)
                val stepped = (a2 * 10f).roundToInt() / 10f
                val height = (stepped * gridH).roundToInt().coerceIn(0, gridH)
                if (y < height) 1f else 0f
            }

            MatrixMode.FIRE -> {
                // Bottom-up flames: brighter near bottom, noisy top edge.
                val noise =
                    sin((x * 0.55f + timeSec * 3.1f) + sin(timeSec * 1.7f + x * 0.2f)) * 0.5f + 0.5f
                val flame = (a * 0.65f + lvl * 0.35f) * (0.55f + 0.55f * noise)
                val height = (flame * (gridH * 0.95f)).roundToInt().coerceIn(0, gridH)
                if (y < height) {
                    val topEdge = (height - 1 - y).coerceAtLeast(0)
                    (0.70f + 0.30f * (1f - (topEdge / 6f).coerceIn(0f, 1f))).coerceIn(0.25f, 1f)
                } else 0f
            }

            MatrixMode.NEEDLE -> {
                // Oscilloscope-like needle trace around the center.
                val mid = (gridH - 1) * 0.5f
                val amp = (0.10f + 0.90f * lvl) * (gridH * 0.28f)
                val freq = 5.0f
                val phase = (x / (gridW - 1f).coerceAtLeast(1f)) * (PI.toFloat() * 2f) * freq
                val yy =
                    (mid + sin(phase + timeSec * 4.2f) * amp).roundToInt().coerceIn(0, gridH - 1)
                when (y) {
                    yy -> 1f
                    yy - 1, yy + 1 -> 0.55f
                    else -> 0f
                }
            }
        }
    }


    val breath = (0.86f + 0.14f * lvl) * (0.72f + 0.28f * dimmer01)

    for (yy in 0 until gridH) {
        for (xx in 0 until gridW) {
            val base = intensityAt(xx, yy)
            if (base <= 0.01f) continue

            val v = (base * breath).coerceIn(0f, 1f)
            val c = colorFor(v, yy)

            val x0 = ox + xx * cell + pad
            val y0 = oy + (gridH - 1 - yy) * cell + pad

            val glowSize = core * (1.25f + 0.30f * v)
            val gx = x0 - (glowSize - core) * 0.5f
            val gy = y0 - (glowSize - core) * 0.5f

            drawRoundRect(
                color = c.copy(alpha = ((0.08f + 0.22f * v) * alpha).coerceIn(0f, 0.42f)),
                topLeft = Offset(gx, gy),
                size = Size(glowSize, glowSize),
                cornerRadius = CornerRadius(r * 2.0f, r * 2.0f)
            )

            drawRoundRect(
                color = if (palette == MatrixPalette.MONO)
                    c.copy(alpha = ((0.20f + 0.78f * v) * alpha).coerceIn(0f, 0.98f))
                else
                    c.copy(alpha = ((0.18f + 0.80f * v) * alpha).coerceIn(0f, 0.98f)),
                topLeft = Offset(x0, y0),
                size = Size(core, core),
                cornerRadius = CornerRadius(r, r)
            )
        }
    }
}

private fun fract(x: Float): Float = x - floor(x)

// --- 5x7 text grid helper for MatrixMode.TEXT (kept tiny + deterministic) ---

private fun buildText5x7Grid(
    w: Int,
    h: Int,
    text: String,
    blink: Boolean,
    timeSec: Float
): Array<FloatArray> {
    val out = Array(h) { FloatArray(w) }

    // Optional blink when LIVE (subtle, not annoying)
    val blinkOn = if (!blink) true else ((timeSec * 1.6f).toInt() % 2 == 0)
    if (!blinkOn) return out

    val charW = 5
    val charH = 7
    val gap = 1

    val textUp = text.trim().uppercase()
    val totalW = textUp.length * charW + (textUp.length - 1) * gap
    val totalH = charH

    val x0 = ((w - totalW) / 2).coerceAtLeast(0)
    val y0 = ((h - totalH) / 2).coerceAtLeast(0)

    for ((ci, ch) in textUp.withIndex()) {
        val glyph = FONT_5X7[ch] ?: FONT_5X7['?']!!
        val gx0 = x0 + ci * (charW + gap)

        for (gy in 0 until charH) {
            val row = glyph[gy]
            for (gx in 0 until charW) {
                val bit = (row shr (charW - 1 - gx)) and 1
                if (bit == 0) continue

                val x = gx0 + gx
                val y = y0 + (charH - 1 - gy) // y=0 is bottom in our grid usage

                if (x in 0 until w && y in 0 until h) {
                    out[y][x] = 1f
                }
            }
        }
    }

    return out
}

private val FONT_5X7: Map<Char, IntArray> = mapOf(
    // Each int is a 5-bit row (MSB on the left), 7 rows per glyph.
    'L' to intArrayOf(0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b10000, 0b11111),
    'I' to intArrayOf(0b11111, 0b00100, 0b00100, 0b00100, 0b00100, 0b00100, 0b11111),
    'V' to intArrayOf(0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b01010, 0b00100),
    'E' to intArrayOf(0b11111, 0b10000, 0b10000, 0b11110, 0b10000, 0b10000, 0b11111),
    'D' to intArrayOf(0b11110, 0b10001, 0b10001, 0b10001, 0b10001, 0b10001, 0b11110),
    'K' to intArrayOf(0b10001, 0b10010, 0b10100, 0b11000, 0b10100, 0b10010, 0b10001),
    '?' to intArrayOf(0b11110, 0b00001, 0b00010, 0b00100, 0b00100, 0b00000, 0b00100),
)


/* ----------------- Stream bar (draw) ----------------- */

private fun DrawScope.drawStreamBarPro(
    device: DeviceRects,
    look: PanelLook,
    dimmer01: Float,
    isRunning: Boolean,
    latencyText: String,
    streamText: String,
    vuTop01: Float,
    vuBottom01: Float,
) {
    val w = size.width
    val minDim = min(size.width, size.height)

    val stripArea = device.stripArea
    val y = stripArea.top + stripArea.height * 0.55f
    val btnR = (minDim * 0.052f).coerceIn(26f, 46f)

    val strip = Rect(
        device.face.left + device.face.width * 0.10f,
        y - btnR * 1.20f,
        device.face.right - device.face.width * 0.10f,
        y + btnR * 1.20f
    )

    val gapY = btnR * 0.36f
    val barH = btnR * 1.75f
    val bar = Rect(
        strip.left,
        strip.top - gapY - barH,
        strip.right,
        strip.top - gapY
    )
    val cr = barH * 0.42f

    drawRoundRect(
        color = Color(0xFF070707).copy(alpha = 0.94f),
        topLeft = Offset(bar.left, bar.top),
        size = Size(bar.width, bar.height),
        cornerRadius = CornerRadius(cr, cr)
    )

    drawRoundRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                look.accentWarm().copy(alpha = 0.10f + 0.10f * dimmer01),
                look.accentDark().copy(alpha = 0.08f + 0.06f * dimmer01),
                Color.Transparent
            ),
            startX = bar.left,
            endX = bar.right
        ),
        topLeft = Offset(bar.left, bar.top),
        size = Size(bar.width, bar.height),
        cornerRadius = CornerRadius(cr, cr)
    )

    drawRoundRect(
        color = look.accentHi().copy(alpha = 0.055f + 0.055f * dimmer01),
        topLeft = Offset(bar.left + 1.6f, bar.top + 1.6f),
        size = Size(bar.width - 3.2f, bar.height - 3.2f),
        cornerRadius = CornerRadius((cr - 1.6f).coerceAtLeast(8f), (cr - 1.6f).coerceAtLeast(8f)),
        style = Stroke(width = 1.8f)
    )

    val iconR = btnR * 0.68f
    val iconGap = iconR * 0.55f
    val castCx = bar.right - iconR * 1.30f
    val stopCx = castCx - (iconR * 2f + iconGap)
    val playCx = stopCx - (iconR * 2f + iconGap)
    val cy = bar.center.y

    fun iconButtonShell(cx: Float, accent: Color) {
        val ringBrush = Brush.linearGradient(
            colors = listOf(
                accent.copy(alpha = 0.22f + 0.12f * dimmer01),
                look.accentWarm().copy(alpha = 0.14f + 0.08f * dimmer01),
                look.accentDark().copy(alpha = 0.32f)
            ),
            start = Offset(cx - iconR, cy - iconR),
            end = Offset(cx + iconR, cy + iconR)
        )

        drawCircle(
            Color.Black.copy(alpha = 0.30f),
            radius = iconR * 1.16f,
            center = Offset(cx + iconR * 0.06f, cy + iconR * 0.10f)
        )
        drawCircle(brush = ringBrush, radius = iconR * 1.08f, center = Offset(cx, cy))
        drawCircle(Color(0xFF101010).copy(alpha = 0.96f), radius = iconR, center = Offset(cx, cy))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.18f + 0.08f * dimmer01), Color.Transparent),
                center = Offset(cx - iconR * 0.18f, cy - iconR * 0.18f),
                radius = iconR * 1.05f
            ),
            radius = iconR * 0.96f,
            center = Offset(cx, cy)
        )
    }

    fun textIcon(cx: Float, accent: Color, symbol: String, symScale: Float = 1.0f) {
        iconButtonShell(cx, accent)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(
                235,
                (accent.red * 255).roundToInt(),
                (accent.green * 255).roundToInt(),
                (accent.blue * 255).roundToInt()
            )
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (iconR * 1.12f * symScale).coerceIn(18f, 34f)
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(symbol, cx, cy + p.textSize * 0.30f, p)
        }
    }

    fun drawCastIcon(cx: Float, accent: Color) {
        iconButtonShell(cx, accent)
        val strokeW = (iconR * 0.16f).coerceIn(2.2f, 4.2f)
        val baseY = cy + iconR * 0.34f
        val leftX = cx - iconR * 0.46f
        val rightX = cx + iconR * 0.38f
        val topY = cy - iconR * 0.30f

        drawRoundRect(
            color = accent.copy(alpha = 0.95f),
            topLeft = Offset(leftX, topY),
            size = Size(rightX - leftX, baseY - topY),
            cornerRadius = CornerRadius(iconR * 0.10f, iconR * 0.10f),
            style = Stroke(width = strokeW)
        )

        val dotC = Offset(cx - iconR * 0.34f, cy + iconR * 0.28f)
        drawCircle(color = accent.copy(alpha = 0.95f), radius = iconR * 0.10f, center = dotC)

        val arc1Rect = Rect(
            left = dotC.x - iconR * 0.22f,
            top = dotC.y - iconR * 0.22f,
            right = dotC.x + iconR * 0.22f,
            bottom = dotC.y + iconR * 0.22f
        )
        val arc2Rect = Rect(
            left = dotC.x - iconR * 0.44f,
            top = dotC.y - iconR * 0.44f,
            right = dotC.x + iconR * 0.44f,
            bottom = dotC.y + iconR * 0.44f
        )
        drawArc(
            color = accent.copy(alpha = 0.95f),
            startAngle = -135f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(arc1Rect.left, arc1Rect.top),
            size = Size(arc1Rect.width, arc1Rect.height),
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )
        drawArc(
            color = accent.copy(alpha = 0.95f),
            startAngle = -135f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(arc2Rect.left, arc2Rect.top),
            size = Size(arc2Rect.width, arc2Rect.height),
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )
    }

    val playAccent = if (isRunning) look.accentWarm() else Color(0xFFFF453A)
    val stopAccent = look.buttonText().copy(alpha = 0.90f)
    val castAccent = if (isRunning) look.accentHi() else look.buttonText().copy(alpha = 0.82f)

    textIcon(playCx, playAccent, "▶", 0.92f)
    textIcon(stopCx, stopAccent, "■", 0.84f)
    drawCastIcon(castCx, castAccent)

    val liveText = if (isRunning) "LIVE" else ""
    val latency = latencyText.ifBlank { "00:00" }
    val streamLabel = streamText.ifBlank { "--" }

    val leftPad = bar.left + bar.height * 0.34f
    val textRight = playCx - iconR * 1.95f

    val livePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val c = look.accentWarm()
        color = android.graphics.Color.argb(
            242,
            (c.red * 255).roundToInt(),
            (c.green * 255).roundToInt(),
            (c.blue * 255).roundToInt()
        )
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = (minDim * 0.0175f).coerceIn(12f, 18f)
        letterSpacing = 0.10f
    }
    val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val c = look.buttonText()
        color = android.graphics.Color.argb(
            190,
            (c.red * 255).roundToInt(),
            (c.green * 255).roundToInt(),
            (c.blue * 255).roundToInt()
        )
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = (minDim * 0.0165f).coerceIn(11f, 16f)
        letterSpacing = 0.04f
    }
    val streamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val c = look.accentHi()
        color = android.graphics.Color.argb(
            176,
            (c.red * 255).roundToInt(),
            (c.green * 255).roundToInt(),
            (c.blue * 255).roundToInt()
        )
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = (minDim * 0.0155f).coerceIn(10f, 14f)
        letterSpacing = 0.02f
    }

    val vuRight = textRight - bar.width * 0.020f
    val vuWidth = (bar.width * 0.22f).coerceIn(84f, 140f)
    val vuLeft = (vuRight - vuWidth).coerceAtLeast(leftPad + bar.width * 0.26f)
    val textMaxRight = (vuLeft - bar.width * 0.028f).coerceAtLeast(leftPad + 24f)

    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        var x = leftPad
        val baseY = cy + timerPaint.textSize * 0.34f

        if (liveText.isNotBlank()) {
            nc.drawText(liveText, x, baseY, livePaint)
            x += livePaint.measureText(liveText) + bar.width * 0.030f
        }

        nc.drawText(latency, x, baseY, timerPaint)
        x += timerPaint.measureText(latency) + bar.width * 0.034f

        val available = (textMaxRight - x).coerceAtLeast(0f)
        var shown = streamLabel
        while (shown.length > 4 && streamPaint.measureText(shown) > available) {
            shown = shown.dropLast(1)
        }
        if (shown != streamLabel && shown.length > 3) {
            shown = shown.dropLast(1) + "…"
        }
        nc.drawText(shown, x, baseY, streamPaint)
    }

    fun drawMiniLedRow(rowCenterY: Float, value01: Float) {
        val segments = 12
        val dotRadius = (barH * 0.082f).coerceIn(2.6f, 5.2f)
        val gap = (dotRadius * 1.05f).coerceIn(3.0f, 6.0f)
        val totalDotsW = segments * dotRadius * 2f + (segments - 1) * gap
        val startX = vuLeft + (vuWidth - totalDotsW) * 0.5f + dotRadius
        val litCount =
            (value01.coerceIn(0f, 1f).pow(0.72f) * segments).roundToInt().coerceIn(0, segments)

        fun dotColor(index: Int): Color = when {
            index <= 7 -> Color(0xFF30D158)
            index <= 10 -> Color(0xFFFFC857)
            else -> Color(0xFFFF453A)
        }

        for (i in 0 until segments) {
            val x = startX + i * (dotRadius * 2f + gap)
            val c = dotColor(i + 1)
            val isOn = i < litCount
            val a = if (isOn) 0.92f else 0.14f
            drawCircle(
                color = c.copy(alpha = a),
                radius = dotRadius,
                center = Offset(x, rowCenterY)
            )
            if (isOn) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(c.copy(alpha = 0.30f), Color.Transparent),
                        center = Offset(x, rowCenterY),
                        radius = dotRadius * 2.4f
                    ),
                    radius = dotRadius * 1.85f,
                    center = Offset(x, rowCenterY)
                )
            }
        }
    }

    val rowGap = (barH * 0.22f).coerceIn(8f, 14f)
    drawMiniLedRow(cy - rowGap * 0.5f, vuTop01)
    drawMiniLedRow(cy + rowGap * 0.5f, vuBottom01)
}

/* ----------------- Control strip (draw) ----------------- */

private fun DrawScope.drawControlStripPro(
    device: DeviceRects,
    look: PanelLook,
    dimmer01: Float,
    clarityLevel: Int,
    castConnected: Boolean,
    castVolume01: Float,
    volPulse01: Float,
    isMatrixMode: Boolean = false,
    matrixPaletteLabel: String = "AMBER",
    matrixModeLabel: String = "SPECTR",
    isLaserMode: Boolean = false,
    laserPaletteLabel: String = "NEON",
    laserRigLabel: String = "FAN",
) {
    val w = size.width
    val minDim = min(size.width, size.height)

    val stripArea = device.stripArea
    val y = stripArea.top + stripArea.height * 0.55f
    val spacing = w * 0.18f
    val xMid = w * 0.50f
    val xL = xMid - spacing
    val xR = xMid + spacing

    val btnR = (minDim * 0.052f).coerceIn(26f, 46f)

    val strip = Rect(
        device.face.left + device.face.width * 0.10f,
        y - btnR * 1.20f,
        device.face.right - device.face.width * 0.10f,
        y + btnR * 1.20f
    )

    drawRoundRect(
        color = Color(0xFF060606).copy(alpha = 0.90f),
        topLeft = Offset(strip.left, strip.top),
        size = Size(strip.width, strip.height),
        cornerRadius = CornerRadius(btnR, btnR)
    )

    val fadeA = (0.10f + 0.12f * dimmer01).coerceIn(0.10f, 0.22f)
    val goldFade = Brush.horizontalGradient(
        colors = listOf(
            look.accentWarm().copy(alpha = fadeA),
            look.accentDark().copy(alpha = fadeA * 0.65f),
            Color(0xFF000000).copy(alpha = 0.00f)
        ),
        startX = strip.left,
        endX = strip.right
    )
    drawRoundRect(
        brush = goldFade,
        topLeft = Offset(strip.left, strip.top),
        size = Size(strip.width, strip.height),
        cornerRadius = CornerRadius(btnR, btnR)
    )

    drawRoundRect(
        color = look.accentHi().copy(alpha = 0.065f + 0.055f * dimmer01),
        topLeft = Offset(strip.left + 1.8f, strip.top + 1.8f),
        size = Size(strip.width - 3.6f, strip.height - 3.6f),
        cornerRadius = CornerRadius(btnR - 2f, btnR - 2f),
        style = Stroke(width = 2f)
    )

    fun pressButton(cx: Float, label: String, subtitle: String?, intensity01: Float) {
        val ringA = (0.16f + 0.10f * intensity01) * (0.70f + 0.30f * dimmer01)
        val ringBrush = Brush.linearGradient(
            colors = listOf(
                look.accentHi().copy(alpha = ringA * 0.80f),
                look.accentWarm().copy(alpha = ringA),
                look.accentDark().copy(alpha = ringA * 1.15f)
            ),
            start = Offset(cx - btnR, y - btnR),
            end = Offset(cx + btnR, y + btnR)
        )

        drawCircle(
            color = Color.Black.copy(alpha = 0.34f),
            radius = btnR * 1.18f,
            center = Offset(cx + btnR * 0.06f, y + btnR * 0.10f)
        )
        drawCircle(brush = ringBrush, radius = btnR * 1.10f, center = Offset(cx, y))
        drawCircle(Color(0xFF0F0F0F).copy(alpha = 0.94f), radius = btnR, center = Offset(cx, y))

        val glowA = (0.05f + 0.12f * intensity01) * (0.45f + 0.55f * dimmer01)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    look.accentWarm().copy(alpha = glowA),
                    Color.Transparent
                ),
                center = Offset(cx - btnR * 0.18f, y - btnR * 0.18f),
                radius = btnR * 1.05f
            ),
            radius = btnR * 0.98f,
            center = Offset(cx, y)
        )

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val c = look.buttonText()
            color = android.graphics.Color.argb(
                125,
                (c.red * 255).roundToInt(),
                (c.green * 255).roundToInt(),
                (c.blue * 255).roundToInt()
            )
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (minDim * 0.016f).coerceIn(11f, 16f)
            letterSpacing = 0.06f
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            val c = look.accentHi()
            color = android.graphics.Color.argb(
                130,
                (c.red * 255).roundToInt(),
                (c.green * 255).roundToInt(),
                (c.blue * 255).roundToInt()
            )
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = (minDim * 0.012f).coerceIn(9f, 12.5f)
            letterSpacing = 0.10f
        }

        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawText(label, cx, y + titlePaint.textSize * 0.22f, titlePaint)
            if (!subtitle.isNullOrBlank()) {
                canvas.nativeCanvas.drawText(
                    subtitle,
                    cx,
                    y + titlePaint.textSize * 1.08f,
                    subPaint
                )
            }
        }
    }

    when {
        isMatrixMode -> {
            pressButton(xL, "COLOR", matrixPaletteLabel, 0.70f)
            pressButton(xR, "SHAPE", matrixModeLabel, 0.70f)
        }

        isLaserMode -> {
            pressButton(xL, "COLOR", laserPaletteLabel, 0.70f)
            pressButton(xR, "SHAPE", laserRigLabel, 0.70f)
        }

        else -> {
            val clarity01 = (clarityLevel.coerceIn(0, 4) / 4f)
            pressButton(xL, "CLARITY", null, clarity01)
            pressButton(xR, "LOOK", null, 0.55f)
        }
    }

    val volW = (minDim * 0.44f).coerceIn(290f, w * 0.70f)
    val volH = (minDim * 0.085f).coerceIn(44f, 72f)

    val vol = Rect(
        xMid - volW * 0.5f,
        y - volH * 0.5f,
        xMid + volW * 0.5f,
        y + volH * 0.5f
    )
    val cr = volH * 0.50f

    drawRoundRect(
        color = Color(0xFF0A0A0A).copy(alpha = 0.93f),
        topLeft = Offset(vol.left, vol.top),
        size = Size(vol.width, vol.height),
        cornerRadius = CornerRadius(cr, cr)
    )

    val rimW = max(1.4f, size.minDimension * 0.0012f)
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                look.accentHi().copy(alpha = 0.12f),
                look.accentWarm().copy(alpha = 0.18f),
                look.accentDark().copy(alpha = 0.38f)
            ),
            start = Offset(vol.left, vol.top),
            end = Offset(vol.right, vol.bottom)
        ),
        topLeft = Offset(vol.left, vol.top),
        size = Size(vol.width, vol.height),
        cornerRadius = CornerRadius(cr, cr),
        style = Stroke(width = rimW)
    )

    val inset = max(3.0f, size.minDimension * 0.0022f)
    val face = Rect(vol.left + inset, vol.top + inset, vol.right - inset, vol.bottom - inset)
    drawRoundRect(
        color = Color(0xFF101010).copy(alpha = 0.92f),
        topLeft = Offset(face.left, face.top),
        size = Size(face.width, face.height),
        cornerRadius = CornerRadius((cr - inset).coerceAtLeast(8f), (cr - inset).coerceAtLeast(8f))
    )

    drawRoundRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.06f + 0.05f * dimmer01),
                Color.Transparent
            ),
            startY = face.top,
            endY = face.top + face.height * 0.55f
        ),
        topLeft = Offset(face.left, face.top),
        size = Size(face.width, face.height),
        cornerRadius = CornerRadius((cr - inset).coerceAtLeast(8f))
    )

    if (castConnected) {
        val v = castVolume01.coerceIn(0f, 1f)
        val glowA = (0.06f + 0.16f * dimmer01 + 0.22f * volPulse01).coerceIn(0.05f, 0.42f)
        val fillW = face.width * v
        if (fillW > 1f) {
            clipRect(face.left, face.top, face.left + fillW, face.bottom) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            look.accentWarm().copy(alpha = glowA * 0.85f),
                            look.accentHi().copy(alpha = glowA),
                            look.accentWarm().copy(alpha = glowA * 0.65f)
                        ),
                        startX = face.left,
                        endX = face.right
                    ),
                    topLeft = Offset(face.left, face.top),
                    size = Size(face.width, face.height),
                    cornerRadius = CornerRadius((cr - inset).coerceAtLeast(8f))
                )
            }
        }
    }

    drawLine(
        color = look.accentHi().copy(alpha = 0.10f + 0.10f * dimmer01 + 0.12f * volPulse01),
        start = Offset(face.center.x, face.top + face.height * 0.18f),
        end = Offset(face.center.x, face.bottom - face.height * 0.18f),
        strokeWidth = max(1.6f, size.minDimension * 0.0013f),
        cap = StrokeCap.Round
    )

    val cheA = (0.14f + 0.10f * dimmer01 + 0.14f * volPulse01).coerceIn(0.10f, 0.40f)
    val cheCol = look.buttonText().copy(alpha = cheA)

    fun chevron(cx: Float, dir: Int) {
        val s = face.height * 0.22f
        val cy = face.center.y
        val x0 = cx - dir * s * 0.35f
        val x1 = cx + dir * s * 0.35f
        drawLine(
            cheCol,
            Offset(x0, cy - s * 0.55f),
            Offset(x1, cy),
            strokeWidth = max(1.3f, size.minDimension * 0.0011f),
            cap = StrokeCap.Round
        )
        drawLine(
            cheCol,
            Offset(x0, cy + s * 0.55f),
            Offset(x1, cy),
            strokeWidth = max(1.3f, size.minDimension * 0.0011f),
            cap = StrokeCap.Round
        )
    }

    chevron(face.left + face.width * 0.22f, dir = -1)
    chevron(face.right - face.width * 0.22f, dir = +1)

    val status = if (!castConnected) "VOL (CAST OFF)" else "VOL"
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        val c = look.buttonText()
        color = android.graphics.Color.argb(
            70,
            (c.red * 255).roundToInt(),
            (c.green * 255).roundToInt(),
            (c.blue * 255).roundToInt()
        )
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = (minDim * 0.0135f).coerceIn(10f, 13.5f)
        letterSpacing = 0.10f
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(status, vol.center.x, vol.bottom + p.textSize * 1.10f, p)
    }
}

/* ----------------- Utils ----------------- */

private fun Int.floorMod(m: Int): Int = ((this % m) + m) % m
private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
private fun smooth01(x: Float): Float {
    val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t)
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * u,
        green = a.green + (b.green - a.green) * u,
        blue = a.blue + (b.blue - a.blue) * u,
        alpha = a.alpha + (b.alpha - a.alpha) * u
    )
}

private val Rect.width: Float get() = right - left
private val Rect.height: Float get() = bottom - top
private val Rect.center: Offset get() = Offset((left + right) * 0.5f, (top + bottom) * 0.5f)
private fun Rect.roundRect(cr: Float): RoundRect = RoundRect(this, CornerRadius(cr, cr))