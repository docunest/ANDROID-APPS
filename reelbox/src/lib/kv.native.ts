// Native (Expo) key/value persistence: one JSON file per key in the app's documents folder.
// Files (not AsyncStorage) because catalog lists can be several MB.
import { Platform } from 'react-native';
import * as FS from 'expo-file-system/legacy';

// Same shape as KV in kv.ts (not imported: from here './kv' would resolve to this file).
export interface KV {
  get<T>(key: string): Promise<T | undefined>;
  set(key: string, value: unknown): Promise<void>;
  del(key: string): Promise<void>;
  keys(): Promise<string[]>;
}

const enc = (k: string) => encodeURIComponent(k).replace(/\*/g, '%2A') + '.json';
const dec = (f: string) => decodeURIComponent(f.replace(/\.json$/, ''));

/** Web preview of the native app (expo start --web): localStorage, no files. */
function webStore(prefix: string): KV {
  const ls = globalThis.localStorage;
  const k = (key: string) => `${prefix}|${key}`;
  return {
    get: async <T,>(key: string) => {
      const v = ls?.getItem(k(key));
      return v == null ? undefined : (JSON.parse(v) as T);
    },
    set: async (key, value) => (value === undefined ? ls?.removeItem(k(key)) : ls?.setItem(k(key), JSON.stringify(value))),
    del: async (key) => ls?.removeItem(k(key)),
    keys: async () =>
      Object.keys(ls ?? {})
        .filter((x) => x.startsWith(`${prefix}|`))
        .map((x) => x.slice(prefix.length + 1)),
  };
}

export function kvStore(dbName: string, storeName: string): KV {
  if (Platform.OS === 'web') return webStore(`${dbName}-${storeName}`);
  const dir = `${FS.documentDirectory}kv/${encodeURIComponent(`${dbName}-${storeName}`)}/`;
  let ready: Promise<void> | null = null;
  const ensure = () =>
    (ready ??= FS.makeDirectoryAsync(dir, { intermediates: true }).catch(() => {}));
  // Writes to one key run in order, so a slow older write never lands after a newer one.
  const chains = new Map<string, Promise<unknown>>();
  const queue = <T,>(key: string, fn: () => Promise<T>): Promise<T> => {
    const p = (chains.get(key) ?? Promise.resolve()).then(fn, fn);
    chains.set(key, p.catch(() => {}));
    return p;
  };
  return {
    async get<T>(key: string) {
      await ensure();
      await chains.get(key);
      try {
        const text = await FS.readAsStringAsync(dir + enc(key));
        return JSON.parse(text) as T;
      } catch {
        return undefined;
      }
    },
    set: (key, value) =>
      queue(key, async () => {
        await ensure();
        if (value === undefined) await FS.deleteAsync(dir + enc(key), { idempotent: true });
        else await FS.writeAsStringAsync(dir + enc(key), JSON.stringify(value));
      }),
    del: (key) =>
      queue(key, async () => {
        await ensure();
        await FS.deleteAsync(dir + enc(key), { idempotent: true });
      }),
    async keys() {
      await ensure();
      const files = await FS.readDirectoryAsync(dir).catch(() => [] as string[]);
      return files.filter((f) => f.endsWith('.json')).map(dec);
    },
  };
}
