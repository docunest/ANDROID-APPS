import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useVodInfo, useVodStreams } from '../lib/catalog';
import { useClient, useSession } from '../lib/session';
import { fmtTime, useFavorites, useMoments, useProgressMap } from '../lib/userdata';
import { rating10, type MediaRef } from '../lib/xtream';
import { ErrorBox, Icon, Poster, Spinner } from '../components/ui';
import { DownloadButton } from '../components/DownloadButton';
import { playPath } from './paths';
import { isTizen } from '../lib/platform';

export default function Movie() {
  const { id = '' } = useParams();
  const { account } = useSession();
  const client = useClient();
  const acc = account!.id;
  const info = useVodInfo(id);
  const list = useVodStreams();
  const fromList = useMemo(() => list.data?.find((m) => String(m.stream_id) === id), [list.data, id]);
  const fav = useFavorites(acc);
  const { moments, remove } = useMoments(acc);
  const progress = useProgressMap(acc)[`movie:${id}`];

  const i = info.data?.info ?? {};
  const md = info.data?.movie_data;
  const name = md?.name || fromList?.name || i.name || 'Movie';
  const poster = i.cover_big || i.movie_image || fromList?.stream_icon;
  const backdrop = i.backdrop_path?.[0];
  const ref: MediaRef = { kind: 'movie', id, name, poster, ext: md?.container_extension || fromList?.container_extension };
  const myMoments = moments.filter((m) => m.ref.kind === 'movie' && m.ref.id === id);
  const r = rating10({ rating: i.rating ?? fromList?.rating, rating_5based: fromList?.rating_5based });
  const year = (i.releasedate || i.release_date || '').slice(0, 4) || fromList?.year;
  const resume = progress && !progress.watched && progress.pos > 20 ? progress.pos : 0;

  if (!info.data && !fromList) {
    if (info.loading || list.loading) return <Spinner label="Loading…" />;
    return <ErrorBox error={info.error ?? 'Movie not found.'} onRetry={info.refresh} />;
  }

  return (
    <div className="page detail">
      {backdrop && <div className="backdrop" style={{ backgroundImage: `url("${backdrop}")` }} />}
      <div className="detail-body">
        <div className="detail-poster">
          <Poster src={poster} name={name} />
        </div>
        <div className="detail-info">
          <h1>{name}</h1>
          <div className="meta">
            {[year, i.duration, i.genre, r ? `★ ${r}` : null, ref.ext?.toUpperCase()].filter(Boolean).map((m) => (
              <span key={String(m)}>{m}</span>
            ))}
          </div>
          <div className="actions">
            <Link className="btn btn-primary" to={playPath(ref, resume)} state={{ ref, startAt: resume }} data-autofocus>
              <Icon name="play" /> {resume ? `Resume ${fmtTime(resume)}` : 'Play'}
            </Link>
            {resume > 0 && (
              <Link className="btn" to={playPath(ref)} state={{ ref, startAt: 0, fromStart: true }}>
                From start
              </Link>
            )}
            <button
              className={`btn ${fav.isFav('movie', id) ? 'on' : ''}`}
              onClick={() => fav.toggle({ kind: 'movie', id, name, poster })}
              aria-pressed={fav.isFav('movie', id)}
            >
              <Icon name={fav.isFav('movie', id) ? 'star' : 'starOutline'} /> {fav.isFav('movie', id) ? 'Favorite' : 'Add to favorites'}
            </button>
            <DownloadButton client={client} ref_={ref} />
            {i.youtube_trailer && !isTizen && (
              <a className="btn" href={`https://www.youtube.com/watch?v=${encodeURIComponent(i.youtube_trailer)}`} target="_blank" rel="noreferrer">
                <Icon name="external" /> Trailer
              </a>
            )}
          </div>
          {progress && (
            <div className="progress-line">
              <div className="bar">
                <div style={{ width: `${Math.min(100, (progress.pos / progress.dur) * 100)}%` }} />
              </div>
              <span className="muted">{progress.watched ? 'Watched' : `${fmtTime(progress.dur - progress.pos)} left`}</span>
            </div>
          )}
          {(i.plot || i.description) && <p className="plot">{i.plot || i.description}</p>}
          <dl className="facts">
            {i.director && (
              <>
                <dt>Director</dt>
                <dd>{i.director}</dd>
              </>
            )}
            {(i.cast || i.actors) && (
              <>
                <dt>Cast</dt>
                <dd>{i.cast || i.actors}</dd>
              </>
            )}
            {i.country && (
              <>
                <dt>Country</dt>
                <dd>{i.country}</dd>
              </>
            )}
          </dl>
          {myMoments.length > 0 && (
            <section className="moments">
              <h2>Your bookmarks</h2>
              {myMoments.map((m) => (
                <div className="moment" key={m.id}>
                  <Link to={playPath(m.ref, m.pos)} state={{ ref: m.ref, startAt: m.pos }} className="moment-link">
                    <Icon name="bookmark" size={18} /> <b>{fmtTime(m.pos)}</b> {m.note}
                  </Link>
                  <button className="btn btn-icon" onClick={() => remove(m.id)} aria-label="Delete bookmark">
                    <Icon name="trash" size={18} />
                  </button>
                </div>
              ))}
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
