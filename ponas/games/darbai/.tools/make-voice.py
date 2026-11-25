#!/usr/bin/env python3
"""Lithuanian prompts with Azure Ona (lt-LT-OnaNeural). Usage: tts.py <game dir>
Reads <game dir>/.tools/voice.json {"file-name": "tekstas"}; makes audio/<file-name>.mp3 for new or changed lines."""
import json, os, subprocess, sys, hashlib, tempfile, time
from xml.sax.saxutils import escape
d = sys.argv[1]
lines = json.load(open(os.path.join(d, '.tools/voice.json')))
os.makedirs(os.path.join(d, 'audio'), exist_ok=True)
stamp_p = os.path.join(d, '.tools/voice.done.json')
done = json.load(open(stamp_p)) if os.path.exists(stamp_p) else {}
URL = 'https://swedencentral.tts.speech.microsoft.com/cognitiveservices/v1'
n = 0
for name, text in lines.items():
    out = os.path.join(d, 'audio', name + '.mp3')
    h = hashlib.sha1(text.encode()).hexdigest()[:10]
    if done.get(name) == h and os.path.exists(out): continue
    ssml = ('<speak version="1.0" xml:lang="lt-LT"><voice name="lt-LT-OnaNeural"><prosody rate="-8%">'
            + escape(text) + '</prosody></voice></speak>')
    tmp = tempfile.mktemp(suffix='.mp3')
    for i in range(5):
        r = subprocess.run(['curl', '-s', '-o', tmp, '-w', '%{http_code}', '-X', 'POST', URL,
            '-H', 'Content-Type: application/ssml+xml', '-H', 'X-Microsoft-OutputFormat: audio-24khz-48kbitrate-mono-mp3',
            '-H', 'Ocp-Apim-Subscription-Key: injected-by-proxy', '--data-binary', ssml], capture_output=True, text=True)
        if r.stdout == '200': break
        time.sleep(2 * (i + 1))
    else:
        sys.exit('TTS failed for ' + name + ': ' + r.stdout)
    # trim silence at both ends, same loudness, small mono file
    subprocess.run(['ffmpeg', '-v', 'error', '-y', '-i', tmp, '-af',
        'silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-45dB,areverse,loudnorm=I=-16:TP=-1.5',
        '-ac', '1', '-ar', '22050', '-c:a', 'libmp3lame', '-b:a', '32k', out], check=True)
    os.remove(tmp)
    done[name] = h; n += 1
json.dump(done, open(stamp_p, 'w'), indent=0, sort_keys=True)
print(f'{n} new clips, {len(lines)} total in {d}/audio')
