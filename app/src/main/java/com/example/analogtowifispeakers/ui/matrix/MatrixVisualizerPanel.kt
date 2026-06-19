// FILE: app/src/main/java/com/example/analogtowifispeakers/ui/matrix/MatrixVisualizerPanel.kt
package com.example.analogtowifispeakers.ui.matrix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.analogtowifispeakers.ui.theme.PanelLook
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Matrix Visualizer (80x20) + minimal gold signature frame + bottom row controls:
 * [ COLOR ]  [ VOLUME SLIDER ]  [ SHAPE ]
 *
 * - No swipes, no pager, no gestures.
 * - Designed to be dropped into the original app as one module/package.
 */
@Composable
fun MatrixVisualizerPanel(
    modifier: Modifier = Modifier,
    // Visual data input
    bands01: List<Float>?,          // preferred: ~40 band magnitudes in 0..1 (low->high). Can be null.
    level01: Float,                 // fallback / overall level (0..1)
    isLive: Boolean,
    // Look / signature
    panelLook: PanelLook,
    // Bottom controls
    volume01: Float,
    onVolume01Changed: (Float) -> Unit,
    // State (kept outside if you want)
    paletteIndex: Int,
    onPaletteNext: () -> Unit,
    modeIndex: Int,
    onModeNext: () -> Unit,
    // Options
    showBottomControls: Boolean = true,
    gridW: Int = 80,
    gridH: Int = 20,
) {
    val mode = remember(modeIndex) { MatrixMode.fromIndex(modeIndex) }
    val palette = remember(paletteIndex) { MatrixPalette.fromIndex(paletteIndex) }
    val frame = remember(panelLook) { FrameSpecMapper.from(panelLook) }
    val chrome = remember(panelLook) { ChromeSpec.from(panelLook) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Visualizer area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            GoldSignatureFrame(
                frame = frame,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .aspectRatio(gridW.toFloat() / gridH.toFloat())
            ) {
                MatrixCanvas(
                    gridW = gridW,
                    gridH = gridH,
                    bands01 = bands01,
                    level01 = level01,
                    isLive = isLive,
                    mode = mode,
                    palette = palette
                )
            }
        }

        if (showBottomControls) {
            BottomControlsRow(
                accent = chrome.accent,
                volume01 = volume01,
                onVolume01Changed = onVolume01Changed,
                onPaletteNext = onPaletteNext,
                onModeNext = onModeNext,
                paletteLabel = palette.label,
                modeLabel = mode.label
            )
        }
    }
}

/* --------------------------- Bottom controls --------------------------- */

@Composable
private fun BottomControlsRow(
    accent: Color,
    volume01: Float,
    onVolume01Changed: (Float) -> Unit,
    onPaletteNext: () -> Unit,
    onModeNext: () -> Unit,
    paletteLabel: String,
    modeLabel: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // COLOR (left)
        PillButton(
            labelTop = "COLOR",
            labelBottom = paletteLabel,
            accent = accent,
            onClick = onPaletteNext,
            modifier = Modifier.width(110.dp)
        )

        Spacer(Modifier.width(14.dp))

        // VOLUME (center)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
                .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = volume01.coerceIn(0f, 1f),
                onValueChange = { onVolume01Changed(it.coerceIn(0f, 1f)) },
                colors = SliderDefaults.colors(
                    thumbColor = accent.copy(alpha = 0.92f),
                    activeTrackColor = accent.copy(alpha = 0.70f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                )
            )
        }

        Spacer(Modifier.width(14.dp))

        // SHAPE (right)
        PillButton(
            labelTop = "SHAPE",
            labelBottom = modeLabel,
            accent = accent,
            onClick = onModeNext,
            modifier = Modifier.width(110.dp)
        )
    }
}

@Composable
private fun PillButton(
    labelTop: String,
    labelBottom: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(56.dp)
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = labelTop,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    letterSpacing = 1.4.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = labelBottom,
                    color = accent.copy(alpha = 0.92f),
                    fontSize = 12.sp,
                    letterSpacing = 0.6.sp
                )
            }
        }
    }
}

