// FILE: app/src/main/java/com/example/analogtowifispeakers/ui/theme/LaserVisualizerLayer.kt
package com.example.analogtowifispeakers.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Slide 5 — LASER VISUAL MODE (3D-ish)
 *
 * Uses shared types from LaserTypes.kt:
 *  - LaserRigType: FAN, CROSS, WEB, FULL_SHOW
 *  - LaserPalette: RETRO, MODERN, RAVE, ACID, MONO
 *  - LaserBeam(originN, angleRad, widthPx, lengthPx, color, intensity, sparkle)
 */
@Composable
fun LaserVisualizerLayer(
    modifier: Modifier = Modifier,
    isLive: Boolean,
    // Audio (0..1)
    level01: Float,
    bass01: Float,
    mid01: Float,
    high01: Float,
    // UI controls
    rigType: LaserRigType,   // SHAPE button
    palette: LaserPalette,   // COLOR button
) {
    var nowSec by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = System.nanoTime()
        while (isActive) {
            nowSec = (System.nanoTime() - start) / 1_000_000_000f
            yield()
        }
    }

    val trailFrames = remember { ArrayDeque<List<LaserBeam>>(16) }

    val beamsNow = remember(isLive, level01, bass01, mid01, high01, rigType, palette, nowSec) {
        if (!isLive) emptyList() else {
            buildLaserBeams(
                t = nowSec,
                level = level01,
                bass = bass01,
                mid = mid01,
                high = high01,
                rig = rigType,
                palette = palette
            )
        }
    }

    LaunchedEffect(beamsNow) {
        trailFrames.addFirst(beamsNow)
        while (trailFrames.size > 10) trailFrames.removeLast()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val frames = trailFrames.toList().asReversed()
        val n = frames.size.coerceAtLeast(1)


        // --- CLUB ATMOSPHERE (fog + subtle viewer wash) ---
        // Core beams remain thin; atmosphere sells the "laser in the air" look.
        val e = level01.coerceIn(0f, 1f)
        val flash01 = ((e - 0.45f) / 0.45f).coerceIn(0f, 1f)
        val ambientA = (0.010f + 0.070f * ((e - 0.10f) / 0.35f).coerceIn(0f, 1f)) * 0.85f

        val washColor = when (palette) {
            LaserPalette.RETRO -> Color(0xFF19FF7A)
            LaserPalette.MODERN -> Color(0xFF2AD0FF)
            LaserPalette.RAVE -> Color(0xFFFF3FD7)
            LaserPalette.ACID -> Color(0xFFB6FF00)
            LaserPalette.MONO -> Color.White
        }

        if (isLive && ambientA > 0.002f) {
            // soft haze across the whole canvas
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(
                        washColor.copy(alpha = ambientA * 0.85f),
                        washColor.copy(alpha = ambientA * 0.35f),
                        washColor.copy(alpha = 0f)
                    ),
                    center = center,
                    radius = size.minDimension * 0.85f
                )
            )
        }

        // "Laser in the eyes" moment — short wash on stronger peaks
        val peakGate = isLive && (e > 0.70f)
        val peakA = if (peakGate) (0.020f + 0.140f * flash01) else 0f
        if (peakA > 0.003f) {
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                    colors = listOf(
                        washColor.copy(alpha = peakA),
                        washColor.copy(alpha = peakA * 0.55f),
                        washColor.copy(alpha = 0f)
                    ),
                    center = Offset(center.x, size.height * 0.42f),
                    radius = size.minDimension * 0.95f
                )
            )
        }

        frames.forEachIndexed { idx, beams ->
            val age = (n - 1 - idx).toFloat()
            val fade = (1f - (age / n.toFloat())).coerceIn(0f, 1f)
            val trailAlpha = (fade * fade * 0.55f).coerceIn(0f, 0.55f)
            beams.forEach { drawLaserBeam(it, globalAlpha = trailAlpha) }
        }

        beamsNow.forEach { drawLaserBeam(it, globalAlpha = 1f) }
    }
}

/* ---------------------------- Core Builder ---------------------------- */

