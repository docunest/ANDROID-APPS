// Catalog hooks. Full lists are fetched once per type and filtered client-side, which gives
// instant category switching, search across everything, and full offline browsing.
import { cached, fetchAndStore, HOUR, useCachedQuery } from './cache';
import { useClient } from './session';
import type { Category, LiveStream, SeriesInfo, SeriesItem, VodInfo, VodStream, Xtream } from './xtream';

const LIST_AGE = 6 * HOUR;
const INFO_AGE = 7 * 24 * HOUR;

const k = (c: Xtream, what: string) => `${c.account.id}:${what}`;

export const useVodCategories = (enabled = true) => {
  const c = useClient();
  return useCachedQuery<Category[]>(enabled ? k(c, 'vod_categories') : null, c.vodCategories, LIST_AGE);
};
export const useVodStreams = (enabled = true) => {
  const c = useClient();
  return useCachedQuery<VodStream[]>(enabled ? k(c, 'vod_streams') : null, c.vodStreams, LIST_AGE);
};
export const useVodInfo = (id: string) => {
  const c = useClient();
  return useCachedQuery<VodInfo>(k(c, `vod_info:${id}`), () => c.vodInfo(id), INFO_AGE);
};
export const useSeriesCategories = (enabled = true) => {
  const c = useClient();
  return useCachedQuery<Category[]>(enabled ? k(c, 'series_categories') : null, c.seriesCategories, LIST_AGE);
};
export const useSeriesList = (enabled = true) => {
  const c = useClient();
  return useCachedQuery<SeriesItem[]>(enabled ? k(c, 'series') : null, c.series, LIST_AGE);
};
export const useSeriesInfo = (id: string | undefined) => {
  const c = useClient();
  return useCachedQuery<SeriesInfo>(id ? k(c, `series_info:${id}`) : null, () => c.seriesInfo(id!), 12 * HOUR);
};
export const useLiveCategories = (enabled = true) => {
  const c = useClient();
  return useCachedQuery<Category[]>(enabled ? k(c, 'live_categories') : null, c.liveCategories, LIST_AGE);
};
export const useLiveStreams = (enabled = true) => {
  const c = useClient();
  return useCachedQuery<LiveStream[]>(enabled ? k(c, 'live_streams') : null, c.liveStreams, LIST_AGE);
};

export const getSeriesInfo = (c: Xtream, id: string) => cached(k(c, `series_info:${id}`), () => c.seriesInfo(id), 12 * HOUR);
export const getVodInfo = (c: Xtream, id: string) => cached(k(c, `vod_info:${id}`), () => c.vodInfo(id), INFO_AGE);

/** Download every list so the whole catalog can be browsed and searched offline. */
export async function saveCatalogOffline(c: Xtream, onStep?: (label: string) => void, withSeriesDetails = false) {
  const steps: [string, string, () => Promise<unknown>][] = [
    ['Movie categories', 'vod_categories', c.vodCategories],
    ['Movies', 'vod_streams', c.vodStreams],
    ['Series categories', 'series_categories', c.seriesCategories],
    ['Series', 'series', c.series],
    ['Live categories', 'live_categories', c.liveCategories],
    ['Live channels', 'live_streams', c.liveStreams],
  ];
  for (const [label, key, fn] of steps) {
    onStep?.(label);
    await fetchAndStore(k(c, key), fn);
  }
  if (withSeriesDetails) {
    const list = (await cached<SeriesItem[]>(k(c, 'series'), c.series, LIST_AGE)) ?? [];
    let i = 0;
    for (const s of list) {
      onStep?.(`Episodes ${++i}/${list.length}`);
      await fetchAndStore(k(c, `series_info:${s.series_id}`), () => c.seriesInfo(s.series_id)).catch(() => {});
    }
  }
}

export function byCategory<T extends { category_id?: string }>(items: T[] | undefined, cat: string | undefined): T[] {
  if (!items) return [];
  if (!cat || cat === 'all') return items;
  return items.filter((i) => String(i.category_id) === cat);
}

export type SortKey = 'recent' | 'az' | 'rating';
export function sortItems<T extends { name: string; added?: string; last_modified?: string; rating?: string | number; rating_5based?: number }>(
  items: T[],
  sort: SortKey,
): T[] {
  const out = [...items];
  if (sort === 'az') out.sort((a, b) => a.name.localeCompare(b.name));
  else if (sort === 'rating') out.sort((a, b) => (Number(b.rating) || (b.rating_5based ?? 0) * 2) - (Number(a.rating) || (a.rating_5based ?? 0) * 2));
  else out.sort((a, b) => Number(b.added ?? b.last_modified ?? 0) - Number(a.added ?? a.last_modified ?? 0));
  return out;
}

/** Accent/case-insensitive match. */
export const fold = (s: string) =>
  s
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase();
export function matches(name: string, q: string) {
  const n = fold(name);
  return fold(q)
    .split(/\s+/)
    .filter(Boolean)
    .every((w) => n.includes(w));
}
