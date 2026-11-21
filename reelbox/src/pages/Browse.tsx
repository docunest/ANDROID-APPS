import { useMemo } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  byCategory,
  matches,
  sortItems,
  useLiveCategories,
  useLiveStreams,
  useSeriesCategories,
  useSeriesList,
  useVodCategories,
  useVodStreams,
  type SortKey,
} from '../lib/catalog';
import type { Query } from '../lib/cache';
import { liveRef, rating10, type Category } from '../lib/xtream';
import { useSession } from '../lib/session';
import { useProgressMap } from '../lib/userdata';
import { useDownloads } from '../lib/downloads';
import { Card, Empty, ErrorBox, Grid, Icon, SavedAt, Spinner } from '../components/ui';
import { playPath } from './paths';

type Kind = 'movies' | 'series' | 'live';

interface Item {
  key: string;
  name: string;
  category_id?: string;
  added?: string;
  last_modified?: string;
  rating?: string | number;
  rating_5based?: number;
  poster?: string;
  to: string;
  state?: unknown;
  sub?: string;
  progressKey?: string;
}

function useKind(kind: Kind): { cats: Query<Category[]>; items: Query<Item[]> } {
  // All hooks run (rules of hooks) but only the current kind is enabled/fetched.
  const vc = useVodCategories(kind === 'movies');
  const vs = useVodStreams(kind === 'movies');
  const sc = useSeriesCategories(kind === 'series');
  const ss = useSeriesList(kind === 'series');
  const lc = useLiveCategories(kind === 'live');
  const ls = useLiveStreams(kind === 'live');
  const mapped = useMemo<Item[] | undefined>(() => {
    if (kind === 'movies')
      return vs.data?.map((m) => ({
        key: String(m.stream_id),
        name: m.name,
        category_id: m.category_id,
        added: m.added,
        rating: m.rating,
        rating_5based: m.rating_5based,
        poster: m.stream_icon,
        to: `/movie/${m.stream_id}`,
        sub: [m.year, rating10(m) ? `★ ${rating10(m)}` : null].filter(Boolean).join(' · ') || undefined,
        progressKey: `movie:${m.stream_id}`,
      }));
    if (kind === 'series')
      return ss.data?.map((s) => ({
        key: String(s.series_id),
        name: s.name,
        category_id: s.category_id,
        last_modified: s.last_modified,
        rating: s.rating,
        rating_5based: s.rating_5based,
        poster: s.cover,
        to: `/show/${s.series_id}`,
        sub: [(s.releaseDate || s.release_date || '').slice(0, 4), rating10(s) ? `★ ${rating10(s)}` : null].filter(Boolean).join(' · ') || undefined,
      }));
    return ls.data?.map((c) => ({
      key: String(c.stream_id),
      name: c.name,
      category_id: c.category_id,
      added: c.added,
      poster: c.stream_icon,
      to: playPath(liveRef(c)),
      state: { ref: liveRef(c) },
    }));
  }, [kind, vs.data, ss.data, ls.data]);
  const pick = kind === 'movies' ? [vc, vs] : kind === 'series' ? [sc, ss] : [lc, ls];
  return { cats: pick[0] as Query<Category[]>, items: { ...(pick[1] as Query<unknown>), data: mapped } as Query<Item[]> };
}

const TITLES: Record<Kind, string> = { movies: 'Movies', series: 'Series', live: 'Live TV' };

export default function Browse({ kind }: { kind: Kind }) {
  const [params, setParams] = useSearchParams();
  const cat = params.get('cat') ?? 'all';
  const sort = (params.get('sort') as SortKey) ?? (kind === 'live' ? 'az' : 'recent');
  const q = params.get('q') ?? '';
  const { account } = useSession();
  const progress = useProgressMap(account!.id);
  const downloaded = new Set(
    useDownloads()
      .filter((d) => d.status === 'done')
      .map((d) => d.key),
  );
  const { cats, items } = useKind(kind);

  const set = (k: string, v: string) => {
    const n = new URLSearchParams(params);
    if (v && v !== 'all') n.set(k, v);
    else n.delete(k);
    setParams(n, { replace: true });
  };

  const counts = useMemo(() => {
    const m = new Map<string, number>();
    for (const i of items.data ?? []) m.set(String(i.category_id), (m.get(String(i.category_id)) ?? 0) + 1);
    return m;
  }, [items.data]);

  const shown = useMemo(() => {
    let list = byCategory(items.data, cat);
    if (q) list = list.filter((i) => matches(i.name, q));
    return sortItems(list, sort);
  }, [items.data, cat, q, sort]);

  const catList = (cats.data ?? []).filter((c) => counts.get(String(c.category_id)));
  const catName = cat === 'all' ? `All ${TITLES[kind].toLowerCase()}` : catList.find((c) => String(c.category_id) === cat)?.category_name ?? 'Category';

  return (
    <div className="page browse">
      <aside className="cats" aria-label="Categories">
        <button className={`cat ${cat === 'all' ? 'on' : ''}`} onClick={() => set('cat', 'all')}>
          All <span className="chip-count">{items.data?.length ?? ''}</span>
        </button>
        {catList.map((c) => (
          <button key={c.category_id} className={`cat ${cat === String(c.category_id) ? 'on' : ''}`} onClick={() => set('cat', String(c.category_id))}>
            {c.category_name} <span className="chip-count">{counts.get(String(c.category_id))}</span>
          </button>
        ))}
      </aside>
      <section className="browse-main">
        <div className="toolbar">
          <h1>{catName}</h1>
          <select className="cat-select" value={cat} onChange={(e) => set('cat', e.target.value)} aria-label="Category">
            <option value="all">All ({items.data?.length ?? 0})</option>
            {catList.map((c) => (
              <option key={c.category_id} value={String(c.category_id)}>
                {c.category_name} ({counts.get(String(c.category_id))})
              </option>
            ))}
          </select>
          <div className="toolbar-row">
            <input className="filter" type="search" placeholder={`Filter ${TITLES[kind].toLowerCase()}…`} value={q} onChange={(e) => set('q', e.target.value)} />
            <select value={sort} onChange={(e) => set('sort', e.target.value)} aria-label="Sort">
              <option value="recent">Newest</option>
              <option value="az">A–Z</option>
              {kind !== 'live' && <option value="rating">Rating</option>}
            </select>
            <button className="btn btn-icon" onClick={items.refresh} title="Refresh from server" aria-label="Refresh">
              <Icon name="refresh" />
            </button>
          </div>
          <SavedAt at={items.at} stale={items.stale} />
        </div>
        {items.loading && !items.data && <Spinner label={`Loading ${TITLES[kind].toLowerCase()}…`} />}
        {items.error && !items.data && <ErrorBox error={items.error} onRetry={items.refresh} />}
        {items.data && !shown.length && <Empty title="No matches">{q ? `Nothing matches “${q}”.` : 'This category is empty.'}</Empty>}
        <Grid
          items={shown}
          wide={kind === 'live'}
          render={(i) => {
            const p = i.progressKey ? progress[i.progressKey] : undefined;
            const dl = i.progressKey && downloaded.has(i.progressKey);
            return (
              <Card
                key={i.key}
                to={i.to}
                state={i.state}
                title={i.name}
                subtitle={i.sub}
                poster={i.poster}
                wide={kind === 'live'}
                progress={p && !p.watched ? p.pos / p.dur : undefined}
                badge={p?.watched ? <Icon name="check" size={16} /> : dl ? <Icon name="download" size={16} /> : undefined}
              />
            );
          }}
        />
      </section>
    </div>
  );
}
