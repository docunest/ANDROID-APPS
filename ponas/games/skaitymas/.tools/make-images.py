#!/usr/bin/env python3
"""Downloads the chosen Commons photo per word into img/<fileKey>.jpg (240x240, photo fitted on white)
and writes img/credits.json (title, author, licence, source page) — the licence/attribution record.
Usage: python3 -I make-images.py <candidates.json> <choices.json>
choices.json: {"word": index into that word's candidates}."""
import json, os, re, sys, time, io, html, urllib.request, urllib.error
from PIL import Image

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UA = {"User-Agent": "skaitymas-content-builder/1.0 (kids reading game)"}

def key(s):
    return "".join(c if ord(c) < 128 else "u%04x" % ord(c) for c in s)

def get(url):
    last = None
    for attempt in range(5):
        time.sleep(0.6)
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            last = e
            time.sleep(min(int(e.headers.get("Retry-After") or 0) or 5 * (attempt + 1), 60))
        except Exception as e:
            last = e
            time.sleep(3)
    raise last

def plain(s):
    return html.unescape(re.sub(r"<[^>]+>", "", s or "")).strip()[:120]

cands = json.load(open(sys.argv[1]))
choices = json.load(open(sys.argv[2]))
out = os.path.join(HERE, "img")
os.makedirs(out, exist_ok=True)
credits = {}
for word, idx in choices.items():
    c = cands[word][idx]
    im = Image.open(io.BytesIO(get(c["thumb"]))).convert("RGB")
    im.thumbnail((240, 240), Image.LANCZOS)
    sq = Image.new("RGB", (240, 240), "white")
    sq.paste(im, ((240 - im.width) // 2, (240 - im.height) // 2))
    sq.save(os.path.join(out, key(word) + ".jpg"), "JPEG", quality=72, optimize=True, progressive=True)
    credits[word] = {"file": key(word) + ".jpg", "title": c["title"], "artist": plain(c["artist"]),
                     "license": plain(c["license"]), "page": c["page"]}
    print(word, "ok", flush=True)
json.dump(credits, open(os.path.join(out, "credits.json"), "w"), ensure_ascii=False, indent=1)
print(len(credits), "images")
