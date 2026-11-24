#!/usr/bin/env python3
"""Search Wikimedia Commons for photo candidates per word and draw contact sheets to choose from.
Usage: python3 -I find-images.py <words.json> <outdir>   (writes candidates.json and sheet-N.png)"""
import json, sys, os, io, time, urllib.request, urllib.parse, urllib.error
from PIL import Image, ImageDraw

UA = {"User-Agent": "skaitymas-content-builder/1.0 (kids reading game)"}
API = "https://commons.wikimedia.org/w/api.php"

def get(url):
    last = None
    for attempt in range(5):
        time.sleep(0.6)  # be polite: Commons answers 429 to fast clients
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            last = e
            wait = int(e.headers.get("Retry-After") or 0) or 5 * (attempt + 1)
            time.sleep(min(wait, 60))
        except Exception as e:
            last = e
            time.sleep(3)
    raise last

def search(q, n=6):
    params = {"action": "query", "format": "json", "generator": "search", "gsrsearch": q + " filetype:bitmap",
              "gsrnamespace": "6", "gsrlimit": str(n), "prop": "imageinfo", "iiprop": "url|extmetadata|size",
              "iiurlwidth": "240"}
    data = json.loads(get(API + "?" + urllib.parse.urlencode(params)))
    pages = sorted((data.get("query", {}).get("pages", {}) or {}).values(), key=lambda p: p.get("index", 0))
    out = []
    for p in pages:
        ii = (p.get("imageinfo") or [{}])[0]
        md = ii.get("extmetadata", {})
        val = lambda k: (md.get(k, {}) or {}).get("value", "")
        out.append({"title": p["title"], "thumb": ii.get("thumburl"), "page": ii.get("descriptionurl"),
                    "license": val("LicenseShortName"), "artist": val("Artist"), "w": ii.get("width"), "h": ii.get("height")})
    return out

def main():
    words = json.load(open(sys.argv[1]))
    out = sys.argv[2]; os.makedirs(out, exist_ok=True)
    cands = {}
    for w, q in words.items():
        # Plain-background photos first, then any photo of the thing; up to 6 distinct candidates.
        seen, got = set(), []
        for query in (q + " white background", q):
            for c in search(query):
                if c["thumb"] and c["title"] not in seen:
                    seen.add(c["title"]); got.append(c)
            if len(got) >= 6:
                break
        cands[w] = got[:6]
        print(w, len(cands[w]), flush=True)
    json.dump(cands, open(os.path.join(out, "candidates.json"), "w"), ensure_ascii=False, indent=1)
    names = list(cands)
    per = 8
    for s in range(0, len(names), per):
        chunk = names[s:s + per]
        sheet = Image.new("RGB", (6 * 130 + 90, len(chunk) * 130), "white")
        d = ImageDraw.Draw(sheet)
        for r, w in enumerate(chunk):
            d.text((4, r * 130 + 55), w, fill="black")
            for i, c in enumerate(cands[w][:6]):
                try:
                    im = Image.open(io.BytesIO(get(c["thumb"]))).convert("RGB")
                    im.thumbnail((120, 120))
                    sheet.paste(im, (90 + i * 130, r * 130 + 5))
                    d.text((90 + i * 130 + 2, r * 130 + 2), str(i), fill="red")
                except Exception as e:
                    d.text((90 + i * 130, r * 130 + 50), "err", fill="red")
        sheet.save(os.path.join(out, f"sheet-{s // per + 1}.png"))
    print("done")

main()
