import json, math
from shapely.geometry import shape, box, Polygon, MultiPolygon
from shapely.ops import unary_union
d = json.load(open('ne50.geojson'))   # ne_50m_admin_0_countries.geojson from github.com/nvkelso/natural-earth-vector (geojson/)
F = {f['properties']['ADM0_A3']: shape(f['geometry']) for f in d['features']}
LON0, LON1, LAT0, LAT1 = -10.4, 4.8, 35.4, 44.2
K = math.cos(math.radians(40))
W = 1000; S = W / ((LON1 - LON0) * K); H = round((LAT1 - LAT0) * S)
def proj(lon, lat): return ((lon - LON0) * K * S, (LAT1 - lat) * S)
clip = box(LON0, LAT0, LON1, LAT1)
def keep(geom, test):
    polys = list(geom.geoms) if isinstance(geom, MultiPolygon) else [geom]
    return MultiPolygon([p for p in polys if test(p.representative_point())])
esp = keep(F['ESP'], lambda c: c.y > 36.0 or (c.x > -6 and c.y > 35.95))   # mainland + Balearics, not Canaries/Ceuta/Melilla
prt = keep(F['PRT'], lambda c: c.x > -10)                               # mainland only
def path(geom, tol=0.012):
    geom = geom.intersection(clip).simplify(tol, preserve_topology=True)
    polys = list(geom.geoms) if hasattr(geom, 'geoms') else [geom]
    out = []
    for p in polys:
        if not isinstance(p, Polygon) or p.area < 0.002: continue
        pts = [proj(x, y) for x, y in p.exterior.coords]
        out.append('M' + 'L'.join('%.1f,%.1f' % q for q in pts[:-1]) + 'Z')
    return ''.join(out)
def bbox(geom):
    a, b, c, e = geom.bounds; x0, y0 = proj(a, e); x1, y1 = proj(c, b)
    return [round(x0, 1), round(y0, 1), round(x1, 1), round(y1, 1)]
others = unary_union([F[k] for k in ('FRA', 'AND', 'MAR', 'DZA')])
CITIES = {  # lon, lat (Wikipedia coordinates of each city centre)
 'lisabona': (-9.139, 38.722), 'portas': (-8.611, 41.150), 'madridas': (-3.704, 40.417),
 'barselona': (2.177, 41.383), 'sevilija': (-5.984, 37.389), 'santjagas': (-8.545, 42.880)}
out = {'w': W, 'h': H,
  'pieces': {'ispanija': {'d': path(esp), 'box': bbox(esp)}, 'portugalija': {'d': path(prt), 'box': bbox(prt)}},
  'others': path(others), 'cities': {k: [round(c, 1) for c in proj(*v)] for k, v in CITIES.items()}}
open('../mapdata.js', 'w').write('/* Spain and Portugal (mainland + Balearics) from Natural Earth 1:50m admin-0 (public domain),\n * equirectangular projection at 40°N, simplified. Made by .tools/make-map.py. */\nwindow.MAP = ' + json.dumps(out) + ';\n')
import os; print(W, H, os.path.getsize('mapdata.js'), len(out['pieces']['ispanija']['d']), len(out['pieces']['portugalija']['d']))
