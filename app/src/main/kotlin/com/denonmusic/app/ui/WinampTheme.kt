package com.denonmusic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A dark, LCD-green, beveled-window look pulled from late-90s media player skins (Winamp chief
 * among them): black panels, hairline bevels instead of elevation/shadow, and monospace everywhere
 * a classic skin would have used its bitmap digit font.
 */
object Winamp {
    val Background = Color(0xFF0B0D0A)
    val Panel = Color(0xFF15170F)
    val PanelLight = Color(0xFF232619)
    val Green = Color(0xFF33FF66)
    val GreenDim = Color(0xFF1E9940)
    val Amber = Color(0xFFFFA200)
    val BevelLight = Color(0xFF3A3D2E)
    val BevelDark = Color(0xFF000000)

    val Mono = FontFamily.Monospace

    val colorScheme = darkColorScheme(
        background = Background,
        surface = Panel,
        surfaceVariant = PanelLight,
        primary = Green,
        onPrimary = Color.Black,
        secondary = Amber,
        onBackground = Green,
        onSurface = Green,
        outline = BevelLight,
    )

    val titleStyle = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp)
    val labelStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp)
    val smallStyle = TextStyle(fontFamily = Mono, fontSize = 11.sp, color = GreenDim)
}

@Composable
fun WinampTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Winamp.colorScheme, content = content)
}

/** Classic Win9x-era inset/outset hairline bevel: light on top-left, dark on bottom-right. */
fun Modifier.bevel(inset: Boolean = false): Modifier = this.drawBehind {
    val light = if (inset) Winamp.BevelDark else Winamp.BevelLight
    val dark = if (inset) Winamp.BevelLight else Winamp.BevelDark
    val w = size.width
    val h = size.height
    drawLine(light, Offset(0f, 0f), Offset(w, 0f), strokeWidth = 1.5f)
    drawLine(light, Offset(0f, 0f), Offset(0f, h), strokeWidth = 1.5f)
    drawLine(dark, Offset(0f, h), Offset(w, h), strokeWidth = 1.5f)
    drawLine(dark, Offset(w, 0f), Offset(w, h), strokeWidth = 1.5f)
}

/** Decorative spectrum-analyzer style bars. Not derived from real audio - purely a skin flourish. */
@Composable
fun EqualizerBars(levels: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val barWidth = size.width / (levels.size * 2f)
        levels.forEachIndexed { index, level ->
            val barHeight = size.height * level.coerceIn(0.05f, 1f)
            val x = index * barWidth * 2f
            drawRect(
                color = Winamp.Green,
                topLeft = Offset(x, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
            )
        }
    }
}

@Composable
fun CrtBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(Winamp.Background))
}
