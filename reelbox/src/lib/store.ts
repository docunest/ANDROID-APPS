// Tiny persistent key/value store on IndexedDB with cross-component change notifications.
import { kvStore } from './kv';
import { useCallback, useEffect, useRef, useState } from 'react';

const db = kvStore('reelbox', 'kv');
const listeners = new Map<string, Set<(v: unknown) => void>>();
const memory = new Map<string, unknown>();

export async function load<T>(key: string, fallback: T): Promise<T> {
  if (memory.has(key)) return memory.get(key) as T;
  try {
    const v = await db.get<T>(key);
    const out = v === undefined ? fallback : v;
    memory.set(key, out);
    return out;
  } catch {
    return fallback;
  }
}

/** Observers of every local write (used by cloud sync to turn edits into synced changes). */
type SaveHook = (key: string, value: unknown, prev: unknown) => void;
const saveHooks = new Set<SaveHook>();
export function onSave(fn: SaveHook): () => void {
  saveHooks.add(fn);
  return () => saveHooks.delete(fn);
}

export interface SaveOpts {
  /** Write without notifying save hooks — for applying changes that came from another device. */
  quiet?: boolean;
}

export async function save<T>(key: string, value: T, opts: SaveOpts = {}): Promise<void> {
  const prev = memory.get(key);
  memory.set(key, value);
  if (!opts.quiet) saveHooks.forEach((fn) => fn(key, value, prev));
  listeners.get(key)?.forEach((fn) => fn(value));
  try {
    await db.set(key, value);
  } catch (e) {
    console.warn('store: save failed', key, e);
  }
}

export async function remove(key: string): Promise<void> {
  memory.delete(key);
  listeners.get(key)?.forEach((fn) => fn(undefined));
  await db.del(key).catch(() => {});
}

export async function allKeys(): Promise<string[]> {
  return await db.keys().catch(() => [] as string[]);
}

export function subscribe(key: string, fn: (v: unknown) => void): () => void {
  let s = listeners.get(key);
  if (!s) listeners.set(key, (s = new Set()));
  s.add(fn);
  return () => s!.delete(fn);
}

/** Read-modify-write that is safe against concurrent updates within this tab. */
const chains = new Map<string, Promise<unknown>>();
export function update<T>(key: string, fallback: T, fn: (cur: T) => T, opts: SaveOpts = {}): Promise<T> {
  const prev = chains.get(key) ?? Promise.resolve();
  const next = prev.then(async () => {
    const cur = await load(key, fallback);
    const out = fn(cur);
    await save(key, out, opts);
    return out;
  });
  chains.set(key, next.catch(() => {}));
  return next;
}

/** React hook: value persisted under `key`, shared by every component using the same key. */
export function usePersisted<T>(key: string, fallback: T): [T, (fn: (cur: T) => T) => Promise<T>, boolean] {
  const fb = useRef(fallback);
  const [value, setValue] = useState<T>(() => (memory.has(key) ? (memory.get(key) as T) : fallback));
  const [ready, setReady] = useState(memory.has(key));
  useEffect(() => {
    let alive = true;
    load(key, fb.current).then((v) => {
      if (alive) {
        setValue(v);
        setReady(true);
      }
    });
    const off = subscribe(key, (v) => setValue((v === undefined ? fb.current : v) as T));
    return () => {
      alive = false;
      off();
    };
  }, [key]);
  const mutate = useCallback((fn: (cur: T) => T) => update(key, fb.current, fn), [key]);
  return [value, mutate, ready];
}
