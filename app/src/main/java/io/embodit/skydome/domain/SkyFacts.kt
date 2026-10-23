package io.embodit.skydome.domain

/** A "tonight" pick, language neutral. */
sealed class Pick {
    data class OfConstellation(val index: Int) : Pick()

    data class OfBody(val body: Body) : Pick()
}

data class PickRow(val pick: Pick, val horizontal: Horizontal)

data class Tonight(val rows: List<PickRow>, val afterDarkMs: Long?)

/** Facts shown on the cards and in "Tonight" (design panels.tonight / card / planetCard). */
object SkyFacts {
    const val DAY_SUN_ALT = -6.0
    const val DARK_SUN_ALT = -12.0
    private const val DAY_MS = 86_400_000L

    /** Monthly candidates: the three constellations with the brightest lead star among those best in that month. */
    fun monthCandidates(catalog: SkyCatalog, month: Int): List<Constellation> =
        catalog.constellations
            .filter { it.bestMonth == month }
            .sortedBy { c -> catalog.starIndex(c.brightestHip)?.let { catalog.stars[it].vmag } ?: 9f }
            .take(3)

    /** The next moment the sun is below -12° (from [fromMs], within a day); [fromMs] itself when already dark. */
    fun nextDark(fromMs: Long, lat: Double, lon: Double): Long? {
        if (Astro.sunAltitude(fromMs, lat, lon) < DARK_SUN_ALT) return fromMs
        return Astro.nextCrossing(fromMs, DAY_MS, DARK_SUN_ALT, rising = false) { Astro.sunAltitude(it, lat, lon) }
    }

    /**
     * Up to four rows: the moon (above the horizon), the two brightest planets higher than 5°, one constellation
     * of the month higher than 10° (else the highest of the year's candidates). By day it is worked out for the
     * moment it gets dark ([Tonight.afterDarkMs]). [monthOf] gives the city's month for a moment.
     */
    fun tonight(catalog: SkyCatalog, timeMs: Long, lat: Double, lon: Double, withBodies: Boolean, monthOf: (Long) -> Int): Tonight {
        val day = Astro.sunAltitude(timeMs, lat, lon) > DAY_SUN_ALT
        val at = if (day) nextDark(timeMs, lat, lon) ?: timeMs else timeMs
        val lst = Astro.lst(at, lon)
        val rows = ArrayList<PickRow>()
        if (withBodies) {
            val bodies = Astro.bodies(at, lat, lon)
            val moon = bodies.first { it.body == Body.MOON }
            val mh = Astro.horizontalAt(moon.eq, lst, lat)
            if (mh.altDeg > 0) rows += PickRow(Pick.OfBody(Body.MOON), mh)
            bodies
                .filter { it.body.isPlanet }
                .map { it to Astro.horizontalAt(it.eq, lst, lat) }
                .filter { it.second.altDeg > 5 }
                .sortedBy { it.first.mag }
                .take(2)
                .forEach { rows += PickRow(Pick.OfBody(it.first.body), it.second) }
        }
        val month = monthOf(at)
        val pick =
            monthCandidates(catalog, month).map { it to Astro.horizontalAt(it.centre, lst, lat) }.firstOrNull { it.second.altDeg > 10 }
                ?: (1..12).flatMap { monthCandidates(catalog, it) }.map { it to Astro.horizontalAt(it.centre, lst, lat) }.maxByOrNull { it.second.altDeg }
        if (pick != null) rows += PickRow(Pick.OfConstellation(pick.first.index), pick.second)
        return Tonight(rows.take(4), if (day) at else null)
    }

    /** Nearest and farthest known distances of a constellation's figure stars, and how many are uncertain. */
    data class DepthFacts(val nearestLy: Float, val farthestLy: Float, val nearestUncertain: Boolean, val farthestUncertain: Boolean, val uncertainCount: Int)

    fun depthFacts(catalog: SkyCatalog, c: Constellation): DepthFacts? {
        val stars = c.stars.map { catalog.stars[it] }
        val known = stars.filter { !it.ly.isNaN() }
        if (known.isEmpty()) return null
        val near = known.minBy { it.ly }
        val far = known.maxBy { it.ly }
        return DepthFacts(near.ly, far.ly, near.uncertain, far.uncertain, stars.count { it.uncertain })
    }

    /**
     * The rise during the local day that starts at [dayStartMs] and the set that follows it (within 24 h, so
     * possibly the next day); null when the body does not rise that day.
     */
    fun riseSet(body: Body, dayStartMs: Long, lat: Double, lon: Double): Pair<Long?, Long?> {
        val th = Astro.riseThreshold(body)
        val alt = { t: Long -> Astro.bodyAltitude(body, t, lat, lon) }
        val rise = Astro.nextCrossing(dayStartMs, DAY_MS, th, rising = true, altitude = alt)
        val set = Astro.nextCrossing(rise ?: dayStartMs, DAY_MS, th, rising = false, altitude = alt)
        return rise to set
    }

    /** Moon phase word index: 0 new, 1 waxing crescent, 2 first quarter, 3 waxing gibbous, 4 full, 5 waning gibbous, 6 last quarter, 7 waning crescent. */
    fun phaseIndex(phase: Double): Int {
        val p = ((phase % 1.0) + 1.0) % 1.0
        return when {
            p < 0.03 || p > 0.97 -> 0
            p < 0.22 -> 1
            p < 0.28 -> 2
            p < 0.47 -> 3
            p < 0.53 -> 4
            p < 0.72 -> 5
            p < 0.78 -> 6
            else -> 7
        }
    }
}
