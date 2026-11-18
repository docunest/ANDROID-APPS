// End-to-end test against the mock Xtream server, in real Chromium.
// Prereqs (the script starts them itself): npm run build; ffmpeg installed.
//   node tests/e2e.mjs
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// Playwright: use the project's devDependency if installed, else a global install.
const { chromium, devices } = createRequire(import.meta.url)('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const OUT = path.join(root, 'tests', 'out');
fs.mkdirSync(OUT, { recursive: true });

const procs = [];
function start(cmd, args, env, ready) {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args, { cwd: root, env: { ...process.env, ...env } });
    procs.push(p);
    let buf = '';
    const t = setTimeout(() => reject(new Error(`${args.join(' ')} did not start: ${buf}`)), 60000);
    const on = (d) => {
      buf += d;
      if (buf.includes(ready)) {
        clearTimeout(t);
        resolve(p);
      }
    };
    p.stdout.on('data', on);
    p.stderr.on('data', on);
  });
}
process.on('exit', () => procs.forEach((p) => p.kill()));

const results = [];
async function step(name, fn) {
  const t0 = Date.now();
  try {
    await fn();
    results.push({ name, ok: true, ms: Date.now() - t0 });
    console.log(`  ✓ ${name}`);
  } catch (e) {
    results.push({ name, ok: false, err: String(e?.message ?? e).split('\n')[0] });
    console.log(`  ✗ ${name}\n      ${String(e?.message ?? e).split('\n').slice(0, 3).join('\n      ')}`);
  }
}
const expect = (cond, msg) => {
  if (!cond) throw new Error(msg);
};

async function videoTime(page) {
  return page.evaluate(() => document.querySelector('video')?.currentTime ?? -1);
}
async function waitPlaying(page, min = 1.5, timeout = 20000) {
  await page.waitForFunction((m) => (document.querySelector('video')?.currentTime ?? 0) > m, min, { timeout });
}

await start('node', ['server/mock-xtream.mjs'], { PORT: '9090' }, 'Mock Xtream on');
await start('node', ['server/mock-xtream.mjs'], { PORT: '9091', CORS: '1' }, 'Mock Xtream on');
await start('node', ['server/mock-xtream.mjs'], { PORT: '9092', THROTTLE_KBPS: '900' }, 'Mock Xtream on');
await start('node', ['server/proxy.mjs'], { PORT: '8080', XTREAM_URL: 'http://localhost:9090' }, 'Reelbox on');
await start('node', ['server/proxy.mjs'], { PORT: '8082', XTREAM_URL: 'http://localhost:9092' }, 'Reelbox on');
// plain static hosting (no proxy) for direct-mode tests
await start('python3', ['-m', 'http.server', '8081', '-d', 'dist'], {}, 'Serving HTTP');
// GitHub Pages layout: https://docunest.github.io/ANDROID-APPS/reelbox/
const pagesRoot = path.join(OUT, 'pages-site');
fs.rmSync(pagesRoot, { recursive: true, force: true });
fs.mkdirSync(path.join(pagesRoot, 'ANDROID-APPS'), { recursive: true });
fs.cpSync(path.join(root, 'dist'), path.join(pagesRoot, 'ANDROID-APPS', 'reelbox'), { recursive: true });
await start('python3', ['-m', 'http.server', '8083', '-d', pagesRoot], {}, 'Serving HTTP');

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });

async function login(page, base, server) {
  await page.goto(base);
  await page.waitForSelector('.setup-card');
  if (server !== undefined) await page.getByLabel('Server URL').fill(server);
  await page.getByLabel('Username').fill('demo');
  await page.getByLabel('Server password').fill('demo');
  await page.getByRole('button', { name: 'Connect' }).click();
}

// ============ Main flow through the proxy ============
console.log('\nMain flow (web/PWA via proxy, 1280×800)');
const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
const page = await ctx.newPage();
const consoleErrors = [];
page.on('console', (m) => m.type() === 'error' && consoleErrors.push(m.text()));
page.on('pageerror', (e) => consoleErrors.push(String(e)));

