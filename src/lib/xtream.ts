// Xtream Codes "player_api.php" client.
// Reference: player_api.php?username=U&password=P&action=get_vod_categories|get_vod_streams|get_vod_info|
//            get_series_categories|get_series|get_series_info|get_live_categories|get_live_streams

export type ConnectionMode = 'auto' | 'direct' | 'proxy';

export interface Account {
  id: string;
  name: string;
  server: string; // e.g. http://my.server:8080
  username: string;
  password: string;
  mode: ConnectionMode;
}

export interface UserInfo {
  username: string;
  auth: number;
  status: string;
  exp_date: string | null;
  is_trial: string;
  active_cons: string;
  created_at: string;
  max_connections: string;
  allowed_output_formats?: string[];
}

export interface ServerInfo {
  url: string;
  port: string;
  https_port?: string;
  server_protocol: string;
  timezone?: string;
}

export interface LoginResponse {
  user_info: UserInfo;
  server_info: ServerInfo;
}

export interface Category {
  category_id: string;
  category_name: string;
  parent_id?: number;
}

export interface VodStream {
  num?: number;
  name: string;
  stream_type?: string;
  stream_id: number;
  stream_icon?: string;
  rating?: string | number;
  rating_5based?: number;
  added?: string;
  category_id?: string;
  container_extension?: string;
  year?: string;
}

export interface SeriesItem {
  series_id: number;
  name: string;
  cover?: string;
  plot?: string;
  cast?: string;
  director?: string;
  genre?: string;
  releaseDate?: string;
  release_date?: string;
  last_modified?: string;
  rating?: string | number;
  rating_5based?: number;
  backdrop_path?: string[];
  youtube_trailer?: string;
  episode_run_time?: string;
  category_id?: string;
}

export interface LiveStream {
  num?: number;
  name: string;
  stream_type?: string;
  stream_id: number;
  stream_icon?: string;
  epg_channel_id?: string | null;
  added?: string;
  category_id?: string;
  tv_archive?: number;
}

export interface VodInfo {
  info: {
    name?: string;
    o_name?: string;
    movie_image?: string;
    cover_big?: string;
    backdrop_path?: string[];
    plot?: string;
    description?: string;
    cast?: string;
    actors?: string;
    director?: string;
    genre?: string;
    releasedate?: string;
    release_date?: string;
    duration?: string;
    duration_secs?: number;
    rating?: string | number;
    youtube_trailer?: string;
    country?: string;
  };
  movie_data: {
    stream_id: number;
    name: string;
    added?: string;
    category_id?: string;
    container_extension?: string;
  };
}

export interface Episode {
  id: string;
  episode_num: number | string;
  title: string;
  container_extension?: string;
  season: number | string;
  added?: string;
  info?: {
    movie_image?: string;
    plot?: string;
    duration_secs?: number;
    duration?: string;
    rating?: string | number;
    releasedate?: string;
  };
}

export interface Season {
  season_number: number;
  name?: string;
  cover?: string;
  overview?: string;
  episode_count?: number;
}

export interface SeriesInfo {
  info: SeriesItem;
  seasons: Season[];
  episodes: Record<string, Episode[]>;
}

/** What the player and the user-data layer need to know about anything playable. */
export type Kind = 'movie' | 'episode' | 'live';
export interface MediaRef {
  kind: Kind;
  id: string; // stream_id or episode id
  name: string;
  poster?: string;
  ext?: string; // container extension
  seriesId?: string;
  seriesName?: string;
  season?: number;
  episode?: number;
}

export const refKey = (r: Pick<MediaRef, 'kind' | 'id'>) => `${r.kind}:${r.id}`;

export class XtreamError extends Error {
  constructor(
    message: string,
    public kind: 'network' | 'auth' | 'http' | 'parse' | 'cors',
  ) {
    super(message);
  }
}

const trimSlash = (s: string) => s.replace(/\/+$/, '');

/** Deterministic id for a server login (53-bit string hash, base36). */
export function accountId(server: string, username: string): string {
  const str = `${normalizeServer(server).toLowerCase()}|${username}`;
  let h1 = 0xdeadbeef;
  let h2 = 0x41c6ce57;
  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }
  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507) ^ Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507) ^ Math.imul(h1 ^ (h1 >>> 13), 3266489909);
  return `a${(4294967296 * (2097151 & h2) + (h1 >>> 0)).toString(36)}`;
}