private fun buildLaserBeams(
    t: Float,
    level: Float,
    bass: Float,
    mid: Float,
    high: Float,
    rig: LaserRigType,
    palette: LaserPalette
): List<LaserBeam> {
    val e = (level * 1.05f).coerceIn(0f, 1f)
    val b = (bass * 1.10f).coerceIn(0f, 1f)
    val m = (mid * 1.10f).coerceIn(0f, 1f)
    val h = (high * 1.10f).coerceIn(0f, 1f)


    // DMX PROGRAM via COLOR: palette selects show program (tunnel/cross/bar/show vibe).
    // SHAPE (rig) still matters as a "fixture layout", but program can override motion for clear visual difference.
    val program: LaserRigType = when (palette) {
        LaserPalette.RETRO -> LaserRigType.FAN
        LaserPalette.MODERN -> LaserRigType.WEB
        LaserPalette.RAVE -> LaserRigType.FULL_SHOW
        LaserPalette.ACID -> LaserRigType.CROSS
        LaserPalette.MONO -> LaserRigType.FAN
    }

    // If user selects a specific rigType, keep it — but if they want "DMX via COLOR", program can take lead in LASER mode.
    // Use program as the primary when rig is FAN (default). Otherwise respect selected rig.
    val effectiveRig = if (rig == LaserRigType.FAN) program else rig

    // Keep your existing musical mapping, but the motion model is now "coming from deep -> forward"
    val spread = lerp(0.35f, 1.35f, smooth01(b).powApprox(0.90f))
    val wobble = lerp(0.02f, 0.28f, smooth01(m).powApprox(0.85f))
    val sparkle = smooth01(h).powApprox(1.25f)
    val densityBoost = (0.55f + 0.45f * e).coerceIn(0f, 1f)

    val colors = paletteSet(palette)
    fun pick(i: Int): Color = colors[(i % colors.size + colors.size) % colors.size]

    val bottom = 0.92f
    val top = 0.14f

    val originMain = Offset(0.50f, bottom)
    val originL = Offset(0.23f, bottom)
    val originR = Offset(0.77f, bottom)

    // A "vanishing point" to sell the depth illusion (deep in screen)
    val vanish = Offset(0.50f, 0.34f)

    // --- STROBE (occasional) ---
    // Gate + blink. Short, punchy, club-like.
    val strobeGate =
        (e > 0.38f && (b > 0.40f || h > 0.55f)) &&
                (sin(t * (2.2f + 5.5f * b) + 1.7f) > 0.80f) // occasional bursts
    val strobeHz = 24f
    val strobeBlink = if (((t * strobeHz).toInt() and 1) == 0) 1f else 0f
    val strobe = if (strobeGate) strobeBlink else 1f

    val beams = ArrayList<LaserBeam>(140)

    // Depth flight -> perspective.
    // flight01: 0 = far (deep), 1 = near (front / can overshoot)
    fun flight01(speed: Float, phase: Float): Float {
        val x = t * speed + phase
        val f = x - kotlin.math.floor(x)
        return f.coerceIn(0f, 1f)
    }

    fun perspectiveFromFlight(f: Float): Float {
        // Make "near" feel REALLY near (accelerates towards viewer)
        val eased = smooth01(f).powApprox(0.72f)
        // Map to a pseudo-z and invert; clamp to avoid infinity
        val z = lerp(26f, 0.7f, eased) // far->near
        return (1f / z).coerceIn(0.02f, 1.0f)
    }

    fun originWithDepth(originN: Offset, p: Float): Offset {
        // As the beam comes forward, its origin drifts away from vanishing point slightly
        // (parallax), giving a "room / dancing" feel.
        val k = (p * 9.0f).coerceIn(0f, 1.15f) // stronger near
        return Offset(
            x = lerp(vanish.x, originN.x, k),
            y = lerp(vanish.y, originN.y, k)
        )
    }

    fun addBeam3D(
        originN: Offset,
        baseAngleRad: Float,
        flight: Float,
        color: Color,
        baseIntensity01: Float,
        sparkle01: Float = 0f,
        lenMul: Float = 1.0f,
        strobeable: Boolean = true,
        sweepPhase: Float = 0f,
        sweepFreq: Float = 1.0f
    ) {
        val p = perspectiveFromFlight(flight)
        val near01 = (p * 18f).coerceIn(0f, 1f)

        // Angle sweeps more aggressively when near (club sweep)
        val sweepAmp = wobble * lerp(0.45f, 2.10f, near01)
        val sweep = sin(t * (0.9f + sweepFreq * (0.9f + 1.6f * b)) + sweepPhase) * sweepAmp
        val ang = baseAngleRad + sweep

        // Width/length/intensity grow as it comes forward
        val widthPx = lerp(0.70f, 3.60f, near01)
        val lengthPx = lerp(0.25f, 1.45f, near01) * lenMul * (0.85f + 0.35f * e)

        // Intensity also rises forward; strobe toggles some beams (not all)
        val st = if (strobeable) strobe else 1f
        val inten = (baseIntensity01 * lerp(0.55f, 1.25f, near01) * st).coerceIn(0f, 1f)

        // Origin parallax (so it feels like depth, not a flat fan)
        val o = originWithDepth(originN, p)

        beams += LaserBeam(
            originN = o,
            angleRad = ang,
            widthPx = widthPx,
            lengthPx = lengthPx,
            color = color,
            intensity = inten,
            sparkle = sparkle01.coerceIn(0f, 1f)
        )
    }

    when (effectiveRig) {
        LaserRigType.FAN -> {
            val count = (18 + (20 * densityBoost)).roundToInt().coerceIn(18, 44)
            repeat(count) { i ->
                val u = if (count == 1) 0.5f else i / (count - 1f)
                val a = (-spread * 0.60f) + u * (spread * 1.20f)
                val base = (-PI.toFloat() / 2f) + a

                // Each beam "flies" from deep to front on its own phase
                val f = flight01(speed = lerp(0.55f, 1.20f, 0.40f + 0.60f * b), phase = u * 7.3f + 0.12f * i)
                val inten = (0.30f + 0.70f * e) * (0.62f + 0.38f * (0.40f + 0.60f * m))

                addBeam3D(
                    originN = originMain,
                    baseAngleRad = base,
                    flight = f,
                    color = pick(i),
                    baseIntensity01 = inten,
                    sparkle01 = sparkle * 0.35f,
                    lenMul = 1.08f,
                    strobeable = true,
                    sweepPhase = u * 6.3f,
                    sweepFreq = 1.0f
                )
            }
        }

        LaserRigType.CROSS -> {
            // Fan (depth-flying)
            val fanCount = (18 + (18 * densityBoost)).roundToInt().coerceIn(18, 42)
            repeat(fanCount) { i ->
                val u = if (fanCount == 1) 0.5f else i / (fanCount - 1f)
                val a = (-spread * 0.55f) + u * (spread * 1.10f)
                val base = (-PI.toFloat() / 2f) + a

                val f = flight01(speed = lerp(0.55f, 1.35f, 0.35f + 0.65f * b), phase = u * 8.1f + i * 0.09f)
                val inten = (0.30f + 0.70f * e)

                addBeam3D(
                    originN = originMain,
                    baseAngleRad = base,
                    flight = f,
                    color = pick(i),
                    baseIntensity01 = inten,
                    sparkle01 = sparkle * 0.42f,
                    lenMul = 1.05f,
                    strobeable = true,
                    sweepPhase = u * 5.8f,
                    sweepFreq = 1.05f
                )
            }

            // Diagonal cross beams (also depth-flying, but less strobe so the "fan" stays the star)
            val crossCount = (6 + (6 * densityBoost)).roundToInt().coerceIn(6, 12)
            repeat(crossCount) { i ->
                val u = if (crossCount == 1) 0.5f else i / (crossCount - 1f)
                val fromTop = Offset(lerp(0.14f, 0.86f, u), lerp(0.10f, 0.28f, 1f - u))
                val toMid = Offset(lerp(0.22f, 0.78f, 1f - u), lerp(0.62f, 0.74f, u))
                val baseAng = atan2(toMid.y - fromTop.y, toMid.x - fromTop.x)

                val f = flight01(speed = lerp(0.40f, 0.95f, 0.25f + 0.75f * m), phase = 3.0f + u * 6.9f + i * 0.13f)
                val flick = 0.80f + 0.20f * sin(t * (6.2f + 10f * sparkle) + u * 9f)
                val inten = (0.34f + 0.66f * (0.25f + 0.75f * h)) * flick

                val col = if (palette == LaserPalette.MONO) Color.White else pick(i + 2)
                addBeam3D(
                    originN = fromTop,
                    baseAngleRad = baseAng,
                    flight = f,
                    color = col,
                    baseIntensity01 = inten,
                    sparkle01 = sparkle * 0.55f,
                    lenMul = 1.18f,
                    strobeable = false, // keep these cleaner; fan does the strobe
                    sweepPhase = u * 4.7f,
                    sweepFreq = 0.8f
                )
            }
        }

        LaserRigType.WEB -> {
            // room-filling web: multiple emitters, each beam flies from deep to front

            val tunnelMode = (palette == LaserPalette.MODERN)
            val webAngleMin = if (tunnelMode) -0.55f else -1.05f
            val webAngleMax = if (tunnelMode)  0.55f else  1.05f
            val emitters = listOf(
                Offset(0.16f, bottom),
                Offset(0.50f, bottom),
                Offset(0.84f, bottom),
                Offset(0.12f, top),
                Offset(0.88f, top)
            )

            val perEmitter = (9 + (11 * densityBoost)).roundToInt().coerceIn(9, 20)
            emitters.forEachIndexed { ei, origin ->
                repeat(perEmitter) { i ->
                    val u = if (perEmitter == 1) 0.5f else i / (perEmitter - 1f)
                    val base = (-PI.toFloat() / 2f) + lerp(webAngleMin, webAngleMax, u)

                    val f = flight01(
                        speed = lerp(0.50f, 1.10f, 0.40f + 0.60f * b),
                        phase = ei * 1.7f + u * 9.0f + i * 0.07f
                    )

                    val inten = (0.24f + 0.76f * e) * (0.55f + 0.45f * (0.30f + 0.70f * m))

                    addBeam3D(
                        originN = origin,
                        baseAngleRad = base,
                        flight = f,
                        color = pick(i + ei * 2),
                        baseIntensity01 = inten,
                        sparkle01 = sparkle * 0.42f,
                        lenMul = 1.10f,
                        strobeable = (ei % 2 == 0), // some strobe, not all
                        sweepPhase = u * 7.0f + ei * 0.7f,
                        sweepFreq = 1.0f
                    )
                }
            }

            // threads (diagonal "web strings") also fly, but softer
            val threads = (10 + (14 * densityBoost)).roundToInt().coerceIn(10, 24)
            repeat(threads) { i ->
                val u = if (threads == 1) 0.5f else i / (threads - 1f)
                val from = Offset(lerp(0.08f, 0.92f, u), lerp(0.10f, 0.30f, 1f - u))
                val to = Offset(lerp(0.18f, 0.82f, 1f - u), lerp(0.64f, 0.80f, u))
                val baseAng = atan2(to.y - from.y, to.x - from.x)

                val f = flight01(speed = lerp(0.35f, 0.85f, 0.25f + 0.75f * m), phase = 2.2f + u * 8.2f + i * 0.11f)
                val inten = (0.18f + 0.82f * (0.25f + 0.75f * m)) * (0.85f + 0.15f * h)

                addBeam3D(
                    originN = from,
                    baseAngleRad = baseAng,
                    flight = f,
                    color = pick(i + 3),
                    baseIntensity01 = inten,
                    sparkle01 = sparkle * 0.55f,
                    lenMul = 1.16f,
                    strobeable = false,
                    sweepPhase = u * 5.4f,
                    sweepFreq = 0.75f
                )
            }
        }

        LaserRigType.FULL_SHOW -> {
            // DnB FESTIVAL MODE: strong depth fly-in + occasional hard strobe
            val fanOrigins = listOf(originL, originR, originMain)
            val fanTotal = (46 + (34 * densityBoost)).roundToInt().coerceIn(46, 84)

            fanOrigins.forEachIndexed { oi, origin ->
                val per = (fanTotal / fanOrigins.size).coerceAtLeast(14)
                repeat(per) { i ->
                    val u = if (per == 1) 0.5f else i / (per - 1f)
                    val a = (-spread * 0.65f) + u * (spread * 1.30f)
                    val base = (-PI.toFloat() / 2f) + a

                    val f = flight01(
                        speed = lerp(0.70f, 1.70f, 0.35f + 0.65f * b),
                        phase = oi * 1.1f + u * 9.4f + i * 0.05f
                    )

                    val col = pick(i + oi * 3)
                    val inten = (0.36f + 0.64f * e) * (0.60f + 0.40f * (0.25f + 0.75f * m))

                    addBeam3D(
                        originN = origin,
                        baseAngleRad = base,
                        flight = f,
                        color = col,
                        baseIntensity01 = inten,
                        sparkle01 = sparkle * 0.70f,
                        lenMul = 1.18f,
                        strobeable = true,
                        sweepPhase = u * 7.5f + oi * 0.9f,
                        sweepFreq = 1.15f
                    )
                }
            }

            // Top scanner sweeps, but also "flies" so it can come out of the window
            val scanOrigin = Offset(0.50f, top)
            val scanCount = (14 + (12 * densityBoost)).roundToInt().coerceIn(14, 26)
            repeat(scanCount) { i ->
                val u = if (scanCount == 1) 0.5f else i / (scanCount - 1f)
                val sweep = sin(t * (2.4f + b * 4.6f) + u * 2.9f)
                val base = lerp(-1.30f, 1.30f, (sweep * 0.5f + 0.5f))
                val baseAng = (-PI.toFloat() / 2f) + base * lerp(0.35f, 1.15f, (0.35f + 0.65f * m))

                val f = flight01(speed = lerp(0.55f, 1.35f, 0.35f + 0.65f * b), phase = 1.8f + u * 6.0f + i * 0.08f)
                val inten = (0.22f + 0.78f * (0.25f + 0.75f * m)) * (0.75f + 0.25f * e)

                addBeam3D(
                    originN = scanOrigin,
                    baseAngleRad = baseAng,
                    flight = f,
                    color = pick(i + 1),
                    baseIntensity01 = inten,
                    sparkle01 = sparkle * 0.90f,
                    lenMul = 1.24f,
                    strobeable = true,
                    sweepPhase = u * 2.9f,
                    sweepFreq = 1.25f
                )
            }

            // Cross flashes: fewer, but punchy
            val crossCount = (10 + (10 * densityBoost)).roundToInt().coerceIn(10, 20)
            repeat(crossCount) { i ->
                val u = if (crossCount == 1) 0.5f else i / (crossCount - 1f)
                val from = Offset(lerp(0.06f, 0.94f, u), lerp(0.16f, 0.30f, 1f - u))
                val to = Offset(lerp(0.18f, 0.82f, 1f - u), lerp(0.62f, 0.82f, u))
                val baseAng = atan2(to.y - from.y, to.x - from.x)

                val f = flight01(speed = lerp(0.45f, 1.05f, 0.25f + 0.75f * m), phase = 0.9f + u * 7.1f + i * 0.10f)
                val flick = 0.88f + 0.12f * sin(t * (8.0f + 10f * sparkle) + u * 9.0f)
                val inten = (0.24f + 0.76f * (0.20f + 0.80f * h)) * flick

                addBeam3D(
                    originN = from,
                    baseAngleRad = baseAng,
                    flight = f,
                    color = pick(i + 4),
                    baseIntensity01 = inten,
                    sparkle01 = sparkle,
                    lenMul = 1.28f,
                    strobeable = true,
                    sweepPhase = u * 6.6f,
                    sweepFreq = 0.95f
                )
            }

            // Bass pumps: short, violent fly-ins (your "comes out of the window" moments)
            val pumpCount = (12 + (10 * densityBoost)).roundToInt().coerceIn(12, 22)
            repeat(pumpCount) { i ->
                val u = if (pumpCount == 1) 0.5f else i / (pumpCount - 1f)
                val origin = Offset(lerp(0.08f, 0.92f, u), bottom)
                val baseAng = (-PI.toFloat() / 2f) + sin(t * 2.9f + u * 7.2f) * lerp(0.28f, 1.35f, b)

                // faster = more "whoosh" forward
                val f = flight01(speed = lerp(0.95f, 2.20f, b), phase = u * 10.4f + i * 0.06f)
                val inten = (0.28f + 0.72f * e) * (0.55f + 0.45f * smooth01(b))

                addBeam3D(
                    originN = origin,
                    baseAngleRad = baseAng,
                    flight = f,
                    color = pick(i + 6),
                    baseIntensity01 = inten,
                    sparkle01 = sparkle * 0.75f,
                    lenMul = 1.15f,
                    strobeable = true,
                    sweepPhase = u * 6.4f,
                    sweepFreq = 1.35f
                )
            }
        }
    }

    return beams
}

