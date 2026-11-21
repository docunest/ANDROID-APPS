// Native (Expo) counterpart of env.ts.
import { AppState, Platform } from 'react-native';
import * as Network from 'expo-network';

export const DEFAULT_API = (process.env.EXPO_PUBLIC_API_URL || '').replace(/\/+$/, '');

let online = true;
const wakers = new Set<() => void>();
try {
  Network.getNetworkStateAsync()
    .then((s) => {
      online = s.isConnected !== false;
    })
    .catch(() => {});
  Network.addNetworkStateListener((s) => {
    const was = online;
    online = s.isConnected !== false;
    if (online && !was) wakers.forEach((f) => f());
  });
} catch {
  /* expo-network unavailable: assume online */
}
AppState.addEventListener('change', (s) => {
  if (s === 'active') wakers.forEach((f) => f());
});

export const isOnline = () => online;
export function onWake(fn: () => void) {
  wakers.add(fn);
}
export const isVisible = () => AppState.currentState === 'active';
export const deviceLabel = () => `${Platform.OS}-app`;
