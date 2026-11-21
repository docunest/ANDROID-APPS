// Family account + cross-device sync through the shared docunest backend (monorepo apps/api).
//
// How it works: every local write to a synced key (favorites, bookmarks, progress, picture
// settings, server logins) is diffed against the previous value and turned into per-item
// changes in an outbox. The outbox is pushed to /reelbox/sync, which returns everything other
// devices changed since our cursor. Conflicts: last write wins per item (by change time).
// Without a family account the app works exactly as before — sync is optional.
import { useEffect, useState } from 'react';
import { allKeys, load, onSave, save, update } from './store';
import { DEFAULT_PICTURE } from './picture';
import { DEFAULT_API, deviceLabel, isOnline, isVisible, onWake } from './env';
export { DEFAULT_API };

export interface CloudUser {
  id: string;
  email: string;
  name: string | null;
}
export interface CloudSession {
  api: string;
  token: string;
  expiresAt: string;
  user: CloudUser;
}
export interface CloudStatus {
  session: CloudSession | null;
  syncing: boolean;
  lastSync: number | null;
  error: string | null;
  expired: boolean;
  pending: number;
}

type Kind = 'favorite' | 'moment' | 'progress' | 'picture' | 'pictureDefault' | 'account';
interface Change {
  kind: Kind;
  key: string;
  value: unknown | null;
  at: number;
}


// ---------------- which local keys sync, and how ----------------

type Shape = 'map' | 'list' | 'single' | 'accounts';
interface Rule {
  kind: Kind;
  shape: Shape;
  match: (storeKey: string) => string | null; // → account id ('' for global)
  storeKey: (acc: string) => string;
}
const accKey = (what: string): Pick<Rule, 'match' | 'storeKey'> => ({
  match: (k) => {
    const m = /^([^:|]+):(.+)$/.exec(k);
    return m && m[2] === what ? m[1] : null;
  },
  storeKey: (acc) => `${acc}:${what}`,
});
const RULES: Rule[] = [
  { kind: 'favorite', shape: 'map', ...accKey('favorites') },
  { kind: 'progress', shape: 'map', ...accKey('progress') },
  { kind: 'picture', shape: 'map', ...accKey('picture') },
  { kind: 'moment', shape: 'list', ...accKey('moments') },
  { kind: 'pictureDefault', shape: 'single', ...accKey('pictureDefault') },
  { kind: 'account', shape: 'accounts', match: (k) => (k === 'accounts' ? '' : null), storeKey: () => 'accounts' },
];

const itemId = (c: Pick<Change, 'kind' | 'key'>) => `${c.kind}|${c.key}`;
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

/** Items of a stored value, keyed by their sync key. */
function items(rule: Rule, acc: string, value: unknown): Map<string, unknown> {
  const out = new Map<string, unknown>();
  if (value === undefined || value === null) return out;
  if (rule.shape === 'map') for (const [k, v] of Object.entries(value as Record<string, unknown>)) out.set(`${acc}|${k}`, v);
  else if (rule.shape === 'list') for (const v of value as { id: string }[]) out.set(`${acc}|${v.id}`, v);
  else if (rule.shape === 'accounts') for (const v of value as { id: string }[]) out.set(v.id, v);
  else out.set(acc, value);
  return out;
}

// ---------------- state ----------------

let session: CloudSession | null = null;
let cursor = 0;
let device = '';
let outbox = new Map<string, Change>();
let versions: Record<string, number> = {};
let status: Omit<CloudStatus, 'session' | 'pending'> = { syncing: false, lastSync: null, error: null, expired: false };
const listeners = new Set<() => void>();
const emit = () => listeners.forEach((f) => f());

let persistTimer: ReturnType<typeof setTimeout> | null = null;
function persistSoon() {
  if (persistTimer) return;
  persistTimer = setTimeout(() => {
    persistTimer = null;
    save('cloud:outbox', [...outbox.values()], { quiet: true });
    save('cloud:versions', versions, { quiet: true });
  }, 400);
}

