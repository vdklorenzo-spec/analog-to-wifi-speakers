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
) {
    val swipeThresholdPx = with(LocalDensity.current) { 52.dp.toPx() }

    // Slides:
    // 1 CLASSIC_VU
    // 2 LED_METERS
    // 3 WAVEFORM
    // 4 MATRIX
    // 5 LASERS
    val modes = remember {
        listOf(
            PanelMode.CLASSIC_VU,
            PanelMode.LED_METERS,
            PanelMode.WAVEFORM,
            PanelMode.MATRIX,
            PanelMode.LASERS
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

    // --- VU smoothing ---
    val vuDeadZone = 0.02f
    val rawLevel = if (isLive) level01.coerceIn(0f, 1f) else 0f
    val gatedLevel = if (rawLevel < vuDeadZone) 0f else rawLevel
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
        laserRigIndex
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
            val inner = Rect(outer.left + bezel, outer.top + bezel, outer.right - bezel, outer.bottom - bezel)

            val facePad = (minDim * 0.012f).coerceIn(6f, 12f)
            val face = Rect(inner.left + facePad, inner.top + facePad, inner.right - facePad, inner.bottom - facePad)

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
            val hitVol = inControlStrip && (pX in volRect.left..volRect.right) && (pY in volRect.top..volRect.bottom)

            val activeMode =
                if (modeT.value < 1f) targetMode else currentMode

            val isMatrixNow = (activeMode == PanelMode.MATRIX)
            val isLaserNow = (activeMode == PanelMode.LASERS)

            // ✅ Edge tap zones inside the strip (for quick prev/next)
            val edgeW = (btnR * 1.15f).coerceIn(28f, 70f)
            val hitPrevEdge = inControlStrip && pX <= (strip.left + edgeW)
            val hitNextEdge = inControlStrip && pX >= (strip.right - edgeW)

            // -------- 1) Handle LEFT button --------
            if (hitLeft && !hitVol) {
                down.consumeDownChange() // ✅ only consume when we handle the tap
                when {
                    isMatrixNow -> matrixPaletteIndex = (matrixPaletteIndex + 1) % MatrixPalette.entries.size
                    isLaserNow -> laserPaletteIndex = (laserPaletteIndex + 1) % PanelLaserPalette.entries.size
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

                val horizontalEnough = abs(totalDx) >= swipeThresholdPx && abs(totalDx) > abs(totalDy) * 1.25f
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
            PanelMode.CLASSIC_VU -> drawClassicVuCentered(device, smoothLevel, isLive, ballistics, dimmer01, timeSec, panelLook, alphaA)
            PanelMode.LED_METERS -> drawLedDotsPro(device, peakForUi, peakHold, isLive, dimmer01, timeSec, look = panelLook, alpha = alphaA)
            PanelMode.WAVEFORM -> drawWaveformPro(device, peakForUi, isLive, dimmer01, timeSec, look = panelLook, alpha = alphaA)
            PanelMode.MATRIX -> drawMatrixLedPro(device, peakForUi, isLive, dimmer01, timeSec, look = panelLook, alpha = alphaA, palette = palette, mode = mMode)
            PanelMode.LASERS -> drawLaserShowPro(device, peakForUi, isLive, dimmer01, timeSec, look = panelLook, alpha = alphaA, palette = lPal, rig = lRig)
        }
        when (targetMode) {
            PanelMode.CLASSIC_VU -> drawClassicVuCentered(device, smoothLevel, isLive, ballistics, dimmer01, timeSec, panelLook, alphaB)
            PanelMode.LED_METERS -> drawLedDotsPro(device, peakForUi, peakHold, isLive, dimmer01, timeSec, look = panelLook, alpha = alphaB)
            PanelMode.WAVEFORM -> drawWaveformPro(device, peakForUi, isLive, dimmer01, timeSec, look = panelLook, alpha = alphaB)
            PanelMode.MATRIX -> drawMatrixLedPro(device, peakForUi, isLive, dimmer01, timeSec, look = panelLook, alpha = alphaB, palette = palette, mode = mMode)
            PanelMode.LASERS -> drawLaserShowPro(device, peakForUi, isLive, dimmer01, timeSec, look = panelLook, alpha = alphaB, palette = lPal, rig = lRig)
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

private enum class PanelMode { CLASSIC_VU, LED_METERS, WAVEFORM, MATRIX, LASERS }

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
    val inner = Rect(outer.left + bezel, outer.top + bezel, outer.right - bezel, outer.bottom - bezel)

    val facePad = (size.minDimension * 0.012f).coerceIn(6f, 12f)
    val face = Rect(inner.left + facePad, inner.top + facePad, inner.right - facePad, inner.bottom - facePad)

    val topArea = Rect(face.left, face.top, face.right, face.top + face.height * 0.12f)
    val stripArea = Rect(face.left, face.top + face.height * 0.74f, face.right, face.bottom)
    val meterArea = Rect(face.left, face.top + face.height * 0.14f, face.right, face.top + face.height * 0.72f)

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

private fun DrawScope.drawFaceplateReflectionsAndHairline(face: Rect, dimmer01: Float, timeSec: Float, look: PanelLook) {
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
        color = look.accentHi().copy(alpha = (0.010f + 0.020f * dimmer01) * (0.55f + 0.45f * glintT)),
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
                    android.graphics.Color.argb((baseA * 0.65f * 255f).roundToInt(), (hi.red * 255).roundToInt(), (hi.green * 255).roundToInt(), (hi.blue * 255).roundToInt()),
                    android.graphics.Color.argb((baseA * 1.00f * 255f).roundToInt(), (warm.red * 255).roundToInt(), (warm.green * 255).roundToInt(), (warm.blue * 255).roundToInt()),
                    android.graphics.Color.argb((baseA * 0.75f * 255f).roundToInt(), (dark.red * 255).roundToInt(), (dark.green * 255).roundToInt(), (dark.blue * 255).roundToInt())
                ),
                floatArrayOf(0.0f, 0.55f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        canvas.nativeCanvas.drawText("vdk", r.center.x, r.center.y + textSize * 0.34f, paint)
    }
}

/* ----------------- Shared Window (thin) ----------------- */

private fun DrawScope.drawHiFiWindowThin(r: Rect, look: PanelLook, alpha: Float, frameBoost: Float = 1.0f) {
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

        val angMin = -65f
        val angMax = 65f
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
    val inner = Rect(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

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
    val leftBand = Rect(inner.left + bandPad, inner.top, inner.center.x - railsTotalW * 0.5f - bandPad, inner.bottom)
    val rightBand = Rect(inner.center.x + railsTotalW * 0.5f + bandPad, inner.top, inner.right - bandPad, inner.bottom)

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
        val slot = Rect(r.left + slotInset, r.top + slotInset, r.right - slotInset, r.bottom - slotInset)
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
        val bandTop = (slot.top + travel01 * (slot.height - bandH)).coerceIn(slot.top, slot.bottom - bandH)

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
    val inner = Rect(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

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

        val a = ((0.10f + 0.34f * (1f - dist)) * (0.35f + 0.65f * dimmer01) * alpha).coerceIn(0.06f, 0.55f)
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
    BLOCKS("BLOCKS");

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
    CROSS_RIG("CROSS"),
    SCANNER("SCAN"),
    FULL_SHOW("SHOW");

    companion object {
        fun fromIndex(i: Int): PanelLaserRig {
            val all = entries
            return all[(i % all.size + all.size) % all.size]
        }
    }
}

private enum class PanelLaserPalette(val label: String) {
    CLUB_NEON("NEON"),
    ACID_GREEN("ACID"),
    CYBER_VIOLET("VIOLET"),
    AMBER_LUXE("AMBER");

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

    val panelW = area.width * 0.90f
    val panelH = area.height * 0.86f
    val panel = Rect(
        area.center.x - panelW * 0.5f,
        area.top + (area.height - panelH) * 0.12f,
        area.center.x + panelW * 0.5f,
        area.top + (area.height - panelH) * 0.12f + panelH
    )

    // Frame + deep black window (unchanged vibe)
    drawHiFiWindowThin(panel, look, alpha, frameBoost = 1.18f)

    val inset = (size.minDimension * 0.017f).coerceIn(10f, 16f)
    val inner = Rect(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

    drawRoundRect(
        color = Color(0xFF000000).copy(alpha = 0.965f * alpha),
        topLeft = Offset(inner.left, inner.top),
        size = Size(inner.width, inner.height),
        cornerRadius = CornerRadius((size.minDimension * 0.010f).coerceIn(6f, 10f))
    )

    // Energy signals
    val lvl = if (isLive) level.coerceIn(0f, 1f) else 0f
    val bass = (lvl.pow(0.72f)).coerceIn(0f, 1f)                 // stronger low-end response
    val mid = (lvl.pow(0.92f)).coerceIn(0f, 1f)
    val high = (lvl.pow(1.12f)).coerceIn(0f, 1f)

    val trio = laserPickTrio(palette, timeSec, bass, mid, high)

    // V2: more dramatic geometry + occasional overshoot bursts
    val spread = lerp(1.35f, 3.15f, smooth01(bass))              // wider fan
    val wobble = lerp(0.16f, 0.78f, smooth01(mid))               // more swing
    val strobe = smooth01(high).pow(1.55f)

    // Burst gate on peaks (lets beams “escape” aggressively)
    val burstGate = smooth01((lvl - 0.68f) / 0.32f).pow(1.10f)   // 0..1
    val burstPulse = (0.55f + 0.45f * sin(timeSec * (12f + 24f * strobe))).coerceIn(0f, 1f)
    val burst = (burstGate * burstPulse).coerceIn(0f, 1f)

    fun toPx(n: Offset): Offset =
        Offset(inner.left + n.x * inner.width, inner.top + n.y * inner.height)

    // Origins (slightly animated for “living rig” feel)
    val main = toPx(Offset(0.50f + 0.02f * sin(timeSec * 0.9f), 0.92f))
    val left = toPx(Offset(0.23f + 0.02f * sin(timeSec * 1.1f + 1.7f), 0.92f))
    val right = toPx(Offset(0.77f + 0.02f * sin(timeSec * 1.0f + 3.1f), 0.92f))
    val scanOrigin = toPx(Offset(0.50f + 0.03f * sin(timeSec * 0.7f), 0.18f))

    val fanOrigins = when (rig) {
        PanelLaserRig.DUAL_FAN -> listOf(left, right)
        else -> listOf(main)
    }

    val baseFanCount = when (rig) {
        PanelLaserRig.FAN -> 30
        PanelLaserRig.DUAL_FAN -> 26
        PanelLaserRig.CROSS_RIG -> 28
        PanelLaserRig.SCANNER -> 18
        PanelLaserRig.FULL_SHOW -> 48
    }

    val crossCount = when (rig) {
        PanelLaserRig.CROSS_RIG -> 8
        PanelLaserRig.FULL_SHOW -> 16
        else -> 0
    }

    val scannerCount = when (rig) {
        PanelLaserRig.SCANNER -> 12
        PanelLaserRig.FULL_SHOW -> 18
        else -> 0
    }

    val beams = ArrayList<PanelLaserBeam>(baseFanCount + crossCount + scannerCount + 20)

    // --- FAN / DUAL FAN (V2: deeper + longer + more violent on peaks)
    fanOrigins.forEachIndexed { oi, origin ->
        val per = (baseFanCount / fanOrigins.size).coerceAtLeast(8)
        val phase = oi * 0.85f

        for (i in 0 until per) {
            val u = if (per == 1) 0.5f else i / (per - 1f)

            // Wider base angle
            val a = (-spread * 0.5f) + u * spread
            val baseAngle = (-PI.toFloat() / 2f) + a

            // More aggressive wobble (adds occasional whip)
            val whip = sin(timeSec * (2.1f + bass * 4.2f) + u * 6.6f + phase) * wobble
            val micro = sin(timeSec * (8.0f + 10.0f * strobe) + u * 13.0f + phase) * (0.035f + 0.07f * mid)
            val ang = baseAngle + whip + micro

            // Depth driver
            val depth = (0.18f + 0.82f * (0.5f + 0.5f * sin(timeSec * 0.62f + u * 5.0f + phase))).coerceIn(0f, 1f)

            // V2 length: can exceed the window hard on peaks
            val lenBase = inner.height * lerp(1.10f, 2.55f, depth)
            val lenBurst = inner.height * (0.0f + 2.4f * burst) * (0.7f + 0.3f * sin(timeSec * 7.5f + u * 4.0f))
            val len = (lenBase + lenBurst).coerceAtLeast(inner.height * 0.9f)

            val end = Offset(
                origin.x + cos(ang) * len,
                origin.y + sin(ang) * len
            )

            val color = trio[(i + oi) % trio.size]

            // Intensity: punchy on bass + depth shimmer + burst snap
            val baseI = (0.22f + 0.78f * lvl)
            val depthI = (0.60f + 0.40f * depth)
            val snap = (1.0f + 0.65f * burst).coerceAtMost(1.65f)
            val intensity = (baseI * depthI * snap).coerceIn(0f, 1f)

            beams += PanelLaserBeam(origin, end, depth, color, intensity)
        }
    }

    // --- CROSS RIG (V2: harder diagonal cuts + peak kicks)
    if (crossCount > 0) {
        val cA = trio.getOrElse(1) { Color(0xFFFF3ED6) }
        val cB = trio.getOrElse(2) { Color(0xFF8A5CFF) }

        for (i in 0 until crossCount) {
            val u = if (crossCount == 1) 0.5f else i / (crossCount - 1f)

            val driftX = 0.03f * sin(timeSec * 0.8f + u * 4.0f)
            val fromTop = toPx(Offset(lerp(0.10f, 0.90f, u) + driftX, lerp(0.10f, 0.30f, 1f - u)))
            val toMid = toPx(Offset(lerp(0.15f, 0.85f, 1f - u) - driftX, lerp(0.60f, 0.78f, u)))

            val depth = (0.22f + 0.78f * (0.5f + 0.5f * sin(timeSec * 1.05f + u * 5.3f))).coerceIn(0f, 1f)
            val flick = 0.70f + 0.30f * sin(timeSec * (7.5f + 16f * strobe) + u * 9.0f)
            val kick = 1.0f + 0.55f * burst

            val intensity = ((0.30f + 0.70f * high) * flick * kick).coerceIn(0f, 1f)

            // Slightly extend on peaks so they can “slash” outside
            val dx = (toMid.x - fromTop.x)
            val dy = (toMid.y - fromTop.y)
            val extend = 1.0f + 0.85f * burst
            val end = Offset(fromTop.x + dx * extend, fromTop.y + dy * extend)

            beams += PanelLaserBeam(fromTop, end, depth, if (i % 2 == 0) cA else cB, intensity)
        }
    }

    // --- SCANNER (V2: faster sweep + longer throws)
    if (scannerCount > 0) {
        val scanColor = trio.firstOrNull() ?: Color(0xFF00FF66)

        for (i in 0 until scannerCount) {
            val u = if (scannerCount == 1) 0.5f else i / (scannerCount - 1f)

            val sweep = sin(timeSec * (2.4f + bass * 5.6f) + u * 3.2f)
            val base = lerp(-1.35f, 1.35f, (sweep * 0.5f + 0.5f))
            val ang = (-PI.toFloat() / 2f) + base * lerp(0.55f, 1.25f, (0.25f + 0.75f * mid))

            val depth = (0.12f + 0.88f * (0.5f + 0.5f * sin(timeSec * 1.55f + u * 8.7f))).coerceIn(0f, 1f)

            val lenBase = inner.height * lerp(1.15f, 2.85f, depth)
            val lenBurst = inner.height * (0.0f + 2.8f * burst)
            val len = (lenBase + lenBurst).coerceAtLeast(inner.height * 1.0f)

            val end = Offset(
                scanOrigin.x + cos(ang) * len,
                scanOrigin.y + sin(ang) * len
            )

            val kick = 1.0f + 0.55f * burst
            val intensity = ((0.16f + 0.84f * mid) * (0.55f + 0.45f * depth) * kick).coerceIn(0f, 1f)

            beams += PanelLaserBeam(scanOrigin, end, depth, scanColor, intensity)
        }
    }

    // --- FULL SHOW extra burst beams (V2: occasional “escape” rockets)
    if (rig == PanelLaserRig.FULL_SHOW) {
        val extra = 16
        val pulse = (0.60f + 0.40f * sin(timeSec * (11f + 24f * strobe))).coerceIn(0f, 1f)

        for (i in 0 until extra) {
            val u = if (extra == 1) 0.5f else i / (extra - 1f)

            val origin = toPx(Offset(lerp(0.06f, 0.94f, u), 0.92f))

            val whip = sin(timeSec * 2.9f + u * 7.9f) * lerp(0.35f, 1.45f, bass)
            val ang = (-PI.toFloat() / 2f) + whip

            val depth = (0.16f + 0.84f * (0.5f + 0.5f * sin(timeSec * 1.25f + u * 6.9f))).coerceIn(0f, 1f)

            val lenBase = inner.height * lerp(1.10f, 2.90f, depth)
            val lenBurst = inner.height * (0.0f + 3.2f * burst)
            val len = (lenBase + lenBurst).coerceAtLeast(inner.height * 1.0f)

            val end = Offset(origin.x + cos(ang) * len, origin.y + sin(ang) * len)

            val col = trio[(i + 1) % trio.size]
            val intensity = ((0.24f + 0.76f * lvl) * pulse * (0.55f + 0.45f * depth) * (1f + 0.55f * burst)).coerceIn(0f, 1f)

            beams += PanelLaserBeam(origin, end, depth, col, intensity)
        }
    }

    // Clip region expanded so beams can overshoot outside inner window (this is the “escape” effect)
    val clipPadX = inner.width * lerp(0.55f, 1.05f, burst)
    val clipPadY = inner.height * lerp(0.55f, 1.15f, burst)

    clipRect(
        inner.left - clipPadX,
        inner.top - clipPadY,
        inner.right + clipPadX,
        inner.bottom + clipPadY
    ) {
        beams.forEach { b ->
            // V2 width + glow rises with depth and burst
            val wPx = lerp(0.95f, 3.20f, b.depth01) * (1.0f + 0.35f * burst)
            val a = (b.intensity01 * alpha).coerceIn(0f, 1f)

            // Fat glow base
            drawLine(
                color = b.color.copy(alpha = (a * lerp(0.16f, 0.28f, burst)).coerceIn(0f, 0.28f)),
                start = b.origin,
                end = b.end,
                strokeWidth = wPx * 3.8f,
                cap = StrokeCap.Round
            )

            // Mid glow
            drawLine(
                color = b.color.copy(alpha = (a * 0.55f).coerceIn(0f, 0.55f)),
                start = b.origin,
                end = b.end,
                strokeWidth = wPx * 1.9f,
                cap = StrokeCap.Round
            )

            // Core beam
            drawLine(
                color = b.color.copy(alpha = a),
                start = b.origin,
                end = b.end,
                strokeWidth = wPx,
                cap = StrokeCap.Round
            )
        }
    }
}
private fun laserPickTrio(
    palette: PanelLaserPalette,
    t: Float,
    bass: Float,
    mid: Float,
    high: Float
): List<Color> {
    val green = Color(0xFF00FF66)
    val cyan = Color(0xFF00E5FF)
    val magenta = Color(0xFFFF3ED6)
    val violet = Color(0xFF8A5CFF)
    val blue = Color(0xFF2D7DFF)
    val amber = Color(0xFFFFB000)
    val hotPink = Color(0xFFFF5A8A)

    val all = when (palette) {
        PanelLaserPalette.CLUB_NEON -> listOf(green, cyan, magenta, violet, blue, amber, hotPink)
        PanelLaserPalette.ACID_GREEN -> listOf(green, Color(0xFF00D95C), amber, Color(0xFF6BFF9B), cyan)
        PanelLaserPalette.CYBER_VIOLET -> listOf(cyan, blue, violet, magenta, Color(0xFF5BE7FF))
        PanelLaserPalette.AMBER_LUXE -> listOf(amber, Color(0xFFFFD36B), green, Color(0xFFB6FF6B), cyan)
    }

    val lead = when {
        high >= mid && high >= bass -> 2
        mid >= bass -> 1
        else -> 0
    }

    val rot = ((t * 0.25f).toInt() % all.size).coerceAtLeast(0)
    fun pick(i: Int) = all[(i + rot).floorMod(all.size)]

    return when (lead) {
        2 -> listOf(pick(2), pick(3), pick(4))
        1 -> listOf(pick(1), pick(2), pick(5.coerceAtMost(all.size - 1)))
        else -> listOf(pick(0), pick(5.coerceAtMost(all.size - 1)), pick(2))
    }
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
    val inner = Rect(panel.left + inset, panel.top + inset, panel.right - inset, panel.bottom - inset)

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
                lerpColor(Color(0xFF8A3B00), Color(0xFFFFD06A), (0.25f + 0.75f * v) * (0.55f + 0.45f * tY))
            MatrixPalette.MAGENTA ->
                lerpColor(Color(0xFF7A1BFF), Color(0xFFFF4FD8), (0.30f + 0.70f * v) * (0.55f + 0.45f * tY))
            MatrixPalette.CYAN ->
                lerpColor(Color(0xFF00A8FF), Color(0xFF6CFFF5), (0.30f + 0.70f * v) * (0.55f + 0.45f * tY))
            MatrixPalette.CLASSIC -> {
                when {
                    tY < 0.60f -> lerpColor(Color(0xFF19C463), Color(0xFF9CFF7A), (tY / 0.60f) * (0.35f + 0.65f * v))
                    tY < 0.85f -> lerpColor(Color(0xFFFFD54F), Color(0xFFFFB300), ((tY - 0.60f) / 0.25f) * (0.35f + 0.65f * v))
                    else -> lerpColor(Color(0xFFFF6A6A), Color(0xFFFF2A2A), ((tY - 0.85f) / 0.15f) * (0.35f + 0.65f * v))
                }
            }
            MatrixPalette.MONO -> Color.White
        }
    }

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
                canvas.nativeCanvas.drawText(subtitle, cx, y + titlePaint.textSize * 1.08f, subPaint)
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
        drawLine(cheCol, Offset(x0, cy - s * 0.55f), Offset(x1, cy), strokeWidth = max(1.3f, size.minDimension * 0.0011f), cap = StrokeCap.Round)
        drawLine(cheCol, Offset(x0, cy + s * 0.55f), Offset(x1, cy), strokeWidth = max(1.3f, size.minDimension * 0.0011f), cap = StrokeCap.Round)
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
private fun smooth01(x: Float): Float { val t = x.coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
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