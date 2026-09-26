#!/usr/bin/env node
// Builds manifest.json for the Ponas tablet app from games/*/meta.json.
// No dependencies. Node 18+.
//
//   node tools/build-manifest.mjs                       -> writes ./manifest.json
//   node tools/build-manifest.mjs --out _site/manifest.json --index _site/index.html
//
// Fails (exit 1) on any rule violation so a broken game never reaches the tablet.

import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const GAMES = join(ROOT, 'games');
const MAX_GAME_BYTES = 2 * 1024 * 1024;
const WARN_GAME_BYTES = 1.5 * 1024 * 1024;
const ID_RE = /^[a-z0-9][a-z0-9-]{0,39}$/;
const PATH_RE = /^[A-Za-z0-9._/-]{1,200}$/;
const COLOR_RE = /^#[0-9A-Fa-f]{6}$/;

const args = process.argv.slice(2);
function arg(name, def) {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : def;
}
const outFile = resolve(arg('--out', join(ROOT, 'manifest.json')));
const indexFile = arg('--index', null);

const errors = [];
const warnings = [];
const err = (id, msg) => errors.push(`games/${id}: ${msg}`);

function sha256(buf) {
  return createHash('sha256').update(buf).digest('hex');
}

function listFiles(dir, prefix = '') {
  const out = [];
  for (const name of readdirSync(dir).sort()) {
    if (name.startsWith('.')) continue; // .DS_Store, .gitkeep, …
    const full = join(dir, name);
    const rel = prefix ? `${prefix}/${name}` : name;
    const st = statSync(full);
    if (st.isDirectory()) out.push(...listFiles(full, rel));
    else if (st.isFile()) out.push(rel);
  }
  return out;
}

function pngSize(buf) {
  const sig = '89504e470d0a1a0a';
  if (buf.length < 24 || buf.subarray(0, 8).toString('hex') !== sig) return null;
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

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

  const enabled = meta.enabled !== false;
  const title = meta.title;
  const icon = meta.icon ?? 'icon.png';
  const color = meta.color;
  const order = meta.order ?? 100;

  if (typeof title !== 'string' || !title.trim()) err(id, 'meta.title must be a non-empty string');
  if (typeof color !== 'string' || !COLOR_RE.test(color)) err(id, 'meta.color must look like "#FFB300"');
  if (typeof order !== 'number' || !Number.isFinite(order)) err(id, 'meta.order must be a number');
  if (typeof icon !== 'string' || !PATH_RE.test(icon)) err(id, 'meta.icon must be a file name like "icon.png"');
  if (meta.enabled !== undefined && typeof meta.enabled !== 'boolean') err(id, 'meta.enabled must be true or false');

  const paths = listFiles(dir).filter((p) => p !== 'meta.json');
  if (!paths.includes('index.html')) err(id, 'missing index.html');
  if (typeof icon === 'string' && !paths.includes(icon)) err(id, `icon "${icon}" not found`);

  const files = [];
  let total = 0;
  const hash = createHash('sha256');
  for (const p of paths) {
    if (!PATH_RE.test(p) || p.split('/').some((s) => s === '..' || s === '.' || s === '')) {
      err(id, `file name "${p}" not allowed (use only A-Z a-z 0-9 . _ - /)`);
      continue;
    }
    const buf = readFileSync(join(dir, p));
    const digest = sha256(buf);
    total += buf.length;
    files.push({ path: p, size: buf.length, sha256: digest });
    hash.update(`${p}\0${digest}\n`);
  }

  if (typeof icon === 'string' && paths.includes(icon)) {
    const s = pngSize(readFileSync(join(dir, icon)));
    if (!s) err(id, `icon "${icon}" is not a PNG`);
    else if (s.w !== 256 || s.h !== 256) err(id, `icon must be 256×256, is ${s.w}×${s.h}`);
  }

  if (total > MAX_GAME_BYTES) err(id, `game is ${(total / 1048576).toFixed(2)} MB, limit is 2 MB`);
  else if (total > WARN_GAME_BYTES) warnings.push(`games/${id}: ${(total / 1048576).toFixed(2)} MB, close to the 2 MB limit`);

  // Basic offline check: no http(s) URLs in code/markup that would try to reach the network.
  for (const f of files) {
    if (!/\.(html|js|css|json|svg)$/i.test(f.path)) continue;
    const text = readFileSync(join(dir, f.path), 'utf8');
    const m = text.match(/(?:src|href)\s*=\s*["']\s*(?:https?:)?\/\/[^"']+|url\(\s*["']?(?:https?:)?\/\/[^)]+\)|@import\s+["']?(?:https?:)?\/\//i);
    if (m) err(id, `${f.path} references an external URL (${m[0].slice(0, 60)}…). Games must be offline.`);
  }

  if (!enabled) { console.log(`- ${id}: disabled, not published`); continue; }

  games.push({
    id,
    title: typeof title === 'string' ? title.trim() : id,
    color,
    order,
    icon,
    hash: hash.digest('hex').slice(0, 16),
    files,
  });
}

for (const w of warnings) console.warn(`warning: ${w}`);
if (errors.length) {
  console.error('\nbuild-manifest failed:\n' + errors.map((e) => `  ✗ ${e}`).join('\n'));
  process.exit(1);
}

games.sort((a, b) => a.order - b.order || a.title.localeCompare(b.title));
const manifest = { version: Math.floor(Date.now() / 1000), games };

mkdirSync(dirname(outFile), { recursive: true });
writeFileSync(outFile, JSON.stringify(manifest, null, 2) + '\n');
for (const g of games) {
  const kb = g.files.reduce((s, f) => s + f.size, 0) / 1024;
  console.log(`✓ ${g.id}  "${g.title}"  ${g.files.length} files, ${kb.toFixed(0)} KB, hash ${g.hash}`);
}
console.log(`wrote ${outFile} (${games.length} games)`);

// Optional: a tiny index page so games can be tried in a phone browser.
if (indexFile) {
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const tiles = games.map((g) =>
    `<a href="games/${g.id}/index.html" style="background:${g.color}"><img src="games/${g.id}/${esc(g.icon)}" alt=""><span>${esc(g.title)}</span></a>`).join('\n');
  const html = `<!doctype html><html lang="lt"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Ponas – žaidimai</title>
<style>body{font-family:sans-serif;background:#FFF6E0;margin:0;padding:16px}
h1{font-size:20px;color:#5D4037}.g{display:flex;flex-wrap:wrap;gap:12px}
a{width:140px;height:140px;border-radius:24px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-decoration:none;color:#fff;font-weight:bold;text-shadow:0 1px 2px #0006}
img{width:90px;height:90px}p{color:#8D6E63;font-size:13px}</style></head>
<body><h1>Ponas – žaidimai</h1><div class="g">
${tiles}
</div><p>Manifest version ${manifest.version}. <a style="all:revert" href="manifest.json">manifest.json</a></p></body></html>
`;
  const f = resolve(indexFile);
  mkdirSync(dirname(f), { recursive: true });
  writeFileSync(f, html);
  console.log(`wrote ${f}`);
}
