import './polyfills';
import { createRoot } from 'react-dom/client';
import App from './App';
import { isNative, isTizen } from './lib/platform';
import './styles.css';

createRoot(document.getElementById('root')!).render(<App />);

// Service worker = app shell offline for web/PWA installs. Native and Tizen ship files locally.
if (__PWA__ && 'serviceWorker' in navigator && window.isSecureContext && !isNative && !isTizen && /^https?:$/.test(location.protocol)) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('./sw.js', { scope: './' }).catch((e) => console.warn('SW registration failed', e));
  });
}
