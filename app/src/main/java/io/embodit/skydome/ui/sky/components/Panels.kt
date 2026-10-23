package io.embodit.skydome.ui.sky.components

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonChip
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.ChipsDefaults
import com.pico.spatial.ui.design.Icon
import com.pico.spatial.ui.design.IconButton
import com.pico.spatial.ui.design.IconButtonDefaults
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.SegmentControl
import com.pico.spatial.ui.design.SegmentControlDefaults
import com.pico.spatial.ui.design.SegmentItem
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import io.embodit.skydome.BuildConfig
import io.embodit.skydome.R
import io.embodit.skydome.domain.Astro
import io.embodit.skydome.domain.Body
import io.embodit.skydome.domain.SkyFacts
import io.embodit.skydome.ui.sky.LanguagePref
import io.embodit.skydome.ui.sky.Layer
import io.embodit.skydome.ui.sky.SkyController
import io.embodit.skydome.ui.sky.SkyEvent
import io.embodit.skydome.ui.sky.SkyUiState
import io.embodit.skydome.ui.sky.Target
import io.embodit.skydome.ui.sky.ToastKind
import io.embodit.skydome.ui.sky.TonightRow
import io.embodit.skydome.ui.sky.debug.probe
import io.embodit.skydome.ui.theme.SkyColors
import io.embodit.skydome.ui.theme.SkyType
import java.text.NumberFormat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// formatting
// ---------------------------------------------------------------------------------------------

internal object Fmt {
    fun hm(t: ZonedDateTime): String = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)

    fun date(t: ZonedDateTime, zh: Boolean): String =
        if (zh) "${t.monthValue}月${t.dayOfMonth}日 ${t.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)}"
        else t.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US))

    fun mag(m: Double): String = String.format(Locale.US, "%.1f", m).replace("-", "−")

    fun mag(m: Float): String = mag(m.toDouble())

    fun ly(r: Resources, ly: Float, uncertain: Boolean): String {
        if (ly < 10f) return r.getString(R.string.ly_under)
        if (ly > 5000f) return r.getString(R.string.ly_over)
        val n = NumberFormat.getIntegerInstance(Locale.US).format(ly.roundToInt())
        return r.getString(if (uncertain) R.string.ly_about else R.string.ly, n)
    }

    fun dirAlt(r: Resources, azDeg: Double, altDeg: Double): String = r.getString(R.string.dir_alt, r.direction(azDeg), altDeg.roundToInt())

    fun hours(r: Resources, deltaMs: Long): String {
        val h = abs(deltaMs) / 3_600_000.0
        val txt = if (h == h.toInt().toDouble()) h.toInt().toString() else String.format(Locale.US, "%.1f", h)
        return r.getString(if (deltaMs >= 0) R.string.hours_plus else R.string.hours_minus, txt)
    }
}

private val GlassShape = RoundedCornerShape(28.dp)

/** Glass panel root (design: Material.Regular, the root draws no extra background). */
@Composable
private fun Glass(w: Float, h: Float, modifier: Modifier = Modifier, padding: Int = 20, content: @Composable () -> Unit) {
    Box(
        modifier =
            Modifier.size(w.dp, h.dp)
                .clip(GlassShape)
                .backgroundMaterial(enable = true, style = Material.Regular)
                .then(glassRefresh())
                .then(modifier)
                .padding(padding.dp)
    ) { content() }
}

// ---------------------------------------------------------------------------------------------
// hand bar (design panels.handBar)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun HandBar(state: SkyUiState?, onEvent: (SkyEvent) -> Unit) {
    Glass(PanelSizes.BAR_W, PanelSizes.BAR_H, padding = 16) {
        if (state == null) return@Glass
        val r = LocalSkyResources.current
        val zh = r.zh()
        val ink = PicoTheme.colorScheme.labelPrimary
        val t = SkyController.zoned(state.displayTime(state.tickMs.coerceAtLeast(SkyController.clock())), state)
        val city = SkyController.city(state)
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).clickable { onEvent(SkyEvent.TimeTapped) }.padding(horizontal = 8.dp, vertical = 2.dp).probe("bar_time"),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!state.live) {
                        Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(SkyColors.Interaction))
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(text = Fmt.hm(t), style = SkyType.time, color = ink, maxLines = 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = Fmt.date(t, zh) + (city?.let { " · " + it.name(zh) } ?: ""), style = SkyType.body, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1)
                    Spacer(Modifier.width(10.dp))
                    BarChip(state, onEvent)
                }
            }
            Spacer(Modifier.width(8.dp))
            BarKey(if (state.fast) R.drawable.ic_pause else R.drawable.ic_play, rs(if (state.fast) R.string.bar_pause else R.string.bar_fast), "bar_fast", state.fast) { onEvent(SkyEvent.FastToggled) }
            Spacer(Modifier.width(8.dp))
            BarKey(R.drawable.ic_sparkle, rs(R.string.bar_tonight), "bar_tonight", false) { onEvent(SkyEvent.TonightTapped) }
            Spacer(Modifier.width(8.dp))
            BarKey(R.drawable.ic_gear, rs(R.string.bar_settings), "bar_settings", false) { onEvent(SkyEvent.SettingsTapped) }
        }
    }
}

