#!/usr/bin/env python3
"""Dock delta updates: make (and check) a patch from one APK to the next.

    python3 delta.py make OLD.apk NEW.apk OUT.patch
    python3 delta.py apply OLD.apk PATCH OUT.apk      # same steps as the app

The head unit downloads the patch instead of the whole APK and rebuilds the
exact new APK bytes from the APK it has installed (Updater.kt, DeltaPatch.kt).
Dex is stored uncompressed in the APK (build.gradle.kts), so a small code
change is a small binary diff (bsdiff).

Format "DKP1" (all integers signed 64-bit big-endian):
    "DKP1" | sha256(old) | sha256(new) | len(new) | zlib stream of records
    record = diff_len, extra_len, seek, diff_len bytes added (mod 256) to the
             old bytes, extra_len bytes copied as they are; then the old
             position moves by seek (may be negative).
Needs: pip install bsdiff4==1.4.4 (make only).
"""
import hashlib
import struct
import sys
import zlib

MAGIC = b"DKP1"


def make(old: bytes, new: bytes) -> bytes:
    import bsdiff4.core as core
    import bsdiff4.format as fmt

    control, diff, extra = core.diff(old, new)
    body = bytearray()
    d = e = 0
    for x, y, z in fmt.unpack_control_block(control):
        body += struct.pack(">qqq", x, y, z) + diff[d:d + x] + extra[e:e + y]
        d += x
        e += y
    head = MAGIC + hashlib.sha256(old).digest() + hashlib.sha256(new).digest() + struct.pack(">q", len(new))
    return head + zlib.compress(bytes(body), 9)


def apply(old: bytes, patch: bytes) -> bytes:
    if patch[:4] != MAGIC:
        raise ValueError("not a DKP1 patch")
    old_sha, new_sha = patch[4:36], patch[36:68]
    (new_len,) = struct.unpack(">q", patch[68:76])
    if hashlib.sha256(old).digest() != old_sha:
        raise ValueError("patch is for a different old APK")
    body = zlib.decompress(patch[76:])
    out = bytearray()
    i = old_pos = 0
    while len(out) < new_len:
        x, y, z = struct.unpack(">qqq", body[i:i + 24])
        i += 24
        for k in range(x):
            o = old_pos + k
            out.append((body[i + k] + (old[o] if 0 <= o < len(old) else 0)) & 0xFF)
        i += x
        out += body[i:i + y]
        i += y
        old_pos += x + z
    if len(out) != new_len or hashlib.sha256(out).digest() != new_sha:
        raise ValueError("rebuilt APK doesn't match")
    return bytes(out)


def main(argv):
    if len(argv) != 5 or argv[1] not in ("make", "apply"):
        sys.exit(__doc__)
    a = open(argv[2], "rb").read()
    b = open(argv[3], "rb").read()
    if argv[1] == "make":
        p = make(a, b)
        if apply(a, p) != b:
            sys.exit("self-check failed")
        open(argv[4], "wb").write(p)
        print(f"{argv[4]}: {len(p)} bytes ({len(b)} bytes full)")
    else:
        open(argv[4], "wb").write(apply(a, b))


if __name__ == "__main__":
    main(sys.argv)
