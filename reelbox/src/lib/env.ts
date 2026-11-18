// What differs between the web app and the native (Expo) app for shared code.
// The native version is env.native.ts.

/** Build-time default for the family backend URL (VITE_API_URL). */
export const DEFAULT_API = ((import.meta.env.VITE_API_URL as string | undefined) || '').replace(/\/+$/, '');

export const isOnline = () => navigator.onLine;

/** Call `fn` when the app comes back: network returns or the app is shown again. */
export function onWake(fn: () => void) {
  window.addEventListener('online', fn);
  document.addEventListener('visibilitychange', () => document.visibilityState === 'visible' && fn());
}

export const isVisible = () => document.visibilityState === 'visible';

export const deviceLabel = () => navigator.platform || 'device';