@Composable
private fun BarKey(icon: Int, desc: String, name: String, active: Boolean, onClick: () -> Unit) {
    val ink = if (active) SkyColors.Ink else PicoTheme.colorScheme.labelPrimary
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = if (active) PicoTheme.colorScheme.interaction else PicoTheme.colorScheme.fillTertiary, contentColor = ink),
        size = IconButtonDefaults.iconButtonSize(width = 56.dp, height = 56.dp),
    ) { Icon(painter = painterResource(icon), contentDescription = desc, modifier = Modifier.size(26.dp), tint = ink) }
}

/** The one chip: day > not now > heading stale / demo (design R2-1). */
@Composable
private fun BarChip(state: SkyUiState, onEvent: (SkyEvent) -> Unit) {
    val (text, event, alert) =
        when {
            SkyController.isDay(state) -> Triple(rs(R.string.chip_to_tonight), SkyEvent.ToTonight, false)
            !state.live && !state.fast -> Triple(rs(R.string.chip_back_now), SkyEvent.BackToNow, false)
            state.headingStale -> Triple(rs(R.string.chip_stale), SkyEvent.Recalibrate, true)
            state.demoHeading -> Triple(rs(R.string.chip_demo), SkyEvent.Recalibrate, true)
            else -> return
        }
    val fg = if (alert) SkyColors.Ink else PicoTheme.colorScheme.labelPrimary
    ButtonChip(
        label = { Text(text = text, color = fg, maxLines = 1, style = SkyType.label) },
        onClick = { onEvent(event) },
        modifier = Modifier.probe("bar_chip"),
        colors = ChipsDefaults.chipColors(contentColor = fg, backgroundColor = if (alert) PicoTheme.colorScheme.alert else PicoTheme.colorScheme.fillSecondary),
    )
}

// ---------------------------------------------------------------------------------------------
// calibrate (design panels.calibrate)
// ---------------------------------------------------------------------------------------------

@Composable
internal fun CalibratePanel(state: SkyUiState?, onConfirm: () -> Unit, onSkip: () -> Unit, onEvent: (SkyEvent) -> Unit) {
    Glass(PanelSizes.CAL_W, PanelSizes.CAL_H, padding = 18) {
        if (state == null) return@Glass
        val ink = PicoTheme.colorScheme.labelPrimary
        val r = LocalSkyResources.current
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = rs(R.string.cal_title), style = SkyType.title, color = ink, maxLines = 1)
            Text(text = rs(R.string.cal_body), style = SkyType.body, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                val words = r.getStringArray(R.array.compass4)
                SegmentControl(modifier = Modifier.width(232.dp).height(48.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
                    words.forEachIndexed { i, w ->
                        SegmentItem(
                            selected = state.calibrateChoice == i,
                            onClick = { onEvent(SkyEvent.CalibrateChoice(i)) },
                            modifier = Modifier.width(56.dp).fillMaxHeight().probe("cal_dir_$i"),
                            textStyle = PicoTheme.typography.labelLarge,
                            colors = segColors(),
                            title = { Text(text = if (r.zh()) w else w.take(1), maxLines = 1, color = ink) },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onSkip,
                    modifier = Modifier.probe("cal_skip"),
                    size = ButtonDefaults.Regular.copy(height = 48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = PicoTheme.colorScheme.labelSecondary),
                ) { Text(text = rs(R.string.cal_skip), maxLines = 1, style = SkyType.body) }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.probe("cal_confirm"),
                    size = ButtonDefaults.Regular.copy(height = 48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.interaction, contentColor = SkyColors.Ink),
                ) { Text(text = rs(R.string.cal_confirm), maxLines = 1, color = SkyColors.Ink) }
            }
        }
    }
}