await step('setup screen detects the proxy and prefills the server', async () => {
  await page.goto('http://localhost:8080/');
  await page.waitForSelector('.setup-card');
  await page.waitForFunction(() => (document.querySelector('input[inputmode=url]')?.value ?? '') !== '');
  expect((await page.getByLabel('Server URL').inputValue()) === 'http://localhost:9090', 'server not prefilled');
  await page.screenshot({ path: `${OUT}/01-setup.png` });
});

await step('wrong password shows a clear error', async () => {
  await page.getByLabel('Username').fill('demo');
  await page.getByLabel('Server password').fill('nope');
  await page.getByRole('button', { name: 'Connect' }).click();
  await page.getByText('Wrong username or password.').waitFor({ timeout: 5000 });
});

await step('login → home shows rows', async () => {
  await page.getByLabel('Server password').fill('demo');
  await page.getByRole('button', { name: 'Connect' }).click();
  await page.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 10000 });
  await page.getByRole('heading', { name: 'Recently updated series' }).waitFor();
  await page.waitForTimeout(500);
  await page.screenshot({ path: `${OUT}/02-home.png` });
});

await step('movies: categories, counts, filter', async () => {
  await page.getByRole('link', { name: 'Movies' }).click();
  await page.waitForSelector('.grid .card');
  const all = await page.locator('.grid .card').count();
  expect(all === 29, `expected 29 movies, got ${all}`);
  // All + 3 non-empty categories; the empty one is hidden
  await page.waitForFunction(() => document.querySelectorAll('.cats .cat').length === 4, null, { timeout: 5000 });
  await page.locator('.cats .cat', { hasText: 'Documentaries' }).click();
  await page.waitForFunction(() => document.querySelectorAll('.grid .card').length === 10);
  await page.getByPlaceholder('Filter movies…').fill('steel');
  await page.waitForFunction(() => document.querySelectorAll('.grid .card').length === 1);
  await page.screenshot({ path: `${OUT}/03-movies.png` });
  await page.getByPlaceholder('Filter movies…').fill('');
  await page.locator('.cats .cat', { hasText: 'All' }).click();
});

await step('arrow keys move focus between cards (TV remote)', async () => {
  await page.locator('.grid .card').first().focus();
  const first = await page.evaluate(() => document.activeElement?.textContent);
  await page.keyboard.press('ArrowRight');
  const second = await page.evaluate(() => document.activeElement?.textContent);
  await page.keyboard.press('ArrowDown');
  const third = await page.evaluate(() => ({ t: document.activeElement?.textContent, card: document.activeElement?.classList.contains('card') }));
  expect(first !== second && second !== third.t && third.card, `focus did not move: ${first} | ${second} | ${third.t}`);
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('ArrowLeft');
  await page.keyboard.press('ArrowLeft');
  const navFocused = await page.evaluate(() => !!document.activeElement?.closest('.cats, .nav'));
  expect(navFocused, 'left from first column should reach the category list');
});

await step('movie detail: info, favorite, play, bookmark, resume', async () => {
  await page.goto('http://localhost:8080/#/movie/100');
  await page.getByRole('heading', { name: 'Steel Valley' }).waitFor();
  await page.getByText('Generated by the Reelbox mock server').waitFor();
  await page.getByRole('button', { name: 'Add to favorites' }).click();
  await page.getByRole('button', { name: 'Favorite' }).waitFor();
  await page.screenshot({ path: `${OUT}/04-movie.png` });
  await page.getByRole('link', { name: 'Play' }).click();
  await waitPlaying(page, 2);
  await page.mouse.move(400, 300);
  await page.getByRole('button', { name: 'Bookmark this moment' }).click();
  await page.getByText(/Bookmarked at/).waitFor();
  await page.screenshot({ path: `${OUT}/05-player.png` });
  // keyboard seek with hidden controls
  await page.waitForFunction(() => document.querySelector('.player.hide'), null, { timeout: 8000 });
  const before = await videoTime(page);
  await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(300);
  const after = await videoTime(page);
  expect(after - before > 8, `ArrowRight should seek +10s (${before} → ${after})`);
  await page.waitForTimeout(5500); // progress saved every 5 s
  await page.keyboard.press('Escape');
  await page.getByRole('link', { name: /Resume \d+:\d\d/ }).waitFor({ timeout: 5000 });
  await page.getByText('Your bookmarks').waitFor();
});

