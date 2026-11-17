// Renders public/icon.svg to the PNG sizes PWA, iOS, Android and Tizen need. Run: node scripts/make-icons.cjs
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
const svg = fs.readFileSync(path.join(__dirname, '../public/icon.svg'), 'utf8');
const out = [
  ['public/icon-192.png', 192, false],
  ['public/icon-512.png', 512, false],
  ['public/icon-maskable-512.png', 512, true],
  ['public/apple-touch-icon.png', 180, true],
  ['tizen/icon.png', 117, false],
  ['resources/icon.png', 1024, true],
];
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage();
  for (const [file, size, fullBleed] of out) {
    await p.setViewportSize({ width: size, height: size });
    const inner = fullBleed ? svg.replace('rx="112" fill="#0d0f14"', 'fill="#0d0f14"') : svg;
    await p.setContent(`<html><body style="margin:0;background:transparent">${inner.replace('<svg ', `<svg width="${size}" height="${size}" `)}</body></html>`);
    fs.mkdirSync(path.dirname(path.join(__dirname, '..', file)), { recursive: true });
    await p.screenshot({ path: path.join(__dirname, '..', file), omitBackground: !fullBleed });
  }
  await b.close();
  console.log('icons written');
})();
