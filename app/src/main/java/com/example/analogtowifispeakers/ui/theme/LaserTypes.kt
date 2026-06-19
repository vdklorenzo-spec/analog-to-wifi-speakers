// FILE: app/src/main/java/com/example/analogtowifispeakers/ui/theme/LaserTypes.kt
package com.example.analogtowifispeakers.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.PI

enum class LaserRigType(val label: String) {
    FAN("FAN"),
    CROSS("CROSS"),
    WEB("WEB"),
    FULL_SHOW("SHOW");

    fun next(): LaserRigType = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromIndex(i: Int): LaserRigType {
            val all = entries
            return all[(i % all.size + all.size) % all.size]
        }
    }
}

enum class LaserPalette(val label: String) {
    RETRO("RETRO"),     // green → amber → soft red
    MODERN("MODERN"),   // teal → blue → purple
    RAVE("RAVE"),       // bigger palette, party vibes
    ACID("ACID"),       // neon
    MONO("MONO");       // white/ice

    fun next(): LaserPalette = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromIndex(i: Int): LaserPalette {
            val all = entries
            return all[(i % all.size + all.size) % all.size]
        }
    }
}

/**
 * originN = normalized origin in 0..1 (x,y) inside the laser window
 * angleRad = direction in radians
 */
data class LaserBeam(
    val originN: Offset,
    val angleRad: Float,
    val widthPx: Float,
    val lengthPx: Float,
    val color: Color,
    val intensity: Float,  // 0..1
    val sparkle: Float = 0f
)

internal fun deg(d: Float): Float = (d * PI.toFloat()) / 180f