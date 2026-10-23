"""Builds the app's offline sky data from the files in tools/data (all public, see README "Data sources").

    python -I tools/resolve_lines.py && python -I tools/make_data.py

Writes app/src/main/assets/sky/:
  stars.bin           every XHIP star with V <= 6.0 plus the fainter line stars, 24 bytes each (little endian):
                      hip i32, ra f32 deg, dec f32 deg (J2000), vmag i16 x100, b-v i16 x1000 (32767 = none),
                      distance f32 light years (-1 = none), parallax relative error f32 (-1 = none)
  lines.tsv           abbr, hip a, hip b: the stick figures (resolve_lines.py)
  constellations.tsv  abbr|en|zh|brightest hip|star en|star zh|best month|blurb zh|blurb en|centre ra|centre dec|bayer code
  boundaries.dat      CDS VI/42 (Roman 1987), unchanged
  cities.tsv          id|zh|en|lat|lon|IANA zone (the 36 cities of the design, coordinates rounded to 0.01 deg)
"""
import math
import os
import shutil
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "data")
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "sky")
LY_PER_PC = 3.2616

CITIES = [
    ("beijing", "北京", "Beijing", 39.90, 116.41, "Asia/Shanghai"),
    ("shanghai", "上海", "Shanghai", 31.23, 121.47, "Asia/Shanghai"),
    ("guangzhou", "广州", "Guangzhou", 23.13, 113.26, "Asia/Shanghai"),
    ("shenzhen", "深圳", "Shenzhen", 22.54, 114.06, "Asia/Shanghai"),
    ("chengdu", "成都", "Chengdu", 30.57, 104.07, "Asia/Shanghai"),
    ("chongqing", "重庆", "Chongqing", 29.56, 106.55, "Asia/Shanghai"),
    ("hangzhou", "杭州", "Hangzhou", 30.27, 120.16, "Asia/Shanghai"),
    ("wuhan", "武汉", "Wuhan", 30.59, 114.31, "Asia/Shanghai"),
    ("xian", "西安", "Xi'an", 34.34, 108.94, "Asia/Shanghai"),
    ("nanjing", "南京", "Nanjing", 32.06, 118.80, "Asia/Shanghai"),
    ("tianjin", "天津", "Tianjin", 39.34, 117.36, "Asia/Shanghai"),
    ("shenyang", "沈阳", "Shenyang", 41.81, 123.43, "Asia/Shanghai"),
    ("harbin", "哈尔滨", "Harbin", 45.80, 126.53, "Asia/Shanghai"),
    ("changchun", "长春", "Changchun", 43.82, 125.32, "Asia/Shanghai"),
    ("kunming", "昆明", "Kunming", 25.04, 102.71, "Asia/Shanghai"),
    ("lhasa", "拉萨", "Lhasa", 29.65, 91.13, "Asia/Shanghai"),
    ("urumqi", "乌鲁木齐", "Urumqi", 43.83, 87.62, "Asia/Shanghai"),
    ("lanzhou", "兰州", "Lanzhou", 36.06, 103.83, "Asia/Shanghai"),
    ("hohhot", "呼和浩特", "Hohhot", 40.84, 111.75, "Asia/Shanghai"),
    ("nanning", "南宁", "Nanning", 22.82, 108.32, "Asia/Shanghai"),
    ("haikou", "海口", "Haikou", 20.04, 110.20, "Asia/Shanghai"),
    ("fuzhou", "福州", "Fuzhou", 26.07, 119.30, "Asia/Shanghai"),
    ("xiamen", "厦门", "Xiamen", 24.48, 118.09, "Asia/Shanghai"),
    ("qingdao", "青岛", "Qingdao", 36.07, 120.38, "Asia/Shanghai"),
    ("tokyo", "东京", "Tokyo", 35.68, 139.69, "Asia/Tokyo"),
    ("seoul", "首尔", "Seoul", 37.57, 126.98, "Asia/Seoul"),
    ("singapore", "新加坡", "Singapore", 1.35, 103.82, "Asia/Singapore"),
    ("sydney", "悉尼", "Sydney", -33.87, 151.21, "Australia/Sydney"),
    ("london", "伦敦", "London", 51.51, -0.13, "Europe/London"),
    ("paris", "巴黎", "Paris", 48.86, 2.35, "Europe/Paris"),
    ("berlin", "柏林", "Berlin", 52.52, 13.40, "Europe/Berlin"),
    ("newyork", "纽约", "New York", 40.71, -74.01, "America/New_York"),
    ("losangeles", "洛杉矶", "Los Angeles", 34.05, -118.24, "America/Los_Angeles"),
    ("sanfrancisco", "旧金山", "San Francisco", 37.77, -122.42, "America/Los_Angeles"),
    ("vancouver", "温哥华", "Vancouver", 49.28, -123.12, "America/Vancouver"),
    ("capetown", "开普敦", "Cape Town", -33.92, 18.42, "Africa/Johannesburg"),
]


