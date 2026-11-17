#!/usr/bin/env node
// A fake Xtream Codes server for development and the end-to-end tests.
// Login: demo / demo. Generates real test videos with ffmpeg on first start.
//
//   node server/mock-xtream.mjs            (port 9090, no CORS — like most real panels)
//   CORS=1 node server/mock-xtream.mjs     (sends CORS headers, to test "direct" mode)
//   THROTTLE_KBPS=300 …                    (slow down video downloads, to test pause/resume)
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 9090);
const CORS = process.env.CORS === '1';
const THROTTLE = Number(process.env.THROTTLE_KBPS || 0);
const MEDIA = path.join(here, '.mock-media');
const USER = 'demo';
const PASS = 'demo';

// ---------- media ----------
function ensureMedia() {
  fs.mkdirSync(MEDIA, { recursive: true });
  const mp4 = path.join(MEDIA, 'sample.mp4');
  if (!fs.existsSync(mp4)) {
    console.log('Generating sample.mp4 with ffmpeg…');
    execFileSync('ffmpeg', [
      '-loglevel', 'error', '-y',
      '-f', 'lavfi', '-i', 'testsrc2=size=640x360:rate=25:duration=90',
      '-f', 'lavfi', '-i', 'sine=frequency=440:duration=90',
      '-c:v', 'libx264', '-preset', 'veryfast', '-pix_fmt', 'yuv420p', '-b:v', '600k',
      '-c:a', 'aac', '-b:a', '64k', '-movflags', '+faststart', mp4,
    ]);
  }
  const hls = path.join(MEDIA, 'live');
  if (!fs.existsSync(path.join(hls, 'index.m3u8'))) {
    console.log('Generating HLS live sample…');
    fs.mkdirSync(hls, { recursive: true });
    execFileSync('ffmpeg', [
      '-loglevel', 'error', '-y', '-i', mp4, '-c', 'copy', '-f', 'hls', '-hls_time', '4', '-hls_playlist_type', 'vod',
      '-hls_segment_filename', path.join(hls, 'seg%03d.ts'), path.join(hls, 'index.m3u8'),
    ]);
  }
}

// ---------- catalog ----------
const now = Math.floor(Date.now() / 1000);
const vodCats = [
  { category_id: '1', category_name: 'Documentaries', parent_id: 0 },
  { category_id: '2', category_name: 'Family', parent_id: 0 },
  { category_id: '3', category_name: 'Road trips · Spain & Portugal', parent_id: 0 },
  { category_id: '9', category_name: 'Empty category', parent_id: 0 },
];
const titles = [
  'Steel Valley', 'The Fish Market at Dawn', 'Railways of the Douro', 'Bridges of Ronda', 'Mines of Río Tinto',
  'Castles in the Air', 'Port of Vigo', 'Gorges & Canyons', 'The Last Ferry', 'Salt Flats', 'Cork Forest',
  'Windmills of La Mancha', 'Desert Light', 'Atlantic Swell', 'Night Train to Lisboa', 'Olive Harvest',
  'Pyrenees Pass', 'Cathedral Builders', 'Algarve Caves', 'Sierra Nevada Snow', 'Camper Life', 'Tiny Kitchen',
  'Ñandú Ranch', 'São Jorge', 'Évora Bones', 'Picos de Europa', 'Basque Coast', 'Galician Rain',
];
const movies = titles.map((name, i) => ({
  num: i + 1,
  name,
  stream_type: 'movie',
  stream_id: 100 + i,
  stream_icon: `http://localhost:${PORT}/img/m${100 + i}.svg`,
  rating: (5 + ((i * 7) % 50) / 10).toFixed(1),
  rating_5based: Number(((5 + ((i * 7) % 50) / 10) / 2).toFixed(1)),
  added: String(now - i * 86400),
  category_id: String((i % 3) + 1),
  container_extension: i === 5 ? 'mkv' : 'mp4',
  year: String(2010 + (i % 15)),
}));
const seriesCats = [
  { category_id: '11', category_name: 'Engineering', parent_id: 0 },
  { category_id: '12', category_name: 'Kids', parent_id: 0 },
];
const series = [
  { series_id: 500, name: 'How It’s Made: Iberia', category_id: '11', plot: 'Factories, mines and ports of Spain and Portugal.' },
  { series_id: 501, name: 'Little Explorers', category_id: '12', plot: 'A family travels the coast in a camper van.' },
  { series_id: 502, name: 'Bridges', category_id: '11', plot: 'One bridge per episode.' },
].map((s, i) => ({
  ...s,
  cover: `http://localhost:${PORT}/img/s${s.series_id}.svg`,
  genre: i ? 'Family' : 'Documentary',
  releaseDate: `20${18 + i}-05-01`,
  last_modified: String(now - i * 3600),
  rating: String(7 + i),
  backdrop_path: [],
  youtube_trailer: '',
  cast: 'Narrator',
}));
const liveCats = [{ category_id: '21', category_name: 'News', parent_id: 0 }, { category_id: '22', category_name: 'Sport', parent_id: 0 }];
const live = ['RTP 1', 'La 1', 'TV Galicia', 'Canal Sur', 'Sport Live', 'EITB'].map((name, i) => ({
  num: i + 1,
  name,
  stream_type: 'live',
  stream_id: 900 + i,
  stream_icon: `http://localhost:${PORT}/img/l${900 + i}.svg`,
  epg_channel_id: null,
  added: String(now - i * 1000),
  category_id: i < 4 ? '21' : '22',
  tv_archive: 0,
}));

