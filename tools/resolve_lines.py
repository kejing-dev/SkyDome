"""Resolve tools/data/lines.txt (Bayer / Flamsteed codes) into HIP pairs and check every segment.

    python -I tools/resolve_lines.py            -> tools/data/lines_hip.tsv + a report on stdout

Inputs (all fetched from CDS VizieR, see tools/fetch_data.sh): data/crossid.tsv (IV/27A, Kostjuk 2002),
data/xhip.tsv (V/137D, Anderson & Francis 2012, V <= 6.0) and data/xhip_extra.tsv (line stars fainter than 6.0).
A code that only exists with a superscript (gam -> gam01 / gam02) resolves to the brighter one and is reported.
"""
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")


def rows(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            if not parts[0].strip().isdigit():
                continue
            yield [p.strip() for p in parts]


def load_cross():
    by_code = {}
    for hip, vmag, fl, bayer, cst, hr in rows(os.path.join(DATA, "crossid.tsv")):
        v = float(vmag) if vmag else 99.0
        for code in filter(None, [bayer, fl]):
            key = (cst, code)
            if key not in by_code or v < by_code[key][1]:
                by_code[key] = (int(hip), v)
    return by_code


def load_stars():
    stars = {}
    for name in ("xhip.tsv", "xhip_extra.tsv"):
        p = os.path.join(DATA, name)
        if not os.path.exists(p):
            continue
        for r in rows(p):
            stars[int(r[0])] = (float(r[1]), float(r[2]), float(r[3]) if r[3] else 99.0)
    return stars


def resolve(by_code, cst, code, notes):
    if ":" in code:
        cst, code = code.split(":")
    if (cst, code) in by_code:
        return by_code[(cst, code)][0]
    alts = sorted((v, h, c) for (k, c), (h, v) in by_code.items() if k == cst and c.rstrip("0123456789") in (code, code + ".") and c != code)
    base = code.rstrip("0123456789")
    if not alts and base != code and (cst, base) in by_code:
        notes.append(f"{cst} {code} -> {base}")
        return by_code[(cst, base)][0]
    if alts:
        notes.append(f"{cst} {code} -> {alts[0][2]} (HIP {alts[0][1]})")
        return alts[0][1]
    return None


def sep(a, b):
    ra1, de1 = map(math.radians, a[:2])
    ra2, de2 = map(math.radians, b[:2])
    c = math.sin(de1) * math.sin(de2) + math.cos(de1) * math.cos(de2) * math.cos(ra1 - ra2)
    return math.degrees(math.acos(max(-1.0, min(1.0, c))))


def main():
    by_code = load_cross()
    stars = load_stars()
    notes, missing, faint, long_, pairs = [], [], set(), [], []
    abbrs = []
    with open(os.path.join(DATA, "lines.txt"), encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            cst, rest = line.split(None, 1)
            abbrs.append(cst)
            for chain in rest.split(","):
                codes = [c.strip() for c in chain.strip().split("-") if c.strip()]
                hips = [resolve(by_code, cst, c, notes) for c in codes]
                for c, h in zip(codes, hips):
                    if h is None:
                        missing.append(f"{cst} {c}")
                    elif h not in stars:
                        faint.add(h)
                for a, b in zip(hips, hips[1:]):
                    if a is None or b is None or a == b:
                        continue
                    pairs.append((cst, a, b))
                    if a in stars and b in stars:
                        d = sep(stars[a], stars[b])
                        if d > 28:
                            long_.append(f"{cst} {a}-{b} {d:.1f} deg")
    print(f"constellations={len(abbrs)} unique={len(set(abbrs))} segments={len(pairs)}")
    print("superscript fallbacks:", *notes, sep="\n  ")
    print("MISSING:", *missing, sep="\n  ")
    print("NOT IN XHIP V<=6 (fetch into xhip_extra.tsv):", ",".join(str(h) for h in sorted(faint)))
    print("LONG SEGMENTS:", *long_, sep="\n  ")
    with open(os.path.join(DATA, "lines_hip.tsv"), "w", encoding="utf-8", newline="\n") as out:
        for cst, a, b in pairs:
            out.write(f"{cst}\t{a}\t{b}\n")


if __name__ == "__main__":
    sys.exit(main())
