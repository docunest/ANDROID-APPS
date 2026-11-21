// Offline downloads. Three storage drivers behind one interface:
//  - native: Capacitor FileTransfer to app storage (iOS: Library/NoCloud, not iCloud-backed-up)
//  - opfs:   Origin Private File System in browsers/PWAs (resumable via HTTP Range)
//  - idb:    IndexedDB chunk storage fallback for engines without OPFS writable streams (resumable)
import { Capacitor } from '@capacitor/core';
import { useEffect, useState } from 'react';
import { load, save } from './store';
import { refKey, type MediaRef, type Xtream } from './xtream';
import { isNative } from './platform';
import { createStore, get as idbGet, set as idbSet, del as idbDel } from 'idb-keyval';

export type DlStatus = 'queued' | 'downloading' | 'paused' | 'done' | 'error';
export type DriverName = 'native' | 'opfs' | 'idb';

export interface DownloadRec {
  key: string;
  accountId: string;
  ref: MediaRef;
  url: string;
  file: string;
  driver: DriverName;
  status: DlStatus;
  bytes: number;
  total: number;
  error?: string;
  addedAt: number;
  doneAt?: number;
}

interface Task {
  url: string;
  file: string;
  offset: number;
  signal: AbortSignal;
  onProgress: (bytes: number, total: number) => void;
}

interface Driver {
  name: DriverName;
  canPause: boolean;
  download(t: Task): Promise<{ bytes: number; total: number }>;
  size(file: string): Promise<number>;
  playUrl(file: string, mime: string): Promise<{ url: string; revoke?: () => void }>;
  remove(file: string): Promise<void>;
}

const CHECKPOINT = 32 * 1024 * 1024;

function totalFrom(res: Response, offset: number): number {
  const cr = res.headers.get('content-range'); // bytes 100-199/1000
  const m = cr && /\/(\d+)/.exec(cr);
  if (m) return Number(m[1]);
  const len = Number(res.headers.get('content-length'));
  return len ? len + offset : 0;
}

async function openRange(url: string, offset: number, signal: AbortSignal) {
  const res = await fetch(url, { headers: offset > 0 ? { Range: `bytes=${offset}-` } : {}, signal, cache: 'no-store' });
  if (!res.ok || !res.body) throw new Error(`Server answered HTTP ${res.status}`);
  const resumed = offset > 0 && res.status === 206;
  const start = resumed ? offset : 0;
  return { res, start, total: totalFrom(res, start), reader: res.body.getReader() };
}

// ---------- OPFS ----------
const opfs: Driver = {
  name: 'opfs',
  canPause: true,
  async download({ url, file, offset, signal, onProgress }) {
    const dir = await opfsDir();
    const fh = await dir.getFileHandle(file, { create: true });
    const { start, total, reader } = await openRange(url, offset, signal);
    let pos = start;
    let w = await (fh as any).createWritable({ keepExistingData: start > 0 });
    if (start > 0) await w.seek(start);
    else await w.truncate(0);
    let sinceCheckpoint = 0;
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        await w.write(value);
        pos += value.byteLength;
        sinceCheckpoint += value.byteLength;
        onProgress(pos, total);
        if (sinceCheckpoint >= CHECKPOINT) {
          // Commit what we have so an app kill / reload can resume from here.
          await w.close();
          w = await (fh as any).createWritable({ keepExistingData: true });
          await w.seek(pos);
          sinceCheckpoint = 0;
        }
      }
      await w.close();
    } catch (e) {
      await w.close().catch(() => {});
      throw e;
    }
    return { bytes: pos, total: total || pos };
  },
  async size(file) {
    try {
      return (await (await (await opfsDir()).getFileHandle(file)).getFile()).size;
    } catch {
      return 0;
    }
  },
  async playUrl(file, mime) {
    const f = await (await (await opfsDir()).getFileHandle(file)).getFile();
    const url = URL.createObjectURL(mime ? new Blob([f], { type: mime }) : f);
    return { url, revoke: () => URL.revokeObjectURL(url) };
  },
  async remove(file) {
    await (await opfsDir()).removeEntry(file).catch(() => {});
  },
};