let debounce: ReturnType<typeof setTimeout> | null = null;
let firstQueued = 0;
function scheduleSync() {
  if (!session) return;
  const now = Date.now();
  if (!debounce) firstQueued = now;
  if (debounce) clearTimeout(debounce);
  // wait for a quiet moment, but never longer than 20 s (progress saves every 5 s while playing)
  const wait = Math.max(0, Math.min(3000, firstQueued + 20000 - now));
  debounce = setTimeout(() => {
    debounce = null;
    syncNow();
  }, wait);
}

function record(kind: Kind, key: string, value: unknown | null) {
  const at = Math.max(Date.now(), (versions[`${kind}|${key}`] ?? 0) + 1);
  const c: Change = { kind, key, value: value === undefined ? null : value, at };
  versions[itemId(c)] = at;
  if (!session) {
    persistSoon(); // remember change times; signing in later offers everything anyway
    return;
  }
  outbox.set(itemId(c), c);
  persistSoon();
  emit();
  scheduleSync();
}

// Local edits → outbox.
onSave((storeKey, value, prev) => {
  for (const rule of RULES) {
    const acc = rule.match(storeKey);
    if (acc === null) continue;
    const before = items(rule, acc, prev);
    const after = items(rule, acc, value);
    for (const [k, v] of after) if (!same(before.get(k), v)) record(rule.kind, k, v);
    for (const k of before.keys()) if (!after.has(k)) record(rule.kind, k, null);
    return;
  }
});

/** Remote change → local store, without re-recording it. */
async function applyRemote(c: Change) {
  const id = itemId(c);
  if ((versions[id] ?? 0) >= c.at) return; // ours is newer or the same change echoed back
  versions[id] = c.at;
  outbox.delete(id);
  const rule = RULES.find((r) => r.kind === c.kind);
  if (!rule) return;
  const quiet = { quiet: true };
  if (rule.shape === 'accounts') {
    await update<{ id: string }[]>(
      'accounts',
      [],
      (cur) => {
        const rest = cur.filter((a) => a.id !== c.key);
        return c.value ? [...rest, c.value as { id: string }] : rest;
      },
      quiet,
    );
    return;
  }
  const sep = c.key.indexOf('|');
  const acc = rule.shape === 'single' ? c.key : c.key.slice(0, sep);
  const sub = c.key.slice(sep + 1);
  const sk = rule.storeKey(acc);
  if (rule.shape === 'map') {
    await update<Record<string, unknown>>(
      sk,
      {},
      (cur) => {
        const n = { ...cur };
        if (c.value === null) delete n[sub];
        else n[sub] = c.value;
        return n;
      },
      quiet,
    );
  } else if (rule.shape === 'list') {
    await update<{ id: string; at?: number }[]>(
      sk,
      [],
      (cur) => {
        const rest = cur.filter((m) => m.id !== sub);
        const next = c.value ? [...rest, c.value as { id: string; at?: number }] : rest;
        return next.sort((a, b) => (b.at ?? 0) - (a.at ?? 0));
      },
      quiet,
    );
  } else {
    await save(sk, c.value ?? DEFAULT_PICTURE, quiet);
  }
}

// ---------------- network ----------------

async function call<T>(api: string, path: string, body: unknown, token?: string): Promise<T> {
  let res: Response;
  try {
    res = await fetch(`${api}${path}`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
      body: JSON.stringify(body),
    });
  } catch {
    throw new Error('Cannot reach the family account server.');
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const e = new Error(typeof data?.message === 'string' ? data.message : `Server answered ${res.status}`) as Error & { status?: number };
    e.status = res.status;
    throw e;
  }
  return data as T;
}

let running: Promise<void> | null = null;
let again = false;

export function syncNow(): Promise<void> {
  if (running) {
    again = true;
    return running;
  }
  running = (async () => {
    do {
      again = false;
      await syncOnce();
    } while (again);
  })().finally(() => {
    running = null;
  });
  return running;
}

