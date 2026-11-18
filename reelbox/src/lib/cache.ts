// Offline-first catalog cache: show the saved copy instantly, refresh in the background
// when it's older than maxAge and we're online. Everything lives in IndexedDB, so the
// catalog stays browsable with no network.
import { kvStore } from './kv';
import { isOnline } from './env';
import { useCallback, useEffect, useRef, useState } from 'react';

const cacheDb = kvStore('reelbox-cache', 'responses');

interface Entry<T> {
  at: number;
  data: T;
}

const mem = new Map<string, Entry<unknown>>();
const inflight = new Map<string, Promise<Entry<unknown>>>();
const listeners = new Map<string, Set<() => void>>();

export const HOUR = 3600_000;

async function readEntry<T>(key: string): Promise<Entry<T> | undefined> {
  const m = mem.get(key) as Entry<T> | undefined;
  if (m) return m;
  const v = await cacheDb.get<Entry<T>>(key).catch(() => undefined);
  if (v) mem.set(key, v);
  return v;
}

export async function fetchAndStore<T>(key: string, fetcher: () => Promise<T>): Promise<Entry<T>> {
  const existing = inflight.get(key);
  if (existing) return existing as Promise<Entry<T>>;
  const p = (async () => {
    const data = await fetcher();
    const entry = { at: Date.now(), data };
    mem.set(key, entry);
    await cacheDb.set(key, entry).catch((e) => console.warn('cache write failed', key, e));
    listeners.get(key)?.forEach((fn) => fn());
    return entry;
  })();
  inflight.set(key, p);
  try {
    return await p;
  } finally {
    inflight.delete(key);
  }
}

/** Non-hook access (used by search, player, prefetch). */
export async function cached<T>(key: string, fetcher: () => Promise<T>, maxAge: number): Promise<T> {
  const e = await readEntry<T>(key);
  if (e && (Date.now() - e.at < maxAge || !isOnline())) return e.data;
  try {
    return (await fetchAndStore(key, fetcher)).data;
  } catch (err) {
    if (e) return e.data; // stale beats nothing
    throw err;
  }
}

export async function peek<T>(key: string): Promise<T | undefined> {
  return (await readEntry<T>(key))?.data;
}

export async function clearCache(prefix = ''): Promise<void> {
  const all = await cacheDb.keys().catch(() => [] as string[]);
  for (const k of all) if (String(k).startsWith(prefix)) await cacheDb.del(k).catch(() => {});
  for (const k of [...mem.keys()]) if (k.startsWith(prefix)) mem.delete(k);
}

export interface Query<T> {
  data?: T;
  loading: boolean;
  error: Error | null;
  /** When the shown data was fetched from the server. */
  at: number | null;
  /** True when showing saved data because refresh failed or we are offline. */
  stale: boolean;
  refresh: () => void;
}

export function useCachedQuery<T>(key: string | null, fetcher: () => Promise<T>, maxAge: number): Query<T> {
  const [state, setState] = useState<{ data?: T; at: number | null; loading: boolean; error: Error | null; stale: boolean }>(
    () => {
      const m = key ? (mem.get(key) as Entry<T> | undefined) : undefined;
      return { data: m?.data, at: m?.at ?? null, loading: !m, error: null, stale: false };
    },
  );
  const fetchRef = useRef(fetcher);
  fetchRef.current = fetcher;
  const [nonce, setNonce] = useState(0);
  const forceRef = useRef(false);

  useEffect(() => {
    if (!key) return;
    let alive = true;
    const force = forceRef.current;
    forceRef.current = false;
    (async () => {
      const e = await readEntry<T>(key);
      if (!alive) return;
      if (e) setState({ data: e.data, at: e.at, loading: false, error: null, stale: false });
      else setState((s) => ({ ...s, data: undefined, at: null, loading: true, error: null }));
      const fresh = e && Date.now() - e.at < maxAge;
      if (fresh && !force) return;
      if (!isOnline()) {
        if (alive) setState((s) => ({ ...s, loading: false, stale: !!e, error: e ? null : new Error('You are offline and this has not been saved yet.') }));
        return;
      }
      try {
        const n = await fetchAndStore(key, () => fetchRef.current());
        if (alive) setState({ data: n.data, at: n.at, loading: false, error: null, stale: false });
      } catch (err) {
        if (alive) setState((s) => ({ ...s, loading: false, stale: !!e, error: e ? null : (err as Error) }));
      }
    })();
    const fn = () => {
      const m = mem.get(key) as Entry<T> | undefined;
      if (m && alive) setState({ data: m.data, at: m.at, loading: false, error: null, stale: false });
    };
    let s = listeners.get(key);
    if (!s) listeners.set(key, (s = new Set()));
    s.add(fn);
    return () => {
      alive = false;
      s!.delete(fn);
    };
  }, [key, maxAge, nonce]);

  const refresh = useCallback(() => {
    forceRef.current = true;
    setNonce((n) => n + 1);
  }, []);

  return { ...state, refresh };
}
