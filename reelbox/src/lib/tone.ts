// Brightness / contrast / colour / gamma — like VLC's "Adjust" filter. Stored per device (a dim
// projector needs a boost, the iPhone doesn't), so it is never synced.
export interface Tone {
  brightness: number; // 1 = unchanged
  contrast: number;
  saturation: number;
  gamma: number; // > 1 lifts dark and mid tones (VLC-style), 1 = unchanged
}

export const NEUTRAL_TONE: Tone = { brightness: 1, contrast: 1, saturation: 1, gamma: 1 };

export const TONE_PRESETS: { label: string; hint: string; tone: Tone }[] = [
  { label: 'Normal', hint: 'No change', tone: NEUTRAL_TONE },
  { label: 'Dim screen', hint: 'Lifts shadows and mid tones for a weak projector', tone: { brightness: 1.1, contrast: 1.05, saturation: 1.1, gamma: 1.4 } },
  { label: 'Very dim', hint: 'Stronger boost for a daylight room or a dark old video', tone: { brightness: 1.2, contrast: 1.1, saturation: 1.2, gamma: 1.8 } },
];

export const TONE_LIMITS: Record<keyof Tone, { min: number; max: number; step: number; label: string }> = {
  brightness: { min: 0.5, max: 2, step: 0.05, label: 'Brightness' },
  gamma: { min: 0.5, max: 3, step: 0.1, label: 'Gamma' },
  contrast: { min: 0.5, max: 2, step: 0.05, label: 'Contrast' },
  saturation: { min: 0, max: 2, step: 0.1, label: 'Colour' },
};

export const isNeutral = (t: Tone) =>
  (Object.keys(NEUTRAL_TONE) as (keyof Tone)[]).every((k) => Math.abs(t[k] - NEUTRAL_TONE[k]) < 0.001);

export const clampTone = (k: keyof Tone, v: number) => {
  const l = TONE_LIMITS[k];
  return Math.min(l.max, Math.max(l.min, Math.round(v / l.step) * l.step));
};

/**
 * How the boost is drawn.
 * - 'filter': real brightness/gamma on the video pixels (browsers, Android).
 * - 'veil': a faint white layer over the video. Samsung TVs/projectors draw video on a hardware
 *   plane below the page, so filters can't reach it, but the page is blended on top of it — the
 *   veil lifts dark and mid tones with any Samsung player (AVPlay too). Blacks turn slightly grey.
 */
export type ToneMode = 'filter' | 'veil';

/** Veil opacity that lifts mid grey about as much as the gamma/brightness would. */
export function veilFor(t: Tone): number {
  if (isNeutral(t)) return 0;
  const mid = Math.min(1, Math.pow(0.5, 1 / t.gamma) * t.brightness); // where 50 % grey should land
  // a + (1 - a)·0.5 = mid, softened: the veil also greys the blacks, so it shouldn't go all the way.
  const a = ((mid - 0.5) / 0.5) * 0.7;
  return Math.round(Math.min(0.45, Math.max(0, a)) * 100) / 100;
}

const SVG_ID = 'reelbox-gamma';

/**
 * CSS `filter` value for a tone. Gamma uses an SVG transfer function (CSS has no gamma());
 * it is applied by the browser's compositor, so it works on videos from servers without CORS.
 */
export function toneFilter(t: Tone): string {
  if (isNeutral(t)) return '';
  const parts: string[] = [];
  if (Math.abs(t.gamma - 1) > 0.001) {
    ensureGammaFilter(t.gamma);
    parts.push(`url(#${SVG_ID})`);
  }
  if (Math.abs(t.brightness - 1) > 0.001) parts.push(`brightness(${t.brightness.toFixed(2)})`);
  if (Math.abs(t.contrast - 1) > 0.001) parts.push(`contrast(${t.contrast.toFixed(2)})`);
  if (Math.abs(t.saturation - 1) > 0.001) parts.push(`saturate(${t.saturation.toFixed(2)})`);
  return parts.join(' ');
}

function ensureGammaFilter(gamma: number) {
  const NS = 'http://www.w3.org/2000/svg';
  let svg = document.getElementById(`${SVG_ID}-svg`) as SVGSVGElement | null;
  if (!svg) {
    svg = document.createElementNS(NS, 'svg');
    svg.id = `${SVG_ID}-svg`;
    svg.setAttribute('width', '0');
    svg.setAttribute('height', '0');
    svg.setAttribute('aria-hidden', 'true');
    svg.style.position = 'absolute';
    svg.innerHTML = `<filter id="${SVG_ID}" color-interpolation-filters="sRGB"><feComponentTransfer>${['R', 'G', 'B']
      .map((c) => `<feFunc${c} type="gamma" amplitude="1" exponent="1" offset="0"/>`)
      .join('')}</feComponentTransfer></filter>`;
    document.body.appendChild(svg);
  }
  const exp = String(Math.round((1 / gamma) * 1000) / 1000);
  svg.querySelectorAll('feFuncR, feFuncG, feFuncB').forEach((f) => f.setAttribute('exponent', exp));
}