async function syncOnce() {
  const s = session;
  if (!s || !isOnline()) return;
  status = { ...status, syncing: true, error: null };
  emit();
  try {
    for (let round = 0; round < 50; round++) {
      const sent = [...outbox.values()].slice(0, 500);
      const r = await call<{ cursor: number; more: boolean; changes: Change[]; rejected: { reason: string }[] }>(
        s.api,
        '/reelbox/sync',
        { since: cursor, device, changes: sent },
        s.token,
      );
      if (session !== s) return; // signed out meanwhile
      for (const c of sent) if (outbox.get(itemId(c))?.at === c.at) outbox.delete(itemId(c));
      for (const c of r.changes) await applyRemote(c);
      cursor = r.cursor;
      await save('cloud:cursor', cursor, { quiet: true });
      persistSoon();
      if (r.rejected?.length) status = { ...status, error: r.rejected[0].reason };
      if (!r.more && !outbox.size) break;
    }
    status = { ...status, syncing: false, lastSync: Date.now(), expired: false };
  } catch (e) {
    const err = e as Error & { status?: number };
    status = { ...status, syncing: false, error: err.status === 401 ? 'Signed out — sign in again.' : err.message, expired: err.status === 401 };
  }
  emit();
}

// ---------------- account ----------------

async function startSession(s: CloudSession) {
  session = s;
  cursor = 0;
  await save('cloud:session', s, { quiet: true });
  await save('cloud:cursor', 0, { quiet: true });
  status = { syncing: false, lastSync: null, error: null, expired: false };
  // Offer everything already on this device. Items never synced before get a low change time,
  // so if the family account already has a newer copy, that copy wins.
  for (const k of await allKeys()) {
    const rule = RULES.find((r) => r.match(k) !== null);
    if (!rule) continue;
    const acc = rule.match(k)!;
    for (const [key, value] of items(rule, acc, await load(k, undefined))) {
      const id = `${rule.kind}|${key}`;
      const at = versions[id] ?? 1;
      versions[id] = at;
      outbox.set(id, { kind: rule.kind, key, value, at });
    }
  }
  persistSoon();
  emit();
  await syncNow();
}

export async function signIn(api: string, email: string, password: string) {
  api = api.trim().replace(/\/+$/, '');
  const r = await call<Omit<CloudSession, 'api'>>(api, '/auth/login', { email, password });
  await save('cloud:api', api, { quiet: true });
  await startSession({ ...r, api });
}

export async function signInWithLink(api: string, token: string) {
  api = api.trim().replace(/\/+$/, '');
  const r = await call<Omit<CloudSession, 'api'>>(api, '/auth/magic', { token });
  await save('cloud:api', api, { quiet: true });
  await startSession({ ...r, api });
}

export async function signOut() {
  session = null;
  outbox = new Map();
  cursor = 0;
  await save('cloud:session', null, { quiet: true });
  await save('cloud:outbox', [], { quiet: true });
  status = { syncing: false, lastSync: null, error: null, expired: false };
  emit();
}

export async function lastApi(): Promise<string> {
  return (await load<string>('cloud:api', '')) || DEFAULT_API;
}

// ---------------- lifecycle ----------------

let started: Promise<void> | null = null;
export function startCloud(): Promise<void> {
  if (started) return started;
  started = (async () => {
    session = await load<CloudSession | null>('cloud:session', null);
    cursor = await load<number>('cloud:cursor', 0);
    // merge with anything recorded before this finished loading
    versions = { ...(await load<Record<string, number>>('cloud:versions', {})), ...versions };
    outbox = new Map([...(await load<Change[]>('cloud:outbox', [])).map((c) => [itemId(c), c] as const), ...outbox]);
    device = await load<string>('cloud:device', '');
    if (!device) {
      device = `${deviceLabel()}-${Math.random().toString(36).slice(2, 8)}`.slice(0, 80);
      await save('cloud:device', device, { quiet: true });
    }
    emit();
    onWake(() => syncNow());
    setInterval(() => isVisible() && syncNow(), 60_000);
    syncNow();
  })();
  return started;
}

export function useCloud(): CloudStatus {
  const snap = (): CloudStatus => ({ ...status, session, pending: outbox.size });
  const [s, set] = useState(snap);
  useEffect(() => {
    const f = () => set(snap());
    listeners.add(f);
    startCloud().then(f);
    return () => {
      listeners.delete(f);
    };
  }, []);
  return s;
}
