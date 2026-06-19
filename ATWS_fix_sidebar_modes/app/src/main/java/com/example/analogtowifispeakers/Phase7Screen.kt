// FILE: app/src/main/java/com/example/analogtowifispeakers/Phase7Screen.kt
package com.example.analogtowifispeakers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun Phase7Screen(
    initials: String,
    level01: Float,
    castReady: Boolean,
    onCastClick: () -> Unit,
    onPlayClick: () -> Unit,
    onStopClick: () -> Unit,
    onSidebarVisibleChanged: (Boolean) -> Unit,
    onLiveChanged: (Boolean) -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {

        val countdownTotal = 35

        // Sidebar starts visible.
        LaunchedEffect(Unit) { onSidebarVisibleChanged(true) }

        var countdownSeconds by remember { mutableIntStateOf(countdownTotal) }
        var countdownRunning by remember { mutableStateOf(false) }

        // LIVE stays active after countdown ends (until STOP)
        var liveActive by remember { mutableStateOf(false) }

        // token to restart countdown cleanly
        var playToken by remember { mutableIntStateOf(0) }

        LaunchedEffect(liveActive) {
            onLiveChanged(liveActive)
            // When countdown finishes and LIVE starts, auto-hide the sidebar.
            if (liveActive) onSidebarVisibleChanged(false)
        }

        // Bulletproof countdown engine
        LaunchedEffect(countdownRunning, playToken) {
            if (!countdownRunning) return@LaunchedEffect

            countdownSeconds = countdownTotal
            liveActive = false

            while (countdownRunning && countdownSeconds > 0) {
                delay(1_000)
                countdownSeconds -= 1
            }

            if (countdownRunning) {
                liveActive = true
                countdownRunning = false
            }
        }

        val handlePlayClick = {
            if (!castReady) {
                onCastClick()
            } else {
                onPlayClick()
                countdownRunning = true
                playToken += 1
            }
        }

        val handleStopClick = {
            onStopClick()
            countdownRunning = false
            liveActive = false
            countdownSeconds = countdownTotal
            // When stopping, show the sidebar again (so user can cast/play again easily).
            onSidebarVisibleChanged(true)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            PremiumSidebarPill(
                initials = initials,
                level01 = level01,
                castReady = castReady,
                onCastClick = onCastClick,
                onPlayClick = handlePlayClick,
                onStopClick = handleStopClick,
                countdownRunning = countdownRunning,
                countdownSeconds = countdownSeconds,
                liveActive = liveActive,
                countdownTotal = countdownTotal,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 14.dp)
            )
        }
    }
}

/* ---------------- Sidebar ---------------- */

@Composable
private fun PremiumSidebarPill(
    initials: String,
    level01: Float,
    castReady: Boolean,
    onCastClick: () -> Unit,
    onPlayClick: () -> Unit,
    onStopClick: () -> Unit,
    countdownRunning: Boolean,
    countdownSeconds: Int,
    liveActive: Boolean,
    countdownTotal: Int,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(26.dp)
    val pillWidth = 96.dp

    val glassNeon = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color(0xFF7FE7FF).copy(alpha = 0.18f),
            0.22f to Color(0xFF7FE7FF).copy(alpha = 0.10f),
            0.55f to Color.White.copy(alpha = 0.06f),
            0.80f to Color.Black.copy(alpha = 0.10f),
            1.00f to Color.Black.copy(alpha = 0.22f)
        )
    )

    Box(
        modifier = modifier
            .width(pillWidth)
            .fillMaxHeight()
            .padding(vertical = 14.dp)
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.60f))
        )

        Column(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(glassNeon)
                .padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Phase7IconButton(onClick = onCastClick) { CastGlyphPremium(sizeDp = 34) }

            if (castReady) {
                Spacer(Modifier.height(14.dp))

                Phase7IconButton(onClick = onPlayClick) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White.copy(alpha = 0.96f),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(Modifier.height(14.dp))
                Phase7IconButton(onClick = onStopClick) { StopGlyph(sizeDp = 24) }

                Spacer(Modifier.height(18.dp))
                StereoVuSegments(level01 = level01)

                Spacer(Modifier.height(12.dp))
                LatencyZone(
                    running = countdownRunning,
                    secondsLeft = countdownSeconds,
                    liveActive = liveActive,
                    total = countdownTotal
                )
            } else {
                Spacer(Modifier.height(18.dp))
                CastHintPill()
            }

            Spacer(Modifier.weight(1f))
        }

        Canvas(modifier = Modifier.matchParentSize()) {
            drawRoundRect(
                color = Color(0xFFBFEFFF).copy(alpha = 0.14f),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(26.dp.toPx(), 26.dp.toPx()),
                style = Stroke(width = 1.2f, cap = StrokeCap.Round)
            )
        }

        Canvas(modifier = Modifier.matchParentSize().padding(1.dp)) {
            drawRoundRect(
                color = Color(0xFFBFEFFF).copy(alpha = 0.06f),
                topLeft = Offset(0f, 0f),
                size = Size(size.width, size.height),
                cornerRadius = CornerRadius(25.dp.toPx(), 25.dp.toPx()),
                style = Stroke(width = 1.0f, cap = StrokeCap.Round)
            )
        }
    }
}

