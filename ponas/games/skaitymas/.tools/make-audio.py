#!/usr/bin/env python3
"""Pre-generates every clip the game needs from data.js. No API key: offline voices only.
  Mbrola lt1 (via espeak-ng) for syllables, words, letter groups and prompts;
  espeak-ng lt phonemes for single letter sounds ("mmm", short "bə").
Needs: espeak-ng mbrola mbrola-lt1 ffmpeg node.   Run: python3 -I .tools/make-audio.py   (from games/skaitymas)
Any clip can later be replaced by a parent recording with the same file name."""
import json, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = json.loads(subprocess.check_output(["node", "-e",
    "require(process.argv[2]); require(process.argv[1]); const d=globalThis.SK_DATA; console.log(JSON.stringify({syl:d.syllables,words:d.pictureWords,"
    "alphabet:d.alphabet,prompts:d.prompts,groups:d.phonograms}))", os.path.join(HERE, "data.js"), os.path.join(HERE, "words.js")]))

def key(s):
    return "".join(c if ord(c) < 128 else "u%04x" % ord(c) for c in s)

# Letter sounds (not letter names): sustained continuants, plosives with a short weak vowel.
LETTER_PH = {"a": "a", "ą": "a:", "b": "b@", "c": "ts@", "č": "tS@", "d": "d@", "e": "e", "ę": "e:", "ė": "ee",
             "f": "f:", "g": "g@", "h": "h@", "i": "i", "į": "i:", "y": "i:", "j": "j:", "k": "k@", "l": "l:",
             "m": "m:", "n": "n:", "o": "o:", "p": "p@", "r": "r:", "s": "s:", "š": "S:", "t": "t@", "u": "u",
             "ų": "u:", "ū": "u:", "v": "v:", "z": "z:", "ž": "Z:"}
GROUP_PH = {"dž": "dZ@", "ch": "x@"}

out_dir = os.path.join(HERE, "audio")
shutil.rmtree(out_dir, ignore_errors=True)   # start clean: no clips left over from removed words
os.makedirs(out_dir, exist_ok=True)
tmp = tempfile.mkdtemp()

def make(name, text, voice, speed):
    wav = os.path.join(tmp, "x.wav")
    subprocess.run(["espeak-ng", "-v", voice, "-s", str(speed), "-w", wav, text], check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    mp3 = os.path.join(out_dir, name + ".mp3")
    # Same loudness for every clip, mono 22 kHz, small files (the whole game must stay under 2 MB).
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", wav, "-af", "loudnorm=I=-16:TP=-1.5,apad=pad_dur=0.05",
                    "-ac", "1", "-ar", "22050", "-codec:a", "libmp3lame", "-b:a", "24k", mp3], check=True)

n = 0
for s in DATA["syl"]:
    make("syl-" + key(s), s, "mb-lt1", 110); n += 1
for w in DATA["words"]:
    make("word-" + key(w), w, "mb-lt1", 105); n += 1
for ch in DATA["alphabet"]:
    make("letter-" + key(ch), "[[" + LETTER_PH[ch] + "]]", "lt", 90); n += 1
for g in DATA["groups"]:
    if g in GROUP_PH:
        make("group-" + key(g), "[[" + GROUP_PH[g] + "]]", "lt", 90)
    else:
        make("group-" + key(g), g, "mb-lt1", 110)
    n += 1
for k, text in DATA["prompts"].items():
    make("prompt-" + k, text, "mb-lt1", 120); n += 1
print(f"{n} clips in audio/")
