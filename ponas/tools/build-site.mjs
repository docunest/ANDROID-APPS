#!/usr/bin/env node
// Builds the GitHub Pages site for Ponas. Node 18+, no dependencies.
//
//   node tools/build-site.mjs              -> ./_site  (then: npx serve _site)
//   node tools/build-site.mjs --out dir
//
// Output:
//   _site/index.html                 game launcher (same look as the tablet home screen)
//   _site/manifest.json              catalog the tablet app reads
//   _site/games/<id>/…               each game, plus ponas.js (from sdk/) and game.json
//
// Every game is validated; any error fails the build so a broken game never reaches the tablet.

import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  readFileSync, readdirSync, statSync, writeFileSync, mkdirSync, existsSync, rmSync, copyFileSync,
} from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const GAMES = join(ROOT, 'games');
const SDK = join(ROOT, 'sdk', 'ponas.js');
const MAX_GAME_BYTES = 2 * 1024 * 1024;
const WARN_GAME_BYTES = 1.5 * 1024 * 1024;
const ID_RE = /^[a-z0-9][a-z0-9-]{0,39}$/;
const PATH_RE = /^[A-Za-z0-9._/-]{1,200}$/;
const COLOR_RE = /^#[0-9A-Fa-f]{6}$/;
// Hosts a game may reach. The app has the same list (GameActivity.ALLOWED_HOSTS), so adding a host
// needs the parent's OK AND a new APK.
const ALLOWED_HOSTS = ['ntfy.sh'];
const RESERVED = ['ponas.js', 'game.json'];

const args = process.argv.slice(2);
const outIdx = args.indexOf('--out');
const OUT = resolve(outIdx >= 0 && args[outIdx + 1] ? args[outIdx + 1] : join(ROOT, '_site'));

const errors = [];
const warnings = [];
const err = (id, msg) => errors.push(`games/${id}: ${msg}`);
const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

function listFiles(dir, prefix = '') {
  const out = [];
  for (const name of readdirSync(dir).sort()) {
    if (name.startsWith('.')) continue;
    const full = join(dir, name);
    const rel = prefix ? `${prefix}/${name}` : name;
    const st = statSync(full);
    if (st.isDirectory()) out.push(...listFiles(full, rel));
    else if (st.isFile()) out.push(rel);
  }
  return out;
}

