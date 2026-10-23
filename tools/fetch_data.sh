#!/usr/bin/env bash
# Re-fetches the public catalogues in tools/data (queried 2026-10-22, CDS VizieR / CDS FTP / JPL Horizons).
# Then: python -I tools/resolve_lines.py && python -I tools/make_data.py
set -eu
cd "$(dirname "$0")/data"
V="https://vizier.cds.unistra.fr/viz-bin/asu-tsv"
# XHIP (V/137D, Anderson & Francis 2012): every star with V <= 6.0
curl -s -o xhip.tsv "$V?-source=V/137D/XHIP&-out=HIP,RAJ2000,DEJ2000,Vmag,B-V,Plx,e_Plx,Dist&Vmag=%3C%3D6.0&-out.max=unlimited"
# stick-figure stars fainter than 6.0 (Mira); xi UMa has no XHIP row: hand-added from IV/27A + I/239 (see xhip_extra.tsv)
curl -s "$V?-source=V/137D/XHIP&-out=HIP,RAJ2000,DEJ2000,Vmag,B-V,Plx,e_Plx,Dist&HIP=10826,8832" | grep -E "^ *[0-9]" > xhip_extra.new
# HD-DM-GC-HR-HIP-Bayer-Flamsteed cross index (IV/27A, Kostjuk 2002): Bayer / Flamsteed -> HIP
curl -s -o crossid.tsv "$V?-source=IV/27A/catalog&-out=HIP,Vmag,Fl,Bayer,Cst,HR&HIP=%3E0&-out.max=unlimited"
# constellation boundaries for "Jupiter is in Gemini" (VI/42, Roman 1987)
curl -s -o vi42_data.dat "https://cdsarc.cds.unistra.fr/ftp/VI/42/data.dat"
curl -s -o vi42_readme.txt "https://cdsarc.cds.unistra.fr/ftp/VI/42/ReadMe"
curl -s -o xhip_readme.txt "https://cdsarc.cds.unistra.fr/ftp/V/137D/ReadMe"
curl -s -o iv27a_readme.txt "https://cdsarc.cds.unistra.fr/ftp/IV/27A/ReadMe"
echo "fetched; xhip_extra.new holds the re-fetched XHIP rows (merge by hand, keep the xi UMa line)"