@Composable
private fun CastHintPill() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Select\nspeaker",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 11.sp,
            lineHeight = 12.sp
        )
    }
}

@Composable
private fun LatencyZone(
    running: Boolean,
    secondsLeft: Int,
    liveActive: Boolean,
    total: Int
) {
    Box(
        modifier = Modifier
            .height(56.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        if (liveActive && !running) {
            Text(
                text = "LIVE",
                color = Color.White.copy(alpha = 0.96f),
                fontSize = 18.sp,
                letterSpacing = 2.0.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
            return@Box
        }

        if (!running) return@Box

        val redAt = (total * 0.70f).roundToInt().coerceAtLeast(1)
        val amberAt = (total * 0.35f).roundToInt().coerceAtLeast(1)

        val color = when {
            secondsLeft >= redAt -> Color(0xFFFF5252)
            secondsLeft >= amberAt -> Color(0xFFFFB74D)
            else -> Color(0xFF25D366)
        }

        if (secondsLeft <= 0) {
            Text(
                text = "LIVE",
                color = Color.White.copy(alpha = 0.96f),
                fontSize = 18.sp,
                letterSpacing = 2.0.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${secondsLeft.coerceAtLeast(1)}s",
                    color = color.copy(alpha = 0.96f),
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "BUFFERING",
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    letterSpacing = 1.6.sp
                )
            }
        }
    }
}

@Composable
private fun Phase7IconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(58.dp)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun CastGlyphPremium(sizeDp: Int) {
    val c = Color.White.copy(alpha = 0.96f)
    Canvas(modifier = Modifier.size(sizeDp.dp)) {
        val w = size.width
        val h = size.height
        val left = w * 0.14f
        val top = h * 0.18f
        val right = w * 0.90f
        val bottom = h * 0.78f

        drawRoundRect(
            color = c.copy(alpha = 0.78f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(w * 0.12f, w * 0.12f),
            style = Stroke(width = w * 0.075f, cap = StrokeCap.Round)
        )

        val cx = right - w * 0.18f
        val cy = bottom - h * 0.10f
        val outerR = w * 0.30f
        val innerR = w * 0.20f
        val stroke = w * 0.085f

        drawArc(
            color = c.copy(alpha = 0.55f),
            startAngle = 180f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(cx - outerR, cy - outerR),
            size = Size(outerR * 2f, outerR * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        drawArc(
            color = c.copy(alpha = 0.75f),
            startAngle = 180f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(cx - innerR, cy - innerR),
            size = Size(innerR * 2f, innerR * 2f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        drawCircle(color = c, radius = w * 0.055f, center = Offset(cx, cy))
    }
}

@Composable
private fun StopGlyph(sizeDp: Int) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.96f))
    )
}

@Composable
private fun StereoVuSegments(level01: Float) {
    val v = level01.coerceIn(0f, 1f)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        VuSegments(level01 = v)
        VuSegments(level01 = v)
    }
}

@Composable
private fun VuSegments(level01: Float) {
    val segments = 14
    val lit = (level01.coerceIn(0f, 1f) * segments).roundToInt()

    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 2.dp)
    ) {
        for (i in segments downTo 1) {
            val isOn = i <= lit
            val baseColor = vuColorFor(i, segments)
            val alpha = if (isOn) 0.92f else 0.18f
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(baseColor.copy(alpha = alpha))
            )
        }
    }
}

private fun vuColorFor(idxFromBottom: Int, total: Int): Color {
    val greenEnd = (total * 0.60f).roundToInt().coerceAtLeast(1)
    val yellowEnd = (total * 0.85f).roundToInt().coerceAtLeast(greenEnd + 1)
    return when {
        idxFromBottom <= greenEnd -> Color(0xFF25D366)
        idxFromBottom <= yellowEnd -> Color(0xFFFFD54F)
        else -> Color(0xFFFF5252)
    }
}