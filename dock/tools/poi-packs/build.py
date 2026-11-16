#!/usr/bin/env python3
"""Offline POI pack for the Dock (one country): OSM extract → small SQLite file.

The same layers as MapPal's Road ahead (MapPal lib/dock/road.ts ROAD_LAYERS / layerOf / infoOf),
so the car finds the nearest fuel, rest area, camper service … with no signal at all.

    osmium tags-filter <country>-latest.osm.pbf $(cat filter.txt) -o small.osm.pbf
    python3 build.py small.osm.pbf poi-<cc>.sqlite --country <cc> --source-date <YYYY-MM-DD>

Table poi(osm, layer, lat, lng, name, brand, info), indexed by (layer, lat) — the Dock asks
"layer X within this box" and sorts by distance itself. No R-tree: not every Android SQLite has it.
"""
import argparse
import math
import os
import re
import sqlite3
import sys
import time

import osmium

VERSION = 1


def layer_of(t):
    g = t.get
    if g("amenity") == "fuel": return "fuel"
    if g("highway") in ("rest_area", "services"): return "rest"
    if g("amenity") == "sanitary_dump_station": return "dump"
    if g("tourism") in ("caravan_site", "camp_site"): return "aire"
    if g("highway") and (g("maxheight") or g("maxweight")): return "limit"
    if g("amenity") == "toilets": return "toilets"
    if g("amenity") == "drinking_water": return "water"
    if g("shop") in ("supermarket", "convenience"): return "grocery"
    if g("leisure") == "playground": return "playground"
    if g("tourism") == "picnic_site": return "picnic"
    if g("natural") == "beach" and g("name"): return "beach"
    if g("man_made") == "works" and g("name"): return "works"
    if g("power") == "plant": return "power"
    if g("landuse") == "quarry" and g("name"): return "quarry"
    if g("waterway") == "dam" and g("name"): return "dam"
    if g("bridge") and g("bridge") != "no" and g("name"): return "bridge"
    if g("aeroway") == "aerodrome" and g("name") and g("iata"): return "airport"
    if g("historic") == "castle" and g("name"): return "castle"
    if g("tourism") == "viewpoint" and g("name"): return "viewpoint"
    if g("man_made") == "lighthouse": return "lighthouse"
    return None


def num(v):
    """"3.5", "3,5 m", "3.5 t" → 3.5; "default", "none", feet → None."""
    m = re.match(r"\s*(\d+(?:[.,]\d+)?)\s*(m|t)?\s*$", v or "")
    return float(m.group(1).replace(",", ".")) if m else None


def limit_matters(t):
    """Only limits a camper van cares about: under 4 m high or 12 t and lighter."""
    h, w = num(t.get("maxheight", "")), num(t.get("maxweight", ""))
    return (h is not None and h < 4.0) or (w is not None and w <= 12)


def info_of(layer, t):
    bits = []
    if layer == "fuel":
        if t.get("fuel:diesel") == "yes": bits.append("diesel")
        if t.get("fuel:lpg") == "yes": bits.append("LPG")
        if t.get("fuel:adblue") == "yes": bits.append("AdBlue")
    if layer == "limit":
        h, w = t.get("maxheight", ""), t.get("maxweight", "")
        if num(h) is not None and num(h) < 4.0: bits.append(f"height {h}" + (" m" if re.fullmatch(r"[\d.]+", h) else ""))
        if num(w) is not None and num(w) <= 12: bits.append(f"weight {w}" + (" t" if re.fullmatch(r"[\d.]+", w) else ""))
    if layer == "power" and t.get("plant:source"): bits.append(t["plant:source"])
    if layer == "rest" and t.get("highway") == "services": bits.append("services")
    if layer == "aire" and t.get("tourism") == "caravan_site": bits.append("aire")
    if t.get("opening_hours") == "24/7": bits.append("24/7")
    return " · ".join(bits) or None


def name_of(t):
    return t.get("name") or t.get("name:en") or t.get("brand")


class Collect(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.rows = []
        self.limit_seen = set()

    def add(self, osm, t, lat, lng):
        layer = layer_of(t)
        if not layer or lat is None:
            return
        if layer == "limit":
            if not limit_matters(t):
                return
            # A road is many ways with the same sign: keep one per ~300 m and value.
            key = (round(lat * 300), round(lng * 300), t.get("maxheight"), t.get("maxweight"))
            if key in self.limit_seen:
                return
            self.limit_seen.add(key)
        self.rows.append((osm, layer, round(lat, 6), round(lng, 6), name_of(t),
                          t.get("brand") or t.get("operator"), info_of(layer, t)))

    def node(self, n):
        if n.tags and n.location.valid():
            self.add(f"n{n.id}", dict(n.tags), n.location.lat, n.location.lon)

    def way(self, w):
        if not w.tags:
            return
        t = dict(w.tags)
        if not layer_of(t):
            return
        pts = [(n.lat, n.lon) for n in w.nodes if n.location.valid()]
        if not pts:
            return
        closed = len(pts) > 3 and w.nodes[0].ref == w.nodes[-1].ref
        if closed:
            ring = pts[:-1]
            lat, lng = sum(p[0] for p in ring) / len(ring), sum(p[1] for p in ring) / len(ring)
        else:
            # A line (road with a limit, bridge): a point that is really on it.
            lat, lng = pts[len(pts) // 2]
        self.add(f"w{w.id}", t, lat, lng)

    def area(self, a):
        if a.from_way():  # closed ways were done in way()
            return
        t = dict(a.tags)
        if not layer_of(t):
            return
        pts = [(n.lat, n.lon) for ring in a.outer_rings() for n in ring if n.location.valid()]
        if pts:
            self.add(f"r{a.orig_id()}", t, sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts))


def build(src, out, country, source_date):
    h = Collect()
    h.apply_file(src, locations=True)
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
        PRAGMA page_size = 4096;
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
        CREATE TABLE poi(id INTEGER PRIMARY KEY, osm TEXT, layer TEXT NOT NULL, lat REAL NOT NULL,
                         lng REAL NOT NULL, name TEXT, brand TEXT, info TEXT);
    """)
    # Sorted by layer then latitude: the (layer, lat) range reads neighbouring pages.
    h.rows.sort(key=lambda r: (r[1], r[2]))
    db.executemany("INSERT INTO poi(osm, layer, lat, lng, name, brand, info) VALUES (?,?,?,?,?,?,?)", h.rows)
    db.execute("CREATE INDEX poi_layer_lat ON poi(layer, lat)")
    counts = {}
    for r in h.rows:
        counts[r[1]] = counts.get(r[1], 0) + 1
    meta = {"version": VERSION, "country": country, "source": "OpenStreetMap via Geofabrik (ODbL)",
            "sourceDate": source_date, "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
            "count": len(h.rows), "layers": ",".join(f"{k}:{v}" for k, v in sorted(counts.items()))}
    db.executemany("INSERT INTO meta VALUES (?, ?)", [(k, str(v)) for k, v in meta.items()])
    db.commit()
    db.execute("VACUUM")
    db.close()
    return meta


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("src")
    p.add_argument("out")
    p.add_argument("--country", required=True)
    p.add_argument("--source-date", default="")
    a = p.parse_args()
    m = build(a.src, a.out, a.country, a.source_date)
    print(m, file=sys.stderr)