await step('player: lock-screen controls and picture-in-picture', async () => {
  await page.goto('http://localhost:8080/#/movie/100');
  await page.getByRole('link', { name: /Resume|Play/ }).first().click();
  await waitPlaying(page, 1);
  const ms = await page.evaluate(() => ({ title: navigator.mediaSession.metadata?.title, state: navigator.mediaSession.playbackState }));
  expect(ms.title === 'Steel Valley' && ms.state === 'playing', `media session ${JSON.stringify(ms)}`);
  await page.mouse.move(300, 300);
  const pipBtn = page.getByRole('button', { name: 'Picture in picture' });
  if (await page.evaluate(() => document.pictureInPictureEnabled)) {
    await pipBtn.click();
    await page.waitForFunction(() => !!document.pictureInPictureElement, null, { timeout: 5000 });
    await pipBtn.click();
    await page.waitForFunction(() => !document.pictureInPictureElement, null, { timeout: 5000 });
  } else {
    expect((await pipBtn.count()) === 0, 'PiP button must hide when the browser has no PiP');
  }
  await page.keyboard.press('Escape');
});

await step('resume starts where we left off', async () => {
  await page.getByRole('link', { name: /Resume/ }).click();
  await page.getByText(/Resumed from/).waitFor({ timeout: 10000 });
  const t = await videoTime(page);
  expect(t > 10, `expected to resume past 10s, at ${t}`);
  await page.keyboard.press('Escape');
});

await step('bookmarks page lists favorite, moment and history', async () => {
  await page.goto('http://localhost:8080/#/library');
  await page.locator('.card', { hasText: 'Steel Valley' }).waitFor();
  await page.getByRole('tab', { name: /Moments/ }).click();
  await page.locator('.list-item', { hasText: 'Steel Valley' }).waitFor();
  await page.getByRole('tab', { name: /History/ }).click();
  await page.locator('.list-item', { hasText: 'Steel Valley' }).waitFor();
  await page.screenshot({ path: `${OUT}/06-bookmarks.png` });
});

await step('series: seasons + episodes (object format)', async () => {
  await page.goto('http://localhost:8080/#/show/500');
  await page.getByRole('heading', { name: 'How It’s Made: Iberia' }).waitFor();
  expect((await page.locator('.episodes .chip').count()) === 2, 'expected 2 seasons');
  expect((await page.locator('.ep').count()) === 4, 'expected 4 episodes');
  await page.screenshot({ path: `${OUT}/07-series.png` });
});

await step('series: array-of-arrays episode format is normalised', async () => {
  await page.goto('http://localhost:8080/#/show/501');
  await page.getByRole('heading', { name: 'Little Explorers' }).waitFor();
  await page.locator('.ep').first().waitFor();
  expect((await page.locator('.episodes .chip').count()) === 2, 'expected 2 seasons');
});

await step('episode end → “Up next” → next episode plays', async () => {
  await page.goto('http://localhost:8080/#/show/500');
  await page.locator('.ep-main').first().click();
  await waitPlaying(page, 1);
  await page.evaluate(() => {
    const v = document.querySelector('video');
    v.currentTime = v.duration - 1.5;
  });
  await page.getByText('Up next').waitFor({ timeout: 10000 });
  await page.screenshot({ path: `${OUT}/08-up-next.png` });
  await page.getByRole('button', { name: /Play now/ }).click();
  await page.waitForURL(/play\/episode\/50012/);
  await waitPlaying(page, 1);
  await page.keyboard.press('Escape');
});

