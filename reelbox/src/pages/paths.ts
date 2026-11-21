import type { MediaRef } from '../lib/xtream';
import type { FavKind } from '../lib/userdata';

export const playPath = (r: MediaRef, startAt?: number) => `/play/${r.kind}/${encodeURIComponent(r.id)}${startAt ? `?t=${Math.floor(startAt)}` : ''}`;

export const detailPath = (kind: FavKind, id: string) => (kind === 'movie' ? `/movie/${id}` : kind === 'series' ? `/show/${id}` : `/play/live/${id}`);
