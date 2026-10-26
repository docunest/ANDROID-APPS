#!/usr/bin/env node
// Smoke test for Ponas games in headless Chromium, landscape 640×375 and portrait 375×640.
//
//   node tools/build-site.mjs && node tools/smoke-test.mjs [game-id ...]
//
// Needs Playwright (dev only, not a game dependency):  npm i --no-save playwright
// If Chromium isn't found, set CHROMIUM=/path/to/chrome (Claude Code cloud: /opt/pw-browsers/chromium-*/chrome-linux/chrome).
//
// Checks per game and orientation: loads without JS errors, the ▶ start overlay works,
// nothing scrolls, pause shows the overlay and resume hides it, close leaves the game,
// and no network requests except ntfy.sh (mocked here) for games that declare it.
// Screenshots go to _shots/<id>-<orientation>.png — look at them.

import { createServer } from 'node:http';
import { readFileSync, existsSync, mkdirSync, readdirSync } from 'node:fs';
import { join, resolve, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SITE = join(ROOT, '_site');
const SHOTS = join(ROOT, '_shots');
if (!existsSync(join(SITE, 'manifest.json'))) { console.error('Run node tools/build-site.mjs first.'); process.exit(1); }

let chromium;
try { ({ chromium } = await import('playwright')); } catch {
  console.error('Playwright missing: npm i --no-save playwright'); process.exit(1);
}
function findChromium() {
  if (process.env.CHROMIUM) return process.env.CHROMIUM;
  try {
    const base = '/opt/pw-browsers';
    for (const d of readdirSync(base)) {
      const p = join(base, d, 'chrome-linux', 'chrome');
      if (d.startsWith('chromium-') && existsSync(p)) return p;
    }
  } catch { /* not there */ }
  return undefined; // let Playwright use its own
}

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json',
  '.png': 'image/png', '.svg': 'image/svg+xml', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.m4a': 'audio/mp4', '.webp': 'image/webp' };
const server = createServer((req, res) => {
  const p = join(SITE, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  if (!p.startsWith(SITE) || !existsSync(p)) { res.writeHead(404); return res.end(); }
  const f = p.endsWith('/') ? join(p, 'index.html') : p;
  try { res.writeHead(200, { 'content-type': TYPES[extname(f)] || 'application/octet-stream' }); res.end(readFileSync(f)); }
  catch { res.writeHead(404); res.end(); }
}).listen(0);
const BASE = `http://localhost:${server.address().port}/`;

const manifest = JSON.parse(readFileSync(join(SITE, 'manifest.json'), 'utf8'));
const want = process.argv.slice(2);
const games = manifest.games.filter((g) => !want.length || want.includes(g.id));
mkdirSync(SHOTS, { recursive: true });

const browser = await chromium.launch({ executablePath: findChromium() });
let failed = 0;
for (const g of games) {
  for (const [w, h, o] of [[640, 375, 'landscape'], [375, 640, 'portrait']]) {
    const ctx = await browser.newContext({ viewport: { width: w, height: h }, hasTouch: true, deviceScaleFactor: 1.6 });
    const page = await ctx.newPage();
    const problems = [];
    page.on('pageerror', (e) => problems.push('JS error: ' + e.message));
    page.on('console', (m) => { if (m.type() === 'error') problems.push('console: ' + m.text()); });
    page.on('request', (r) => {
      const u = r.url();
      if (u.startsWith(BASE) || u.startsWith('data:') || u.startsWith('blob:')) return;
      if (u.startsWith('https://ntfy.sh/') && g.network.includes('ntfy.sh')) return;
      problems.push('network request not allowed: ' + u);
    });
    await page.route('https://ntfy.sh/**', (r) => r.request().url().endsWith('/sse')
      ? r.fulfill({ status: 200, headers: { 'content-type': 'text/event-stream', 'access-control-allow-origin': '*' }, body: '' })
      : r.fulfill({ status: 200, headers: { 'access-control-allow-origin': '*' }, body: '{}' }));
    try {
      await page.goto(`${BASE}games/${g.id}/index.html`);
      await page.waitForTimeout(300);
      if (!(await page.evaluate(() => !!window.Ponas))) problems.push('ponas.js not loaded');
      await page.locator('#ponas-start').click();
      await page.waitForTimeout(800);
      // tap around the middle of the screen a few times, like a child would
      for (const [x, y] of [[0.5, 0.5], [0.3, 0.6], [0.7, 0.6], [0.5, 0.8]]) {
        await page.mouse.click(w * x, h * y); await page.waitForTimeout(250);
      }
      await page.screenshot({ path: join(SHOTS, `${g.id}-${o}.png`) });
      const size = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.scrollHeight]);
      if (size[0] > w + 1 || size[1] > h + 1) problems.push(`page scrolls: content is ${size[0]}×${size[1]}`);
      await page.locator('#ponas-pause').click(); await page.waitForTimeout(150);
      if (!(await page.evaluate(() => Ponas.paused && !document.getElementById('ponas-paused').classList.contains('ponas-hidden')))) problems.push('pause overlay did not show');
      await page.locator('#ponas-paused').click(); await page.waitForTimeout(150);
      if (await page.evaluate(() => Ponas.paused)) problems.push('resume did not work');
      await page.locator('#ponas-close').click(); await page.waitForTimeout(400);
      if (page.url().includes(`/games/${g.id}/`)) problems.push('close did not leave the game');
    } catch (e) { problems.push(String(e.message || e).split('\n')[0]); }
    await ctx.close();
    if (problems.length) failed++;
    console.log(`${problems.length ? '✗' : '✓'} ${g.id} ${o}${problems.length ? '\n    ' + problems.join('\n    ') : ''}`);
  }
}
await browser.close();
server.close();
console.log(failed ? `\n${failed} check(s) failed. Screenshots in _shots/` : '\nAll good. Look at the screenshots in _shots/');
process.exit(failed ? 1 : 0);