await step('live channel plays (HLS through proxy, playlist rewritten)', async () => {
  await page.goto('http://localhost:8080/#/live');
  await page.locator('.card', { hasText: 'RTP 1' }).click();
  await waitPlaying(page, 1.5);
  await page.getByText('LIVE', { exact: true }).waitFor();
  await page.keyboard.press('Escape');
});

await step('redirecting stream (load balancer 302) plays through proxy', async () => {
  await page.goto('http://localhost:8080/#/movie/107');
  await page.getByRole('link', { name: 'Play' }).click();
  await waitPlaying(page, 1);
  await page.keyboard.press('Escape');
});

async function frameBox(page) {
  return page.evaluate(() => {
    const v = document.querySelector('.player-stage video').getBoundingClientRect();
    const s = document.querySelector('.player-stage').getBoundingClientRect();
    return { x: v.x - s.x, y: v.y - s.y, w: v.width, h: v.height, sw: s.width, sh: s.height };
  });
}
const near = (a, b, tol = 6) => Math.abs(a - b) <= tol;

await step('picture: double-boxed video — black bars detected and removed', async () => {
  await page.goto('http://localhost:8080/#/movie/200');
  await page.getByRole('link', { name: 'Play' }).click();
  await waitPlaying(page, 1);
  // 640×360 frame, picture is the centre 480×270 (75 %). Stage is 1280×800.
  // Fit (contain) the 16:9 picture → 1280×720, so the whole frame must be drawn at 1280/0.75 wide.
  await page.waitForFunction(() => document.querySelector('.player-stage video')?.getBoundingClientRect().width > 1600, null, { timeout: 15000 });
  await page.waitForTimeout(400); // css transition
  const b = await frameBox(page);
  expect(near(b.w, 1280 / 0.75) && near(b.h, 720 / 0.75), `frame ${b.w}×${b.h}`);
  expect(near(b.x + 0.125 * b.w, 0) && near(b.y + 0.125 * b.h, 40), `picture should start at x=0,y=40 (got ${b.x + 0.125 * b.w}, ${b.y + 0.125 * b.h})`);
  await page.mouse.move(500, 300);
  await page.getByRole('button', { name: /Picture: aspect ratio and zoom/ }).click();
  await page.getByText(/Removed bars: top\/bottom 1[23]%, sides 1[23]%/).waitFor();
  await page.screenshot({ path: `${OUT}/14-picture-autocrop.png` });
});

await step('picture: turning auto-remove off shows the original small picture', async () => {
  await page.getByRole('button', { name: /Auto-remove on/ }).click();
  await page.waitForTimeout(400);
  const b = await frameBox(page);
  // 640×360 frame fitted to 1280×800 → 1280×720; picture inside is only 960×540 (the "sad" double box).
  expect(near(b.w, 1280) && near(b.h, 720), `frame ${b.w}×${b.h}`);
  await page.getByRole('button', { name: /Auto-remove off/ }).click();
});

await step('picture: shape, fill, zoom and move; remembered per title', async () => {
  await page.getByRole('button', { name: '4:3', exact: true }).click();
  await page.waitForTimeout(400);
  let b = await frameBox(page);
  // frame forced to 4:3, crop keeps centre 75 % → picture aspect 4:3 → fits 1066.7×800
  expect(near(b.h * 0.75, 800) && near(b.w * 0.75, 1066.7), `4:3 frame ${b.w}×${b.h}`);
  await page.getByRole('button', { name: 'Stretch' }).click();
  await page.waitForTimeout(400);
  b = await frameBox(page);
  expect(near(b.w * 0.75, 1280) && near(b.h * 0.75, 800), `stretch ${b.w}×${b.h}`);
  await page.getByRole('button', { name: 'Fit', exact: true }).click();
  await page.getByRole('button', { name: 'Auto', exact: true }).click();
  await page.getByRole('button', { name: 'Zoom in' }).click();
  await page.getByRole('button', { name: 'Zoom in' }).click();
  await page.locator('.seg-value', { hasText: '110%' }).waitFor();
  await page.getByRole('button', { name: 'Move picture up' }).click();
  await page.waitForTimeout(400);
  b = await frameBox(page);
  expect(near(b.w, (1280 / 0.75) * 1.1, 8), `zoomed frame ${b.w}`);
  // keyboard: Z cycles fit, A cycles shape
  await page.getByRole('button', { name: 'Close' }).click();
  await page.keyboard.press('z');
  await page.getByText('Fill', { exact: true }).first().waitFor();
  await page.keyboard.press('z');
  await page.keyboard.press('z'); // back to Fit
  await page.keyboard.press('Escape');
  // reopen: zoom remembered for this movie, crop applied straight away from cache
  await page.getByRole('link', { name: /Resume|Play/ }).first().click();
  await waitPlaying(page, 0.5);
  await page.waitForTimeout(500);
  b = await frameBox(page);
  expect(near(b.w, (1280 / 0.75) * 1.1, 8), `remembered zoom/crop, frame ${b.w}`);
  await page.screenshot({ path: `${OUT}/15-picture-zoomed.png` });
  await page.keyboard.press('Escape');
});