function seriesInfo(id) {
  const s = series.find((x) => x.series_id === Number(id));
  if (!s) return null;
  const seasons = id == 502 ? 1 : 2;
  const eps = {};
  for (let se = 1; se <= seasons; se++) {
    eps[se] = Array.from({ length: 4 }, (_, e) => ({
      id: String(Number(id) * 100 + se * 10 + e + 1),
      episode_num: e + 1,
      title: `${['Pilot', 'The Forge', 'Harbour', 'Finale'][e]}${se > 1 ? ` (S${se})` : ''}`,
      container_extension: 'mp4',
      season: se,
      added: String(now - (se * 10 + e) * 3600),
      info: { duration_secs: 90, duration: '00:01:30', plot: `Episode ${e + 1} of season ${se}.`, movie_image: `http://localhost:${PORT}/img/e${se}${e}.svg` },
    }));
  }
  return {
    seasons: Array.from({ length: seasons }, (_, i) => ({ season_number: i + 1, name: `Season ${i + 1}`, episode_count: 4 })),
    info: s,
    // Series 501 uses the array-of-arrays format some panels return.
    episodes: id == 501 ? Object.values(eps) : eps,
  };
}

function svg(id) {
  const hue = [...id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 0);
  const wide = id.startsWith('l') || id.startsWith('e');
  const [w, h] = wide ? [320, 180] : [200, 300];
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><defs><linearGradient id="g" x2="1" y2="1"><stop offset="0" stop-color="hsl(${hue},60%,45%)"/><stop offset="1" stop-color="hsl(${(hue + 60) % 360},60%,20%)"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/><text x="50%" y="55%" font-family="sans-serif" font-size="${wide ? 40 : 48}" fill="#fff" text-anchor="middle">${id}</text></svg>`;
}

// ---------- http ----------
function json(res, data) {
  res.writeHead(200, { 'content-type': 'application/json' });
  res.end(JSON.stringify(data));
}

function sendFile(req, res, file, type) {
  const stat = fs.statSync(file);
  const size = stat.size;
  const range = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
  let start = 0;
  let end = size - 1;
  let status = 200;
  if (range) {
    start = range[1] ? Number(range[1]) : size - Number(range[2]);
    end = range[1] && range[2] ? Number(range[2]) : size - 1;
    if (start >= size) {
      res.writeHead(416, { 'content-range': `bytes */${size}` });
      return res.end();
    }
    status = 206;
  }
  res.writeHead(status, {
    'content-type': type,
    'content-length': end - start + 1,
    'accept-ranges': 'bytes',
    ...(status === 206 ? { 'content-range': `bytes ${start}-${end}/${size}` } : {}),
  });
  if (req.method === 'HEAD') return res.end();
  const stream = fs.createReadStream(file, { start, end, highWaterMark: 64 * 1024 });
  if (!THROTTLE) return stream.pipe(res);
  // crude throttle: pause after each chunk
  stream.on('data', (chunk) => {
    stream.pause();
    res.write(chunk, () => setTimeout(() => stream.resume(), (chunk.length / (THROTTLE * 1024)) * 1000));
  });
  stream.on('end', () => res.end());
  res.on('close', () => stream.destroy());
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://localhost:${PORT}`);
  const p = url.pathname;
  if (CORS) {
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'Range');
    res.setHeader('Access-Control-Expose-Headers', 'Content-Length, Content-Range');
    if (req.method === 'OPTIONS') return res.writeHead(204).end();
  }
  if (p.startsWith('/img/')) {
    // Images always allow CORS (like TMDB) so the service worker can cache them.
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.writeHead(200, { 'content-type': 'image/svg+xml', 'cache-control': 'max-age=86400' });
    return res.end(svg(p.slice(5).replace('.svg', '')));
  }
  if (p === '/player_api.php') {
    const q = url.searchParams;
    if (q.get('username') !== USER || q.get('password') !== PASS) return json(res, { user_info: { auth: 0 } });
    const action = q.get('action');
    switch (action) {
      case null:
        return json(res, {
          user_info: { username: USER, password: PASS, auth: 1, status: 'Active', exp_date: String(now + 86400 * 200), is_trial: '0', active_cons: '0', created_at: String(now - 86400 * 400), max_connections: '2', allowed_output_formats: ['m3u8', 'ts'] },
          server_info: { url: 'localhost', port: String(PORT), server_protocol: 'http', timezone: 'Europe/Madrid' },
        });
      case 'get_vod_categories':
        return json(res, vodCats);
      case 'get_vod_streams':
        return json(res, q.get('category_id') ? movies.filter((m) => m.category_id === q.get('category_id')) : movies);
      case 'get_vod_info': {
        const m = movies.find((x) => x.stream_id === Number(q.get('vod_id')));
        if (!m) return json(res, []);
        return json(res, {
          info: {
            name: m.name,
            movie_image: m.stream_icon,
            plot: `${m.name} — a ${m.year} film about how things are made in Iberia. Generated by the Reelbox mock server.`,
            genre: ['Documentary', 'Family', 'Travel'][Number(m.category_id) - 1],
            director: 'A. Director',
            cast: 'Ana, João, Iker',
            releasedate: `${m.year}-03-01`,
            duration: '00:01:30',
            duration_secs: 90,
            rating: m.rating,
            youtube_trailer: m.stream_id === 100 ? 'dQw4w9WgXcQ' : '',
            backdrop_path: [`http://localhost:${PORT}/img/b${m.stream_id}.svg`],
            country: 'Spain',
          },
          movie_data: { stream_id: m.stream_id, name: m.name, added: m.added, category_id: m.category_id, container_extension: m.container_extension },
        });
      }
      case 'get_series_categories':
        return json(res, seriesCats);
      case 'get_series':
        return json(res, series);
      case 'get_series_info':
        return json(res, seriesInfo(q.get('series_id')) ?? []);
      case 'get_live_categories':
        return json(res, liveCats);
      case 'get_live_streams':
        return json(res, live);
      default:
        return json(res, []);
    }
  }
  const m = /^\/(lb\/)?(movie|series|live)\/([^/]+)\/([^/]+)\/([^/.]+)\.(\w+)$/.exec(p);
  if (m) {
    const [, lb, kind, u, pw, id, ext] = m;
    if (decodeURIComponent(u) !== USER || decodeURIComponent(pw) !== PASS) return res.writeHead(401).end();
    // Movie 107 redirects to a "load balancer" path like many real panels do.
    if (kind === 'movie' && id === '107' && !lb) {
      res.writeHead(302, { location: `/lb${p}` });
      return res.end();
    }
    if (kind === 'live') {
      if (ext !== 'm3u8') return res.writeHead(404).end();
      res.writeHead(200, { 'content-type': 'application/vnd.apple.mpegurl' });
      const pl = fs.readFileSync(path.join(MEDIA, 'live', 'index.m3u8'), 'utf8').replace(/^(seg\d+\.ts)$/gm, `/hls/$1`);
      return res.end(pl);
    }
    return sendFile(req, res, path.join(MEDIA, 'sample.mp4'), ext === 'mkv' ? 'video/x-matroska' : 'video/mp4');
  }
  const seg = /^\/hls\/(seg\d+\.ts)$/.exec(p);
  if (seg) return sendFile(req, res, path.join(MEDIA, 'live', seg[1]), 'video/mp2t');
  res.writeHead(404).end();
});

ensureMedia();
server.listen(PORT, () => console.log(`Mock Xtream on http://localhost:${PORT}  (demo/demo, CORS ${CORS ? 'on' : 'off'}${THROTTLE ? `, ${THROTTLE} KB/s` : ''})`));
