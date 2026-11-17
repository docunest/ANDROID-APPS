import { useEffect } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useSession } from '../lib/session';
import { exitApp, isIOS, isKey, isNative, isStandalone } from '../lib/platform';
import { usePersisted } from '../lib/store';
import { focusFirst } from '../lib/spatial';
import { Icon } from './ui';

const NAV = [
  { to: '/', label: 'Home', icon: 'home', end: true },
  { to: '/movies', label: 'Movies', icon: 'movie' },
  { to: '/series', label: 'Series', icon: 'tv' },
  { to: '/live', label: 'Live', icon: 'live' },
  { to: '/search', label: 'Search', icon: 'search' },
  { to: '/library', label: 'Bookmarks', short: 'Saved', icon: 'star' },
  { to: '/downloads', label: 'Downloads', short: 'Offline', icon: 'download' },
  { to: '/settings', label: 'Settings', icon: 'settings' },
];

/** Back key on Tizen remote / Escape / Android hardware back. */
export function useBackHandler() {
  const nav = useNavigate();
  const loc = useLocation();
  useEffect(() => {
    const goBack = () => {
      if (loc.pathname === '/' || loc.pathname === '/setup') exitApp();
      else if (loc.key === 'default') nav('/', { replace: true });
      else nav(-1);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || !isKey(e, 'back')) return;
      const t = e.target as HTMLElement;
      if (e.keyCode === 27 && (t instanceof HTMLInputElement || t instanceof HTMLTextAreaElement) && (t as HTMLInputElement).value) return;
      if (document.fullscreenElement) return; // browser exits fullscreen itself
      e.preventDefault();
      goBack();
    };
    window.addEventListener('keydown', onKey);
    return () => {
      window.removeEventListener('keydown', onKey);
    };
  }, [loc, nav]);
}

/** On TV/remote, land focus on the page's main action after every navigation. */
function useTvAutofocus() {
  const loc = useLocation();
  useEffect(() => {
    if (!document.documentElement.classList.contains('tv')) return;
    const timers = [60, 400, 1200].map((ms) =>
      setTimeout(() => {
        const a = document.activeElement;
        if (a && a !== document.body && !a.closest('.nav')) return;
        const main = document.getElementById('main');
        if (main) focusFirst(main);
      }, ms),
    );
    return () => timers.forEach(clearTimeout);
  }, [loc.pathname]);
}

export default function Layout() {
  const { online, account, via } = useSession();
  useBackHandler();
  useTvAutofocus();
  return (
    <div className="app">
      <nav className="nav" aria-label="Main">
        <div className="brand" aria-hidden="true">
          <span className="brand-mark">▶</span>
          <span className="brand-name">Reelbox</span>
        </div>
        {NAV.map((n) => (
          <NavLink key={n.to} to={n.to} end={n.end} aria-label={n.label} className={({ isActive }) => `nav-item ${isActive ? 'active' : ''} nav-${n.icon}`}>
            <Icon name={n.icon} />
            <span className="nav-label">{n.label}</span>
            <span className="nav-short" aria-hidden="true">
              {'short' in n ? n.short : n.label}
            </span>
          </NavLink>
        ))}
        <div className="nav-spacer" />
        {account && (
          <div className="nav-account" title={`${account.name} · ${via === 'proxy' ? 'via proxy' : 'direct'}`}>
            {account.name}
          </div>
        )}
      </nav>
      {!online && (
        <div className="offline-banner" role="status">
          <Icon name="cloudOff" size={18} /> Offline — showing your saved catalog and downloads
        </div>
      )}
      <InstallHint />
      <main className="main" id="main">
        <Outlet />
      </main>
    </div>
  );
}

/** iPhone without an Apple developer account = install as a web app from Safari. */
function InstallHint() {
  const [dismissed, setDismissed, ready] = usePersisted<boolean>('installHintDismissed', false);
  if (!ready || dismissed || !isIOS || isStandalone || isNative) return null;
  return (
    <div className="install-hint" role="note">
      <span>
        <b>Install Reelbox:</b> tap <span aria-label="Share">Share</span> <span className="share-glyph" aria-hidden="true">⬆︎</span> then <b>Add to Home Screen</b>. It then opens
        full-screen and keeps your downloads safe.
      </span>
      <button className="btn btn-icon" onClick={() => setDismissed(() => true)} aria-label="Dismiss">
        <Icon name="close" size={16} />
      </button>
    </div>
  );
}