/* --------------------------- Gold signature frame --------------------------- */

private data class FrameSpec(
    val outer: Brush,
    val innerStroke: Color,
    val innerGlow: Color,
    val cornerDp: Float,
    val strokeDp: Float,
    val insetDp: Float,
)

@Composable
private fun GoldSignatureFrame(
    frame: FrameSpec,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        // Outer bezel
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(frame.outer, RoundedCornerShape(frame.cornerDp.dp))
        )

        // Inner black recess
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(frame.insetDp.dp)
                .background(Color.Black, RoundedCornerShape((frame.cornerDp - 2f).dp))
        ) {
            // Inner glow + stroke
            Canvas(modifier = Modifier.matchParentSize()) {
                val cr = CornerRadius(
                    (frame.cornerDp - 2f).dp.toPx(),
                    (frame.cornerDp - 2f).dp.toPx()
                )
                drawRoundRect(
                    color = frame.innerGlow,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = cr
                )
                drawRoundRect(
                    color = frame.innerStroke,
                    topLeft = Offset.Zero,
                    size = Size(size.width, size.height),
                    cornerRadius = cr,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = frame.strokeDp.dp.toPx())
                )
            }

            Box(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

private object FrameSpecMapper {
    fun from(panelLook: PanelLook): FrameSpec {
        return when (panelLook) {
            PanelLook.GOLD -> gold()
            else -> neutral()
        }
    }

    private fun gold(): FrameSpec = FrameSpec(
        outer = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF2A1E10),
                Color(0xFF9A7A3A),
                Color(0xFFD7BA6A),
                Color(0xFF8A6A33),
                Color(0xFF241A0E),
            )
        ),
        innerStroke = Color(0xFFFFE7A3).copy(alpha = 0.22f),
        innerGlow = Color(0xFFFFD57A).copy(alpha = 0.06f),
        cornerDp = 18f,
        strokeDp = 1.25f,
        insetDp = 7f
    )

    private fun neutral(): FrameSpec = FrameSpec(
        outer = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF101010),
                Color(0xFF303030),
                Color(0xFF1A1A1A),
                Color(0xFF0C0C0C)
            )
        ),
        innerStroke = Color.White.copy(alpha = 0.14f),
        innerGlow = Color.White.copy(alpha = 0.05f),
        cornerDp = 18f,
        strokeDp = 1.2f,
        insetDp = 7f
    )
}

/* --------------------------- Matrix renderer --------------------------- */

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
        private val all = entries
        fun fromIndex(i: Int): MatrixMode = all[(i % all.size + all.size) % all.size]
    }
}

private enum class MatrixPalette(val label: String) {
    AMBER("AMBER"),
    MAGENTA("MAGENTA"),
    CYAN("CYAN"),
    CLASSIC("CLASSIC"),
    MONO("MONO");

    companion object {
        private val all = entries
        fun fromIndex(i: Int): MatrixPalette = all[(i % all.size + all.size) % all.size]
    }
}

private data class ChromeSpec(val accent: Color) {
    companion object {
        fun from(panelLook: PanelLook): ChromeSpec {
            return when (panelLook) {
                PanelLook.GOLD -> ChromeSpec(accent = Color(0xFFD7BA6A))
                else -> ChromeSpec(accent = Color(0xFFBFEFFF))
            }
        }
    }
}

@Composable
private fun MatrixCanvas(
    gridW: Int,
    gridH: Int,
    bands01: List<Float>?,
    level01: Float,
    isLive: Boolean,
    mode: MatrixMode,
    palette: MatrixPalette,
) {
    val timeSec = rememberTimeSec()

    Canvas(modifier = Modifier.fillMaxSize()) {
        val intens = buildIntensityGrid(
            w = gridW,
            h = gridH,
            mode = mode,
            palette = palette,
            timeSec = timeSec,
            bands01 = bands01,
            level01 = level01,
            isLive = isLive
        )
        drawLedMatrix(
            w = gridW,
            h = gridH,
            intens = intens,
            palette = palette
        )
    }
}