function pngSize(buf) {
  if (buf.length < 24 || buf.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') return null;
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

function gitTime(...paths) {
  try {
    const out = execFileSync('git', ['log', '-1', '--format=%ct', '--', ...paths], { cwd: ROOT, stdio: ['ignore', 'pipe', 'ignore'] })
      .toString().trim();
    return out ? Number(out) : null;
  } catch { return null; }
}

if (!existsSync(SDK)) { console.error('sdk/ponas.js is missing'); process.exit(1); }
const sdkBuf = readFileSync(SDK);

const games = [];
const dirs = existsSync(GAMES)
  ? readdirSync(GAMES).filter((d) => !d.startsWith('.') && !d.startsWith('_') && statSync(join(GAMES, d)).isDirectory()).sort()
  : [];

for (const id of dirs) {
  const dir = join(GAMES, id);
  if (!ID_RE.test(id)) { err(id, 'folder name must be lowercase letters, digits and dashes'); continue; }
  const metaPath = join(dir, 'meta.json');
  if (!existsSync(metaPath)) { err(id, 'missing meta.json'); continue; }

  let meta;
  try { meta = JSON.parse(readFileSync(metaPath, 'utf8')); } catch (e) { err(id, `meta.json is not valid JSON: ${e.message}`); continue; }

  const title = meta.title;
  const icon = meta.icon ?? 'icon.png';
  const color = meta.color;
  const order = meta.order ?? 100;
  const version = String(meta.version ?? '1');
  const network = meta.network ?? [];
  const enabled = meta.enabled !== false;

  if (typeof title !== 'string' || !title.trim()) err(id, 'meta.title must be a non-empty string');
  if (typeof color !== 'string' || !COLOR_RE.test(color)) err(id, 'meta.color must look like "#FFB300"');
  if (typeof order !== 'number' || !Number.isFinite(order)) err(id, 'meta.order must be a number');
  if (typeof icon !== 'string' || !PATH_RE.test(icon)) err(id, 'meta.icon must be a file name like "icon.png"');
  if (meta.enabled !== undefined && typeof meta.enabled !== 'boolean') err(id, 'meta.enabled must be true or false');
  if (!/^[0-9A-Za-z.\-]{1,20}$/.test(version)) err(id, 'meta.version must be short, like "1" or "1.2"');
  if (!Array.isArray(network) || network.some((h) => !ALLOWED_HOSTS.includes(h))) {
    err(id, `meta.network may only list ${ALLOWED_HOSTS.join(', ')} (ask the parent before adding hosts)`);
  }

  const paths = listFiles(dir).filter((p) => p !== 'meta.json');
  for (const r of RESERVED) if (paths.includes(r)) err(id, `"${r}" is added by the build; don't put it in the game folder`);
  if (!paths.includes('index.html')) err(id, 'missing index.html');
  if (typeof icon === 'string' && !paths.includes(icon)) err(id, `icon "${icon}" not found`);

  const files = [];
  let total = 0;
  const hash = createHash('sha256');
  const addFile = (p, buf) => {
    const digest = sha256(buf);
    total += buf.length;
    files.push({ path: p, size: buf.length, sha256: digest });
    hash.update(`${p}\0${digest}\n`);
  };
  for (const p of paths) {
    if (!PATH_RE.test(p) || p.split('/').some((s) => s === '..' || s === '.' || s === '')) {
      err(id, `file name "${p}" not allowed (use only A-Z a-z 0-9 . _ - /)`);
      continue;
    }
    addFile(p, readFileSync(join(dir, p)));
  }
  addFile('ponas.js', sdkBuf);
  files.sort((a, b) => a.path.localeCompare(b.path));

  if (paths.includes('index.html')) {
    const html = readFileSync(join(dir, 'index.html'), 'utf8');
    if (!/<script[^>]+src=["']ponas\.js["']/.test(html)) err(id, 'index.html must include <script src="ponas.js"></script>');
    if (!/name=["']viewport["']/.test(html)) err(id, 'index.html needs a viewport meta tag');
  }

  if (typeof icon === 'string' && paths.includes(icon)) {
    const s = pngSize(readFileSync(join(dir, icon)));
    if (!s) err(id, `icon "${icon}" is not a PNG`);
    else if (s.w !== 256 || s.h !== 256) err(id, `icon must be 256×256, is ${s.w}×${s.h}`);
  }

  if (total > MAX_GAME_BYTES) err(id, `game is ${(total / 1048576).toFixed(2)} MB, limit is 2 MB`);
  else if (total > WARN_GAME_BYTES) warnings.push(`games/${id}: ${(total / 1048576).toFixed(2)} MB, close to the 2 MB limit`);

  // Offline check: games must not load anything from the internet themselves.
  let usesNet = false;
  for (const p of paths) {
    if (!/\.(html|js|css|json|svg)$/i.test(p)) continue;
    const text = readFileSync(join(dir, p), 'utf8');
    if (/Ponas\.net\b/.test(text)) usesNet = true;
    const m = text.match(/(?:src|href)\s*=\s*["']\s*(?:https?:)?\/\/[^"']+|url\(\s*["']?(?:https?:)?\/\/[^)]+\)|@import\s+["']?(?:https?:)?\/\/|fetch\(\s*["'`]https?:|new\s+(?:WebSocket|EventSource)\(/i);
    if (m) err(id, `${p} reaches the network directly (${m[0].slice(0, 50)}…). Use Ponas.net for multiplayer; everything else must be in the game folder.`);
  }
  if (usesNet && !network.includes('ntfy.sh')) err(id, 'uses Ponas.net, so meta.json needs "network": ["ntfy.sh"]');

  games.push({
    id,
    title: typeof title === 'string' ? title.trim() : id,
    color,
    order,
    icon,
    version,
    enabled,
    network,
    updated: gitTime(`games/${id}`, 'sdk') ?? Math.floor(Date.now() / 1000),
    size: total,
    hash: hash.digest('hex').slice(0, 16),
    files,
  });
}

for (const w of warnings) console.warn(`warning: ${w}`);
if (errors.length) {
  console.error('\nbuild-site failed:\n' + errors.map((e) => `  ✗ ${e}`).join('\n'));
  process.exit(1);
}

games.sort((a, b) => a.order - b.order || a.title.localeCompare(b.title));
const manifest = { version: Math.floor(Date.now() / 1000), games };

// ---- write the site ----
rmSync(OUT, { recursive: true, force: true });
mkdirSync(join(OUT, 'games'), { recursive: true });
for (const g of games) {
  const src = join(GAMES, g.id);
  const dst = join(OUT, 'games', g.id);
  for (const f of g.files) {
    mkdirSync(dirname(join(dst, f.path)), { recursive: true });
    if (f.path === 'ponas.js') writeFileSync(join(dst, f.path), sdkBuf);
    else copyFileSync(join(src, f.path), join(dst, f.path));
  }
  writeFileSync(join(dst, 'game.json'), JSON.stringify(g, null, 2) + '\n');
}
writeFileSync(join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
writeFileSync(join(OUT, 'index.html'), launcherHtml(manifest));
writeFileSync(join(OUT, '.nojekyll'), '');

for (const g of games) {
  console.log(`✓ ${g.id}  "${g.title}"  v${g.version}  ${g.files.length} files, ${(g.size / 1024).toFixed(0)} KB, hash ${g.hash}` +
    `${g.enabled ? '' : '  (disabled)'}${g.network.length ? '  net: ' + g.network.join(',') : ''}`);
}
console.log(`wrote ${OUT} (${games.length} games)`);

function launcherHtml(man) {
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const day = (t) => new Date(t * 1000).toISOString().slice(0, 10);
  const lum = (hex) => {
    const n = parseInt(hex.slice(1), 16);
    return 0.299 * (n >> 16) + 0.587 * ((n >> 8) & 255) + 0.114 * (n & 255);
  };
  const tiles = man.games.map((g) => `
    <a class="tile${g.enabled ? '' : ' off'}" href="games/${g.id}/index.html" style="background:${g.color};color:${lum(g.color) > 186 ? '#3E2723' : '#fff'}">
      <img src="games/${g.id}/${esc(g.icon)}" alt="">
      <span>${esc(g.title)}</span>
      <small>v${esc(g.version)} · ${day(g.updated)}${g.network.length ? ' · 🌐' : ''}${g.enabled ? '' : ' · off'}</small>
    </a>`).join('');
  return `<!doctype html>
<html lang="lt">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Ponas</title>
<link rel="icon" href="data:,">
<style>
  :root { --bg: #FFF6E0; --ink: #5D4037; }
  * { box-sizing: border-box; }
  html, body { margin: 0; background: var(--bg); font-family: system-ui, sans-serif; color: var(--ink);
    -webkit-tap-highlight-color: transparent; }
  header { display: flex; align-items: center; gap: 10px; padding: 14px 16px 4px; }
  header svg { width: 40px; height: 40px; }
  header b { font-size: 22px; }
  .grid { display: grid; gap: 14px; padding: 12px 16px 24px;
    grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); }
  .tile { aspect-ratio: 1; border-radius: 28px; text-decoration: none; display: flex; flex-direction: column;
    align-items: center; justify-content: center; padding: 10px; box-shadow: 0 4px 0 rgba(0,0,0,.15);
    transition: transform .1s; }
  .tile:active { transform: scale(.94); }
  .tile img { width: 62%; height: auto; flex: 0 0 auto; }
  .tile span { font-weight: 700; font-size: 17px; margin-top: 4px; text-shadow: 0 1px 2px rgba(0,0,0,.25); }
  .tile small { font-size: 11px; opacity: .85; margin-top: 2px; }
  .tile.off { filter: grayscale(.8); opacity: .6; }
  footer { padding: 0 16px 24px; font-size: 12px; opacity: .7; }
  footer a { color: inherit; }
</style>
</head>
<body>
<header>
  <svg viewBox="0 0 108 108" aria-hidden="true"><circle cx="54" cy="54" r="54" fill="#FFB300"/>
    <circle cx="54" cy="60" r="20" fill="#fff"/><circle cx="46" cy="58" r="3" fill="#3E2723"/><circle cx="62" cy="58" r="3" fill="#3E2723"/>
    <path d="M45 67Q54 75 63 67" stroke="#3E2723" stroke-width="3" fill="none" stroke-linecap="round"/>
    <rect x="30" y="40" width="48" height="6" fill="#3E2723"/><rect x="40" y="23" width="28" height="18" fill="#3E2723"/>
    <rect x="40" y="34" width="28" height="5" fill="#E53935"/></svg>
  <b>Ponas</b>
</header>
<main class="grid">${tiles}
</main>
<footer>Catalog ${day(man.version)} · ${man.games.length} games · <a href="manifest.json">manifest.json</a></footer>
</body>
</html>
`;
}