await step('picture: a normal 16:9 video is left alone', async () => {
  await page.goto('http://localhost:8080/#/movie/104');
  await page.getByRole('link', { name: 'Play' }).click();
  await waitPlaying(page, 5); // several detection samples
  const b = await frameBox(page);
  expect(near(b.w, 1280) && near(b.h, 720) && near(b.y, 40), `frame ${b.w}×${b.h} at y=${b.y}`);
  await page.keyboard.press('Escape');
});

await step('search across movies, series and live', async () => {
  await page.goto('http://localhost:8080/#/search');
  await page.getByRole('searchbox', { name: 'Search' }).fill('port');
  await page.getByRole('heading', { name: /Movies \(\d+\)/ }).waitFor();
  await page.getByRole('searchbox', { name: 'Search' }).fill('evora');
  await page.locator('.card', { hasText: 'Évora Bones' }).waitFor(); // accent-insensitive
  await page.screenshot({ path: `${OUT}/09-search.png` });
});

await step('download a movie for offline (OPFS)', async () => {
  await page.goto('http://localhost:8080/#/movie/101');
  await page.getByRole('heading', { name: 'The Fish Market at Dawn' }).waitFor();
  await page.getByRole('button', { name: 'Download' }).click();
  await page.getByRole('button', { name: 'Downloaded' }).waitFor({ timeout: 60000 });
  await page.goto('http://localhost:8080/#/downloads');
  await page.getByText(/7\.2 MB · ready/).waitFor();
  expect(await page.getByText(/browser file storage/).isVisible(), 'expected OPFS driver');
  await page.screenshot({ path: `${OUT}/10-downloads.png` });
});

await step('download a whole season', async () => {
  await page.goto('http://localhost:8080/#/show/502');
  await page.getByRole('button', { name: 'Download season' }).click();
  await page.goto('http://localhost:8080/#/downloads');
  await page.waitForFunction(() => document.querySelectorAll('.dl-done').length >= 5, null, { timeout: 90000 });
});

await step('offline: app shell, saved catalog, downloaded video plays', async () => {
  await page.evaluate(() => navigator.serviceWorker.ready);
  await ctx.setOffline(true);
  await page.goto('http://localhost:8080/#/');
  await page.getByText(/Offline — showing your saved catalog/).waitFor({ timeout: 10000 });
  await page.getByRole('heading', { name: 'Downloaded' }).waitFor();
  await page.screenshot({ path: `${OUT}/11-offline-home.png` });
  await page.getByRole('link', { name: 'Movies' }).click();
  await page.waitForSelector('.grid .card');
  expect((await page.locator('.grid .card').count()) === 29, 'saved catalog should list all movies offline');
  await page.goto('http://localhost:8080/#/movie/101');
  await page.getByRole('link', { name: 'Play' }).click();
  await waitPlaying(page, 1.5);
  await page.getByText('Offline copy').waitFor();
  await page.screenshot({ path: `${OUT}/12-offline-player.png` });
  await page.keyboard.press('Escape');
});

