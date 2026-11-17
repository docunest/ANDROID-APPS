#!/usr/bin/env node
// Reelbox server: serves the built web app and forwards /xt/* to your Xtream server.
// Why: browsers block calls to most Xtream panels (no CORS headers, plain http). Going through
// this same-origin proxy fixes that for the web/PWA build (MacBook, iPhone/Android browsers).
//
//   XTREAM_URL=http://my-server:8080 node server/proxy.mjs
//
// Env:
//   XTREAM_URL   (required) your Xtream server, e.g. http://1.2.3.4:8080
//   PORT         default 8080          HOST  default 0.0.0.0
//   TLS_CERT / TLS_KEY   paths to a certificate → serves https (needed for offline/PWA on other devices)
//   STATIC_DIR   default ../dist
// No dependencies; Node 18+.
import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Readable } from 'node:stream';

const here = path.dirname(fileURLToPath(import.meta.url));
const TARGET = (process.env.XTREAM_URL || '').replace(/\/+$/, '');
const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const STATIC = path.resolve(process.env.STATIC_DIR || path.join(here, '..', 'dist'));

if (!TARGET) {
  console.error('Set XTREAM_URL, e.g. XTREAM_URL=http://my-server:8080 node server/proxy.mjs');
  process.exit(1);
}
const targetOrigin = new URL(TARGET).origin;
// Hosts the Xtream server redirected us to (load balancers). Only these may be reached via __abs.
const allowedOrigins = new Set([targetOrigin]);

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

const PASS_HEADERS = ['content-type', 'content-length', 'content-range', 'accept-ranges', 'last-modified', 'etag', 'cache-control'];

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', 'Range, Content-Type');
  res.setHeader('Access-Control-Expose-Headers', 'Content-Length, Content-Range, Accept-Ranges');
}

const b64 = (s) => Buffer.from(s).toString('base64url');
const unb64 = (s) => Buffer.from(s, 'base64url').toString();

function proxiedPath(absUrl, prefix) {
  const u = new URL(absUrl);
  if (u.origin === targetOrigin) return `${prefix}${u.pathname}${u.search}`;
  allowedOrigins.add(u.origin);
  return `${prefix}/__abs/${b64(u.href)}`;
}

function rewritePlaylist(text, baseUrl, prefix) {
  const fix = (uri) => proxiedPath(new URL(uri, baseUrl).href, prefix);
  return text
    .split(/\r?\n/)
    .map((line) => {
      if (!line || line.startsWith('#')) return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${fix(u)}"`);
      return fix(line.trim());
    })
    .join('\n');
}

async function forward(req, res, upstreamUrl, prefix) {
  const ctrl = new AbortController();
  res.on('close', () => ctrl.abort());
  const headers = { 'user-agent': req.headers['user-agent'] || 'Reelbox' };
  if (req.headers.range) headers.range = req.headers.range;
  let up;
  try {
    up = await fetch(upstreamUrl, { method: req.method === 'HEAD' ? 'HEAD' : 'GET', headers, redirect: 'follow', signal: ctrl.signal });
  } catch (e) {
    if (ctrl.signal.aborted) return;
    res.writeHead(502, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ error: 'upstream unreachable', detail: String(e?.cause?.code || e?.message || e) }));
    return;
  }
  const finalOrigin = new URL(up.url).origin;
  if (finalOrigin !== targetOrigin) allowedOrigins.add(finalOrigin);

  const ct = up.headers.get('content-type') || '';
  const isPlaylist = /mpegurl/i.test(ct) || /\.m3u8(\?|$)/i.test(new URL(up.url).pathname);
  if (isPlaylist && up.ok && req.method !== 'HEAD') {
    const body = rewritePlaylist(await up.text(), up.url, prefix);
    res.writeHead(up.status, { 'content-type': 'application/vnd.apple.mpegurl', 'cache-control': 'no-cache' });
    res.end(body);
    return;
  }
  const out = {};
  // fetch() transparently decompresses, so a compressed length would be wrong.
  const decoded = !!up.headers.get('content-encoding');
  for (const h of PASS_HEADERS) {
    const v = up.headers.get(h);
    if (v && !(decoded && h === 'content-length')) out[h] = v;
  }
  res.writeHead(up.status, out);
  if (!up.body || req.method === 'HEAD') return res.end();
  Readable.fromWeb(up.body)
    .on('error', () => res.destroy())
    .pipe(res);
}

function serveStatic(req, res, pathname) {
  let rel = decodeURIComponent(pathname).replace(/^\/+/, '');
  if (!rel) rel = 'index.html';
  let file = path.join(STATIC, rel);
  if (!file.startsWith(STATIC)) {
    res.writeHead(403).end();
    return;
  }
  if (!fs.existsSync(file) || fs.statSync(file).isDirectory()) file = path.join(STATIC, 'index.html');
  if (!fs.existsSync(file)) {
    res.writeHead(404, { 'content-type': 'text/plain' }).end('Build the app first: npm run build');
    return;
  }
  const ext = path.extname(file);
  const immutable = /\/assets\//.test(file);
  res.writeHead(200, {
    'content-type': MIME[ext] || 'application/octet-stream',
    'cache-control': immutable ? 'public, max-age=31536000, immutable' : 'no-cache',
  });
  if (req.method === 'HEAD') return res.end();
  fs.createReadStream(file).pipe(res);
}

async function handler(req, res) {
  const url = new URL(req.url, 'http://x');
  const p = url.pathname;
  if (p === '/xt' || p.startsWith('/xt/')) {
    cors(res);
    if (req.method === 'OPTIONS') return res.writeHead(204).end();
    if (req.method !== 'GET' && req.method !== 'HEAD') return res.writeHead(405).end();
    const prefix = `${String(req.headers['x-forwarded-prefix'] || '').replace(/\/+$/, '')}/xt`;
    if (p === '/xt/__reelbox') {
      res.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      return res.end(JSON.stringify({ server: TARGET, app: 'reelbox-proxy', version: 1 }));
    }
    if (p.startsWith('/xt/__abs/')) {
      let abs;
      try {
        abs = new URL(unb64(p.slice('/xt/__abs/'.length)));
      } catch {
        return res.writeHead(400).end();
      }
      if (!allowedOrigins.has(abs.origin)) return res.writeHead(403).end('host not allowed');
      return forward(req, res, abs.href, prefix);
    }
    return forward(req, res, `${TARGET}${p.slice(3)}${url.search}`, prefix);
  }
  if (req.method !== 'GET' && req.method !== 'HEAD') return res.writeHead(405).end();
  serveStatic(req, res, p);
}

const tls = process.env.TLS_CERT && process.env.TLS_KEY;
const server = tls
  ? https.createServer({ cert: fs.readFileSync(process.env.TLS_CERT), key: fs.readFileSync(process.env.TLS_KEY) }, handler)
  : http.createServer(handler);
server.listen(PORT, HOST, () => {
  console.log(`Reelbox on ${tls ? 'https' : 'http'}://${HOST === '0.0.0.0' ? 'localhost' : HOST}:${PORT}  →  Xtream ${TARGET}`);
  console.log(`Serving app from ${STATIC}`);
});
