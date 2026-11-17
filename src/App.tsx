import { lazy, Suspense, useEffect } from 'react';
import { HashRouter, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { SessionProvider, useSession } from './lib/session';
import { installSpatialNav } from './lib/spatial';
import { isNative, isTV, nativePlatform, registerTvKeys } from './lib/platform';
import { usePersisted } from './lib/store';
import Layout from './components/Layout';
import { Spinner, Toaster } from './components/ui';
import Home from './pages/Home';
import Browse from './pages/Browse';
import Setup from './pages/Setup';

const Movie = lazy(() => import('./pages/Movie'));
const Show = lazy(() => import('./pages/Show'));
const Player = lazy(() => import('./pages/Player'));
const Search = lazy(() => import('./pages/Search'));
const Library = lazy(() => import('./pages/Library'));
const Downloads = lazy(() => import('./pages/Downloads'));
const Settings = lazy(() => import('./pages/Settings'));

function Gate() {
  const { ready, account } = useSession();
  const loc = useLocation();
  if (!ready) return <Spinner label="Starting…" />;
  if (!account && loc.pathname !== '/setup') return <Navigate to="/setup" replace />;
  return (
    <Suspense fallback={<Spinner />}>
      <Routes>
        <Route path="/setup" element={<Setup />} />
        <Route path="/play/:kind/:id" element={<Player />} />
        <Route element={<Layout />}>
          <Route index element={<Home />} />
          <Route path="/movies" element={<Browse key="movies" kind="movies" />} />
          <Route path="/series" element={<Browse key="series" kind="series" />} />
          <Route path="/live" element={<Browse key="live" kind="live" />} />
          <Route path="/movie/:id" element={<Movie />} />
          <Route path="/show/:id" element={<Show />} />
          <Route path="/search" element={<Search />} />
          <Route path="/library" element={<Library />} />
          <Route path="/downloads" element={<Downloads />} />
          <Route path="/settings" element={<Settings />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </Suspense>
  );
}

function Chrome() {
  const [tvMode] = usePersisted<'auto' | 'on' | 'off'>('tvMode', 'auto');
  useEffect(() => {
    document.documentElement.classList.toggle('tv', tvMode === 'on' || (tvMode === 'auto' && isTV));
  }, [tvMode]);
  useEffect(() => {
    registerTvKeys();
    const off = installSpatialNav();
    let removeBack: (() => void) | undefined;
    if (isNative && nativePlatform === 'android') {
      // Android back → synthetic Escape, so the player/pages handle it like a remote's Back key.
      import('@capacitor/app').then(({ App }) => {
        const h = App.addListener('backButton', () => {
          window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true, cancelable: true } as KeyboardEventInit));
        });
        removeBack = () => void h.then((x) => x.remove());
      });
    }
    return () => {
      off();
      removeBack?.();
    };
  }, []);
  return null;
}

export default function App() {
  return (
    <HashRouter>
      <SessionProvider>
        <Chrome />
        <Gate />
        <Toaster />
      </SessionProvider>
    </HashRouter>
  );
}