await step('offline: non-downloaded title explains why it cannot play', async () => {
  await page.goto('http://localhost:8080/#/play/movie/102');
  // ref not known yet for 102 → open via detail
  await page.goto('http://localhost:8080/#/movie/102');
  await page.getByRole('link', { name: 'Play' }).click();
  await page.getByText('You are offline and this has not been downloaded.').waitFor({ timeout: 8000 });
  await ctx.setOffline(false);
});

await step('no console errors in main flow', async () => {
  const relevant = consoleErrors.filter((e) => !/ERR_INTERNET_DISCONNECTED|Failed to load resource|net::ERR/.test(e));
  expect(!relevant.length, relevant.slice(0, 3).join(' | '));
});
await ctx.close();

// ============ Pause / resume download (HTTP Range) ============
console.log('\nPause/resume download (throttled server)');
for (const driver of ['opfs', 'idb']) {
  await step(`pause + resume continues from the same byte (${driver})`, async () => {
    const c = await browser.newContext();
    if (driver === 'idb') await c.addInitScript(() => delete FileSystemFileHandle.prototype.createWritable);
    const p = await c.newPage();
    await login(p, 'http://localhost:8082/');
    await p.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 10000 });
    await p.goto('http://localhost:8082/#/movie/103');
    await p.getByRole('button', { name: 'Download' }).click();
    await p.waitForFunction(() => /^\d+%$/.test(document.querySelector('.dl-downloading')?.getAttribute('aria-label') ?? '') && parseInt(document.querySelector('.dl-downloading').getAttribute('aria-label')) >= 15, null, { timeout: 30000 });
    await p.locator('.dl-downloading').click(); // pause
    await p.getByRole('button', { name: /Paused \d+%/ }).waitFor();
    const pausedPct = parseInt((await p.getByRole('button', { name: /Paused/ }).getAttribute('aria-label')).match(/\d+/)[0]);
    // reload the app mid-download to prove state survives
    await p.reload();
    await p.getByRole('button', { name: /Paused \d+%/ }).waitFor();
    const reqs = [];
    p.on('request', (r) => r.url().includes('/movie/') && reqs.push(r.headers()['range']));
    await p.getByRole('button', { name: /Paused/ }).click(); // resume
    await p.getByRole('button', { name: 'Downloaded' }).waitFor({ timeout: 60000 });
    expect(reqs.some((r) => r && /^bytes=[1-9]\d*-$/.test(r)), `resume should send a Range header, saw ${JSON.stringify(reqs)}`);
    await p.goto('http://localhost:8082/#/downloads');
    await p.getByText(/7\.2 MB · ready/).waitFor();
    if (driver === 'idb') await p.getByText('Saved to browser database').waitFor();
    // the stored file must be complete and playable
    await p.goto('http://localhost:8082/#/movie/103');
    await c.setOffline(true);
    await p.getByRole('link', { name: 'Play' }).click();
    await waitPlaying(p, 1);
    const dur = await p.evaluate(() => document.querySelector('video').duration);
    expect(Math.abs(dur - 90) < 1.5, `offline file duration ${dur}, expected 90 (paused at ${pausedPct}%)`);
    await c.close();
  });
}

// ============ Direct mode (no proxy) ============
console.log('\nDirect mode (static hosting, no proxy)');
await step('server WITH CORS works directly', async () => {
  const c = await browser.newContext();
  const p = await c.newPage();
  await login(p, 'http://localhost:8081/', 'localhost:9091');
  await p.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 10000 });
  await p.goto('http://localhost:8081/#/movie/100');
  await p.getByRole('link', { name: 'Play' }).click();
  await waitPlaying(p, 1);
  await c.close();
});
await step('server WITHOUT CORS gives an actionable message', async () => {
  const c = await browser.newContext();
  const p = await c.newPage();
  await login(p, 'http://localhost:8081/', 'http://localhost:9090');
  await p.locator('.debug-cors').waitFor({ timeout: 30000 });
  await p.screenshot({ path: `${OUT}/13-cors-error.png` });
  await c.close();
});

