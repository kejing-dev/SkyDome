package io.embodit.skydome.domain

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One star of the bundled catalogue (XHIP, V <= 6.0, plus the fainter stick-figure stars). */
class Star(
    val hip: Int,
    val eq: Equatorial,
    val vmag: Float,
    /** B-V colour index, NaN when unknown. */
    val bv: Float,
    /** Light years, NaN when the catalogue has no distance. */
    val ly: Float,
    /** Parallax relative error e_Plx / Plx, NaN when unknown. */
    val relErr: Float,
) {
    val unit: Vec3d = eq.unit()

    /** The distance is "about" (design: sigma / plx > 0.2). */
    val uncertain: Boolean
        get() = ly.isNaN() || relErr.isNaN() || relErr > 0.2f
}

/** A stick-figure segment: indices into [SkyCatalog.stars]. */
class Segment(val constellation: Int, val a: Int, val b: Int)

class Constellation(
    val index: Int,
    val abbr: String,
    val en: String,
    val zh: String,
    val brightestHip: Int,
    val starEn: String,
    val starZh: String,
    /** Month (1..12) whose evenings have it on the meridian at 21:00. */
    val bestMonth: Int,
    val blurbZh: String,
    val blurbEn: String,
    val centre: Equatorial,
    /** Bayer code of the brightest star (CDS IV/27A: alf, bet, ..., or a Flamsteed number). */
    val brightestCode: String = "",
) {
    /** "γ Cas" style designation of the brightest star. */
    val brightestDesignation: String
        get() = (GREEK[brightestCode.take(3)]?.let { g -> g + brightestCode.drop(3).trimStart('.', '0').let { if (it.isEmpty()) "" else superscript(it) } } ?: brightestCode) + " " + abbr

    companion object {
        private val GREEK =
            mapOf(
                "alf" to "α", "bet" to "β", "gam" to "γ", "del" to "δ", "eps" to "ε", "zet" to "ζ", "eta" to "η", "the" to "θ",
                "iot" to "ι", "kap" to "κ", "lam" to "λ", "mu." to "μ", "nu." to "ν", "ksi" to "ξ", "omi" to "ο", "pi." to "π",
                "rho" to "ρ", "sig" to "σ", "tau" to "τ", "ups" to "υ", "phi" to "φ", "chi" to "χ", "psi" to "ψ", "ome" to "ω",
            )

        private fun superscript(d: String) = d.map { "⁰¹²³⁴⁵⁶⁷⁸⁹"[it - '0'] }.joinToString("")
    }

    /** Indices of its segments and stars, filled by the catalogue. */
    val segments = ArrayList<Int>()
    val stars = LinkedHashSet<Int>()
}

data class City(val id: String, val zh: String, val en: String, val lat: Double, val lon: Double, val zone: String)

/** One row of CDS VI/42 (Roman 1987): RA hours and southern declination for equinox B1875. */
class BoundaryRow(val raLow: Double, val raUp: Double, val decLow: Double, val abbr: String)

/**
 * The offline sky data from `assets/sky` (tools/make_data.py), parsed from plain streams so it loads the
 * same in the app and in JVM tests.
 */
class SkyCatalog(
    val stars: List<Star>,
    val segments: List<Segment>,
    val constellations: List<Constellation>,
    val boundaries: List<BoundaryRow>,
    val cities: List<City>,
) {
    private val byHip: Map<Int, Int> = HashMap<Int, Int>().also { m -> stars.forEachIndexed { i, s -> m[s.hip] = i } }
    private val byAbbr: Map<String, Constellation> = constellations.associateBy { it.abbr }

    fun starIndex(hip: Int): Int? = byHip[hip]

    fun constellation(abbr: String): Constellation? = byAbbr[abbr] ?: byAbbr.entries.firstOrNull { it.key.equals(abbr, ignoreCase = true) }?.value

    fun city(id: String): City? = cities.firstOrNull { it.id == id }

    /**
     * The constellation that contains a J2000 position (Roman 1987: precess to B1875, then the first row whose
     * southern limit is below and whose RA range holds it). Used for "Jupiter is in Gemini".
     */
    fun constellationAt(eq: Equatorial): Constellation? {
        val p = Astro.precessFromJ2000(eq, 1875.0)
        val raH = Math.toDegrees(p.ra) / 15.0
        val decDeg = Math.toDegrees(p.dec)
        for (r in boundaries) {
            if (decDeg >= r.decLow && raH >= r.raLow && raH < r.raUp) return byAbbr[r.abbr]
        }
        return null
    }

    companion object {
        fun load(open: (String) -> InputStream): SkyCatalog {
            val stars = readStars(open("sky/stars.bin"))
            val index = HashMap<Int, Int>().also { m -> stars.forEachIndexed { i, s -> m[s.hip] = i } }
            val constellations =
                open("sky/constellations.tsv").bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }.mapIndexed { i, line ->
                    val f = line.split("|")
                    Constellation(i, f[0], f[1], f[2], f[3].toInt(), f[4], f[5], f[6].toInt(), f[7], f[8], Equatorial.deg(f[9].toDouble(), f[10].toDouble()), f.getOrElse(11) { "" })
                }
            val cIndex = constellations.associateBy { it.abbr }
            val segments = ArrayList<Segment>()
            open("sky/lines.tsv").bufferedReader(Charsets.UTF_8).forEachLine { line ->
                val f = line.trim().split("\t")
                if (f.size < 3) return@forEachLine
                val c = cIndex[f[0]] ?: return@forEachLine
                val a = index[f[1].toInt()] ?: return@forEachLine
                val b = index[f[2].toInt()] ?: return@forEachLine
                c.segments += segments.size
                c.stars += a
                c.stars += b
                segments += Segment(c.index, a, b)
            }
            val boundaries =
                open("sky/boundaries.dat").bufferedReader(Charsets.US_ASCII).readLines().filter { it.isNotBlank() }.map { line ->
                    val f = line.trim().split(Regex("\\s+"))
                    BoundaryRow(f[0].toDouble(), f[1].toDouble(), f[2].toDouble(), f[3])
                }
            val cities =
                open("sky/cities.tsv").bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }.map { line ->
                    val f = line.split("|")
                    City(f[0], f[1], f[2], f[3].toDouble(), f[4].toDouble(), f[5])
                }
            return SkyCatalog(stars, segments, constellations, boundaries, cities)
        }

        private fun readStars(input: InputStream): List<Star> {
            val bytes = input.use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = buf.int
            val out = ArrayList<Star>(n)
            repeat(n) {
                val hip = buf.int
                val ra = buf.float.toDouble()
                val dec = buf.float.toDouble()
                val v = buf.short / 100f
                val bvRaw = buf.short
                val ly = buf.float
                val rel = buf.float
                out += Star(hip, Equatorial.deg(ra, dec), v, if (bvRaw.toInt() == 32767) Float.NaN else bvRaw / 1000f, if (ly < 0) Float.NaN else ly, if (rel < 0) Float.NaN else rel)
            }
            return out
        }
    }
}
