// Favorites, position bookmarks ("moments"), watch progress. Stored per account, on-device.
import { load, save, update, usePersisted } from './store';
import { refKey, type MediaRef } from './xtream';

export type FavKind = 'movie' | 'series' | 'live';
export interface Favorite {
  kind: FavKind;
  id: string;
  name: string;
  poster?: string;
  at: number;
}
export interface Moment {
  id: string;
  ref: MediaRef;
  pos: number;
  note: string;
  at: number;
}
export interface Progress {
  ref: MediaRef;
  pos: number;
  dur: number;
  at: number;
  watched: boolean;
}

const ns = (acc: string, what: string) => `${acc}:${what}`;

export function useFavorites(acc: string) {
  const [favs, mutate] = usePersisted<Record<string, Favorite>>(ns(acc, 'favorites'), {});
  return {
    favs,
    isFav: (kind: FavKind, id: string) => !!favs[`${kind}:${id}`],
    toggle: (f: Omit<Favorite, 'at'>) =>
      mutate((cur) => {
        const key = `${f.kind}:${f.id}`;
        const next = { ...cur };
        if (next[key]) delete next[key];
        else next[key] = { ...f, at: Date.now() };
        return next;
      }),
  };
}

export function useMoments(acc: string) {
  const [moments, mutate] = usePersisted<Moment[]>(ns(acc, 'moments'), []);
  return {
    moments,
    add: (ref: MediaRef, pos: number, note?: string) =>
      mutate((cur) => [
        { id: Math.random().toString(36).slice(2, 10), ref, pos, note: note || `${ref.name} at ${fmtTime(pos)}`, at: Date.now() },
        ...cur,
      ]),
    remove: (id: string) => mutate((cur) => cur.filter((m) => m.id !== id)),
    rename: (id: string, note: string) => mutate((cur) => cur.map((m) => (m.id === id ? { ...m, note } : m))),
  };
}

export function useProgressMap(acc: string) {
  const [map] = usePersisted<Record<string, Progress>>(ns(acc, 'progress'), {});
  return map;
}

export function saveProgress(acc: string, ref: MediaRef, pos: number, dur: number) {
  if (ref.kind === 'live' || !isFinite(dur) || dur <= 0) return Promise.resolve();
  // Finished = past 92 %, or (for feature-length) inside the last 3 minutes of credits.
  const watched = pos / dur > 0.92 || (dur >= 1200 && dur - pos < 180);
  return update<Record<string, Progress>>(ns(acc, 'progress'), {}, (cur) => ({
    ...cur,
    [refKey(ref)]: { ref, pos, dur, at: Date.now(), watched },
  }));
}

export function clearProgress(acc: string, key: string) {
  return update<Record<string, Progress>>(ns(acc, 'progress'), {}, (cur) => {
    const n = { ...cur };
    delete n[key];
    return n;
  });
}

export async function getProgress(acc: string, key: string): Promise<Progress | undefined> {
  return (await load<Record<string, Progress>>(ns(acc, 'progress'), {}))[key];
}

/** Remember refs so the player can be reopened from a URL alone (reload, deep link). */
export async function rememberRef(ref: MediaRef) {
  await update<Record<string, MediaRef>>('refs', {}, (cur) => ({ ...cur, [refKey(ref)]: ref }));
}
export async function recallRef(key: string): Promise<MediaRef | undefined> {
  return (await load<Record<string, MediaRef>>('refs', {}))[key];
}

/** Continue-watching list: started, not finished, newest first. */
export function continueWatching(map: Record<string, Progress>): Progress[] {
  const seen = new Set<string>();
  return Object.values(map)
    .filter((p) => !p.watched && p.pos > 20)
    .sort((a, b) => b.at - a.at)
    .filter((p) => {
      // one entry per series
      const k = p.ref.seriesId ? `s:${p.ref.seriesId}` : refKey(p.ref);
      if (seen.has(k)) return false;
      seen.add(k);
      return true;
    });
}

export async function exportUserData(acc: string) {
  return {
    app: 'reelbox',
    version: 1,
    exportedAt: new Date().toISOString(),
    favorites: await load(ns(acc, 'favorites'), {}),
    moments: await load(ns(acc, 'moments'), []),
    progress: await load(ns(acc, 'progress'), {}),
  };
}

export async function importUserData(acc: string, data: any) {
  if (!data || data.app !== 'reelbox') throw new Error('Not a Reelbox export file.');
  await update<Record<string, Favorite>>(ns(acc, 'favorites'), {}, (cur) => ({ ...cur, ...(data.favorites ?? {}) }));
  await update<Moment[]>(ns(acc, 'moments'), [], (cur) => {
    const ids = new Set(cur.map((m) => m.id));
    return [...cur, ...((data.moments ?? []) as Moment[]).filter((m) => !ids.has(m.id))];
  });
  await update<Record<string, Progress>>(ns(acc, 'progress'), {}, (cur) => {
    const out = { ...cur };
    for (const [k, p] of Object.entries((data.progress ?? {}) as Record<string, Progress>)) {
      if (!out[k] || out[k].at < p.at) out[k] = p;
    }
    return out;
  });
  await save('lastImport', Date.now());
}

export function fmtTime(sec: number): string {
  if (!isFinite(sec) || sec < 0) return '0:00';
  sec = Math.floor(sec);
  const h = Math.floor(sec / 3600);
  const m = Math.floor((sec % 3600) / 60);
  const s = sec % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}` : `${m}:${String(s).padStart(2, '0')}`;
}
