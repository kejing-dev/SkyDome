package io.embodit.skydome.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pico.spatial.ui.design.PicoTheme

/**
 * The design's exact colours (design-package `palette`). This is the only file with colour literals; the
 * four semantic roles go into `PicoTheme` through [SkyDomeTheme], the brand tokens are used directly by the
 * sky (Unlit materials) and a few panel accents. One accent in the sky: amber.
 */
object SkyColors {
    // ---- ColorScheme roles with a fixed design value (the other twelve inherit SpatialUI Vibrant)
    val Interaction = Color(0xFFFFC86B) // design-style: fixed-figma-color interaction: looked at / guide / not now
    val Passable = Color(0xFF7FD6C2) // design-style: fixed-figma-color passable
    val Alert = Color(0xFFFFB04A) // design-style: fixed-figma-color alert: demo heading
    val Error = Color(0xFFFF7A6B) // design-style: fixed-figma-color error: failure hint

    // ---- brand tokens
    val Zenith = Color(0xFF05070F) // design-style: fixed-figma-color zenith
    val HorizonNight = Color(0xFF141B33) // design-style: fixed-figma-color horizonNight
    val HorizonDusk = Color(0xFFC9733A) // design-style: fixed-figma-color horizonDusk
    val DayWash = Color(0xFF1C3A57) // design-style: fixed-figma-color dayWash
    val StarWhite = Color(0xFFF4F6FF) // design-style: fixed-figma-color starWhite
    val StarBlue = Color(0xFFA9C4FF) // design-style: fixed-figma-color starBlue
    val StarOrange = Color(0xFFFFC79A) // design-style: fixed-figma-color starOrange
    val LineIdle = Color(0xFF7FA8FF) // design-style: fixed-figma-color lineIdle (drawn at 35 %)
    val LineLit = Color(0xFFFFC86B) // design-style: fixed-figma-color lineLit
    val GlassEdge = Color(0xFFA9C4FF) // design-style: fixed-figma-color glassEdge (drawn at 8 %)
    val Moon = Color(0xFFE8E4D8) // design-style: fixed-figma-color moon
    val MoonDark = Color(0xFF2A2A2E) // design-style: fixed-figma-color moon night side
    val Sun = Color(0xFFFFB547) // design-style: fixed-figma-color sun
    val Mercury = Color(0xFFC9C2B8) // design-style: fixed-figma-color planets.mercury
    val Venus = Color(0xFFFFF1C9) // design-style: fixed-figma-color planets.venus
    val Mars = Color(0xFFFF8A5B) // design-style: fixed-figma-color planets.mars
    val Jupiter = Color(0xFFF2D9B0) // design-style: fixed-figma-color planets.jupiter
    val Saturn = Color(0xFFE8D08A) // design-style: fixed-figma-color planets.saturn
    val Ground = Color(0xFF0B0D12) // design-style: fixed-figma-color ground
    val Ink = Color(0xFF16181C) // design-style: fixed-figma-color ink on amber fills
    val White = Color(0xFFFFFFFF) // design-style: fixed-figma-color white
    val TagFill = Color(0x990B0F1C) // design-style: fixed-figma-color sky tag backing (zenith at 60 %)

    /** Six star colours by B-V, blue-white to orange-red (StarLook.colorClass). */
    val StarClasses = listOf(Color(0xFFA9C4FF), Color(0xFFCFDCFF), Color(0xFFF4F6FF), Color(0xFFFFF4E0), Color(0xFFFFDDB0), Color(0xFFFFC79A)) // design-style: fixed-figma-color star B-V ramp
}

/** Type roles of the design (`typography.roles`). */
object SkyType {
    val time: TextStyle
        @Composable get() = PicoTheme.typography.displayLarge.copy(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum, lnum")

    val title: TextStyle
        @Composable get() = PicoTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)

    val popupTitle: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

    val body: TextStyle
        @Composable get() = PicoTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum, lnum")

    val label: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")

    /** Sky tags: a panel scaled x3.75 at 5.6 m, so 32 sp is about 0.1 m = 0.95° high (design: >= 0.9°, QA q1-3). */
    val skyTag: TextStyle
        @Composable get() = PicoTheme.typography.titleMedium.copy(fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)

    /** Tags on the pulled-down model (eye, distance ticks), at arm's length. */
    val modelTag: TextStyle
        @Composable get() = PicoTheme.typography.labelMedium.copy(fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum, lnum")
}