@Composable
private fun rememberTimeSec(): Float {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (true) {
            kotlinx.coroutines.delay(16)
            val now = System.nanoTime()
            t = (now - start) / 1_000_000_000f
        }
    }
    return t
}

private fun buildIntensityGrid(
    w: Int,
    h: Int,
    mode: MatrixMode,
    palette: MatrixPalette,
    timeSec: Float,
    bands01: List<Float>?,
    level01: Float,
    isLive: Boolean
): Array<FloatArray> {
    val out = Array(h) { FloatArray(w) { 0f } }

    fun bandAt(x: Int): Float {
        val b = bands01
        if (b.isNullOrEmpty()) return level01.coerceIn(0f, 1f)
        val idx = ((x.toFloat() / (w - 1).coerceAtLeast(1)) * (b.size - 1)).roundToInt()
        return b[idx.coerceIn(0, b.size - 1)].coerceIn(0f, 1f)
    }

    when (mode) {
        MatrixMode.SPECTRUM -> {
            for (x in 0 until w) {
                val a = bandAt(x)
                val height = (a * h).roundToInt().coerceIn(0, h)
                for (y in 0 until height) out[y][x] = 1f
                if (height in 1 until h) out[height - 1][x] = 1f
            }
        }

        MatrixMode.MIRROR -> {
            val mid = (h - 1) / 2f
            for (x in 0 until w) {
                val a = bandAt(x)
                val half = (a * (h / 2f)).roundToInt().coerceIn(0, h)
                for (i in 0 until half) {
                    val yUp = (mid + i).roundToInt().coerceIn(0, h - 1)
                    val yDn = (mid - i).roundToInt().coerceIn(0, h - 1)
                    out[yUp][x] = 1f
                    out[yDn][x] = 1f
                }
            }
        }

        MatrixMode.WAVE -> {
            for (x in 0 until w) {
                val a = bandAt(x) * 0.85f + level01 * 0.15f
                val phase = (x / w.toFloat()) * (PI.toFloat() * 2f)
                val wobble = sin(phase * 3f + timeSec * 2.1f) * 0.35f
                val y = ((h - 1) * 0.5f + (a - 0.5f + wobble) * (h * 0.42f))
                    .roundToInt()
                    .coerceIn(0, h - 1)
                out[y][x] = 1f
                if (y + 1 < h) out[y + 1][x] = 0.55f
                if (y - 1 >= 0) out[y - 1][x] = 0.55f
            }
        }

        MatrixMode.BLOCKS -> {
            val energy = ((bands01?.average()?.toFloat()) ?: level01).coerceIn(0f, 1f)
            val count = (8 + energy * 28).roundToInt().coerceIn(8, 36)

            for (i in 0 until count) {
                val fx = fract(sin((i * 91.7f + timeSec * 0.9f)) * 43758.5453f)
                val fy = fract(sin((i * 47.2f + timeSec * 1.3f)) * 24634.6345f)
                val x = (fx * (w - 1)).roundToInt().coerceIn(0, w - 1)
                val y = (fy * (h - 1)).roundToInt().coerceIn(0, h - 1)
                val size = if (energy > 0.55f) 2 else 1
                for (dx in 0 until size) for (dy in 0 until size) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val yy = (y + dy).coerceIn(0, h - 1)
                    out[yy][xx] = max(out[yy][xx], 0.9f)
                }
            }
        }

        MatrixMode.PULSE -> {
            val a = ((bands01?.take(6)?.average()?.toFloat()) ?: level01).coerceIn(0f, 1f)
            val centerY = (h - 1) * 0.5f
            val amp = (a * (h * 0.45f))
            for (x in 0 until w) {
                val phase = (x / w.toFloat()) * (PI.toFloat() * 2f)
                val y = (centerY + sin(phase * 2f + timeSec * 3f) * amp).roundToInt()
                val yy = y.coerceIn(0, h - 1)
                out[yy][x] = 1f
                if (yy + 1 < h) out[yy + 1][x] = 0.45f
                if (yy - 1 >= 0) out[yy - 1][x] = 0.45f
            }
        }


        MatrixMode.SPRING -> {
            // Spectrum bars with a subtle springy overshoot / bounce feel.
            val baseEnergy = ((bands01?.average()?.toFloat()) ?: level01).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val a = bandAt(x)
                val phase = timeSec * (7.5f + 6.0f * baseEnergy) + x * 0.18f
                val overshoot = 1f + 0.22f * sin(phase + a * 2.4f)
                val height = (a * overshoot * h).roundToInt().coerceIn(0, h)
                for (y in 0 until height) out[y][x] = 1f
                if (height in 1 until h) {
                    out[height - 1][x] = 1f
                    if (height < h) out[height][x] = max(out[height][x], 0.45f)
                }
            }
        }

        MatrixMode.RAIN -> {
            // "Matrix rain" drops whose speed/length respond to audio energy.
            val energy = ((bands01?.average()?.toFloat()) ?: level01).coerceIn(0f, 1f)
            val baseSpeed = 0.55f + 2.2f * energy
            val trailBase = (3 + (energy * 9f)).roundToInt().coerceIn(3, 12)

            for (x in 0 until w) {
                val seed = fract(sin(x * 91.7f) * 43758.5453f)
                val colSpeed = baseSpeed * (0.75f + 0.65f * seed)
                val head = ((timeSec * colSpeed * h) + seed * (h + 7)).toInt()
                val yHead = (h - 1) - (head % (h + trailBase))

                // Head + trailing tail
                for (i in 0..trailBase) {
                    val yy = yHead + i
                    if (yy in 0 until h) {
                        val v = (1f - i / (trailBase.toFloat() + 0.0001f)).coerceIn(0f, 1f)
                        out[yy][x] = max(out[yy][x], 0.25f + 0.75f * v)
                    }
                }

                // Occasional secondary sparkle when energy is higher
                if (energy > 0.55f && seed > 0.72f) {
                    val y2 = (yHead - (2 + (seed * 5f).toInt()))
                    if (y2 in 0 until h) out[y2][x] = max(out[y2][x], 0.75f)
                }
            }
        }

        MatrixMode.STAIR -> {
            // Quantized "stair" bars with a traveling feel across columns.
            val energy = ((bands01?.average()?.toFloat()) ?: level01).coerceIn(0f, 1f)
            val step = 2 // quantization in LED rows
            val travel = sin(timeSec * (1.6f + 2.2f * energy)) * 0.5f + 0.5f

            for (x in 0 until w) {
                val a = bandAt(x)
                val bias = sin(timeSec * 2.4f + x * 0.22f) * (0.10f + 0.22f * energy)
                val mixed = (a * 0.82f + travel * 0.18f + bias).coerceIn(0f, 1f)
                val raw = mixed * h
                val q = ((raw / step).roundToInt() * step).coerceIn(0, h)
                for (y in 0 until q) out[y][x] = 1f
                if (q in 1 until h) out[q - 1][x] = 1f
            }
        }

        MatrixMode.FIRE -> {
            // Bottom-up flames: dense jitter near the base, thinner as it rises.
            val energy = ((bands01?.average()?.toFloat()) ?: level01).coerceIn(0f, 1f)
            for (x in 0 until w) {
                val a = bandAt(x) * 0.85f + energy * 0.15f
                for (y in 0 until h) {
                    val ny = y / (h - 1f).coerceAtLeast(1f)
                    val base = (a * (1.15f - ny * 1.05f)).coerceIn(0f, 1f)

                    val n = fract(sin(x * 12.9898f + y * 78.233f + timeSec * (2.8f + 1.8f * energy)) * 43758.5453f)
                    val flicker = (n - 0.5f) * (0.55f + 0.65f * energy)

                    val v = (base + flicker).coerceIn(0f, 1f)
                    if (v > 0.22f) out[y][x] = max(out[y][x], v)
                }
            }
        }

        MatrixMode.NEEDLE -> {
            // Stylized "needle vibration": a bright moving trace around the center line.
            val low = ((bands01?.take(6)?.average()?.toFloat()) ?: level01).coerceIn(0f, 1f)
            val center = (h - 1) * 0.5f
            val amp = (0.10f + 0.34f * low) * (h * 0.45f)

            for (x in 0 until w) {
                val a = bandAt(x)
                val phase = (x / w.toFloat()) * (PI.toFloat() * 2f)
                val wobble = sin(phase * 5.0f + timeSec * (10.0f + 8.0f * low)) * (0.45f + 0.55f * a)
                val y = (center + wobble * amp).roundToInt().coerceIn(0, h - 1)

                out[y][x] = max(out[y][x], 1f)
                if (y + 1 < h) out[y + 1][x] = max(out[y + 1][x], 0.55f)
                if (y - 1 >= 0) out[y - 1][x] = max(out[y - 1][x], 0.55f)

                // occasional sharp ticks (like micro-vibrations)
                val tick = fract(sin((x * 37.2f + timeSec * (9.0f + 10.0f * low))) * 24634.6345f)
                if (tick > 0.985f && y + 2 < h) out[y + 2][x] = max(out[y + 2][x], 0.80f)
            }
        }

        MatrixMode.TEXT -> {
            val word = if (isLive) "LIVE" else "VDK"
            drawText5x7(out, w, h, word, blink = isLive, timeSec = timeSec)
        }
    }

    val breath = 0.85f + 0.15f * (level01.coerceIn(0f, 1f))
    for (y in 0 until h) for (x in 0 until w) {
        out[y][x] = (out[y][x] * breath).coerceIn(0f, 1f)
    }

    return out
}