async function opfsDir() {
  const root = await navigator.storage.getDirectory();
  return root.getDirectoryHandle('downloads', { create: true });
}

// ---------- IndexedDB chunks ----------
const chunkDb = createStore('reelbox-files', 'chunks');
const CHUNK = 4 * 1024 * 1024; // at most this much is lost if the app is killed mid-download
interface ChunkIndex {
  count: number;
  bytes: number;
}

const idb: Driver = {
  name: 'idb',
  canPause: true,
  async download({ url, file, offset, signal, onProgress }) {
    const idx = (await idbGet<ChunkIndex>(`${file}:index`, chunkDb)) ?? { count: 0, bytes: 0 };
    // Resume only on a chunk boundary we fully stored.
    const from = Math.min(offset, idx.bytes);
    const { start, total, reader } = await openRange(url, from, signal);
    if (start === 0) {
      for (let i = 0; i < idx.count; i++) await idbDel(`${file}:${i}`, chunkDb);
      idx.count = 0;
      idx.bytes = 0;
    }
    let parts: Uint8Array[] = [];
    let partBytes = 0;
    const flush = async () => {
      if (!partBytes) return;
      await idbSet(`${file}:${idx.count}`, new Blob(parts as BlobPart[]), chunkDb);
      idx.count++;
      idx.bytes += partBytes;
      await idbSet(`${file}:index`, idx, chunkDb);
      parts = [];
      partBytes = 0;
    };
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        parts.push(value);
        partBytes += value.byteLength;
        onProgress(idx.bytes + partBytes, total);
        if (partBytes >= CHUNK) await flush();
      }
      await flush();
    } catch (e) {
      await flush().catch(() => {});
      throw e;
    }
    return { bytes: idx.bytes, total: total || idx.bytes };
  },
  async size(file) {
    return (await idbGet<ChunkIndex>(`${file}:index`, chunkDb))?.bytes ?? 0;
  },
  async playUrl(file, mime) {
    const idx = (await idbGet<ChunkIndex>(`${file}:index`, chunkDb)) ?? { count: 0, bytes: 0 };
    const blobs: Blob[] = [];
    for (let i = 0; i < idx.count; i++) {
      const b = await idbGet<Blob>(`${file}:${i}`, chunkDb);
      if (b) blobs.push(b);
    }
    const url = URL.createObjectURL(new Blob(blobs, { type: mime || 'video/mp4' }));
    return { url, revoke: () => URL.revokeObjectURL(url) };
  },
  async remove(file) {
    const idx = await idbGet<ChunkIndex>(`${file}:index`, chunkDb);
    for (let i = 0; i < (idx?.count ?? 0); i++) await idbDel(`${file}:${i}`, chunkDb);
    await idbDel(`${file}:index`, chunkDb);
  },
};

// ---------- Native (Capacitor) ----------
async function nativeUri(file: string) {
  const { Filesystem, Directory } = await import('@capacitor/filesystem');
  const directory = Capacitor.getPlatform() === 'ios' ? Directory.LibraryNoCloud : Directory.Data;
  await Filesystem.mkdir({ path: 'downloads', directory, recursive: true }).catch(() => {});
  const { uri } = await Filesystem.getUri({ path: `downloads/${file}`, directory });
  return { Filesystem, uri };
}

