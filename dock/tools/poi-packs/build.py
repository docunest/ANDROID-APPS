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

# Layer order for the web chunks (rows carry the index). Append only — the web app reads it from the manifest.
LAYERS = ["fuel", "rest", "toilets", "water", "grocery", "dump", "aire", "limit", "playground", "picnic", "beach",
          "works", "power", "quarry", "dam", "bridge", "airport", "castle", "viewpoint", "lighthouse"]
CELL = 4  # web cells are 1/4° (~28 km north–south)
WEB_CHUNK_BYTES = 6_000_000  # JSON before gzip (~2 MB gzipped) — small enough for a Vercel function to pass on


def layer_of(t):
    g = t.get
    if g("amenity") == "fuel": return "fuel" if diesel_maybe(t) else None
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


GAS_ONLY = {"cng", "lng", "biogas", "h2", "hydrogen", "electricity"}


def diesel_maybe(t):
    """Owner: "I only care about diesel" — EV chargers are never here (amenity=charging_station isn't
    a fuel layer). Drop stations tagged fuel:diesel=no, and ones whose listed fuels are only CNG/LNG/
    hydrogen/electricity (Dourogás-style gas stations). NOT ones listing only LPG or AdBlue: in Portugal
    334 normal Repsol/Galp/BP stations carry just fuel:lpg=yes — mappers note the extra, not diesel."""
    if t.get("fuel:diesel") == "yes" or t.get("fuel:HGV_diesel") == "yes":
        return True
    if t.get("fuel:diesel") == "no":
        return False
    listed = {k[5:] for k, v in t.items() if k.startswith("fuel:") and v == "yes"}
    return not listed or not listed <= GAS_ONLY


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
    if layer == "fuel" and (t.get("fuel:diesel") == "yes" or t.get("fuel:HGV_diesel") == "yes"):
        bits.append("diesel ✓")  # confirmed in OSM; otherwise unknown (but almost always there)
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


def web_chunks(rows, cc, out_dir):
    """MapPal web app (iPhone, offline): the same rows as gzipped JSON chunks of 1/4° cells, stored per
    cell in IndexedDB and read back by the cells around the phone. Row: [layer index, lat×1e5, lng×1e5,
    name, brand, info, osm id]."""
    import gzip, hashlib, json
    cells = {}
    for osm, layer, lat, lng, name, brand, info in rows:
        key = f"{math.floor(lat * CELL)}_{math.floor(lng * CELL)}"
        cells.setdefault(key, []).append([LAYERS.index(layer), round(lat * 1e5), round(lng * 1e5), name, brand, info, osm])
    os.makedirs(out_dir, exist_ok=True)
    chunks, cur, size = [], {}, 0

    def flush():
        nonlocal cur, size
        if not cur:
            return
        name = f"poi-{cc}-w{len(chunks) + 1}.json.gz"
        body = json.dumps({"v": 1, "cc": cc, "cells": cur}, ensure_ascii=False, separators=(",", ":")).encode()
        gz = gzip.compress(body, 9, mtime=0)
        with open(os.path.join(out_dir, name), "wb") as f:
            f.write(gz)
        chunks.append({"file": name, "bytes": len(gz), "sha256": hashlib.sha256(gz).hexdigest(),
                       "cells": len(cur), "count": sum(len(v) for v in cur.values()),
                       # Which cells are in it — MapPal's Road ahead fetches only the chunks along a route.
                       "cellList": ",".join(cur)})
        cur, size = {}, 0

    for key in sorted(cells):
        n = len(json.dumps(cells[key], ensure_ascii=False, separators=(",", ":")))
        if size and size + n > WEB_CHUNK_BYTES:
            flush()
        cur[key] = cells[key]
        size += n
    flush()
    return chunks


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("src")
    p.add_argument("out")
    p.add_argument("--country", required=True)
    p.add_argument("--source-date", default="")
    p.add_argument("--web-dir", help="also write the web app's JSON chunks here (+ web.json listing them)")
    a = p.parse_args()
    m = build(a.src, a.out, a.country, a.source_date)
    print(m, file=sys.stderr)
    if a.web_dir:
        import json
        rows = sqlite3.connect(a.out).execute("SELECT osm, layer, lat, lng, name, brand, info FROM poi ORDER BY id").fetchall()
        chunks = web_chunks(rows, a.country, a.web_dir)
        with open(os.path.join(a.web_dir, f"{a.country}.web.json"), "w") as f:
            json.dump({"layers": LAYERS, "cell": CELL, "chunks": chunks}, f)
        print(f"web: {len(chunks)} chunks, {sum(c['bytes'] for c in chunks) // 1000} kB", file=sys.stderr)