/* ---------------------------- Drawing ---------------------------- */

private fun DrawScope.drawLaserBeam(
    beam: LaserBeam,
    globalAlpha: Float
) {
    val origin = Offset(beam.originN.x * size.width, beam.originN.y * size.height)

    val baseLen = size.minDimension * 0.95f
    val len = baseLen * beam.lengthPx

    val dx = cos(beam.angleRad) * len
    val dy = sin(beam.angleRad) * len
    val end = Offset(origin.x + dx, origin.y + dy)

    val unitPx = 1.dp.toPx()
    val wPx = (beam.widthPx * unitPx).coerceAtLeast(0.6f)

    val a = (beam.intensity * globalAlpha).coerceIn(0f, 1f)
    val glowA = (a * (0.10f + 0.10f * beam.sparkle)).coerceIn(0f, 0.22f)
    val coreA = a

    // Soft air-glow (haze catch) — subtle, not cartoon-thick
    drawLine(
        color = beam.color.copy(alpha = glowA),
        start = origin,
        end = end,
        strokeWidth = wPx * (1.45f + 0.35f * beam.sparkle),
        cap = StrokeCap.Round
    )

    // Core beam (hair-thin)
    drawLine(
        color = beam.color.copy(alpha = coreA),
        start = origin,
        end = end,
        strokeWidth = wPx,
        cap = StrokeCap.Round
    )

    // Hot core (slightly whiter, thinner) for "laser brightness" without thickness
    val hot = lerpColor(beam.color, Color.White, 0.22f)
    drawLine(
        color = hot.copy(alpha = (a * 0.55f).coerceIn(0f, 0.70f)),
        start = origin,
        end = end,
        strokeWidth = (wPx * 0.55f).coerceAtLeast(0.55f),
        cap = StrokeCap.Round
    )
}