@Composable
private fun segColors() =
    SegmentControlDefaults.colors(
        itemBackgroundColor = Color.Transparent,
        itemContentColor = PicoTheme.colorScheme.labelPrimary,
        selectedItemBackgroundColor = PicoTheme.colorScheme.fillSecondary,
        selectedItemContentColor = PicoTheme.colorScheme.labelPrimary,
    )

// ---------------------------------------------------------------------------------------------
// popups
// ---------------------------------------------------------------------------------------------

@Composable
private fun PopupTitle(text: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(40.dp)) {
        Text(text = text, style = SkyType.popupTitle, color = PicoTheme.colorScheme.labelPrimary, modifier = Modifier.weight(1f), maxLines = 1)
        IconButton(
            onClick = onClose,
            modifier = Modifier.probe("popup_close"),
            colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
            size = IconButtonDefaults.iconButtonSize(width = 36.dp, height = 36.dp),
        ) { Icon(painter = painterResource(R.drawable.ic_close), contentDescription = null, modifier = Modifier.size(18.dp), tint = PicoTheme.colorScheme.labelPrimary) }
    }
}

@Composable
internal fun TimePlacePanel(state: SkyUiState?, onEvent: (SkyEvent) -> Unit) {
    Glass(PanelSizes.TIME_W, PanelSizes.TIME_H, padding = 16) {
        if (state == null) return@Glass
        val r = LocalSkyResources.current
        val zh = r.zh()
        val ink = PicoTheme.colorScheme.labelPrimary
        val cat = SkyController.catalog ?: return@Glass
        val t = SkyController.zoned(state.displayTime(SkyController.clock()), state)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PopupTitle(rs(R.string.tp_title)) { onEvent(SkyEvent.PopupDismissed) }
            if (state.cityFallback) Text(text = rs(R.string.tp_fallback), style = SkyType.label, color = PicoTheme.colorScheme.alert, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallIcon(R.drawable.ic_chevron_left, rs(R.string.tp_prev), "tp_prev") { onEvent(SkyEvent.DateStep(-1)) }
                Text(text = Fmt.date(t, zh), style = SkyType.popupTitle, color = ink, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 132.dp))
                SmallIcon(R.drawable.ic_chevron_right, rs(R.string.tp_next), "tp_next") { onEvent(SkyEvent.DateStep(1)) }
                Spacer(Modifier.weight(1f))
                TextButton(rs(R.string.tp_today), "tp_today") { onEvent(SkyEvent.Today) }
            }
            if (!state.live) TextButton(rs(R.string.tp_now), "tp_now") { onEvent(SkyEvent.BackToNow) }
            Text(text = rs(R.string.tp_city), style = SkyType.label, color = PicoTheme.colorScheme.labelSecondary)
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(cat.cities, key = { it.id }) { c ->
                    ListItem(
                        headlineContent = { Text(text = c.name(zh), maxLines = 1, color = ink) },
                        trailingContent = { if (c.id == state.cityId) Icon(painter = painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.interaction) },
                        modifier = Modifier.clickable { onEvent(SkyEvent.CitySelected(c.id)) }.probe("city_${c.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: Int, desc: String, name: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.probe(name),
        colors = IconButtonDefaults.iconButtonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
        size = IconButtonDefaults.iconButtonSize(width = 40.dp, height = 40.dp),
    ) { Icon(painter = painterResource(icon), contentDescription = desc, modifier = Modifier.size(20.dp), tint = PicoTheme.colorScheme.labelPrimary) }
}

@Composable
private fun TextButton(text: String, name: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.probe(name),
        size = ButtonDefaults.Small,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = PicoTheme.colorScheme.labelPrimary),
    ) { Text(text = text, maxLines = 1) }
}

@Composable
internal fun TonightPanel(state: SkyUiState?, onEvent: (SkyEvent) -> Unit) {
    Glass(PanelSizes.TONIGHT_W, PanelSizes.TONIGHT_H, padding = 16) {
        if (state == null) return@Glass
        val r = LocalSkyResources.current
        val zh = r.zh()
        val ink = PicoTheme.colorScheme.labelPrimary
        val tn = SkyController.tonight(state)
        val title = tn.afterDarkMs?.let { r.getString(R.string.tn_after_dark, Fmt.hm(SkyController.zoned(it, state))) } ?: rs(R.string.tn_now)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PopupTitle(title) { onEvent(SkyEvent.PopupDismissed) }
            if (tn.rows.isEmpty()) Text(text = rs(R.string.tn_none), style = SkyType.body, color = PicoTheme.colorScheme.labelSecondary)
            tn.rows.forEachIndexed { i, row -> TonightLine(row, i, zh, ink, r) { onEvent(SkyEvent.TonightPicked(row)) } }
        }
    }
}

