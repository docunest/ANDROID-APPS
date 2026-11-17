import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as RPointerEvent, type KeyboardEvent as RKeyboardEvent } from 'react';
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useSession } from '../lib/session';
import { AvPlayEngine, HtmlEngine, type Engine, type EngineState } from '../lib/engines';
import { downloads } from '../lib/downloads';
import { getSeriesInfo } from '../lib/catalog';
import { fmtTime, getProgress, recallRef, rememberRef, saveProgress, useFavorites, useMoments } from '../lib/userdata';
import { episodeRef, refKey, type Kind, type MediaRef } from '../lib/xtream';
import { isIOS, isKey, isSafari, isTizen, nativePlatform } from '../lib/platform';
import { focusFirst } from '../lib/spatial';
import { load } from '../lib/store';
import { Icon, toast } from '../components/ui';
import { playPath } from './paths';

const SPEEDS = [0.75, 1, 1.25, 1.5, 2];
const HIDE_AFTER = 4000;

export type EnginePref = 'auto' | 'html' | 'avplay';

export default function Player() {
  const { kind, id = '' } = useParams<{ kind: Kind; id: string }>();
  const key = `${kind}:${id}`;
  const loc = useLocation();
  const nav = useNavigate();
  const [sp] = useSearchParams();
  const { client, account, online } = useSession();
  const acc = account!.id;
  const locState = (loc.state ?? {}) as { ref?: MediaRef; startAt?: number; fromStart?: boolean };

  const [ref, setRef] = useState<MediaRef | null>(locState.ref && refKey(locState.ref) === key ? locState.ref : null);
  const [fatal, setFatal] = useState<string | null>(null);
  const [st, setSt] = useState<EngineState | null>(null);
  const [offline, setOffline] = useState(false);
  const [engineName, setEngineName] = useState<'html' | 'avplay' | null>(null);
  const [controls, setControls] = useState(true);
  const [panel, setPanel] = useState<null | 'audio' | 'text' | 'speed'>(null);
  const [resumedAt, setResumedAt] = useState(0);
  const [next, setNext] = useState<MediaRef | null>(null);
  const [countdown, setCountdown] = useState<number | null>(null);
  const [reloadNonce, setReloadNonce] = useState(0);

  const stage = useRef<HTMLDivElement>(null);
  const root = useRef<HTMLDivElement>(null);
  const engine = useRef<Engine | null>(null);
  const lastActivity = useRef(Date.now());
  const triedAvplay = useRef(false);
  const forceEngine = useRef<'html' | 'avplay' | null>(null);
  const resumeFrom = useRef<number | null>(null);

  const moments = useMoments(acc);
  const fav = useFavorites(acc);

  // 1. Resolve what to play (state → saved refs).
  useEffect(() => {
    if (ref && refKey(ref) === key) {
      rememberRef(ref);
      return;
    }
    recallRef(key).then((r) => (r ? setRef(r) : setFatal('This item is not in your saved library. Open it from Movies or Series.')));
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps

  // 2. Load source into an engine.
  useEffect(() => {
    if (!ref || !client) return;
    let alive = true;
    let revoke: (() => void) | undefined;
    let off: (() => void) | undefined;
    (async () => {
      setFatal(null);
      setSt(null);
      setCountdown(null);
      // start position
      let startAt = 0;
      const t = sp.get('t');
      if (resumeFrom.current !== null) startAt = resumeFrom.current;
      else if (locState.fromStart) startAt = 0;
      else if (locState.startAt !== undefined) startAt = locState.startAt;
      else if (t) startAt = Number(t) || 0;
      else if (ref.kind !== 'live') {
        const p = await getProgress(acc, key);
        if (p && !p.watched && p.pos > 20) startAt = p.pos;
      }
      resumeFrom.current = null;
      // offline copy first, then network
      const local = ref.kind === 'live' ? null : await downloads.playable(key);
      let url: string;
      if (local) {
        url = local.url;
        revoke = local.revoke;
      } else if (!online) {
        if (alive) setFatal('You are offline and this has not been downloaded.');
        return;
      } else url = client.streamUrl(ref);
      if (!alive) {
        revoke?.();
        return;
      }
      setOffline(!!local);
      // engine choice
      const pref = await load<EnginePref>('playerEngine', 'auto');
      const ext = (ref.ext || '').toLowerCase();
      let which: 'html' | 'avplay' = 'html';
      if (AvPlayEngine.available()) {
        if (forceEngine.current) which = forceEngine.current;
        else if (pref === 'avplay') which = 'avplay';
        else if (pref === 'auto' && !local && !['mp4', 'm4v', 'webm'].includes(ext) && ref.kind !== 'live') which = 'avplay';
      }
      engine.current?.destroy();
      const e = which === 'avplay' ? new AvPlayEngine(stage.current!) : new HtmlEngine(stage.current!);
      engine.current = e;
      setEngineName(which);
      off = e.onChange((s) => alive && setSt(s));
      setSt(e.state);
      if (startAt > 0) setResumedAt(startAt);
      await e.load(url, { startAt, live: ref.kind === 'live' });
    })().catch((err) => alive && setFatal(String(err?.message ?? err)));
    return () => {
      alive = false;
      const e = engine.current;
      if (e && ref.kind !== 'live' && e.state.duration > 0) saveProgress(acc, ref, e.state.time, e.state.duration);
      off?.();
      e?.destroy();
      engine.current = null;
      revoke?.();
    };
  }, [ref, client, reloadNonce]); // eslint-disable-line react-hooks/exhaustive-deps

  // 3. Persist progress every 5 s while playing.
  useEffect(() => {
    if (!ref || ref.kind === 'live') return;
    const iv = setInterval(() => {
      const e = engine.current;
      if (e && !e.state.paused && e.state.duration > 0) saveProgress(acc, ref, e.state.time, e.state.duration);
    }, 5000);
    const flush = () => {
      const e = engine.current;
      if (e && e.state.duration > 0) saveProgress(acc, ref, e.state.time, e.state.duration);
    };
    window.addEventListener('pagehide', flush);
    document.addEventListener('visibilitychange', flush);
    return () => {
      clearInterval(iv);
      window.removeEventListener('pagehide', flush);
      document.removeEventListener('visibilitychange', flush);
    };
  }, [ref, acc]);

  // Keep the screen on while playing (phones/laptops dim otherwise).
  const playing = !!st && !st.paused;
  useEffect(() => {
    if (!playing || !('wakeLock' in navigator)) return;
    let lock: { release: () => Promise<void> } | null = null;
    let alive = true;
    (navigator as any).wakeLock
      .request('screen')
      .then((l: typeof lock) => (alive ? (lock = l) : l?.release()))
      .catch(() => {});
    return () => {
      alive = false;
      lock?.release().catch(() => {});
    };
  }, [playing]);

  // 4. Work out the next episode.
  useEffect(() => {
    setNext(null);
    if (!ref || ref.kind !== 'episode' || !ref.seriesId || !client) return;
    getSeriesInfo(client, ref.seriesId)
      .then((si) => {
        const all = si.seasons.flatMap((s) => si.episodes[String(s.season_number)] ?? []);
        const i = all.findIndex((e) => String(e.id) === ref.id);
        const n = i >= 0 ? all[i + 1] : undefined;
        if (n) setNext(episodeRef(n, { id: ref.seriesId!, name: ref.seriesName ?? '', cover: ref.poster }));
      })
      .catch(() => {});
  }, [ref, client]);

  // 5. Tizen: if <video> can't decode it, retry with the native AVPlay engine.
  useEffect(() => {
    if (st?.errorCode === 'unsupported' && engineName === 'html' && isTizen && AvPlayEngine.available() && !triedAvplay.current) {
      triedAvplay.current = true;
      forceEngine.current = 'avplay';
      resumeFrom.current = st.time;
      setReloadNonce((n) => n + 1);
    }
  }, [st?.errorCode, engineName]); // eslint-disable-line react-hooks/exhaustive-deps

  // 6. Auto-play next episode.
  useEffect(() => {
    if (!st?.ended || !next) return;
    setCountdown(8);
    setControls(true);
  }, [st?.ended, next]);
  useEffect(() => {
    if (countdown === null) return;
    if (countdown <= 0) {
      goNext();
      return;
    }
    const t = setTimeout(() => setCountdown((c) => (c === null ? null : c - 1)), 1000);
    return () => clearTimeout(t);
  }, [countdown]); // eslint-disable-line react-hooks/exhaustive-deps

  const goNext = useCallback(() => {
    if (!next) return;
    setCountdown(null);
    nav(playPath(next), { replace: true, state: { ref: next, startAt: 0 } });
    setRef(next);
  }, [next, nav]);

  const exit = useCallback(() => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (loc.key === 'default') nav('/', { replace: true });
    else nav(-1);
  }, [nav, loc.key]);

  // Controls visibility.
  const poke = useCallback(() => {
    lastActivity.current = Date.now();
    setControls(true);
  }, []);
  useEffect(() => {
    if (!controls || panel || countdown !== null) return;
    const iv = setInterval(() => {
      const e = engine.current;
      if (e && !e.state.paused && Date.now() - lastActivity.current > HIDE_AFTER) setControls(false);
    }, 500);
    return () => clearInterval(iv);
  }, [controls, panel, countdown]);

  // Remote / keyboard.
  useEffect(() => {
    const onKey = (ev: KeyboardEvent) => {
      const e = engine.current;
      const consume = () => {
        ev.preventDefault();
        ev.stopPropagation();
      };
      lastActivity.current = Date.now();
      if (isKey(ev, 'back')) {
        if (document.fullscreenElement && ev.keyCode === 27) return;
        consume();
        if (panel) setPanel(null);
        else exit();
        return;
      }
      if (!e) return;
      if (isKey(ev, 'playPause')) return consume(), e.toggle(), poke();
      if (isKey(ev, 'play')) return consume(), e.play(), poke();
      if (isKey(ev, 'pause')) return consume(), e.pause(), poke();
      if (isKey(ev, 'stop')) return consume(), exit();
      if (isKey(ev, 'fastForward')) return consume(), e.seek(e.state.time + 30), poke();
      if (isKey(ev, 'rewind')) return consume(), e.seek(e.state.time - 10), poke();
      const tgt = ev.target as HTMLElement;
      const onControl = tgt && tgt !== document.body && root.current?.contains(tgt) && tgt.matches('button, a, [role="slider"]');
      if (ev.key === ' ' && !onControl) return consume(), e.toggle(), poke();
      if (ev.key === 'f' || ev.key === 'F') return consume(), toggleFullscreen();
      if (!controls) {
        // Controls hidden: arrows/enter act on playback directly, like a TV.
        if (isKey(ev, 'enter')) return consume(), e.toggle(), poke();
        if (isKey(ev, 'left') && ref?.kind !== 'live') return consume(), e.seek(e.state.time - 10), poke();
        if (isKey(ev, 'right') && ref?.kind !== 'live') return consume(), e.seek(e.state.time + 10), poke();
        if (isKey(ev, 'up') || isKey(ev, 'down')) {
          consume();
          poke();
          setTimeout(() => focusFirst(root.current!), 0);
        }
      } else if (!onControl && (isKey(ev, 'up') || isKey(ev, 'down') || isKey(ev, 'left') || isKey(ev, 'right'))) {
        consume();
        focusFirst(root.current!);
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [controls, panel, exit, poke, ref]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (controls) setTimeout(() => root.current && !root.current.contains(document.activeElement) && focusFirst(root.current), 0);
  }, [controls]);

  function toggleFullscreen() {
    const v = stage.current?.querySelector('video') as any;
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    else if (root.current?.requestFullscreen) root.current.requestFullscreen().catch(() => v?.webkitEnterFullscreen?.());
    else v?.webkitEnterFullscreen?.(); // iPhone
  }

  const e = engine.current;
  const live = ref?.kind === 'live';
  const title = ref?.seriesName ?? ref?.name ?? '';
  const subtitle = ref?.seriesName ? `S${ref.season}·E${ref.episode} · ${ref.name}` : '';
  const ext = (ref?.ext || '').toLowerCase();
  const formatHint =
    st?.errorCode === 'unsupported' && (isIOS || isSafari) && ['mkv', 'avi', 'wmv', 'flv'].includes(ext)
      ? `iPhone and Safari cannot play .${ext} files. Use “Open in VLC”, or have the server deliver MP4/HLS.`
      : st?.errorCode === 'unsupported'
        ? `This ${platformLabel()} cannot decode this file (.${ext || '?'}). Try “Open in VLC”.`
        : null;
  const extUrl = ref && client ? client.externalUrl(ref) : '';
  const vlcUrl = isIOS || nativePlatform === 'ios' ? `vlc-x-callback://x-callback-url/stream?url=${encodeURIComponent(extUrl)}` : /Android/i.test(navigator.userAgent) ? `intent:${extUrl}#Intent;package=org.videolan.vlc;type=video/*;end` : `vlc://${extUrl}`;
  const errorMsg = fatal ?? (st?.error && !(st.errorCode === 'unsupported' && isTizen && !triedAvplay.current) ? st.error : null);

  return (
    <div
      ref={root}
      className={`player ${controls ? 'show' : 'hide'}`}
      onPointerMove={poke}
      onPointerDown={(ev) => {
        if (ev.target === ev.currentTarget || (ev.target as HTMLElement).classList.contains('player-tap')) {
          if (!controls) poke();
          else if (ev.pointerType === 'mouse') (e?.toggle(), poke());
          else setControls(false);
        }
      }}
    >
      <div className="player-stage" ref={stage} />
      <div className="player-tap" />
      {st?.cue && <div className="player-cue">{st.cue}</div>}
      {st?.buffering && !errorMsg && <div className="player-spinner spinner" />}

      <div className="player-ui" data-nav-scope={controls ? '' : undefined} aria-hidden={!controls}>
        <div className="player-top">
          <button className="btn btn-icon" onClick={exit} aria-label="Back">
            <Icon name="back" size={28} />
          </button>
          <div className="player-title">
            <div>{title}</div>
            {subtitle && <div className="muted">{subtitle}</div>}
          </div>
          {offline && <span className="pill">Offline copy</span>}
          {live && <span className="pill live">LIVE</span>}
        </div>

        {!live && st && (
          <div className="player-seek">
            <span className="t">{fmtTime(st.time)}</span>
            <SeekBar time={st.time} duration={st.duration} onSeek={(t) => (e?.seek(t), poke())} />
            <span className="t">-{fmtTime(Math.max(0, st.duration - st.time))}</span>
          </div>
        )}

        <div className="player-bar">
          {!live && (
            <button className="btn btn-icon" onClick={() => (e?.seek((e?.state.time ?? 0) - 10), poke())} aria-label="Back 10 seconds">
              <Icon name="rew" size={28} />
            </button>
          )}
          <button className="btn btn-icon btn-play" onClick={() => (e?.toggle(), poke())} aria-label={st?.paused ? 'Play' : 'Pause'} data-autofocus>
            <Icon name={st?.paused ? 'play' : 'pause'} size={36} />
          </button>
          {!live && (
            <button className="btn btn-icon" onClick={() => (e?.seek((e?.state.time ?? 0) + 30), poke())} aria-label="Forward 30 seconds">
              <Icon name="ffw" size={28} />
            </button>
          )}
          <div className="player-bar-spacer" />
          {ref && !live && (
            <button
              className="btn btn-icon"
              onClick={() => {
                moments.add(ref, e?.state.time ?? 0);
                toast(`Bookmarked at ${fmtTime(e?.state.time ?? 0)}`);
                poke();
              }}
              aria-label="Bookmark this moment"
              title="Bookmark this moment"
            >
              <Icon name="bookmark" />
            </button>
          )}
          {ref && live && (
            <button className="btn btn-icon" onClick={() => fav.toggle({ kind: 'live', id: ref.id, name: ref.name, poster: ref.poster })} aria-label="Favorite channel">
              <Icon name={fav.isFav('live', ref.id) ? 'star' : 'starOutline'} />
            </button>
          )}
          {st && st.audio.length > 1 && (
            <button className="btn btn-icon" onClick={() => setPanel(panel === 'audio' ? null : 'audio')} aria-label="Audio track" title="Audio">
              <Icon name="audio" />
            </button>
          )}
          {st && st.text.length > 0 && (
            <button className="btn btn-icon" onClick={() => setPanel(panel === 'text' ? null : 'text')} aria-label="Subtitles" title="Subtitles">
              <Icon name="cc" />
            </button>
          )}
          {!live && (
            <button className="btn btn-icon" onClick={() => setPanel(panel === 'speed' ? null : 'speed')} aria-label="Playback speed" title="Speed">
              <Icon name="speed" />
              {st && st.rate !== 1 && <small>{st.rate}×</small>}
            </button>
          )}
          {next && (
            <button className="btn btn-icon" onClick={goNext} aria-label="Next episode" title="Next episode">
              <Icon name="next" />
            </button>
          )}
          {!isTizen && (
            <button className="btn btn-icon" onClick={toggleFullscreen} aria-label="Fullscreen" title="Fullscreen (F)">
              <Icon name="fullscreen" />
            </button>
          )}
        </div>
      </div>

      {panel && st && (
        <div className="player-panel" data-nav-scope="">
          <h3>{panel === 'audio' ? 'Audio' : panel === 'text' ? 'Subtitles' : 'Speed'}</h3>
          {panel === 'audio' &&
            st.audio.map((t) => (
              <button key={t.id} className={`opt ${st.audioSel === t.id ? 'on' : ''}`} onClick={() => (e?.setAudio(t.id), setPanel(null))} data-autofocus={st.audioSel === t.id || undefined}>
                {t.label}
              </button>
            ))}
          {panel === 'text' && (
            <>
              <button className={`opt ${st.textSel === null ? 'on' : ''}`} onClick={() => (e?.setText(null), setPanel(null))}>
                Off
              </button>
              {st.text.map((t) => (
                <button key={t.id} className={`opt ${st.textSel === t.id ? 'on' : ''}`} onClick={() => (e?.setText(t.id), setPanel(null))}>
                  {t.label}
                </button>
              ))}
            </>
          )}
          {panel === 'speed' &&
            SPEEDS.map((r) => (
              <button key={r} className={`opt ${st.rate === r ? 'on' : ''}`} onClick={() => (e?.setRate(r), setPanel(null))} data-autofocus={st.rate === r || undefined}>
                {r}×
              </button>
            ))}
          <button className="opt muted" onClick={() => setPanel(null)}>
            Close
          </button>
        </div>
      )}

      {resumedAt > 0 && st && !st.error && st.time < resumedAt + 8 && st.time >= resumedAt - 1 && (
        <div className="player-resume">
          Resumed from {fmtTime(resumedAt)}
          <button
            className="btn"
            onClick={() => {
              e?.seek(0);
              setResumedAt(0);
            }}
          >
            Start over
          </button>
        </div>
      )}

      {countdown !== null && next && (
        <div className="player-next" data-nav-scope="">
          <div className="muted">Up next</div>
          <div className="player-next-title">
            S{next.season}·E{next.episode} · {next.name}
          </div>
          <div className="actions">
            <button className="btn btn-primary" onClick={goNext} data-autofocus>
              <Icon name="play" /> Play now ({countdown})
            </button>
            <button className="btn" onClick={() => setCountdown(null)}>
              Cancel
            </button>
          </div>
        </div>
      )}

      {errorMsg && (
        <div className="player-error" data-nav-scope="" role="alert">
          <h2>Can’t play this</h2>
          <p>{errorMsg}</p>
          {formatHint && <p className="muted">{formatHint}</p>}
          <div className="actions">
            {!fatal && (
              <button
                className="btn btn-primary"
                data-autofocus
                onClick={() => {
                  resumeFrom.current = st?.time ?? 0;
                  setReloadNonce((n) => n + 1);
                }}
              >
                <Icon name="refresh" /> Retry
              </button>
            )}
            {isTizen && AvPlayEngine.available() && engineName === 'html' && (
              <button
                className="btn"
                onClick={() => {
                  forceEngine.current = 'avplay';
                  setReloadNonce((n) => n + 1);
                }}
              >
                Use Samsung player
              </button>
            )}
            {isTizen && engineName === 'avplay' && (
              <button
                className="btn"
                onClick={() => {
                  forceEngine.current = 'html';
                  setReloadNonce((n) => n + 1);
                }}
              >
                Use web player
              </button>
            )}
            {extUrl && !isTizen && (
              <a className="btn" href={vlcUrl}>
                <Icon name="external" /> Open in VLC
              </a>
            )}
            {extUrl && !isTizen && (
              <button
                className="btn"
                onClick={() =>
                  navigator.clipboard
                    ?.writeText(extUrl)
                    .then(() => toast('Stream link copied'))
                    .catch(() => toast(extUrl))
                }
              >
                Copy link
              </button>
            )}
            <button className="btn" onClick={exit} data-autofocus={fatal ? true : undefined}>
              <Icon name="back" /> Back
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function platformLabel() {
  return isTizen ? 'projector' : isIOS ? 'iPhone' : 'device';
}

function SeekBar({ time, duration, onSeek }: { time: number; duration: number; onSeek: (t: number) => void }) {
  const bar = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<number | null>(null);
  const frac = useMemo(() => (duration > 0 ? (drag ?? time) / duration : 0), [drag, time, duration]);
  const at = (ev: RPointerEvent) => {
    const r = bar.current!.getBoundingClientRect();
    return Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width)) * duration;
  };
  return (
    <div
      ref={bar}
      className="seek"
      role="slider"
      tabIndex={0}
      aria-label="Seek"
      aria-valuemin={0}
      aria-valuemax={Math.round(duration)}
      aria-valuenow={Math.round(time)}
      aria-valuetext={fmtTime(time)}
      onPointerDown={(ev) => {
        if (!duration) return;
        ev.stopPropagation();
        bar.current!.setPointerCapture(ev.pointerId);
        setDrag(at(ev));
      }}
      onPointerMove={(ev) => drag !== null && setDrag(at(ev))}
      onPointerUp={(ev) => {
        if (drag === null) return;
        onSeek(at(ev));
        setDrag(null);
      }}
      onPointerCancel={() => setDrag(null)}
      onKeyDown={(ev: RKeyboardEvent) => {
        if (ev.keyCode === 37) (ev.preventDefault(), onSeek(time - 10));
        if (ev.keyCode === 39) (ev.preventDefault(), onSeek(time + 10));
      }}
    >
      <div className="seek-track">
        <div className="seek-fill" style={{ width: `${frac * 100}%` }} />
        <div className="seek-thumb" style={{ left: `${frac * 100}%` }} />
      </div>
      {drag !== null && (
        <div className="seek-tip" style={{ left: `${frac * 100}%` }}>
          {fmtTime(drag)}
        </div>
      )}
    </div>
  );
}