/* ---------------------------- Palette ---------------------------- */


private fun lerpColor(a: Color, b: Color, t: Float): Color {
    val x = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * x,
        green = a.green + (b.green - a.green) * x,
        blue = a.blue + (b.blue - a.blue) * x,
        alpha = a.alpha + (b.alpha - a.alpha) * x
    )
}

private fun paletteSet(p: LaserPalette): List<Color> {
    val green = Color(0xFF00FF66)
    val lime = Color(0xFF7CFF3A)
    val cyan = Color(0xFF00E5FF)
    val ice = Color(0xFF6CFFF5)
    val blue = Color(0xFF2D7DFF)
    val indigo = Color(0xFF4D4CFF)
    val violet = Color(0xFF8A5CFF)
    val magenta = Color(0xFFFF3ED6)
    val hotPink = Color(0xFFFF5A8A)
    val amber = Color(0xFFFFB000)
    val gold = Color(0xFFFFD36B)
    val red = Color(0xFFFF2A2A)

    return when (p) {
        LaserPalette.RETRO -> listOf(green, Color(0xFF6BFF9B), amber, gold, Color(0xFFFF5A3A))
        LaserPalette.MODERN -> listOf(cyan, ice, blue, indigo, violet, magenta)
        LaserPalette.RAVE -> listOf(green, cyan, blue, violet, magenta, hotPink, amber, gold, red)
        LaserPalette.ACID -> listOf(green, lime, Color(0xFF00D95C), amber, cyan, ice)
        LaserPalette.MONO -> listOf(Color.White)
    }
}

/* ---------------------------- Helpers ---------------------------- */

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

private fun smooth01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * pow-achtige curve zonder kotlin.math.pow nodig (voorkomt import issues).
 * p in ~[0.5..2.0] is genoeg voor visuals.
 */
private fun Float.powApprox(p: Float): Float {
    val x = this.coerceIn(0f, 1f)
    // simple bias curve: x^(p) benaderen via mix van x en x*x
    // p<1 => meer boost, p>1 => meer compress
    val xx = x * x
    val t = ((p - 0.5f) / 1.5f).coerceIn(0f, 1f)
    return lerp(x, xx, t)
}