@Composable
private fun TonightLine(row: TonightRow, i: Int, zh: Boolean, ink: Color, r: Resources, onClick: () -> Unit) {
    val (icon, name) =
        when (val t = row.target) {
            is Target.OfBody -> (if (t.body == Body.MOON) R.drawable.ic_moon else R.drawable.ic_planet) to r.bodyName(t.body)
            is Target.Constellation -> R.drawable.ic_stars to (SkyController.constellation(t.index)?.name(zh) ?: "")
        }
    ListItem(
        headlineContent = { Text(text = name, maxLines = 1, color = ink) },
        leadingContent = { Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp), tint = ink) },
        trailingContent = { Text(text = Fmt.dirAlt(r, row.azDeg, row.altDeg), style = SkyType.body, color = PicoTheme.colorScheme.labelSecondary, maxLines = 1) },
        modifier = Modifier.clickable(onClick = onClick).probe("tonight_$i"),
    )
}

@Composable
internal fun SettingsPanel(state: SkyUiState?, onEvent: (SkyEvent) -> Unit) {
    Glass(PanelSizes.SETTINGS_W, PanelSizes.SETTINGS_H, padding = 16) {
        if (state == null) return@Glass
        val ink = PicoTheme.colorScheme.labelPrimary
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PopupTitle(rs(R.string.st_title)) { onEvent(SkyEvent.PopupDismissed) }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Section(rs(R.string.st_sky))
                SwitchRow(rs(R.string.st_lines), state.layer(Layer.LINES), "st_lines") { onEvent(SkyEvent.LayerToggled(Layer.LINES, it)) }
                SwitchRow(rs(R.string.st_bodies), state.layer(Layer.BODIES), "st_bodies") { onEvent(SkyEvent.LayerToggled(Layer.BODIES, it)) }
                SwitchRow(rs(R.string.st_compass), state.layer(Layer.COMPASS), "st_compass") { onEvent(SkyEvent.LayerToggled(Layer.COMPASS, it)) }
                Section(rs(R.string.st_view))
                SwitchRow(rs(R.string.st_dark), state.fullDark, "st_dark", rs(R.string.st_dark_body)) { onEvent(SkyEvent.FullDarkToggled(it)) }
                Section(rs(R.string.st_heading))
                Box(modifier = Modifier.padding(vertical = 4.dp)) { TextButton(rs(R.string.st_recal), "st_recal") { onEvent(SkyEvent.Recalibrate) } }
                Section(rs(R.string.st_language))
                SegmentControl(modifier = Modifier.fillMaxWidth().height(44.dp), backgroundColor = PicoTheme.colorScheme.fillTertiary) {
                    val labels = listOf(rs(R.string.lang_system), rs(R.string.lang_zh), rs(R.string.lang_en))
                    LanguagePref.entries.forEachIndexed { i, l ->
                        SegmentItem(
                            selected = state.language == l,
                            onClick = { onEvent(SkyEvent.LanguageSelected(l)) },
                            modifier = Modifier.width(132.dp).fillMaxHeight().probe("lang_${l.tag}"),
                            textStyle = PicoTheme.typography.labelLarge,
                            colors = segColors(),
                            title = { Text(text = labels[i], maxLines = 1, color = ink) },
                        )
                    }
                }
                Section(rs(R.string.st_about))
                Text(text = rs(R.string.st_version, BuildConfig.VERSION_NAME), style = SkyType.body, color = ink)
                Spacer(Modifier.height(4.dp))
                Text(text = rs(R.string.st_credits), style = SkyType.label, color = PicoTheme.colorScheme.labelSecondary)
            }
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text = text, style = SkyType.label, color = PicoTheme.colorScheme.labelSecondary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, name: String, sub: String? = null, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(text = text, maxLines = 1, color = PicoTheme.colorScheme.labelPrimary) },
        supportingContent = sub?.let { s -> { Text(text = s, maxLines = 1, style = SkyType.label, color = PicoTheme.colorScheme.labelSecondary) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.probe(name)) },
    )
}

// ---------------------------------------------------------------------------------------------
// cards
// ---------------------------------------------------------------------------------------------

