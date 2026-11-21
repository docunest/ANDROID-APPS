import { useMemo } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useSeriesInfo, useSeriesList } from '../lib/catalog';
import { useClient, useSession } from '../lib/session';
import { fmtTime, useFavorites, useProgressMap } from '../lib/userdata';
import { downloads } from '../lib/downloads';
import { episodeRef, rating10, refKey, type MediaRef } from '../lib/xtream';
import { Chips, ErrorBox, Icon, Poster, SavedAt, Spinner, toast } from '../components/ui';
import { DownloadButton } from '../components/DownloadButton';
import { playPath } from './paths';
import { isTizen } from '../lib/platform';

export default function Show() {
  const { id = '' } = useParams();
  const [params, setParams] = useSearchParams();
  const { account } = useSession();
  const client = useClient();
  const acc = account!.id;
  const info = useSeriesInfo(id);
  const list = useSeriesList();
  const fromList = useMemo(() => list.data?.find((s) => String(s.series_id) === id), [list.data, id]);
  const fav = useFavorites(acc);
  const progress = useProgressMap(acc);

  const si = info.data;
  const meta = { ...(fromList ?? {}), ...(si?.info ?? {}) };
  const name = meta.name || 'Series';
  const cover = meta.cover;
  const series = { id, name, cover };

  // Where to continue: latest progress entry for this series.
  const last = useMemo(
    () =>
      Object.values(progress)
        .filter((p) => p.ref.seriesId === id)
        .sort((a, b) => b.at - a.at)[0],
    [progress, id],
  );

  const allEps = useMemo(() => {
    if (!si) return [] as MediaRef[];
    return si.seasons.flatMap((s) => (si.episodes[String(s.season_number)] ?? []).map((e) => episodeRef(e, series)));
  }, [si]); // eslint-disable-line react-hooks/exhaustive-deps

  const nextUp = useMemo<{ ref: MediaRef; startAt: number } | null>(() => {
    if (!allEps.length) return null;
    if (!last) return { ref: allEps[0], startAt: 0 };
    const i = allEps.findIndex((e) => e.id === last.ref.id);
    if (i < 0) return { ref: allEps[0], startAt: 0 };
    if (!last.watched) return { ref: allEps[i], startAt: last.pos };
    return allEps[i + 1] ? { ref: allEps[i + 1], startAt: 0 } : null;
  }, [allEps, last]);

  const seasonParam = params.get('s');
  const season = seasonParam ?? String(nextUp?.ref.season ?? si?.seasons[0]?.season_number ?? 1);
  const eps = si?.episodes[season] ?? [];

  if (!si && !fromList) {
    if (info.loading || list.loading) return <Spinner label="Loading…" />;
    return <ErrorBox error={info.error ?? 'Series not found.'} onRetry={info.refresh} />;
  }

  const r = rating10(meta);
  const year = (meta.releaseDate || meta.release_date || '').slice(0, 4);
  const backdrop = meta.backdrop_path?.[0];

  const downloadSeason = async () => {
    const refs = eps.map((e) => episodeRef(e, series));
    for (const ref of refs) await downloads.enqueue(client, ref).catch(() => {});
    toast(`Queued ${refs.length} episodes for offline`);
  };

  return (
    <div className="page detail">
      {backdrop && <div className="backdrop" style={{ backgroundImage: `url("${backdrop}")` }} />}
      <div className="detail-body">
        <div className="detail-poster">
          <Poster src={cover} name={name} />
        </div>
        <div className="detail-info">
          <h1>{name}</h1>
          <div className="meta">
            {[year, meta.genre, r ? `★ ${r}` : null, si ? `${si.seasons.length} season${si.seasons.length === 1 ? '' : 's'}` : null].filter(Boolean).map((m) => (
              <span key={String(m)}>{m}</span>
            ))}
          </div>
          <div className="actions">
            {nextUp && (
              <Link className="btn btn-primary" to={playPath(nextUp.ref, nextUp.startAt)} state={{ ref: nextUp.ref, startAt: nextUp.startAt }} data-autofocus>
                <Icon name="play" />
                {last ? `${nextUp.startAt ? 'Resume' : 'Play'} S${nextUp.ref.season}·E${nextUp.ref.episode}` : 'Play S1·E1'}
              </Link>
            )}
            <button className={`btn ${fav.isFav('series', id) ? 'on' : ''}`} onClick={() => fav.toggle({ kind: 'series', id, name, poster: cover })} aria-pressed={fav.isFav('series', id)}>
              <Icon name={fav.isFav('series', id) ? 'star' : 'starOutline'} /> {fav.isFav('series', id) ? 'Favorite' : 'Add to favorites'}
            </button>
            {meta.youtube_trailer && !isTizen && (
              <a className="btn" href={`https://www.youtube.com/watch?v=${encodeURIComponent(meta.youtube_trailer)}`} target="_blank" rel="noreferrer">
                <Icon name="external" /> Trailer
              </a>
            )}
          </div>
          {meta.plot && <p className="plot">{meta.plot}</p>}
          {meta.cast && (
            <dl className="facts">
              <dt>Cast</dt>
              <dd>{meta.cast}</dd>
            </dl>
          )}
        </div>
      </div>

      {info.loading && !si && <Spinner label="Loading episodes…" />}
      {info.error && !si && <ErrorBox error={info.error} onRetry={info.refresh} />}
      {si && (
        <section className="episodes">
          <div className="episodes-head">
            <Chips
              value={season}
              options={si.seasons.map((s) => ({ value: String(s.season_number), label: s.name || `Season ${s.season_number}` }))}
              onChange={(v) => {
                const n = new URLSearchParams(params);
                n.set('s', v);
                setParams(n, { replace: true });
              }}
            />
            <button className="btn" onClick={downloadSeason}>
              <Icon name="download" /> Download season
            </button>
            <SavedAt at={info.at} stale={info.stale} />
          </div>
          <ol className="ep-list">
            {eps.map((e) => {
              const ref = episodeRef(e, series);
              const p = progress[refKey(ref)];
              const start = p && !p.watched ? p.pos : 0;
              const dur = e.info?.duration_secs || (p?.dur ?? 0);
              return (
                <li key={e.id} className="ep">
                  <Link className="ep-main" to={playPath(ref, start)} state={{ ref, startAt: start }}>
                    <div className="ep-thumb">
                      <Poster src={e.info?.movie_image} name={ref.name} wide />
                      {p && (
                        <div className="card-progress">
                          <div style={{ width: `${p.watched ? 100 : Math.min(100, (p.pos / p.dur) * 100)}%` }} />
                        </div>
                      )}
                    </div>
                    <div className="ep-text">
                      <div className="ep-title">
                        <span className="ep-num">{e.episode_num}.</span> {ref.name}
                        {p?.watched && <Icon name="check" size={16} className="ep-watched" />}
                      </div>
                      <div className="muted small">
                        {[dur ? fmtTime(dur) : e.info?.duration, start ? `${fmtTime(start)} watched` : null].filter(Boolean).join(' · ')}
                      </div>
                      {e.info?.plot && <p className="ep-plot">{e.info.plot}</p>}
                    </div>
                  </Link>
                  <DownloadButton client={client} ref_={ref} compact />
                </li>
              );
            })}
          </ol>
        </section>
      )}
    </div>
  );
}