def rows(path):
    with open(path, encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            parts = [p.strip() for p in line.rstrip("\n").split("#")[0].split("\t")]
            if parts[0].isdigit():
                yield parts + [""] * (8 - len(parts))


def fnum(s):
    try:
        return float(s)
    except ValueError:
        return None


def main():
    os.makedirs(OUT, exist_ok=True)
    stars = {}
    for name in ("xhip.tsv", "xhip_extra.tsv"):
        for hip, ra, de, v, bv, plx, eplx, dist in (r[:8] for r in rows(os.path.join(DATA, name))):
            plx_v, eplx_v, dist_v = fnum(plx), fnum(eplx), fnum(dist)
            stars[int(hip)] = dict(
                ra=float(ra), de=float(de), v=fnum(v) if fnum(v) is not None else 9.99, bv=fnum(bv),
                ly=dist_v * LY_PER_PC if dist_v else -1.0,
                rel=(eplx_v / plx_v) if (plx_v and plx_v > 0 and eplx_v is not None) else -1.0,
            )
    order = sorted(stars, key=lambda h: stars[h]["v"])
    with open(os.path.join(OUT, "stars.bin"), "wb") as f:
        f.write(struct.pack("<i", len(order)))
        for h in order:
            s = stars[h]
            bv = 32767 if s["bv"] is None else int(round(s["bv"] * 1000))
            f.write(struct.pack("<iffhhff", h, s["ra"], s["de"], int(round(s["v"] * 100)), bv, s["ly"], s["rel"]))

    lines = [l.rstrip("\n").split("\t") for l in open(os.path.join(DATA, "lines_hip.tsv"), encoding="utf-8")]
    shutil.copy(os.path.join(DATA, "lines_hip.tsv"), os.path.join(OUT, "lines.tsv"))
    cross = {}
    home = {}
    for hip, vmag, fl, bayer, cst, hr in (r[:6] for r in rows(os.path.join(DATA, "crossid.tsv"))):
        cross.setdefault(int(hip), set()).update(c for c in (bayer, fl) if c)
        home[int(hip)] = cst

    members = {}
    for cst, a, b in lines:
        members.setdefault(cst, set()).update((int(a), int(b)))
    out = []
    warnings = []
    months = {}
    for line in open(os.path.join(DATA, "constellations.tsv"), encoding="utf-8"):
        if line.startswith("#") or not line.strip():
            continue
        abbr, en, zh, names, bzh, ben = line.rstrip("\n").split("|")
        hs = members[abbr]
        own = [h for h in hs if home.get(h) == abbr] or list(hs)
        bright = min(own, key=lambda h: stars[h]["v"])
        star_en = star_zh = ""
        for item in filter(None, names.split(";")):
            code, n_en, n_zh = item.split(":")
            if code in cross.get(bright, ()):
                star_en, star_zh = n_en, n_zh
        if names and not star_en:
            warnings.append(f"{abbr}: brightest line star HIP {bright} ({cross.get(bright)}, V {stars[bright]['v']}) has no name in the table")
        sx = sum(math.cos(math.radians(stars[h]["ra"])) * math.cos(math.radians(stars[h]["de"])) for h in hs)
        sy = sum(math.sin(math.radians(stars[h]["ra"])) * math.cos(math.radians(stars[h]["de"])) for h in hs)
        sz = sum(math.sin(math.radians(stars[h]["de"])) for h in hs)
        ra_c = math.degrees(math.atan2(sy, sx)) % 360
        de_c = math.degrees(math.atan2(sz, math.hypot(sx, sy)))
        # month in which the centre crosses the meridian at 21:00 local mean time
        day = ((ra_c / 15.0 - 3.66) % 24) / 0.0657
        month = int(day / 30.44) % 12 + 1
        months[abbr] = month
        greek = sorted(c for c in cross.get(bright, ()) if not c.isdigit())
        flam = sorted(c for c in cross.get(bright, ()) if c.isdigit())
        code = greek[0] if greek else (flam[0] if flam else "")
        out.append([abbr, en, zh, str(bright), star_en, star_zh, str(month), bzh, ben, f"{ra_c:.3f}", f"{de_c:.3f}", code])
        if len(bzh) > 30:
            warnings.append(f"{abbr}: zh blurb {len(bzh)} chars")
    with open(os.path.join(OUT, "constellations.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for r in out:
            f.write("|".join(r) + "\n")
    shutil.copy(os.path.join(DATA, "vi42_data.dat"), os.path.join(OUT, "boundaries.dat"))
    with open(os.path.join(OUT, "cities.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for c in CITIES:
            f.write("|".join(str(x) for x in c) + "\n")
    print(f"stars={len(order)} lines={len(lines)} constellations={len(out)} cities={len(CITIES)}")
    print("bytes:", {n: os.path.getsize(os.path.join(OUT, n)) for n in os.listdir(OUT)})
    for w in warnings:
        print("WARN", w)


if __name__ == "__main__":
    main()
