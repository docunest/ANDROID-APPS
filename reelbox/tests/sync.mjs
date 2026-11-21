// Family account + sync across devices, against the real shared API and Postgres.
// The API lives in docunest/monorepo → apps/api; point API_DIR at a built checkout of it.
//   (cd <monorepo>/apps/api && npm ci && npm run build) && npm run build
//   API_DIR=<monorepo>/apps/api TEST_DATABASE_URL=postgres://postgres:pg@localhost:5433/postgres node tests/sync.mjs   (database is wiped)
import { createRequire } from 'node:module';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const { chromium, devices } = require('playwright');
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const apiDir = path.resolve(process.env.API_DIR || path.join(root, '../../monorepo/apps/api'));
const { Pool } = require(path.join(apiDir, 'node_modules/pg'));
const bcrypt = require(path.join(apiDir, 'node_modules/bcryptjs'));
const OUT = path.join(root, 'tests', 'out');
fs.mkdirSync(OUT, { recursive: true });

const DB = process.env.TEST_DATABASE_URL;
if (!DB) {
  console.error('Set TEST_DATABASE_URL (a throwaway Postgres — it gets wiped).');
  process.exit(2);
}
const API = 'http://localhost:3100';
const APP = 'http://localhost:8080/';
const ADMIN = 'admin-' + randomBytes(8).toString('hex');

const procs = [];
process.on('exit', () => procs.forEach((p) => p.kill()));
function start(cmd, args, env, ready, cwd = root) {
  return new Promise((resolve, reject) => {
    const p = spawn(cmd, args, { cwd, env: { ...process.env, ...env } });
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

const results = [];
async function step(name, fn) {
  try {
    await fn();
    results.push({ name, ok: true });
    console.log(`  ✓ ${name}`);
  } catch (e) {
    results.push({ name, ok: false, err: String(e?.message ?? e) });
    console.log(`  ✗ ${name}\n      ${String(e?.message ?? e).split('\n').slice(0, 3).join('\n      ')}`);
  }
}
const expect = (c, m) => {
  if (!c) throw new Error(m);
};

// ---- database as MapPal leaves it: one family user in mappal_user ----
const pool = new Pool({ connectionString: DB });
await pool.query('DROP SCHEMA public CASCADE; CREATE SCHEMA public;');
await pool.query(`CREATE TABLE mappal_user (id text PRIMARY KEY, email text UNIQUE NOT NULL, name text, "passwordHash" text NOT NULL, "createdAt" timestamp(3) NOT NULL DEFAULT now())`);
await pool.query(`INSERT INTO mappal_user (id, email, name, "passwordHash") VALUES ('cmum', 'mum@family.test', 'Mum', $1)`, [bcrypt.hashSync('mappal-pass-1', 10)]);

await start('node', ['dist/main.js'], {
  PORT: '3100',
  DATABASE_URL: DB,
  AUTH_SECRET: randomBytes(32).toString('hex'),
  ADMIN_SEED_SECRET: ADMIN,
  DATA_KEY: randomBytes(32).toString('base64'),
  REELBOX_APP_URL: APP.replace(/\/$/, ''),
}, 'docunest api on', apiDir);
await start('node', ['server/mock-xtream.mjs'], { PORT: '9090' }, 'Mock Xtream on');
await start('node', ['server/proxy.mjs'], { PORT: '8080', XTREAM_URL: 'http://localhost:9090' }, 'Reelbox on');

const imp = await fetch(`${API}/admin/import/mappal-users`, { method: 'POST', headers: { 'x-admin-secret': ADMIN } }).then((r) => r.json());
console.log(`\nImported MapPal users: ${imp.imported.join(', ')}\n`);

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });

async function familyLogin(p) {
  await p.goto(APP);
  await p.waitForSelector('.setup-card');
  await p.getByRole('button', { name: /Family account/ }).click();
  await p.getByLabel('Email').fill('mum@family.test');
  await p.getByLabel('Password', { exact: true }).fill('mappal-pass-1');
  await p.getByLabel('Family account server').fill(API);
  await p.getByRole('button', { name: 'Sign in' }).click();
}
async function syncNow(p) {
  await p.goto(APP + '#/settings');
  await p.getByRole('button', { name: 'Sync now' }).click();
  await p.waitForFunction(() => /Up to date/.test(document.querySelector('[aria-label="Family account"] dd.error-text, [aria-label="Family account"] dd[aria-live]')?.textContent ?? ''), null, { timeout: 15000 });
}
const frameW = (p) => p.evaluate(() => document.querySelector('.player-stage video')?.getBoundingClientRect().width ?? 0);

const A = await browser.newContext({ viewport: { width: 1280, height: 800 } }); // MacBook
const B = await browser.newContext({ ...devices['iPhone 13'] }); // iPhone
const pa = await A.newPage();
const pb = await B.newPage();

console.log('Family sync across devices');

await step('wrong family password is refused', async () => {
  await pa.goto(APP);
  await pa.getByRole('button', { name: /Family account/ }).click();
  await pa.getByLabel('Email').fill('mum@family.test');
  await pa.getByLabel('Password', { exact: true }).fill('wrong');
  await pa.getByLabel('Family account server').fill(API);
  await pa.getByRole('button', { name: 'Sign in' }).click();
  await pa.getByText('Wrong email or password.').waitFor();
});

await step('MacBook: sign in with the MapPal password, then add the Xtream server', async () => {
  await familyLogin(pa);
  await pa.getByText(/Signed in as Mum/).waitFor({ timeout: 10000 });
  await pa.screenshot({ path: `${OUT}/40-setup-family-signed-in.png` });
  await pa.getByLabel('Username').fill('demo');
  await pa.getByLabel('Server password').fill('demo');
  await pa.getByRole('button', { name: 'Connect' }).click();
  await pa.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 10000 });
});

