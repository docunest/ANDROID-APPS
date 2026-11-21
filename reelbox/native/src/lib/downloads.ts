// Offline downloads for the native app: files in the app's documents folder, resumable
// (pause / app restart), one at a time so the server's connection limit isn't exceeded.
import * as FS from 'expo-file-system/legacy';
import { load, update, usePersisted } from '@shared/store';
import { refKey, type MediaRef, type Xtream } from '@shared/xtream';

export type DlStatus = 'queued' | 'downloading' | 'paused' | 'done' | 'error';
export interface Download {
  ref: MediaRef;
  accountId: string;
  url: string;
  file: string; // file:// uri
  status: DlStatus;
  bytes: number;
  total: number;
  error?: string;
  resumeData?: string;
  at: number;
}
type Map_ = Record<string, Download>;
const KEY = 'native:downloads';
const dir = `${FS.documentDirectory}downloads/`;

const tasks = new Map<string, FS.DownloadResumable>();
const live = new Map<string, { bytes: number; total: number }>();
const liveListeners = new Set<() => void>();
let running = false;

const patch = (key: string, p: Partial<Download>) =>
  update<Map_>(KEY, {}, (cur) => (cur[key] ? { ...cur, [key]: { ...cur[key], ...p } } : cur));

export function useDownloads() {
  const [map] = usePersisted<Map_>(KEY, {});
  return map;
}

/** Live byte counts (updated many times a second; persisted state only every few seconds). */
export function subscribeProgress(fn: () => void) {
  liveListeners.add(fn);
  return () => {
    liveListeners.delete(fn);
  };
}
export const liveProgress = (key: string) => live.get(key);

export async function startDownload(client: Xtream, ref: MediaRef) {
  if (ref.kind === 'live') return;
  const key = refKey(ref);
  const ext = (ref.ext || 'mp4').replace(/[^a-z0-9]/gi, '') || 'mp4';
  await FS.makeDirectoryAsync(dir, { intermediates: true }).catch(() => {});
  await update<Map_>(KEY, {}, (cur) =>
    cur[key]?.status === 'done'
      ? cur
      : {
          ...cur,
          [key]: {
            ref,
            accountId: client.account.id,
            url: client.externalUrl(ref),
            file: `${dir}${ref.kind}-${ref.id.replace(/[^a-z0-9_-]/gi, '_')}.${ext}`,
            status: 'queued',
            bytes: cur[key]?.bytes ?? 0,
            total: cur[key]?.total ?? 0,
            resumeData: cur[key]?.resumeData,
            at: Date.now(),
          },
        },
  );
  pump();
}

export async function pauseDownload(key: string) {
  const t = tasks.get(key);
  if (t) {
    try {
      const st = await t.pauseAsync();
      await patch(key, { status: 'paused', resumeData: st.resumeData });
    } catch {
      await patch(key, { status: 'paused' });
    }
    tasks.delete(key);
  } else await patch(key, { status: 'paused' });
}

export async function resumeDownload(key: string) {
  await patch(key, { status: 'queued', error: undefined });
  pump();
}

export async function removeDownload(key: string) {
  const t = tasks.get(key);
  tasks.delete(key);
  if (t) await t.cancelAsync().catch(() => {});
  const d = (await load<Map_>(KEY, {}))[key];
  if (d) await FS.deleteAsync(d.file, { idempotent: true }).catch(() => {});
  await update<Map_>(KEY, {}, (cur) => {
    const n = { ...cur };
    delete n[key];
    return n;
  });
}

export async function localFileFor(ref: MediaRef): Promise<string | null> {
  const d = (await load<Map_>(KEY, {}))[refKey(ref)];
  if (!d || d.status !== 'done') return null;
  const info = await FS.getInfoAsync(d.file).catch(() => null);
  return info?.exists ? d.file : null;
}

/** Restart unfinished downloads after the app was closed. */
export async function resumeAllOnLaunch() {
  await update<Map_>(KEY, {}, (cur) => {
    const n = { ...cur };
    for (const [k, d] of Object.entries(n)) if (d.status === 'downloading') n[k] = { ...d, status: 'queued' };
    return n;
  });
  pump();
}

async function pump() {
  if (running) return;
  running = true;
  try {
    for (;;) {
      const map = await load<Map_>(KEY, {});
      const next = Object.entries(map)
        .filter(([, d]) => d.status === 'queued')
        .sort((a, b) => a[1].at - b[1].at)[0];
      if (!next) break;
      await runOne(next[0], next[1]);
    }
  } finally {
    running = false;
  }
}

async function runOne(key: string, d: Download) {
  await patch(key, { status: 'downloading', error: undefined });
  let lastSave = 0;
  const task = FS.createDownloadResumable(
    d.url,
    d.file,
    {},
    (p) => {
      const total = p.totalBytesExpectedToWrite > 0 ? p.totalBytesExpectedToWrite : 0;
      live.set(key, { bytes: p.totalBytesWritten, total });
      liveListeners.forEach((f) => f());
      const now = Date.now();
      if (now - lastSave > 4000) {
        lastSave = now;
        patch(key, { bytes: p.totalBytesWritten, total });
      }
    },
    d.resumeData,
  );
  tasks.set(key, task);
  try {
    const r = d.resumeData ? await task.resumeAsync() : await task.downloadAsync();
    if (tasks.get(key) !== task) return; // paused or removed meanwhile
    tasks.delete(key);
    if (!r) return;
    if (r.status >= 400) {
      await FS.deleteAsync(d.file, { idempotent: true }).catch(() => {});
      await patch(key, { status: 'error', error: `Server answered HTTP ${r.status}`, resumeData: undefined });
      return;
    }
    const info = await FS.getInfoAsync(d.file);
    const size = info.exists ? (info.size ?? 0) : 0;
    await patch(key, { status: 'done', bytes: size, total: size, resumeData: undefined });
    live.delete(key);
    liveListeners.forEach((f) => f());
  } catch (e) {
    if (tasks.get(key) !== task) return;
    tasks.delete(key);
    await patch(key, { status: 'error', error: (e as Error)?.message || 'Download failed' });
  }
}

export function fmtBytes(n: number) {
  if (!n) return '0 MB';
  if (n > 1e9) return `${(n / 1e9).toFixed(2)} GB`;
  return `${Math.round(n / 1e6)} MB`;
}
