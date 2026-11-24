#!/usr/bin/env bash
# Pre-generates the Lithuanian syllable clips for skaitymas. No API key needed:
# both voices are offline (espeak-ng "lt" and Mbrola "mb-lt1"). Run once, commit audio/.
#   sudo apt-get install -y espeak-ng mbrola mbrola-lt1 ffmpeg
#   bash make-audio.sh
set -euo pipefail
cd "$(dirname "$0")"
SYL="ma na la ra sa ša pa ba da ta ka ga"
for voice in mb-lt1 lt; do
  out="audio"; [ "$voice" = lt ] && out="audio/lt"; mkdir -p "$out"
  for s in $SYL; do
    espeak-ng -v "$voice" -s 115 -w "/tmp/skaitymas-$s-$voice.wav" "$s"
    f="${s//š/sh}"   # file names are ASCII only
    ffmpeg -loglevel error -y -i "/tmp/skaitymas-$s-$voice.wav" -ac 1 -codec:a libmp3lame -b:a 32k "$out/$f.mp3"
    rm -f "/tmp/skaitymas-$s-$voice.wav"
  done
done
echo "clips in audio/ (mb-lt1) and audio/lt/ (espeak)"
