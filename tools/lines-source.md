# Constellation lines: source and check record (design dataModel.lines, needsCheck N7)

**What:** 88 stick figures, 571 segments (`tools/data/lines.txt`, Bayer / Flamsteed codes; `lines_hip.tsv`, HIP pairs;
shipped as `app/src/main/assets/sky/lines.tsv`).

**How they were made (2026-10-22, build station):** the IAU publishes no line data file. The figures were recorded by
hand, constellation by constellation, following the shapes of the IAU constellation charts (IAU and Sky & Telescope,
Roger Sinnott and Rick Fienberg; the figures on those charts are Alan MacRobert's). Stars are named by their Bayer
letter or Flamsteed number and resolved to Hipparcos numbers with the CDS cross index IV/27A (Kostjuk 2002) by
`tools/resolve_lines.py`. Nothing was copied from Stellarium, d3-celestial or HYG data files.

A few faint constellations are simplified (Lac, LMi, Lyn, Cam, Mon, Nor, Pup, Vel, Hya's tail, Eri's middle): they keep
the recognisable chain, not every star of the chart.

**Checks:**
- every code resolves to a Hipparcos star in XHIP (`resolve_lines.py` prints MISSING: empty);
- no segment is longer than 28° (prints LONG SEGMENTS: empty);
- the 88 figures were drawn on one sheet and looked at one by one (`resolve_lines.py` output, contact sheet in the
  build station's scratchpad): Orion, the Big Dipper, Cassiopeia, Cygnus, Scorpius, Leo, Crux, Pegasus' square read
  as expected;
- on the emulator (2027-01-15 21:00 Beijing) the lit figure of Orion sits on its stars.

**Licence:** a set of "which star connects to which" pairs is a fact, recorded here by the build station. The IAU charts
it was checked against are distributed with attribution "IAU and Sky & Telescope" (the IAU Office of Astronomy for
Education's adapted charts carry CC BY 4.0). The app's About section credits them. Open for the user (gate B item N7):
confirm this attribution is enough for a product, or name another figure set.
