// Renders the Reelbox icon into the iOS and Android native projects (run after `cap add`).
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const root = path.join(__dirname, '..');
const BG = '#0d0f14';
// full icon (rounded square tile on dark bg) and a foreground-only version for Android adaptive icons
const tile = (s) => `<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}" viewBox="0 0 512 512"><rect width="512" height="512" fill="${BG}"/><rect x="96" y="96" width="320" height="320" rx="76" fill="#ffb020"/><path d="M214 178v156l124-78z" fill="#1a1200"/></svg>`;
const round = (s) => `<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}" viewBox="0 0 512 512"><circle cx="256" cy="256" r="256" fill="${BG}"/><rect x="116" y="116" width="280" height="280" rx="66" fill="#ffb020"/><path d="M222 192v128l104-64z" fill="#1a1200"/></svg>`;
// adaptive foreground: 108dp canvas, safe zone is the middle 66dp
const fg = (s) => `<svg xmlns="http://www.w3.org/2000/svg" width="${s}" height="${s}" viewBox="0 0 108 108"><rect x="31" y="31" width="46" height="46" rx="11" fill="#ffb020"/><path d="M48 41.5v25l19-12.5z" fill="#1a1200"/></svg>`;
const dens = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const jobs = [];
const res = path.join(root, 'android/app/src/main/res');
if (fs.existsSync(res)) {
  for (const [d, m] of Object.entries(dens)) {
    jobs.push([`${res}/mipmap-${d}/ic_launcher.png`, 48 * m, tile, false]);
    jobs.push([`${res}/mipmap-${d}/ic_launcher_round.png`, 48 * m, round, true]);
    jobs.push([`${res}/mipmap-${d}/ic_launcher_foreground.png`, 108 * m, fg, true]);
  }
  const bg = `${res}/values/ic_launcher_background.xml`;
  fs.writeFileSync(bg, fs.readFileSync(bg, 'utf8').replace(/#[0-9A-Fa-f]{6}/, BG.toUpperCase()));
}
const ios = path.join(root, 'ios/App/App/Assets.xcassets/AppIcon.appiconset/AppIcon-512@2x.png');
if (fs.existsSync(path.dirname(ios))) jobs.push([ios, 1024, tile, false]);
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  for (const [file, size, fn, transparent] of jobs) {
    await p.setViewportSize({ width: Math.round(size), height: Math.round(size) });
    await p.setContent(`<html><body style="margin:0;background:transparent">${fn(Math.round(size))}</body></html>`);
    await p.screenshot({ path: file, omitBackground: transparent });
  }
  await b.close();
  console.log(`wrote ${jobs.length} native icons`);
})();