@Composable
internal fun ConstellationCard(state: SkyUiState?, onEvent: (SkyEvent) -> Unit) {
    Glass(PanelSizes.CARD_W, PanelSizes.CARD_H, padding = 18) {
        val s = state ?: return@Glass
        val c = SkyController.constellation(s.pulled) ?: return@Glass
        val cat = SkyController.catalog ?: return@Glass
        val city = SkyController.city(s) ?: return@Glass
        val r = LocalSkyResources.current
        val zh = r.zh()
        val ink = PicoTheme.colorScheme.labelPrimary
        val sub = PicoTheme.colorScheme.labelSecondary
        val star = cat.starIndex(c.brightestHip)?.let { cat.stars[it] }
        val starName = (if (zh) c.starZh else c.starEn).ifBlank { c.brightestDesignation }
        val t = s.displayTime(SkyController.clock())
        val h = Astro.horizontal(c.centre, t, city.lat, city.lon)
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(text = c.name(zh), style = SkyType.title, color = ink, maxLines = 1)
            Text(text = (if (zh) c.en else "") .let { if (it.isBlank()) c.abbr else "$it · ${c.abbr}" }, style = SkyType.label, color = sub, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            if (star != null) Fact(r.getString(R.string.cd_brightest, starName.trim(), Fmt.mag(star.vmag)))
            Fact(r.getString(R.string.cd_best, r.getStringArray(R.array.months)[c.bestMonth - 1]))
            Fact(r.getString(R.string.cd_now, if (h.altDeg > 0) Fmt.dirAlt(r, h.azDeg, h.altDeg) else r.getString(R.string.cd_below)))
            Text(text = if (zh) c.blurbZh else c.blurbEn, style = SkyType.body, color = sub, maxLines = if (s.depth) 1 else 2, overflow = TextOverflow.Ellipsis)
            if (s.depth) {
                SkyFacts.depthFacts(cat, c)?.let { d ->
                    Fact(r.getString(R.string.cd_range, Fmt.ly(r, d.nearestLy, d.nearestUncertain), Fmt.ly(r, d.farthestLy, d.farthestUncertain)))
                    if (d.uncertainCount > 0) Text(text = if (d.uncertainCount == 1) r.getString(R.string.cd_uncertain_one) else r.getString(R.string.cd_uncertain, d.uncertainCount), style = SkyType.label, color = sub, maxLines = 1)
                }
            }
            Spacer(Modifier.weight(1f))
            // taste profile D1: the one-line hint for the thumbstick turn
            Text(text = rs(R.string.cd_stick), style = SkyType.label, color = sub, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(text = rs(R.string.cd_depth), style = SkyType.body, color = ink, maxLines = 1)
                Spacer(Modifier.width(8.dp))
                Switch(checked = s.depth, onCheckedChange = { onEvent(SkyEvent.DepthToggled(it)) }, modifier = Modifier.probe("card_depth"))
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { onEvent(SkyEvent.PutBack) },
                    modifier = Modifier.probe("card_put_back"),
                    size = ButtonDefaults.Small,
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = PicoTheme.colorScheme.fillTertiary, contentColor = ink),
                ) { Text(text = rs(R.string.cd_put_back), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Fact(text: String) {
    Text(text = text, style = SkyType.body, color = PicoTheme.colorScheme.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun BodyCard(state: SkyUiState?) {
    Glass(PanelSizes.BODY_W, PanelSizes.BODY_H, padding = 18) {
        val s = state ?: return@Glass
        val b = s.bodyCard ?: return@Glass
        val city = SkyController.city(s) ?: return@Glass
        val cat = SkyController.catalog ?: return@Glass
        val r = LocalSkyResources.current
        val zh = r.zh()
        val ink = PicoTheme.colorScheme.labelPrimary
        val t = s.displayTime(SkyController.clock())
        val bs = Astro.bodies(t, city.lat, city.lon).first { it.body == b }
        // the name in both languages, as in the design ("木星 Jupiter"); English copy shows the English name only
        val title = if (zh) "${r.bodyName(b)} ${b.english}" else b.english
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(text = title, style = SkyType.title, color = ink, maxLines = 1)
            if (b == Body.SUN) {
                Fact(rs(R.string.bd_sun))
            } else {
                val inC = cat.constellationAt(bs.eq)?.name(zh)
                Fact(if (inC != null) r.getString(R.string.bd_mag_in, Fmt.mag(bs.mag), inC) else r.getString(R.string.bd_mag, Fmt.mag(bs.mag)))
            }
            val day = SkyController.zoned(t, s).toLocalDate().atStartOfDay(SkyController.zone(s))
            val (rise, set) = SkyFacts.riseSet(b, day.toInstant().toEpochMilli(), city.lat, city.lon)
            fun when_(ms: Long): String {
                val z = SkyController.zoned(ms, s)
                return if (z.toLocalDate() == day.toLocalDate()) r.getString(R.string.bd_today, Fmt.hm(z)) else r.getString(R.string.bd_tomorrow, Fmt.hm(z))
            }
            val upNow = Astro.bodyAltitude(b, t, city.lat, city.lon) > 0
            Fact(
                when {
                    rise != null && set != null -> r.getString(R.string.bd_rise_set, when_(rise), when_(set))
                    rise != null -> r.getString(R.string.bd_rise_only, when_(rise))
                    set != null -> r.getString(R.string.bd_set_only, when_(set))
                    upNow -> rs(R.string.bd_up_all_day)
                    else -> rs(R.string.bd_no_rise)
                }
            )
            if (b == Body.MOON) {
                val km = NumberFormat.getIntegerInstance(Locale.US).format((bs.distAu * 149_597_870.7 / 1000).roundToInt() * 1000)
                Fact(r.getString(R.string.bd_dist_km, km))
                Fact(r.getString(R.string.bd_phase, r.phaseName(SkyFacts.phaseIndex(bs.phase)), (bs.illuminated * 100).roundToInt()))
            } else {
                Fact(r.getString(R.string.bd_dist_au, String.format(Locale.US, "%.2f", bs.distAu)))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// tags, readout, toast
// ---------------------------------------------------------------------------------------------

/**
 * A one-line tag: text in a dark pill so it reads over the dome; amber when looked at. [sky] = the large tags at
 * 5.6 m (names, compass), else the small ones on the pulled-down model.
 */
@Composable
internal fun SkyTag(text: String?, w: Float, lit: Boolean = false, alert: Boolean = false, sky: Boolean = true) {
    val h = if (sky) PanelSizes.TAG_H else PanelSizes.READOUT_H
    Box(modifier = Modifier.size(w.dp, h.dp), contentAlignment = Alignment.Center) {
        if (text == null) return@Box
        val color = when {
            lit -> SkyColors.Interaction
            alert -> SkyColors.Alert
            else -> SkyColors.StarWhite
        }
        Box(modifier = Modifier.clip(CircleShape).background(SkyColors.TagFill).padding(horizontal = if (sky) 16.dp else 12.dp, vertical = if (sky) 6.dp else 4.dp)) {
            Text(text = text, style = if (sky) SkyType.skyTag else SkyType.modelTag, color = color, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
internal fun ToastPanel(kind: ToastKind?) {
    Box(modifier = Modifier.size(PanelSizes.TOAST_W.dp, PanelSizes.TOAST_H.dp), contentAlignment = Alignment.Center) {
        if (kind == null) return@Box
        val text =
            rs(
                when (kind) {
                    ToastKind.PINCH_HINT -> R.string.toast_pinch
                    ToastKind.NO_LINES -> R.string.toast_no_lines
                    ToastKind.SWITCH_FAILED -> R.string.toast_switch_failed
                    ToastKind.BACK_TO_NOW -> R.string.toast_now
                    ToastKind.DEMO_HEADING -> R.string.toast_demo
                }
            )
        val color = if (kind == ToastKind.SWITCH_FAILED) PicoTheme.colorScheme.error else PicoTheme.colorScheme.labelPrimary
        Box(modifier = Modifier.clip(CircleShape).backgroundMaterial(enable = true, style = Material.Regular).then(glassRefresh()).padding(horizontal = 20.dp, vertical = 10.dp)) {
            Text(text = text, style = SkyType.body, color = color, maxLines = 1)
        }
    }
}

@Composable
internal fun ReadoutPanel(deltaMs: Long?, timeMs: Long?, state: SkyUiState?) {
    Box(modifier = Modifier.size(PanelSizes.READOUT_W.dp, PanelSizes.READOUT_H.dp), contentAlignment = Alignment.Center) {
        if (deltaMs == null || timeMs == null || state == null) return@Box
        val r = LocalSkyResources.current
        Box(modifier = Modifier.clip(CircleShape).background(SkyColors.TagFill).padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(text = r.getString(R.string.dial_delta, Fmt.hours(r, deltaMs), Fmt.hm(SkyController.zoned(timeMs, state))), style = SkyType.label, color = SkyColors.Interaction, maxLines = 1)
        }
    }
}
