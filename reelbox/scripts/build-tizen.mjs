// Builds the Samsung Tizen package folder (build/tizen) and an UNSIGNED .wgt.
// Samsung devices only install signed packages: sign with Tizen Studio / tizen CLI (see README → Samsung projector).
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = path.join(root, 'build', 'tizen');
execFileSync('npx', ['vite', 'build', '--mode', 'tizen'], { cwd: root, stdio: 'inherit' });
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
fs.cpSync(path.join(root, 'dist-tizen'), out, { recursive: true });
for (const f of ['config.xml', 'icon.png']) fs.copyFileSync(path.join(root, 'tizen', f), path.join(out, f));
// keep the version in config.xml in sync with package.json
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'));
const cfg = path.join(out, 'config.xml');
fs.writeFileSync(cfg, fs.readFileSync(cfg, 'utf8').replace(/(<widget[^>]*\sversion=")[^"]+"/, `$1${pkg.version}"`));
const wgt = path.join(root, 'build', 'Reelbox-unsigned.wgt');
fs.rmSync(wgt, { force: true });
execFileSync('zip', ['-qr', wgt, '.'], { cwd: out });
console.log(`\nTizen app folder: ${path.relative(root, out)}\nUnsigned package: ${path.relative(root, wgt)}  (sign it: see README)`);