const native: Driver = {
  name: 'native',
  canPause: false,
  async download({ url, file, onProgress, signal }) {
    const { FileTransfer } = await import('@capacitor/file-transfer');
    const { Filesystem, uri } = await nativeUri(file);
    const h = await FileTransfer.addListener('progress', (p) => {
      if (p.url === url) onProgress(p.bytes, p.lengthComputable ? p.contentLength : 0);
    });
    try {
      await FileTransfer.downloadFile({ url, path: uri, progress: true });
    } finally {
      await h.remove();
    }
    if (signal.aborted) throw new DOMException('Aborted', 'AbortError');
    const st = await Filesystem.stat({ path: uri });
    return { bytes: st.size, total: st.size };
  },
  async size(file) {
    try {
      const { Filesystem, uri } = await nativeUri(file);
      return (await Filesystem.stat({ path: uri })).size;
    } catch {
      return 0;
    }
  },
  async playUrl(file) {
    const { uri } = await nativeUri(file);
    return { url: Capacitor.convertFileSrc(uri) };
  },
  async remove(file) {
    const { Filesystem, uri } = await nativeUri(file);
    await Filesystem.deleteFile({ path: uri }).catch(() => {});
  },
};

const drivers: Record<DriverName, Driver> = { native, opfs, idb };

export function pickDriver(): DriverName | null {
  if (isNative) return 'native';
  const secure = window.isSecureContext;
  const hasWritable = typeof (globalThis as any).FileSystemFileHandle?.prototype?.createWritable === 'function';
  if (secure && typeof navigator.storage?.getDirectory === 'function' && hasWritable) return 'opfs';
  if (typeof indexedDB !== 'undefined') return 'idb';
  return null;
}

export const MIME: Record<string, string> = {
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  mov: 'video/quicktime',
  mkv: 'video/x-matroska',
  webm: 'video/webm',
  avi: 'video/x-msvideo',
  ts: 'video/mp2t',
};

// ---------- Manager ----------
type Listener = () => void;

class DownloadManager {
  recs: Record<string, DownloadRec> = {};
  private loaded: Promise<void>;
  private active = new Map<string, AbortController>();
  private listeners = new Set<Listener>();
  private persistTimer: ReturnType<typeof setTimeout> | null = null;
  concurrency = 1;

  constructor() {
    this.loaded = load<Record<string, DownloadRec>>('downloads', {}).then((r) => {
      this.recs = r;
      // Anything that was mid-flight when the app closed is now paused (resumable).
      for (const rec of Object.values(this.recs)) {
        if (rec.status === 'downloading') rec.status = drivers[rec.driver]?.canPause ? 'paused' : 'queued';
      }
      this.emit();
      this.pump();
    });
  }

  ready() {
    return this.loaded;
  }

  subscribe(fn: Listener) {
    this.listeners.add(fn);
    return () => this.listeners.delete(fn);
  }

  private emit(persistNow = true) {
    this.listeners.forEach((fn) => fn());
    if (persistNow) {
      if (this.persistTimer) clearTimeout(this.persistTimer);
      this.persistTimer = null;
      save('downloads', { ...this.recs });
    } else if (!this.persistTimer) {
      this.persistTimer = setTimeout(() => {
        this.persistTimer = null;
        save('downloads', { ...this.recs });
      }, 3000);
    }
  }

  get(key: string) {
    return this.recs[key];
  }

  list() {
    return Object.values(this.recs).sort((a, b) => b.addedAt - a.addedAt);
  }

  async enqueue(client: Xtream, ref: MediaRef) {
    await this.loaded;
    const driver = pickDriver();
    if (!driver) throw new Error('This device cannot store downloads.');
    const key = refKey(ref);
    if (this.recs[key] && this.recs[key].status !== 'error') return;
    navigator.storage?.persist?.().catch(() => {});
    const ext = (ref.ext || 'mp4').toLowerCase();
    this.recs[key] = {
      key,
      accountId: client.account.id,
      ref,
      url: client.streamUrl(ref),
      file: `${key.replace(':', '-')}.${ext}`,
      driver,
      status: 'queued',
      bytes: 0,
      total: 0,
      addedAt: Date.now(),
    };
    this.emit();
    this.pump();
  }

  pause(key: string) {
    const rec = this.recs[key];
    if (!rec) return;
    const ctrl = this.active.get(key);
    if (ctrl) {
      // Status flips to 'paused' in run() once the driver has committed the partial file,
      // so closing the app right after pausing never loses the bytes already downloaded.
      ctrl.abort();
      return;
    }
    rec.status = 'paused';
    this.emit();
  }

