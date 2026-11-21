import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { downloads, driverCanPause, fmtBytes, pickDriver, storageEstimate, useDownloads, type DownloadRec } from '../lib/downloads';
import { isSecure } from '../lib/platform';
import { Empty, Icon, Poster } from '../components/ui';
import { playPath } from './paths';

const DRIVER_LABEL = { native: 'app storage', opfs: 'browser file storage', idb: 'browser database' };

export default function Downloads() {
  const list = useDownloads();
  const [est, setEst] = useState<{ usage: number; quota: number } | null>(null);
  useEffect(() => {
    storageEstimate().then(setEst);
  }, [list.length, list.filter((d) => d.status === 'done').length]);
  const driver = pickDriver();
  const active = list.filter((d) => d.status !== 'done');
  const done = list.filter((d) => d.status === 'done');
  const totalDone = done.reduce((s, d) => s + d.bytes, 0);

  return (
    <div className="page">
      <h1>Downloads</h1>
      <p className="muted">
        {driver
          ? `Saved to ${DRIVER_LABEL[driver]} on this device. ${done.length} video${done.length === 1 ? '' : 's'}, ${fmtBytes(totalDone)}.`
          : 'This device cannot store downloads.'}
        {est && est.quota > 0 && ` Free space for the app: ${fmtBytes(Math.max(0, est.quota - est.usage))}.`}
      </p>
      {!isSecure && (
        <p className="hint">This page is not on https or localhost, so the browser limits offline storage. Use the native app, or serve Reelbox over https.</p>
      )}
      {active.length > 0 && (
        <section>
          <h2>In progress</h2>
          <ul className="list">
            {active.map((d) => (
              <DlItem key={d.key} d={d} />
            ))}
          </ul>
        </section>
      )}
      {done.length > 0 && (
        <section>
          <h2>Ready offline</h2>
          <ul className="list">
            {done.map((d) => (
              <DlItem key={d.key} d={d} />
            ))}
          </ul>
        </section>
      )}
      {!list.length && <Empty title="No downloads">Open a movie or episode and press Download to watch it without internet.</Empty>}
    </div>
  );
}

function DlItem({ d }: { d: DownloadRec }) {
  const pct = d.total ? Math.floor((d.bytes / d.total) * 100) : 0;
  const title = d.ref.seriesName ? `${d.ref.seriesName} · S${d.ref.season}E${d.ref.episode}` : d.ref.name;
  return (
    <li className={`list-item dl dl-${d.status}`}>
      <Link to={d.status === 'done' ? playPath(d.ref) : d.ref.kind === 'movie' ? `/movie/${d.ref.id}` : `/show/${d.ref.seriesId}`} state={{ ref: d.ref }} className="list-main">
        <div className="dl-thumb">
          <Poster src={d.ref.poster} name={d.ref.name} />
        </div>
        <div className="dl-text">
          <div className="list-title">{title}</div>
          {d.ref.seriesName && <div className="small">{d.ref.name}</div>}
          <div className="muted small">
            {d.status === 'done' && `${fmtBytes(d.bytes)} · ready`}
            {d.status === 'downloading' && `${d.total ? `${pct}% · ` : ''}${fmtBytes(d.bytes)}${d.total ? ` of ${fmtBytes(d.total)}` : ''}`}
            {d.status === 'queued' && 'Waiting…'}
            {d.status === 'paused' && `Paused at ${pct}% (${fmtBytes(d.bytes)})`}
            {d.status === 'error' && <span className="error-text">{d.error}</span>}
          </div>
          {d.status !== 'done' && (
            <div className="bar">
              <div style={{ width: `${pct}%` }} />
            </div>
          )}
        </div>
      </Link>
      {d.status === 'done' && (
        <Link className="btn btn-icon" to={playPath(d.ref)} state={{ ref: d.ref }} aria-label="Play">
          <Icon name="play" />
        </Link>
      )}
      {d.status === 'downloading' && driverCanPause(d.driver) && (
        <button className="btn btn-icon" onClick={() => downloads.pause(d.key)} aria-label="Pause">
          <Icon name="pause" />
        </button>
      )}
      {(d.status === 'paused' || d.status === 'error') && (
        <button className="btn btn-icon" onClick={() => downloads.resume(d.key)} aria-label={d.status === 'error' ? 'Retry' : 'Resume'}>
          <Icon name={d.status === 'error' ? 'refresh' : 'download'} />
        </button>
      )}
      <button
        className="btn btn-icon"
        onClick={() => {
          if (d.status !== 'done' || confirm(`Delete the offline copy of “${title}”?`)) downloads.remove(d.key);
        }}
        aria-label="Delete download"
      >
        <Icon name="trash" />
      </button>
    </li>
  );
}
