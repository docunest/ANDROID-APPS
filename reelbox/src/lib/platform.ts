// Platform detection and remote-control / hardware key handling.
import { Capacitor } from '@capacitor/core';

declare global {
  interface Window {
    tizen?: any;
    webapis?: any;
  }
}

const ua = typeof navigator !== 'undefined' ? navigator.userAgent : '';

export const nativePlatform = Capacitor.getPlatform(); // 'ios' | 'android' | 'web'
export const isNative = Capacitor.isNativePlatform();
export const isTizen = typeof window !== 'undefined' && (!!window.tizen || /Tizen/i.test(ua));
export const isIOS =
  nativePlatform === 'ios' ||
  /iPad|iPhone|iPod/.test(ua) ||
  (typeof navigator !== 'undefined' && navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
export const isSafari = /^((?!chrome|android|crios|fxios).)*safari/i.test(ua);
/** Running as an installed home-screen web app (iPhone "Add to Home Screen", desktop PWA). */
export const isStandalone =
  typeof window !== 'undefined' && ((navigator as any).standalone === true || window.matchMedia?.('(display-mode: standalone)').matches === true);
export const isTV = isTizen || /SMART-TV|SmartTV|Web0S|AFT[A-Z]/i.test(ua);

export const platformName = isTizen
  ? 'Samsung Tizen'
  : isNative
    ? nativePlatform === 'ios'
      ? 'iOS app'
      : 'Android app'
    : 'Web browser';

/** Key codes, including Samsung Tizen remote codes. */
export const KEYS = {
  back: [10009, 27], // Tizen Return, Escape
  enter: [13],
  playPause: [10252, 179],
  play: [415],
  pause: [19],
  stop: [413],
  fastForward: [417, 228],
  rewind: [412, 227],
  left: [37],
  up: [38],
  right: [39],
  down: [40],
};

export const isKey = (e: KeyboardEvent, group: keyof typeof KEYS) =>
  KEYS[group].includes(e.keyCode) ||
  (group === 'back' && e.key === 'Escape') ||
  (group === 'playPause' && e.key === 'MediaPlayPause');

/** Tizen only delivers media keys to apps that register them. */
export function registerTvKeys() {
  const dev = window.tizen?.tvinputdevice;
  if (!dev) return;
  const wanted = ['MediaPlayPause', 'MediaPlay', 'MediaPause', 'MediaStop', 'MediaFastForward', 'MediaRewind'];
  for (const k of wanted) {
    try {
      dev.registerKey(k);
    } catch {
      /* key not available on this device */
    }
  }
}

export function exitApp() {
  try {
    window.tizen?.application?.getCurrentApplication()?.exit();
  } catch {
    /* not tizen */
  }
  if (isNative && nativePlatform === 'android') {
    import('@capacitor/app').then(({ App }) => App.exitApp()).catch(() => {});
  }
}

/** True when the page can use OPFS / service workers (https or localhost or native). */
export const isSecure = typeof window !== 'undefined' && (window.isSecureContext || isNative);
