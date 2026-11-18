// Key/value persistence. Web: IndexedDB (idb-keyval). The native app (Expo) uses kv.native.ts,
// which Metro picks automatically, so store.ts and cache.ts are shared unchanged.
import { createStore, del, get, keys, set } from 'idb-keyval';

export interface KV {
  get<T>(key: string): Promise<T | undefined>;
  set(key: string, value: unknown): Promise<void>;
  del(key: string): Promise<void>;
  keys(): Promise<string[]>;
}

export function kvStore(dbName: string, storeName: string): KV {
  const db = createStore(dbName, storeName);
  return {
    get: <T,>(key: string) => get<T>(key, db),
    set: (key, value) => set(key, value, db),
    del: (key) => del(key, db),
    keys: async () => ((await keys(db)) as unknown[]).map(String),
  };
}