private fun DrawScope.drawLedMatrix(
    w: Int,
    h: Int,
    intens: Array<FloatArray>,
    palette: MatrixPalette
) {
    val cellW = size.width / w.toFloat()
    val cellH = size.height / h.toFloat()
    val cell = min(cellW, cellH)

    val drawW = cell * w
    val drawH = cell * h
    val ox = (size.width - drawW) * 0.5f
    val oy = (size.height - drawH) * 0.5f

    val r = cell * 0.18f
    val core = cell * 0.72f
    val pad = (cell - core) * 0.5f

    for (yy in 0 until h) {
        for (xx in 0 until w) {
            val v = intens[yy][xx].coerceIn(0f, 1f)
            if (v <= 0.01f) continue

            val color = paletteColor(palette, v, yy, h)

            val x0 = ox + xx * cell + pad
            val y0 = oy + (h - 1 - yy) * cell + pad

            val glowSize = core * (1.25f + 0.30f * v)
            val gx = x0 - (glowSize - core) * 0.5f
            val gy = y0 - (glowSize - core) * 0.5f

            drawRoundRect(
                color = color.copy(alpha = (0.10f + 0.26f * v).coerceIn(0f, 0.38f)),
                topLeft = Offset(gx, gy),
                size = Size(glowSize, glowSize),
                cornerRadius = CornerRadius(r * 2.0f, r * 2.0f)
            )

            drawRoundRect(
                color = color.copy(alpha = (0.22f + 0.78f * v).coerceIn(0f, 0.98f)),
                topLeft = Offset(x0, y0),
                size = Size(core, core),
                cornerRadius = CornerRadius(r, r)
            )
        }
    }
}