await step('GitHub Pages layout: app works from /ANDROID-APPS/reelbox/ with a CORS server', async () => {
  const c = await browser.newContext();
  const p = await c.newPage();
  await login(p, 'http://localhost:8083/ANDROID-APPS/reelbox/', 'localhost:9091');
  await p.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 10000 });
  const sw = await p.evaluate(() => navigator.serviceWorker.ready.then((r) => r.scope));
  expect(sw.endsWith('/ANDROID-APPS/reelbox/'), `service worker scope ${sw}`);
  await p.goto('http://localhost:8083/ANDROID-APPS/reelbox/#/install');
  await p.locator('.qr svg').waitFor();
  await p.getByText('iPhone · no Apple ID needed').waitFor();
  await p.screenshot({ path: `${OUT}/16-install-page.png`, fullPage: true });
  await c.close();
});

// ============ Form factors ============
console.log('\nForm factors');
await step('iPhone layout (bottom tab bar, no horizontal scroll)', async () => {
  const c = await browser.newContext({ ...devices['iPhone 13'] });
  const p = await c.newPage();
  await login(p, 'http://localhost:8080/');
  await p.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 10000 });
  await p.waitForTimeout(400);
  await p.screenshot({ path: `${OUT}/20-iphone-home.png` });
  const overflow = await p.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(!overflow, 'page scrolls horizontally on iPhone');
  const navBottom = await p.evaluate(() => getComputedStyle(document.querySelector('.nav')).bottom);
  expect(navBottom === '0px', 'nav should be a bottom bar');
  await p.goto('http://localhost:8080/#/show/500');
  await p.locator('.ep').first().waitFor();
  await p.screenshot({ path: `${OUT}/21-iphone-series.png` });
  await p.goto('http://localhost:8080/#/movies');
  await p.waitForSelector('.grid .card');
  await p.screenshot({ path: `${OUT}/22-iphone-movies.png` });
  await p.locator('.grid .card').first().click();
  await p.getByRole('link', { name: 'Play' }).click();
  await waitPlaying(p, 1);
  await p.screenshot({ path: `${OUT}/23-iphone-player.png` });
  await c.close();
});

await step('projector/TV layout at 1920×1080 with remote navigation', async () => {
  const c = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const p = await c.newPage();
  await login(p, 'http://localhost:8080/');
  await p.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 10000 });
  await p.goto('http://localhost:8080/#/settings');
  await p.getByLabel(/TV layout/).selectOption('on');
  await p.goto('http://localhost:8080/#/');
  await p.waitForTimeout(400);
  expect(await p.evaluate(() => document.documentElement.classList.contains('tv')), 'tv class missing');
  // remote: from nothing focused, arrows land on something, Enter opens it
  await p.keyboard.press('ArrowDown');
  for (let i = 0; i < 3; i++) await p.keyboard.press('ArrowRight');
  const focused = await p.evaluate(() => document.activeElement?.className);
  expect(/card|nav-item/.test(focused ?? ''), `unexpected focus ${focused}`);
  await p.screenshot({ path: `${OUT}/30-tv-home.png` });
  await p.goto('http://localhost:8080/#/movie/100');
  await p.getByRole('heading', { name: 'Steel Valley' }).waitFor();
  await p.waitForTimeout(300);
  await p.locator('[data-autofocus]').focus();
  await p.keyboard.press('Enter');
  await waitPlaying(p, 1);
  // Tizen remote: Return key (10009) exits the player
  await p.waitForTimeout(500);
  await p.evaluate(() => window.dispatchEvent(new KeyboardEvent('keydown', { keyCode: 10009, bubbles: true, cancelable: true })));
  await p.getByRole('heading', { name: 'Steel Valley' }).waitFor({ timeout: 5000 });
  await p.screenshot({ path: `${OUT}/31-tv-detail.png` });
  await c.close();
});

await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify(results, null, 2));
process.exit(failed.length ? 1 : 0);
