// Turns `expo export` output into something Expo Go can open from plain static hosting
// (GitHub Pages): one Expo Updates manifest per platform (ios.json / android.json) that points at
// the exported Hermes bundle and lists every asset, plus an install page with QR codes.
//
// Usage: PUBLIC_URL=https://docunest.github.io/ANDROID-APPS/reelbox/app node scripts/publish-pages.mjs [distDir]
// Open in Expo Go: exps://docunest.github.io/ANDROID-APPS/reelbox/app/ios.json
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { copyFileSync, readFileSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = process.argv[2] || join(root, 'dist');
const base = (process.env.PUBLIC_URL || '').replace(/\/+$/, '');
if (!/^https:\/\//.test(base)) throw new Error('Set PUBLIC_URL to the https address the dist folder will be served from.');

const meta = JSON.parse(readFileSync(join(dist, 'metadata.json'), 'utf8'));
const exp = JSON.parse(execFileSync('npx', ['expo', 'config', '--json', '--type', 'public'], { cwd: root, encoding: 'utf8' }));
const sdk = exp.sdkVersion;
if (!sdk) throw new Error('expo config has no sdkVersion');

const sha256url = (buf) => createHash('sha256').update(buf).digest('base64url');
const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp', svg: 'image/svg+xml', ttf: 'font/ttf', otf: 'font/otf', xml: 'application/xml', json: 'application/json', mp4: 'video/mp4' };

copyFileSync(join(root, 'assets/icon.png'), join(dist, 'icon.png'));

const urls = {};
for (const platform of ['ios', 'android']) {
  const fm = meta.fileMetadata[platform];
  if (!fm) continue;
  const bundle = readFileSync(join(dist, fm.bundle));
  const bundleHash = sha256url(bundle);
  const manifest = {
    // A stable id per bundle: the same build always gets the same id.
    id: [...createHash('sha256').update(platform + bundleHash).digest('hex').slice(0, 32)]
      .map((c, i) => ([8, 12, 16, 20].includes(i) ? `-${c}` : c))
      .join(''),
    createdAt: new Date().toISOString(),
    runtimeVersion: `exposdk:${sdk}`,
    launchAsset: { key: `bundle-${bundleHash.slice(0, 16)}`, hash: bundleHash, contentType: 'application/javascript', url: `${base}/${fm.bundle}` },
    assets: fm.assets.map((a) => {
      const buf = readFileSync(join(dist, a.path));
      return {
        hash: sha256url(buf),
        key: a.path.split('/').pop(), // md5 — what the bundle's asset registry refers to
        contentType: MIME[a.ext] ?? 'application/octet-stream',
        fileExtension: `.${a.ext}`,
        url: `${base}/${a.path}`,
      };
    }),
    metadata: {},
    extra: {
      expoClient: { ...exp, iconUrl: `${base}/icon.png`, hostUri: base.replace(/^https:\/\//, '') },
      scopeKey: `@anonymous/${exp.slug}-reelbox`,
      eas: {},
    },
  };
  writeFileSync(join(dist, `${platform}.json`), JSON.stringify(manifest));
  urls[platform] = `exps://${base.replace(/^https:\/\//, '')}/${platform}.json`;
  console.log(`${platform}: ${urls[platform]}  (${fm.assets.length} assets, bundle ${(bundle.length / 1e6).toFixed(1)} MB)`);
}

const page = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Reelbox app</title>
<style>
:root{--bg:#0d0f14;--bg2:#151821;--line:#2a2f40;--text:#eef0f6;--muted:#9aa1b5;--accent:#ffb020}
body{margin:0;background:var(--bg);color:var(--text);font:16px/1.5 -apple-system,system-ui,sans-serif}
main{max-width:640px;margin:0 auto;padding:24px 16px 48px}
h1{margin:0 0 4px}p{color:var(--muted)}ol{padding-left:22px}li{margin:10px 0}
.card{background:var(--bg2);border:1px solid var(--line);border-radius:12px;padding:16px;margin:16px 0}
a.btn{display:block;text-align:center;background:var(--accent);color:#1a1200;font-weight:700;text-decoration:none;padding:14px;border-radius:10px;margin:12px 0}
a{color:var(--accent)}code{word-break:break-all;color:var(--text)}
#qr{display:none;background:#fff;padding:12px;border-radius:12px;width:max-content;margin:12px auto}
</style></head><body><main>
<h1>Reelbox for iPhone &amp; Android</h1>
<p>The native app runs inside <b>Expo Go</b> (free, from the App Store / Play Store). No Apple developer account, no sideloading — and unlike the web app it can talk to any Xtream server.</p>
<div class="card"><ol>
<li>Install <b>Expo Go</b>: <a href="https://apps.apple.com/app/expo-go/id982107779">App Store</a> · <a href="https://play.google.com/store/apps/details?id=host.exp.exponent">Google Play</a>.</li>
<li>On the phone, tap the button below (or scan the QR code with the Camera app).</li>
<li>Reelbox opens in Expo Go. Next time, it's listed on Expo Go's home screen.</li>
</ol>
<a class="btn" id="open" href="${urls.ios}">Open Reelbox in Expo Go</a>
<div id="qr"></div>
<p style="font-size:14px">iPhone: <code>${urls.ios}</code><br>Android: <code>${urls.android ?? ''}</code></p>
</div>
<p style="font-size:14px">Updates arrive automatically: the app checks this page's version each time it starts.</p>
</main>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcode-generator/1.4.4/qrcode.min.js"></script>
<script>
var urls=${JSON.stringify(urls)};
var url=/Android/i.test(navigator.userAgent)?urls.android:urls.ios;
document.getElementById('open').href=url;
if(!/iPhone|iPad|Android/i.test(navigator.userAgent)&&window.qrcode){var q=qrcode(0,'M');q.addData(urls.ios);q.make();var el=document.getElementById('qr');el.innerHTML=q.createSvgTag(6,0)+'<div style="color:#000;text-align:center;font-size:13px">Scan with iPhone camera</div>';el.style.display='block';}
</script>
</body></html>`;
writeFileSync(join(dist, 'index.html'), page);
console.log('wrote', join(dist, 'index.html'));
