// Picture geometry: fit / fill / stretch, forced aspect ratio, zoom, pan and black-bar cropping.
// Old rips are often "windowboxed": a 16:9 film letterboxed into 4:3, then pillarboxed back
// into 16:9 (or the reverse), leaving bars on all four sides. We find the real picture inside
// the frame (crop), then fit *that* to the screen.

export type Fit = 'contain' | 'cover' | 'fill';

export interface Crop {
  l: number; // fractions of the full frame, 0..1
  t: number;
  r: number;
  b: number;
}

export interface Picture {
  fit: Fit;
  /** Force display aspect ratio of the full frame (fixes squashed/stretched video). null = from file. */
  aspect: number | null;
  zoom: number; // 1 = none
  panX: number; // -1..1, fraction of the overflow
  panY: number;
  /** Detect and remove black bars baked into the video. */
  autoCrop: boolean;
  /** Manual crop; overrides auto detection when set. */
  crop: Crop | null;
}

export const DEFAULT_PICTURE: Picture = { fit: 'contain', aspect: null, zoom: 1, panX: 0, panY: 0, autoCrop: true, crop: null };

export const ASPECTS: { label: string; value: number | null }[] = [
  { label: 'Auto', value: null },
  { label: '4:3', value: 4 / 3 },
  { label: '16:9', value: 16 / 9 },
  { label: '1.85', value: 1.85 },
  { label: '2.39', value: 2.39 },
];

export const ZOOM_STEP = 0.05;
export const ZOOM_MIN = 0.5;
export const ZOOM_MAX = 3;

export const NO_CROP: Crop = { l: 0, t: 0, r: 1, b: 1 };

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

/**
 * Where to place the full video frame (in container pixels) so that the cropped
 * region is fitted to the container according to `p`.
 */
export function frameRect(container: { w: number; h: number }, intrinsic: { w: number; h: number }, p: Picture, crop: Crop | null): Rect {
  const { w: W, h: H } = container;
  const c = crop ?? NO_CROP;
  const cw = Math.max(0.05, c.r - c.l);
  const ch = Math.max(0.05, c.b - c.t);
  const frameAspect = p.aspect ?? (intrinsic.w > 0 && intrinsic.h > 0 ? intrinsic.w / intrinsic.h : 16 / 9);
  const contentAspect = (frameAspect * cw) / ch;
  let bw: number;
  let bh: number;
  if (p.fit === 'fill') {
    bw = W;
    bh = H;
  } else {
    const wider = contentAspect > W / H;
    const byWidth = p.fit === 'contain' ? wider : !wider;
    if (byWidth) {
      bw = W;
      bh = W / contentAspect;
    } else {
      bh = H;
      bw = H * contentAspect;
    }
  }
  bw *= p.zoom;
  bh *= p.zoom;
  // Pan: move within the overflow (only meaningful when the picture is larger than the screen).
  const ox = Math.max(0, bw - W) / 2;
  const oy = Math.max(0, bh - H) / 2;
  const bx = (W - bw) / 2 - p.panX * ox;
  const by = (H - bh) / 2 - p.panY * oy;
  const fw = bw / cw;
  const fh = bh / ch;
  return { x: bx - c.l * fw, y: by - c.t * fh, w: fw, h: fh };
}

// ---------- black bar detection ----------

const SAMPLE_W = 192;

/** Content box of one frame, or null if the frame is too dark/uniform to judge. */
export function detectFrame(source: CanvasImageSource, vw: number, vh: number, ctx: CanvasRenderingContext2D): Crop | null {
  const w = SAMPLE_W;
  const h = Math.max(8, Math.round((SAMPLE_W * vh) / vw));
  ctx.canvas.width = w;
  ctx.canvas.height = h;
  ctx.drawImage(source, 0, 0, w, h);
  const px = ctx.getImageData(0, 0, w, h).data; // throws if the video is cross-origin without CORS
  const luma = new Float32Array(w * h);
  let bright = 0;
  for (let i = 0, j = 0; i < px.length; i += 4, j++) {
    const y = 0.2126 * px[i] + 0.7152 * px[i + 1] + 0.0722 * px[i + 2];
    luma[j] = y;
    if (y > 40) bright++;
  }
  if (bright < w * h * 0.04) return null; // fade-to-black, night scene: no information
  // A line is "bar" if nearly all of its pixels are near black.
  const isBarRow = (y: number) => {
    let dark = 0;
    for (let x = 0; x < w; x++) if (luma[y * w + x] < 28) dark++;
    return dark > w * 0.97;
  };
  const isBarCol = (x: number) => {
    let dark = 0;
    for (let y = 0; y < h; y++) if (luma[y * w + x] < 28) dark++;
    return dark > h * 0.97;
  };
  let t = 0;
  while (t < h / 2 && isBarRow(t)) t++;
  let b = h - 1;
  while (b > h / 2 && isBarRow(b)) b--;
  let l = 0;
  while (l < w / 2 && isBarCol(l)) l++;
  let r = w - 1;
  while (r > w / 2 && isBarCol(r)) r--;
  return { l: l / w, t: t / h, r: (r + 1) / w, b: (b + 1) / h };
}

/** Union of content boxes: the picture area only ever grows as brighter frames reveal its true edges. */
export function unionCrop(a: Crop | null, b: Crop | null): Crop | null {
  if (!a) return b;
  if (!b) return a;
  return { l: Math.min(a.l, b.l), t: Math.min(a.t, b.t), r: Math.max(a.r, b.r), b: Math.max(a.b, b.b) };
}

/** Ignore tiny bars (encoder edges, overscan noise) — only crop when it matters. */
export function significant(c: Crop | null): Crop | null {
  if (!c) return null;
  const snap = (v: number, edge: number) => (Math.abs(v - edge) < 0.025 ? edge : v);
  const s = { l: snap(c.l, 0), t: snap(c.t, 0), r: snap(c.r, 1), b: snap(c.b, 1) };
  if (s.l === 0 && s.t === 0 && s.r === 1 && s.b === 1) return null;
  if (s.r - s.l < 0.3 || s.b - s.t < 0.3) return null; // implausible: mostly black frame
  return s;
}

export function describeCrop(c: Crop | null): string {
  if (!c) return 'No black bars found';
  const parts: string[] = [];
  const tb = Math.round(((c.t + (1 - c.b)) / 2) * 100);
  const lr = Math.round(((c.l + (1 - c.r)) / 2) * 100);
  if (tb) parts.push(`top/bottom ${tb}%`);
  if (lr) parts.push(`sides ${lr}%`);
  return `Removed bars: ${parts.join(', ')}`;
}

export const samePicture = (a: Picture, b: Picture) => JSON.stringify(a) === JSON.stringify(b);

export function pictureSummary(p: Picture): string {
  const bits: string[] = [];
  if (p.fit !== 'contain') bits.push(p.fit === 'cover' ? 'Fill' : 'Stretch');
  if (p.aspect) bits.push(ASPECTS.find((a) => a.value && Math.abs(a.value - p.aspect!) < 0.01)?.label ?? p.aspect.toFixed(2));
  if (Math.abs(p.zoom - 1) > 0.001) bits.push(`${Math.round(p.zoom * 100)}%`);
  return bits.join(' · ');
}
