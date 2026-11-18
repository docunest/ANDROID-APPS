import { router } from 'expo-router';
import { refKey, type MediaRef } from '@shared/xtream';
import { rememberRef } from '@shared/userdata';

export async function play(ref: MediaRef, opts: { from?: number } = {}) {
  await rememberRef(ref);
  router.push({ pathname: '/player', params: { key: refKey(ref), ...(opts.from != null ? { from: String(opts.from) } : {}) } });
}
export const openMovie = (id: string | number) => router.push({ pathname: '/movie/[id]', params: { id: String(id) } });
export const openSeries = (id: string | number) => router.push({ pathname: '/series/[id]', params: { id: String(id) } });
