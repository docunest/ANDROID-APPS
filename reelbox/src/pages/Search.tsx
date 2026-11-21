import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { matches, useLiveStreams, useSeriesList, useVodStreams } from '../lib/catalog';
import { liveRef } from '../lib/xtream';
import { Card, Empty, Icon, Row, Spinner } from '../components/ui';
import { playPath } from './paths';

const LIMIT = 40;

export default function Search() {
  const [params, setParams] = useSearchParams();
  const [q, setQ] = useState(params.get('q') ?? '');
  const movies = useVodStreams();
  const series = useSeriesList();
  const live = useLiveStreams();

  const term = q.trim();
  const res = useMemo(() => {
    if (term.length < 2) return null;
    return {
      movies: (movies.data ?? []).filter((m) => matches(m.name, term)),
      series: (series.data ?? []).filter((s) => matches(s.name, term)),
      live: (live.data ?? []).filter((c) => matches(c.name, term)),
    };
  }, [term, movies.data, series.data, live.data]);

  const loading = movies.loading || series.loading || live.loading;
  const total = res ? res.movies.length + res.series.length + res.live.length : 0;

  return (
    <div className="page">
      <div className="search-box">
        <Icon name="search" />
        <input
          type="search"
          data-autofocus
          autoFocus
          placeholder="Search movies, series and channels"
          value={q}
          onChange={(e) => {
            setQ(e.target.value);
            setParams(e.target.value ? { q: e.target.value } : {}, { replace: true });
          }}
          aria-label="Search"
        />
      </div>
      {loading && !res && <Spinner label="Loading catalog…" />}
      {res && total === 0 && <Empty title="No results">Nothing matches “{term}”.</Empty>}
      {res && res.movies.length > 0 && (
        <Row title={`Movies (${res.movies.length})`}>
          {res.movies.slice(0, LIMIT).map((m) => (
            <Card key={m.stream_id} to={`/movie/${m.stream_id}`} title={m.name} poster={m.stream_icon} />
          ))}
        </Row>
      )}
      {res && res.series.length > 0 && (
        <Row title={`Series (${res.series.length})`}>
          {res.series.slice(0, LIMIT).map((s) => (
            <Card key={s.series_id} to={`/show/${s.series_id}`} title={s.name} poster={s.cover} />
          ))}
        </Row>
      )}
      {res && res.live.length > 0 && (
        <Row title={`Live (${res.live.length})`}>
          {res.live.slice(0, LIMIT).map((c) => (
            <Card key={c.stream_id} wide to={playPath(liveRef(c))} state={{ ref: liveRef(c) }} title={c.name} poster={c.stream_icon} />
          ))}
        </Row>
      )}
      {!res && !loading && <p className="muted center">Type at least 2 letters. Search works offline on your saved catalog.</p>}
    </div>
  );
}
