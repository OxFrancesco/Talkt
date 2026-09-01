package org.buddytools.talkt.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF090A0C)
val Surface = Color(0xFF121417)
val SurfaceRaised = Color(0xFF1A1D21)
val Mint = Color(0xFFB9F6D2)
val MintDim = Color(0xFF68C991)
val Cloud = Color(0xFFE7ECE9)
val Muted = Color(0xFF909792)
val Hairline = Color(0xFF2A2F2C)
val Warning = Color(0xFFFFCC80)

private val TalktColors = darkColorScheme(
    primary = Mint,
    onPrimary = Ink,
    background = Ink,
    onBackground = Cloud,
    surface = Surface,
    onSurface = Cloud,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = Muted,
    outline = Hairline,
    secondary = MintDim,
    error = Color(0xFFFF8A80),
)

@Composable
fun TalktTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = TalktColors, content = content)
}