  resume(key: string) {
    const rec = this.recs[key];
    if (!rec || rec.status === 'done' || rec.status === 'downloading') return;
    rec.status = 'queued';
    rec.error = undefined;
    this.emit();
    this.pump();
  }

  async remove(key: string) {
    const rec = this.recs[key];
    if (!rec) return;
    this.active.get(key)?.abort();
    delete this.recs[key];
    this.emit();
    await drivers[rec.driver].remove(rec.file);
  }

  async playable(key: string): Promise<{ url: string; revoke?: () => void } | null> {
    await this.loaded;
    const rec = this.recs[key];
    if (!rec || rec.status !== 'done') return null;
    const ext = rec.file.split('.').pop() || 'mp4';
    try {
      return await drivers[rec.driver].playUrl(rec.file, MIME[ext] || '');
    } catch (e) {
      console.warn('offline file missing', e);
      return null;
    }
  }

  private pump() {
    const running = [...this.active.keys()].length;
    if (running >= this.concurrency) return;
    const next = this.list()
      .reverse()
      .find((r) => r.status === 'queued' && !this.active.has(r.key));
    if (!next) return;
    this.run(next);
    this.pump();
  }

  private async run(rec: DownloadRec) {
    const ctrl = new AbortController();
    this.active.set(rec.key, ctrl);
    rec.status = 'downloading';
    rec.error = undefined;
    this.emit();
    const driver = drivers[rec.driver];
    try {
      const offset = driver.canPause ? await driver.size(rec.file) : 0;
      let last = 0;
      const { bytes, total } = await driver.download({
        url: rec.url,
        file: rec.file,
        offset,
        signal: ctrl.signal,
        onProgress: (b, t) => {
          rec.bytes = b;
          if (t) rec.total = t;
          const now = Date.now();
          if (now - last > 400) {
            last = now;
            this.emit(false);
          }
        },
      });
      if (!this.recs[rec.key]) return; // removed meanwhile
      if (total && bytes < total) throw new Error('Connection ended early — tap resume.');
      rec.bytes = bytes;
      rec.total = total;
      rec.status = 'done';
      rec.doneAt = Date.now();
    } catch (e) {
      if (!this.recs[rec.key]) return;
      if ((e as Error)?.name === 'AbortError') {
        rec.status = 'paused';
      } else {
        rec.status = 'error';
        rec.error = errorText(e);
      }
    } finally {
      this.active.delete(rec.key);
      this.emit();
      this.pump();
    }
  }
}

function errorText(e: unknown): string {
  const m = (e as Error)?.message || String(e);
  if (/QuotaExceeded|quota/i.test(m)) return 'Not enough storage space on this device.';
  if (/Failed to fetch|NetworkError|Load failed/i.test(m)) return 'Network error — tap retry when you are back online.';
  return m;
}

export const downloads = new DownloadManager();

export function useDownloads(): DownloadRec[] {
  const [list, setList] = useState(() => downloads.list());
  useEffect(() => {
    const off = downloads.subscribe(() => setList(downloads.list()));
    downloads.ready().then(() => setList(downloads.list()));
    return () => {
      off();
    };
  }, []);
  return list;
}

export function useDownload(key: string): DownloadRec | undefined {
  const list = useDownloads();
  return list.find((d) => d.key === key);
}

export async function storageEstimate(): Promise<{ usage: number; quota: number } | null> {
  try {
    const e = await navigator.storage?.estimate?.();
    return e ? { usage: e.usage ?? 0, quota: e.quota ?? 0 } : null;
  } catch {
    return null;
  }
}

export function fmtBytes(n: number): string {
  if (!n) return '0 B';
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.min(u.length - 1, Math.floor(Math.log(n) / Math.log(1024)));
  return `${(n / 1024 ** i).toFixed(i >= 2 ? 1 : 0)} ${u[i]}`;
}

export const driverCanPause = (d: DriverName) => drivers[d].canPause;
