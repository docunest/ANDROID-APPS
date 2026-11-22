import { ASPECTS, describeCrop, ZOOM_MAX, ZOOM_MIN, ZOOM_STEP, type Crop, type Fit, type Picture } from '../lib/picture';
import { Icon } from './ui';
import { clampTone, isNeutral, TONE_LIMITS, TONE_PRESETS, type Tone } from '../lib/tone';

export type DetectState = 'off' | 'scanning' | 'done' | 'unavailable';

interface Props {
  picture: Picture;
  custom: boolean;
  scopeLabel: string;
  detected: Crop | null;
  detectState: DetectState;
  onChange: (p: Partial<Picture>) => void;
  onDetectNow: () => void;
  onReset: () => void;
  onMakeDefault: () => void;
  onClose: () => void;
  tone: Tone;
  /** False on Samsung's AVPlay engine, which draws below the page. */
  toneSupported: boolean;
  onTone: (t: Tone) => void;
}

const FITS: { value: Fit; label: string; hint: string }[] = [
  { value: 'contain', label: 'Fit', hint: 'Whole picture, bars if needed' },
  { value: 'cover', label: 'Fill', hint: 'Fill the screen, crop edges' },
  { value: 'fill', label: 'Stretch', hint: 'Fill the screen, distort' },
];

const pct = (z: number) => `${Math.round(z * 100)}%`;
const clampZoom = (z: number) => Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, Math.round(z / ZOOM_STEP) * ZOOM_STEP));