await step('MacBook: favorite, watch half a movie, zoom an old rip', async () => {
  await pa.goto(APP + '#/movie/100');
  await pa.getByRole('button', { name: 'Add to favorites' }).click();
  await pa.goto(APP + '#/movie/104');
  await pa.getByRole('link', { name: 'Play' }).click();
  await pa.waitForFunction(() => document.querySelector('video')?.currentTime > 1, null, { timeout: 15000 });
  await pa.evaluate(() => (document.querySelector('video').currentTime = 42));
  await pa.waitForTimeout(5800);
  await pa.keyboard.press('Escape');
  await pa.goto(APP + '#/movie/200');
  await pa.getByRole('link', { name: 'Play' }).click();
  await pa.waitForFunction(() => document.querySelector('video')?.currentTime > 0.5, null, { timeout: 15000 });
  await pa.keyboard.press('+');
  await pa.keyboard.press('+');
  await pa.waitForTimeout(300);
  await pa.keyboard.press('Escape');
  await syncNow(pa);
});

await step('iPhone: family sign-in alone brings the server, no Xtream typing', async () => {
  await familyLogin(pb);
  await pb.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 15000 });
  await pb.screenshot({ path: `${OUT}/41-iphone-after-family-login.png` });
});

await step('iPhone: sees the favorite and the continue-watching point', async () => {
  await pb.getByRole('heading', { name: 'Continue watching' }).waitFor({ timeout: 10000 });
  await pb.getByRole('heading', { name: 'Favorites' }).waitFor();
  await pb.goto(APP + '#/movie/104');
  await pb.getByRole('link', { name: /Resume 0:4\d/ }).waitFor({ timeout: 10000 });
});

await step('iPhone: the old rip opens with the zoom set on the MacBook', async () => {
  await pb.goto(APP + '#/movie/200');
  await pb.getByRole('link', { name: 'Play' }).click();
  await pb.waitForFunction(() => document.querySelector('video')?.currentTime > 0.5, null, { timeout: 15000 });
  await pb.mouse.click(200, 300);
  await pb.locator('.player-bar small', { hasText: '110%' }).waitFor({ timeout: 5000 });
  await pb.keyboard.press('Escape');
});

await step('iPhone offline: add a favorite; it reaches the MacBook once back online', async () => {
  await B.setOffline(true);
  await pb.goto(APP + '#/movie/101');
  await pb.getByRole('link', { name: 'Play' }).click().catch(() => {});
  // offline + not downloaded: can't play — use the library instead: favorite this one
  await pb.goto(APP + '#/movie/101');
  await pb.getByRole('button', { name: 'Add to favorites' }).click();
  await B.setOffline(false);
  await syncNow(pb);
  await syncNow(pa);
  await pa.goto(APP + '#/library');
  await pa.locator('.card', { hasText: 'The Fish Market at Dawn' }).waitFor({ timeout: 10000 });
});

await step('MacBook removes a favorite → gone on the iPhone (deletes sync too)', async () => {
  await pa.locator('.fav-cell', { hasText: 'Steel Valley' }).getByRole('button', { name: /Remove/ }).click();
  await syncNow(pa);
  await syncNow(pb);
  await pb.goto(APP + '#/library');
  await pb.locator('.card', { hasText: 'The Fish Market at Dawn' }).waitFor();
  expect((await pb.locator('.card', { hasText: 'Steel Valley' }).count()) === 0, 'Steel Valley should be gone on the iPhone');
});

await step('projector: single-use login link signs in with no typing', async () => {
  const link = await fetch(`${API}/admin/login-links`, {
    method: 'POST',
    headers: { 'x-admin-secret': ADMIN, 'content-type': 'application/json' },
    body: JSON.stringify({ email: 'mum@family.test', days: 7 }),
  }).then((r) => r.json());
  expect(link.reelboxUrl?.startsWith(APP + '#/login?token='), `link ${link.reelboxUrl}`);
  const C = await browser.newContext({ viewport: { width: 1920, height: 1080 } });
  const pc = await C.newPage();
  await pc.goto(link.reelboxUrl);
  await pc.getByRole('heading', { name: 'Recently added movies' }).waitFor({ timeout: 15000 });
  await pc.getByRole('heading', { name: 'Favorites' }).waitFor({ timeout: 10000 });
  // the same link can't be used twice
  const pd = await (await browser.newContext()).newPage();
  await pd.goto(link.reelboxUrl);
  await pd.getByText(/invalid, expired or already used/).waitFor({ timeout: 10000 });
  await C.close();
});

await step('server logins are encrypted in the database', async () => {
  const r = await pool.query(`SELECT value::text AS v FROM reelbox_item WHERE kind = 'account'`);
  expect(r.rows.length === 1, `expected 1 synced server, got ${r.rows.length}`);
  expect(!r.rows[0].v.includes('demo') && r.rows[0].v.includes('enc'), 'account row should be encrypted');
});

await step('sign out stops syncing but keeps everything on the device', async () => {
  await pb.goto(APP + '#/settings');
  pb.once('dialog', (d) => d.accept());
  await pb.getByRole('region', { name: 'Family account' }).getByRole('button', { name: 'Sign out' }).click();
  await pb.getByRole('button', { name: 'Sign in' }).waitFor();
  await pb.goto(APP + '#/library');
  await pb.locator('.card', { hasText: 'The Fish Market at Dawn' }).waitFor();
});

await browser.close();
await pool.end();
const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} passed`);
process.exit(failed.length ? 1 : 0);
