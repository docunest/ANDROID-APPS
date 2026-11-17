import { useMemo } from 'react';
import { useSession } from '../lib/session';
import { useSeriesList, useVodStreams } from '../lib/catalog';
import { continueWatching, useFavorites, useProgressMap } from '../lib/userdata';
import { useDownloads } from '../lib/downloads';
import { Card, Empty, ErrorBox, Row, Spinner } from '../components/ui';
import { playPath, detailPath } from './paths';

export default function Home() {
  const { account, online } = useSession();
  const acc = account!.id;
  const progress = useProgressMap(acc);
  const { favs } = useFavorites(acc);
  const movies = useVodStreams();
  const series = useSeriesList();
  const dls = useDownloads().filter((d) => d.status === 'done' && d.accountId === acc);

  const cont = useMemo(() => continueWatching(progress).slice(0, 20), [progress]);
  const newMovies = useMemo(() => [...(movies.data ?? [])].sort((a, b) => Number(b.added ?? 0) - Number(a.added ?? 0)).slice(0, 24), [movies.data]);
  const newSeries = useMemo(
    () => [...(series.data ?? [])].sort((a, b) => Number(b.last_modified ?? 0) - Number(a.last_modified ?? 0)).slice(0, 24),
    [series.data],
  );
  const favList = Object.values(favs).sort((a, b) => b.at - a.at);

  const loading = movies.loading && series.loading && !movies.data && !series.data;
  const nothing = !loading && !cont.length && !favList.length && !newMovies.length && !newSeries.length && !dls.length;

  return (
    <div className="page">
      {cont.length > 0 && (
        <Row title="Continue watching" to="/library?tab=history">
          {cont.map((p) => (
            <Card
              key={`${p.ref.kind}:${p.ref.id}`}
              to={playPath(p.ref)}
              state={{ ref: p.ref }}
              title={p.ref.seriesName ?? p.ref.name}
              subtitle={p.ref.seriesName ? `S${p.ref.season}·E${p.ref.episode} ${p.ref.name}` : undefined}
              poster={p.ref.poster}
              progress={p.pos / p.dur}
            />
          ))}
        </Row>
      )}
      {!online && dls.length > 0 && (
        <Row title="Downloaded" to="/downloads">
          {dls.map((d) => (
            <Card key={d.key} to={playPath(d.ref)} state={{ ref: d.ref }} title={d.ref.seriesName ?? d.ref.name} subtitle={d.ref.seriesName ? d.ref.name : undefined} poster={d.ref.poster} />
          ))}
        </Row>
      )}
      {favList.length > 0 && (
        <Row title="Favorites" to="/library">
          {favList.map((f) => (
            <Card key={`${f.kind}:${f.id}`} to={detailPath(f.kind, f.id)} title={f.name} poster={f.poster} />
          ))}
        </Row>
      )}
      {newMovies.length > 0 && (
        <Row title="Recently added movies" to="/movies">
          {newMovies.map((m) => (
            <Card key={m.stream_id} to={`/movie/${m.stream_id}`} title={m.name} poster={m.stream_icon} />
          ))}
        </Row>
      )}
      {newSeries.length > 0 && (
        <Row title="Recently updated series" to="/series">
          {newSeries.map((s) => (
            <Card key={s.series_id} to={`/show/${s.series_id}`} title={s.name} poster={s.cover} />
          ))}
        </Row>
      )}
      {online && dls.length > 0 && (
        <Row title="Downloaded" to="/downloads">
          {dls.map((d) => (
            <Card key={d.key} to={playPath(d.ref)} state={{ ref: d.ref }} title={d.ref.seriesName ?? d.ref.name} subtitle={d.ref.seriesName ? d.ref.name : undefined} poster={d.ref.poster} />
          ))}
        </Row>
      )}
      {loading && <Spinner label="Loading your library…" />}
      {!loading && movies.error && series.error && <ErrorBox error={movies.error} onRetry={() => (movies.refresh(), series.refresh())} />}
      {nothing && !movies.error && <Empty title="Nothing here yet">Your server has no movies or series, or they could not be loaded.</Empty>}
    </div>
  );
}
