import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useSession } from '../lib/session';
import { clearProgress, fmtTime, useFavorites, useMoments, useProgressMap } from '../lib/userdata';
import { refKey } from '../lib/xtream';
import { Card, Chips, Empty, Grid, Icon } from '../components/ui';
import { detailPath, playPath } from './paths';

type Tab = 'favorites' | 'moments' | 'history';

export default function Library() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) || 'favorites';
  const { account } = useSession();
  const acc = account!.id;
  const { favs, toggle } = useFavorites(acc);
  const { moments, remove, rename } = useMoments(acc);
  const progress = useProgressMap(acc);
  const [editing, setEditing] = useState<string | null>(null);

  const favList = useMemo(() => Object.values(favs).sort((a, b) => b.at - a.at), [favs]);
  const history = useMemo(() => Object.values(progress).sort((a, b) => b.at - a.at), [progress]);

  return (
    <div className="page">
      <h1>Bookmarks</h1>
      <Chips<Tab>
        value={tab}
        onChange={(t) => setParams({ tab: t }, { replace: true })}
        options={[
          { value: 'favorites', label: 'Favorites', count: favList.length },
          { value: 'moments', label: 'Moments', count: moments.length },
          { value: 'history', label: 'History', count: history.length },
        ]}
      />

      {tab === 'favorites' &&
        (favList.length ? (
          <Grid
            items={favList}
            render={(f) => (
              <div key={`${f.kind}:${f.id}`} className="fav-cell">
                <Card to={detailPath(f.kind, f.id)} state={f.kind === 'live' ? { ref: { kind: 'live', id: f.id, name: f.name, poster: f.poster } } : undefined} title={f.name} subtitle={f.kind === 'movie' ? 'Movie' : f.kind === 'series' ? 'Series' : 'Channel'} poster={f.poster} />
                <button className="btn btn-icon fav-remove" onClick={() => toggle(f)} aria-label={`Remove ${f.name} from favorites`}>
                  <Icon name="close" size={16} />
                </button>
              </div>
            )}
          />
        ) : (
          <Empty title="No favorites yet">Use “Add to favorites” on any movie, series or channel.</Empty>
        ))}

      {tab === 'moments' &&
        (moments.length ? (
          <ul className="list">
            {moments.map((m) => (
              <li key={m.id} className="list-item">
                <Link to={playPath(m.ref, m.pos)} state={{ ref: m.ref, startAt: m.pos }} className="list-main">
                  <Icon name="bookmark" />
                  <div>
                    <div className="list-title">
                      {m.ref.seriesName ? `${m.ref.seriesName} · S${m.ref.season}E${m.ref.episode}` : m.ref.name} <b>{fmtTime(m.pos)}</b>
                    </div>
                    {editing === m.id ? null : <div className="muted small">{m.note}</div>}
                  </div>
                </Link>
                {editing === m.id ? (
                  <form
                    className="inline-form"
                    onSubmit={(e) => {
                      e.preventDefault();
                      const v = new FormData(e.currentTarget).get('note');
                      rename(m.id, String(v || m.note));
                      setEditing(null);
                    }}
                  >
                    <input name="note" defaultValue={m.note} autoFocus aria-label="Bookmark note" />
                    <button className="btn">Save</button>
                  </form>
                ) : (
                  <button className="btn btn-icon" onClick={() => setEditing(m.id)} aria-label="Edit note">
                    ✎
                  </button>
                )}
                <button className="btn btn-icon" onClick={() => remove(m.id)} aria-label="Delete bookmark">
                  <Icon name="trash" size={18} />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <Empty title="No moments yet">Press the bookmark button in the player to save a moment you want to come back to.</Empty>
        ))}

      {tab === 'history' &&
        (history.length ? (
          <ul className="list">
            {history.map((p) => (
              <li key={refKey(p.ref)} className="list-item">
                <Link to={playPath(p.ref)} state={{ ref: p.ref }} className="list-main">
                  <Icon name={p.watched ? 'check' : 'play'} />
                  <div>
                    <div className="list-title">{p.ref.seriesName ? `${p.ref.seriesName} · S${p.ref.season}E${p.ref.episode} ${p.ref.name}` : p.ref.name}</div>
                    <div className="muted small">
                      {p.watched ? 'Watched' : `${fmtTime(p.pos)} of ${fmtTime(p.dur)}`} · {new Date(p.at).toLocaleDateString()}
                    </div>
                  </div>
                </Link>
                <button className="btn btn-icon" onClick={() => clearProgress(acc, refKey(p.ref))} aria-label="Remove from history">
                  <Icon name="trash" size={18} />
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <Empty title="Nothing watched yet" />
        ))}
    </div>
  );
}