private fun paletteColor(p: MatrixPalette, v: Float, y: Int, h: Int): Color {
    val t = (y / (h - 1f).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return when (p) {
        MatrixPalette.AMBER ->
            lerpColor(Color(0xFF8A3B00), Color(0xFFFFD06A), (0.25f + 0.75f * v) * (0.55f + 0.45f * t))
        MatrixPalette.MAGENTA ->
            lerpColor(Color(0xFF7A1BFF), Color(0xFFFF4FD8), (0.30f + 0.70f * v) * (0.55f + 0.45f * t))
        MatrixPalette.CYAN ->
            lerpColor(Color(0xFF00A8FF), Color(0xFF6CFFF5), (0.30f + 0.70f * v) * (0.55f + 0.45f * t))
        MatrixPalette.CLASSIC -> {
            when {
                t < 0.60f -> lerpColor(Color(0xFF19C463), Color(0xFF9CFF7A), (t / 0.60f) * (0.35f + 0.65f * v))
                t < 0.85f -> lerpColor(Color(0xFFFFD54F), Color(0xFFFFB300), ((t - 0.60f) / 0.25f) * (0.35f + 0.65f * v))
                else -> lerpColor(Color(0xFFFF6A6A), Color(0xFFFF2A2A), ((t - 0.85f) / 0.15f) * (0.35f + 0.65f * v))
            }
        }
        MatrixPalette.MONO -> Color.White.copy(alpha = 0.25f + 0.75f * v)
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val u = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * u,
        green = a.green + (b.green - a.green) * u,
        blue = a.blue + (b.blue - a.blue) * u,
        alpha = 1f
    )
}

private fun fract(x: Float): Float = x - floor(x)

/* --------------------------- 5x7 text drawing --------------------------- */

private fun drawText5x7(
    out: Array<FloatArray>,
    w: Int,
    h: Int,
    text: String,
    blink: Boolean,
    timeSec: Float
) {
    val font = Font5x7
    val chars = text.uppercase()

    val charW = 5
    val charH = 7
    val spacing = 1

    val totalW = chars.length * charW + (chars.length - 1) * spacing
    val startX = ((w - totalW) / 2).coerceAtLeast(0)
    val startY = ((h - charH) / 2).coerceAtLeast(0)

    val blinkOn = if (!blink) true else (sin(timeSec * 6.0f) > -0.2f)

    for (i in chars.indices) {
        val glyph = font[chars[i]] ?: font['?']!!
        val gx0 = startX + i * (charW + spacing)
        val gy0 = startY

        for (gy in 0 until charH) {
            for (gx in 0 until charW) {
                if (glyph[gy][gx] == 1 && blinkOn) {
                    val x = gx0 + gx
                    val y = gy0 + (charH - 1 - gy)
                    if (x in 0 until w && y in 0 until h) {
                        out[y][x] = max(out[y][x], 1f)
                    }
                }
            }
        }
    }
}

private object Font5x7 {
    private val L = arrayOf(
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,1,1,1,1),
    )
    private val I = arrayOf(
        intArrayOf(1,1,1,1,1),
        intArrayOf(0,0,1,0,0),
        intArrayOf(0,0,1,0,0),
        intArrayOf(0,0,1,0,0),
        intArrayOf(0,0,1,0,0),
        intArrayOf(0,0,1,0,0),
        intArrayOf(1,1,1,1,1),
    )
    private val V = arrayOf(
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(0,1,0,1,0),
        intArrayOf(0,1,0,1,0),
        intArrayOf(0,0,1,0,0),
    )
    private val E = arrayOf(
        intArrayOf(1,1,1,1,1),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,1,1,1,0),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,0,0,0,0),
        intArrayOf(1,1,1,1,1),
    )
    private val D = arrayOf(
        intArrayOf(1,1,1,1,0),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,1,1,1,0),
    )
    private val K = arrayOf(
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,1,0),
        intArrayOf(1,0,1,0,0),
        intArrayOf(1,1,0,0,0),
        intArrayOf(1,0,1,0,0),
        intArrayOf(1,0,0,1,0),
        intArrayOf(1,0,0,0,1),
    )
    private val Q = arrayOf(
        intArrayOf(0,1,1,1,0),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,0,0,1),
        intArrayOf(1,0,1,0,1),
        intArrayOf(0,1,1,1,0),
        intArrayOf(0,0,0,0,1),
    )

    private val map: Map<Char, Array<IntArray>> = mapOf(
        'L' to L,
        'I' to I,
        'V' to V,
        'E' to E,
        'D' to D,
        'K' to K,
        '?' to Q
    )

    operator fun get(c: Char): Array<IntArray>? = map[c]
}