export function PicturePanel({ picture: p, custom, scopeLabel, detected, detectState, onChange, onDetectNow, onReset, onMakeDefault, onClose, tone, toneSupported, onTone }: Props) {
  const zoomed = p.zoom > 1.001 || p.fit === 'cover';
  return (
    <div className="player-panel picture-panel" data-nav-scope="" role="dialog" aria-label="Picture settings">
      <div className="pp-head">
        <h3>Picture</h3>
        <span className="muted small">Saved for {scopeLabel}</span>
      </div>

      <div className="pp-row" role="group" aria-label="Fit">
        <span className="pp-label">Fit</span>
        <div className="seg">
          {FITS.map((f) => (
            <button key={f.value} className={`seg-btn ${p.fit === f.value ? 'on' : ''}`} aria-pressed={p.fit === f.value} title={f.hint} onClick={() => onChange({ fit: f.value })} data-autofocus={p.fit === f.value || undefined}>
              {f.label}
            </button>
          ))}
        </div>
      </div>

      <div className="pp-row" role="group" aria-label="Aspect ratio">
        <span className="pp-label">Shape</span>
        <div className="seg">
          {ASPECTS.map((a) => {
            const on = a.value === null ? p.aspect === null : p.aspect !== null && Math.abs(p.aspect - a.value) < 0.01;
            return (
              <button key={a.label} className={`seg-btn ${on ? 'on' : ''}`} aria-pressed={on} onClick={() => onChange({ aspect: a.value })} title={a.value ? `Show the frame as ${a.label} (fixes squashed or stretched video)` : 'As stored in the file'}>
                {a.label}
              </button>
            );
          })}
        </div>
      </div>

      <div className="pp-row" role="group" aria-label="Zoom">
        <span className="pp-label">Zoom</span>
        <div className="seg">
          <button className="seg-btn" onClick={() => onChange({ zoom: clampZoom(p.zoom - ZOOM_STEP) })} aria-label="Zoom out">
            −
          </button>
          <span className="seg-value" aria-live="polite">
            {pct(p.zoom)}
          </span>
          <button className="seg-btn" onClick={() => onChange({ zoom: clampZoom(p.zoom + ZOOM_STEP) })} aria-label="Zoom in">
            +
          </button>
          {[1, 1.33].map((z) => (
            <button key={z} className={`seg-btn ${Math.abs(p.zoom - z) < 0.01 ? 'on' : ''}`} onClick={() => onChange({ zoom: z, panX: 0, panY: 0 })} title={z === 1.33 ? 'Typical fix for 4:3 ↔ 16:9 double conversion' : 'No zoom'}>
              {z === 1 ? 'Reset' : pct(z)}
            </button>
          ))}
        </div>
      </div>

      {zoomed && (
        <div className="pp-row" role="group" aria-label="Position">
          <span className="pp-label">Move</span>
          <div className="seg">
            <button className="seg-btn" aria-label="Move picture up" onClick={() => onChange({ panY: Math.max(-1, p.panY - 0.25) })}>
              ▲
            </button>
            <button className="seg-btn" aria-label="Move picture down" onClick={() => onChange({ panY: Math.min(1, p.panY + 0.25) })}>
              ▼
            </button>
            <button className="seg-btn" aria-label="Move picture left" onClick={() => onChange({ panX: Math.max(-1, p.panX - 0.25) })}>
              ◀
            </button>
            <button className="seg-btn" aria-label="Move picture right" onClick={() => onChange({ panX: Math.min(1, p.panX + 0.25) })}>
              ▶
            </button>
            {(p.panX || p.panY) !== 0 && (
              <button className="seg-btn" onClick={() => onChange({ panX: 0, panY: 0 })}>
                Center
              </button>
            )}
          </div>
        </div>
      )}

      <div className="pp-row" role="group" aria-label="Black bars">
        <span className="pp-label">Bars</span>
        <div className="seg">
          <button className={`seg-btn ${p.autoCrop ? 'on' : ''}`} aria-pressed={p.autoCrop} onClick={() => onChange({ autoCrop: !p.autoCrop, crop: null })}>
            Auto-remove {p.autoCrop ? 'on' : 'off'}
          </button>
          {p.autoCrop && detectState !== 'unavailable' && (
            <button className="seg-btn" onClick={onDetectNow}>
              Re-scan
            </button>
          )}
        </div>
      </div>
      <p className="pp-status muted small" aria-live="polite">
        {!p.autoCrop
          ? 'Black bars inside the video are shown as-is.'
          : detectState === 'unavailable'
            ? 'This source can’t be scanned for bars here — use Zoom (133% fixes most double-boxed videos).'
            : detectState === 'scanning' && !detected
              ? 'Looking for black bars…'
              : describeCrop(detected)}
      </p>

      <div className="pp-head pp-sub">
        <h3>Brightness</h3>
        <span className="muted small">Saved for this screen</span>
      </div>
      <div className="pp-row" role="group" aria-label="Brightness presets">
        <span className="pp-label">Boost</span>
        <div className="seg">
          {TONE_PRESETS.map((pr) => {
            const on = (Object.keys(pr.tone) as (keyof Tone)[]).every((k) => Math.abs(pr.tone[k] - tone[k]) < 0.001);
            return (
              <button key={pr.label} className={`seg-btn ${on ? 'on' : ''}`} aria-pressed={on} title={pr.hint} onClick={() => onTone(pr.tone)}>
                {pr.label}
              </button>
            );
          })}
        </div>
      </div>
      {(Object.keys(TONE_LIMITS) as (keyof Tone)[]).map((k) => (
        <div className="pp-row" role="group" aria-label={TONE_LIMITS[k].label} key={k}>
          <span className="pp-label">{TONE_LIMITS[k].label}</span>
          <div className="seg">
            <button className="seg-btn" aria-label={`${TONE_LIMITS[k].label} down`} onClick={() => onTone({ ...tone, [k]: clampTone(k, tone[k] - TONE_LIMITS[k].step) })}>
              −
            </button>
            <span className="seg-value" aria-live="polite">
              {k === 'gamma' ? tone[k].toFixed(1) : pct(tone[k])}
            </span>
            <button className="seg-btn" aria-label={`${TONE_LIMITS[k].label} up`} onClick={() => onTone({ ...tone, [k]: clampTone(k, tone[k] + TONE_LIMITS[k].step) })}>
              +
            </button>
          </div>
        </div>
      ))}
      {!toneSupported && !isNeutral(tone) && (
        <p className="pp-status muted small">
          Samsung’s built-in player can’t be brightened by an app. Use the projector’s Settings → Picture (Gamma / Brightness), or in Reelbox Settings set Video engine to “Web player” (plays MP4 and HLS).
        </p>
      )}
      {!toneSupported && isNeutral(tone) && <p className="pp-status muted small">Not available with Samsung’s built-in player — use the projector’s own Picture settings.</p>}

      <div className="pp-actions">
        {custom && (
          <button className="opt" onClick={onReset}>
            <Icon name="refresh" size={16} /> Reset to default
          </button>
        )}
        <button className="opt" onClick={onMakeDefault}>
          Use for all videos
        </button>
        <button className="opt muted" onClick={onClose}>
          Close
        </button>
      </div>
    </div>
  );
}