export function normalizeServer(input: string): string {
  let s = input.trim();
  if (!s) return s;
  if (!/^https?:\/\//i.test(s)) s = 'http://' + s;
  try {
    const u = new URL(s);
    // People often paste a full player_api / get.php URL — keep only origin + base path.
    let path = u.pathname.replace(/\/(player_api|get|xmltv)\.php.*$/i, '');
    return trimSlash(u.origin + path);
  } catch {
    return trimSlash(s);
  }
}

export class Xtream {
  constructor(
    public account: Account,
    /** Base URL requests go to: the server itself (direct) or our proxy (…/xt). */
    public base: string,
  ) {}

  private apiUrl(action?: string, params: Record<string, string | number> = {}) {
    const q = new URLSearchParams({ username: this.account.username, password: this.account.password });
    if (action) q.set('action', action);
    for (const [k, v] of Object.entries(params)) q.set(k, String(v));
    return `${trimSlash(this.base)}/player_api.php?${q}`;
  }

  async api<T>(action?: string, params?: Record<string, string | number>, timeoutMs = 45000): Promise<T> {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    let res: Response;
    try {
      res = await fetch(this.apiUrl(action, params), { signal: ctrl.signal, cache: 'no-store' });
    } catch (e) {
      const aborted = (e as Error)?.name === 'AbortError';
      throw new XtreamError(
        aborted
          ? 'The server took too long to answer.'
          : 'Could not reach the server. If it is up, the browser may be blocking it (CORS / http on an https page) — use the bundled proxy, or the native app.',
        aborted ? 'network' : 'cors',
      );
    } finally {
      clearTimeout(timer);
    }
    if (res.status === 401 || res.status === 403) throw new XtreamError('Wrong username or password.', 'auth');
    if (!res.ok) throw new XtreamError(`Server answered HTTP ${res.status}.`, 'http');
    const text = await res.text();
    try {
      return JSON.parse(text) as T;
    } catch {
      throw new XtreamError('Server did not return Xtream JSON — check the server URL.', 'parse');
    }
  }

  async login(): Promise<LoginResponse> {
    const r = await this.api<LoginResponse>(undefined, undefined, 20000);
    if (!r || !r.user_info || Number(r.user_info.auth) !== 1) throw new XtreamError('Wrong username or password.', 'auth');
    return r;
  }

  vodCategories = () => this.api<Category[]>('get_vod_categories').then((v) => arr(v));
  vodStreams = () => this.api<VodStream[]>('get_vod_streams').then((v) => arr<VodStream>(v));
  vodInfo = (id: string | number) => this.api<VodInfo>('get_vod_info', { vod_id: id });
  seriesCategories = () => this.api<Category[]>('get_series_categories').then((v) => arr<Category>(v));
  series = () => this.api<SeriesItem[]>('get_series').then((v) => arr<SeriesItem>(v));
  seriesInfo = (id: string | number) => this.api<any>('get_series_info', { series_id: id }).then(normalizeSeriesInfo);
  liveCategories = () => this.api<Category[]>('get_live_categories').then((v) => arr<Category>(v));
  liveStreams = () => this.api<LiveStream[]>('get_live_streams').then((v) => arr<LiveStream>(v));

  streamUrl(ref: MediaRef, liveFormat: 'm3u8' | 'ts' = 'm3u8'): string {
    const { username: u, password: p } = this.account;
    const b = trimSlash(this.base);
    const enc = encodeURIComponent;
    switch (ref.kind) {
      case 'movie':
        return `${b}/movie/${enc(u)}/${enc(p)}/${ref.id}.${ref.ext || 'mp4'}`;
      case 'episode':
        return `${b}/series/${enc(u)}/${enc(p)}/${ref.id}.${ref.ext || 'mp4'}`;
      case 'live':
        return `${b}/live/${enc(u)}/${enc(p)}/${ref.id}.${liveFormat}`;
    }
  }

  /** URL pointing at the real server (for "open in VLC" / copy link), independent of the proxy. */
  externalUrl(ref: MediaRef): string {
    return new Xtream(this.account, this.account.server).streamUrl(ref);
  }
}

const arr = <T,>(v: T[]): T[] => (Array.isArray(v) ? v : []);

export function normalizeSeriesInfo(raw: any): SeriesInfo {
  const info = (raw?.info ?? {}) as SeriesItem;
  let episodes: Record<string, Episode[]> = {};
  const e = raw?.episodes;
  if (Array.isArray(e)) {
    // Some panels return [[ep, ep], [ep]] instead of {"1": [...]}
    for (const group of e) {
      const list = Array.isArray(group) ? group : [group];
      for (const ep of list) {
        const s = String(ep?.season ?? 1);
        (episodes[s] ||= []).push(ep);
      }
    }
  } else if (e && typeof e === 'object') {
    episodes = e;
  }
  for (const s of Object.keys(episodes)) {
    episodes[s] = episodes[s]
      .map((ep) => ({ ...ep, id: String(ep.id), season: Number(ep.season ?? s) }))
      .sort((a, b) => Number(a.episode_num) - Number(b.episode_num));
  }
  let seasons: Season[] = Array.isArray(raw?.seasons) ? raw.seasons : [];
  // Seasons list is often missing or incomplete; derive from episodes.
  const have = new Set(seasons.map((s) => Number(s.season_number)));
  for (const s of Object.keys(episodes)) {
    if (!have.has(Number(s))) seasons.push({ season_number: Number(s), name: `Season ${s}` });
  }
  seasons = seasons
    .filter((s) => episodes[String(s.season_number)]?.length)
    .sort((a, b) => Number(a.season_number) - Number(b.season_number));
  return { info, seasons, episodes };
}

export function episodeRef(ep: Episode, series: { id: string; name: string; cover?: string }): MediaRef {
  return {
    kind: 'episode',
    id: String(ep.id),
    name: ep.title || `Episode ${ep.episode_num}`,
    poster: ep.info?.movie_image || series.cover,
    ext: ep.container_extension,
    seriesId: series.id,
    seriesName: series.name,
    season: Number(ep.season),
    episode: Number(ep.episode_num),
  };
}

export const movieRef = (m: VodStream): MediaRef => ({
  kind: 'movie',
  id: String(m.stream_id),
  name: m.name,
  poster: m.stream_icon,
  ext: m.container_extension,
});

export const liveRef = (c: LiveStream): MediaRef => ({
  kind: 'live',
  id: String(c.stream_id),
  name: c.name,
  poster: c.stream_icon,
});

export function rating10(r: { rating?: string | number; rating_5based?: number }): number | null {
  const n = Number(r.rating);
  if (n > 0) return Math.round(n * 10) / 10;
  if (r.rating_5based && r.rating_5based > 0) return Math.round(r.rating_5based * 20) / 10;
  return null;
}

export function formatExpiry(exp: string | null | undefined): string {
  if (!exp || exp === 'null') return 'Never';
  const d = new Date(Number(exp) * 1000);
  return isNaN(d.getTime()) ? String(exp) : d.toLocaleDateString();
}
