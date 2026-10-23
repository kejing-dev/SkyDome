package io.embodit.skydome.ui.sky.components

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import io.embodit.skydome.R
import io.embodit.skydome.domain.Body
import io.embodit.skydome.domain.City
import io.embodit.skydome.domain.Constellation
import io.embodit.skydome.ui.sky.LanguagePref
import java.util.Locale

/**
 * Copy for the language chosen in the app (follow system / 中文 / English), switched at run time without
 * restarting the Stage: the screen provides a Resources object for that locale and every panel reads its
 * strings from it (Spatial Ruler). The data names (constellations, cities, stars) carry both languages.
 */
val LocalSkyResources = staticCompositionLocalOf<Resources> { error("LocalSkyResources not provided") }

fun localizedResources(context: Context, language: LanguagePref): Resources {
    val tag =
        when (language) {
            LanguagePref.SYSTEM -> return context.resources
            LanguagePref.ZH -> "zh-CN"
            LanguagePref.EN -> "en"
        }
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return context.createConfigurationContext(configuration).resources
}

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int): String = LocalSkyResources.current.getString(id)

@Composable
@ReadOnlyComposable
fun rs(@StringRes id: Int, vararg args: Any): String = LocalSkyResources.current.getString(id, *args)

@Composable
@ReadOnlyComposable
fun isZh(): Boolean = LocalSkyResources.current.getString(R.string.lang_code) == "zh"

fun Resources.zh(): Boolean = getString(R.string.lang_code) == "zh"

fun Constellation.name(zh: Boolean): String = if (zh) this.zh else en

fun City.name(zh: Boolean): String = if (zh) this.zh else en

fun Resources.bodyName(b: Body): String =
    getString(
        when (b) {
            Body.SUN -> R.string.body_sun
            Body.MOON -> R.string.body_moon
            Body.MERCURY -> R.string.body_mercury
            Body.VENUS -> R.string.body_venus
            Body.MARS -> R.string.body_mars
            Body.JUPITER -> R.string.body_jupiter
            Body.SATURN -> R.string.body_saturn
        }
    )

fun Resources.direction(azDeg: Double): String = getStringArray(R.array.compass8)[io.embodit.skydome.domain.Compass.index(azDeg)]

fun Resources.phaseName(index: Int): String = getStringArray(R.array.moon_phases)[index.coerceIn(0, 7)]

/** Panel sizes (design-package `panels[].sizeDp`), in dp. */
object PanelSizes {
    const val BAR_W = 560f
    const val BAR_H = 128f
    const val CAL_W = 560f
    const val CAL_H = 200f
    const val TIME_W = 440f
    const val TIME_H = 520f
    const val TONIGHT_W = 440f
    const val TONIGHT_H = 320f
    const val SETTINGS_W = 440f
    const val SETTINGS_H = 520f
    const val CARD_W = 380f
    const val CARD_H = 340f
    const val BODY_W = 360f
    const val BODY_H = 236f
    /** Sky tags at 5.6 m (x3.75): 64 dp high = 0.19 m, the 32 sp glyphs about 0.1 m = 0.95° (design: >= 0.9°). */
    const val TAG_W = 380f
    const val TAG_H = 64f
    const val SMALL_TAG_W = 240f
    const val READOUT_W = 200f
    const val READOUT_H = 40f
    const val TOAST_W = 480f
    const val TOAST_H = 48f
}
