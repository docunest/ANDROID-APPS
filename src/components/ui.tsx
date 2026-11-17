import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';

// ---------- Icons (inline, so they work offline and on Tizen) ----------
const paths: Record<string, string> = {
  play: 'M8 5v14l11-7z',
  pause: 'M6 5h4v14H6zM14 5h4v14h-4z',
  back: 'M15.4 7.4 14 6l-6 6 6 6 1.4-1.4L10.8 12z',
  rew: 'M11 18V6l-8.5 6zm.5-6 8.5 6V6z',
  ffw: 'M4 18l8.5-6L4 6zm9-12v12l8.5-6z',
  star: 'M12 17.3 18.2 21l-1.6-7L22 9.2l-7.2-.6L12 2 9.2 8.6 2 9.2 7.4 14l-1.6 7z',
  starOutline:
    'M22 9.2l-7.2-.6L12 2 9.2 8.6 2 9.2 7.4 14l-1.6 7L12 17.3 18.2 21l-1.6-7zM12 15.4l-3.8 2.3 1-4.3-3.3-2.9 4.4-.4L12 6.1l1.7 4 4.4.4-3.3 2.9 1 4.3z',
  bookmark: 'M17 3H7a2 2 0 0 0-2 2v16l7-3 7 3V5a2 2 0 0 0-2-2z',
  download: 'M5 20h14v-2H5zm7-18v12.2l-4.6-4.6L6 11l6 6 6-6-1.4-1.4-4.6 4.6V2z',
  check: 'M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z',
  trash: 'M6 19a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V7H6zM19 4h-3.5l-1-1h-5l-1 1H5v2h14z',
  search: 'M15.5 14h-.8l-.3-.3A6.5 6.5 0 1 0 9.5 16a6.5 6.5 0 0 0 4.2-1.6l.3.3v.8l5 5 1.5-1.5zm-6 0a4.5 4.5 0 1 1 0-9 4.5 4.5 0 0 1 0 9z',
  home: 'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
  movie: 'M18 4l2 4h-3l-2-4h-2l2 4h-3l-2-4H8l2 4H7L5 4H4a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V4z',
  tv: 'M21 3H3a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h5v2h8v-2h5a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2zm0 14H3V5h18z',
  live: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM5.6 5.6l1.4 1.4a7 7 0 0 0 0 10l-1.4 1.4a9 9 0 0 1 0-12.8zm12.8 0a9 9 0 0 1 0 12.8L17 17a7 7 0 0 0 0-10z',
  library: 'M4 6H2v14a2 2 0 0 0 2 2h14v-2H4zm16-4H8a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V4a2 2 0 0 0-2-2zm-8 12.5v-9l6 4.5z',
  settings:
    'M19.1 12.9a7 7 0 0 0 0-1.8l2-1.6-2-3.4-2.4 1a7 7 0 0 0-1.5-.9L15 3.6h-4l-.4 2.6a7 7 0 0 0-1.5.9l-2.4-1-2 3.4 2 1.6a7 7 0 0 0 0 1.8l-2 1.6 2 3.4 2.4-1c.5.4 1 .7 1.5.9l.4 2.6h4l.4-2.6c.5-.2 1-.5 1.5-.9l2.4 1 2-3.4zM13 15.5a3.5 3.5 0 1 1 0-7 3.5 3.5 0 0 1 0 7z',
  fullscreen: 'M7 14H5v5h5v-2H7zm-2-4h2V7h3V5H5zm12 7h-3v2h5v-5h-2zM14 5v2h3v3h2V5z',
  cc: 'M19 4H5a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2zm-8 7H9.5v-.5h-2v3h2V13H11v1a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1zm7 0h-1.5v-.5h-2v3h2V13H18v1a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1h3a1 1 0 0 1 1 1z',
  audio: 'M12 3v10.6A4 4 0 1 0 14 17V7h4V3z',
  next: 'M6 18l8.5-6L6 6zM16 6v12h2V6z',
  refresh: 'M17.7 6.3A8 8 0 1 0 19.7 14h-2.1a6 6 0 1 1-1.4-6.2L13 11h7V4z',
  cloudOff: 'M19.4 10A7.5 7.5 0 0 0 6.6 6.9L4.3 4.6 3 5.9l2.8 2.8A5 5 0 0 0 6 19h11.7l2 2 1.3-1.3zM6 17a3 3 0 0 1-.1-6l7.9 6zm16-1.4c0-2.4-1.6-4.6-3.9-5.3L21.6 14 22 15.6z',
  external: 'M14 3v2h3.6l-9.8 9.8 1.4 1.4L19 6.4V10h2V3zm5 16H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7h-2z',
  speed: 'M20.4 8.6 19 10a8 8 0 0 1-1 8H6a8 8 0 0 1 10.5-11.9l1.3-1.3A10 10 0 0 0 4.3 20h15.4a10 10 0 0 0 .7-11.4zm-10 6.8a2 2 0 0 0 2.8 0l5.7-8.5-8.5 5.7a2 2 0 0 0 0 2.8z',
  close: 'M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z',
};

export function Icon({ name, size = 22, className }: { name: keyof typeof paths | string; size?: number; className?: string }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" aria-hidden="true" fill="currentColor">
      <path d={paths[name] ?? ''} />
    </svg>
  );
}

// ---------- Poster image with graceful fallback ----------
export function Poster({ src, name, wide }: { src?: string; name: string; wide?: boolean }) {
  const [failed, setFailed] = useState(!src);
  useEffect(() => setFailed(!src), [src]);
  if (failed) {
    const hue = [...name].reduce((h, c) => (h * 31 + c.charCodeAt(0)) % 360, 7);
    return (
      <div className={`poster-fallback ${wide ? 'wide' : ''}`} style={{ background: `linear-gradient(135deg, hsl(${hue} 45% 28%), hsl(${(hue + 50) % 360} 45% 16%))` }}>
        <span>{name.replace(/^[^\p{L}\p{N}]+/u, '').slice(0, 40)}</span>
      </div>
    );
  }
  return <img src={src} alt="" loading="lazy" decoding="async" referrerPolicy="no-referrer" onError={() => setFailed(true)} />;
}

export interface CardProps {
  to: string;
  state?: unknown;
  title: string;
  subtitle?: string;
  poster?: string;
  progress?: number; // 0..1
  badge?: ReactNode;
  wide?: boolean;
}

export function Card({ to, state, title, subtitle, poster, progress, badge, wide }: CardProps) {
  return (
    <Link to={to} state={state} className={`card ${wide ? 'card-wide' : ''}`}>
      <div className="card-art">
        <Poster src={poster} name={title} wide={wide} />
        {badge && <div className="card-badge">{badge}</div>}
        {progress !== undefined && progress > 0 && (
          <div className="card-progress">
            <div style={{ width: `${Math.min(100, progress * 100)}%` }} />
          </div>
        )}
      </div>
      <div className="card-title" title={title}>
        {title}
      </div>
      {subtitle && <div className="card-sub">{subtitle}</div>}
    </Link>
  );
}

/** Grid that renders in pages as you scroll — large libraries stay fast on projector/phones. */
export function Grid<T>({ items, render, page = 60, wide }: { items: T[]; render: (t: T) => ReactNode; page?: number; wide?: boolean }) {
  const [count, setCount] = useState(page);
  const sentinel = useRef<HTMLDivElement>(null);
  useEffect(() => setCount(page), [items, page]);
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (ents) => {
        if (ents.some((e) => e.isIntersecting)) setCount((c) => c + page);
      },
      { rootMargin: '800px' },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [items, page]);
  return (
    <>
      <div className={`grid ${wide ? 'grid-wide' : ''}`}>{items.slice(0, count).map(render)}</div>
      {count < items.length && <div ref={sentinel} className="grid-sentinel" />}
    </>
  );
}

export function Row({ title, to, children }: { title: string; to?: string; children: ReactNode }) {
  return (
    <section className="row">
      <div className="row-head">
        <h2>{title}</h2>
        {to && (
          <Link to={to} className="link-more">
            See all
          </Link>
        )}
      </div>
      <div className="row-scroll">{children}</div>
    </section>
  );
}

export function Spinner({ label }: { label?: string }) {
  return (
    <div className="spinner-wrap" role="status">
      <div className="spinner" />
      {label && <span>{label}</span>}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="empty">
      <h3>{title}</h3>
      {children && <div className="muted">{children}</div>}
    </div>
  );
}

export function ErrorBox({ error, onRetry }: { error: Error | string; onRetry?: () => void }) {
  return (
    <div className="error-box">
      <p>{typeof error === 'string' ? error : error.message}</p>
      {onRetry && (
        <button className="btn" onClick={onRetry}>
          <Icon name="refresh" /> Try again
        </button>
      )}
    </div>
  );
}

export function Chips<T extends string>({ value, options, onChange }: { value: T; options: { value: T; label: string; count?: number }[]; onChange: (v: T) => void }) {
  return (
    <div className="chips" role="tablist">
      {options.map((o) => (
        <button key={o.value} role="tab" aria-selected={o.value === value} className={`chip ${o.value === value ? 'on' : ''}`} onClick={() => onChange(o.value)}>
          {o.label}
          {o.count !== undefined && <span className="chip-count">{o.count}</span>}
        </button>
      ))}
    </div>
  );
}

// ---------- Toasts ----------
type ToastFn = (msg: string) => void;
let pushToast: ToastFn = () => {};
export const toast: ToastFn = (m) => pushToast(m);

export function Toaster() {
  const [items, setItems] = useState<{ id: number; msg: string }[]>([]);
  useEffect(() => {
    pushToast = (msg) => {
      const id = Date.now() + Math.random();
      setItems((l) => [...l.slice(-2), { id, msg }]);
      setTimeout(() => setItems((l) => l.filter((t) => t.id !== id)), 3200);
    };
  }, []);
  return (
    <div className="toasts" aria-live="polite">
      {items.map((t) => (
        <div key={t.id} className="toast">
          {t.msg}
        </div>
      ))}
    </div>
  );
}

export function SavedAt({ at, stale }: { at: number | null; stale?: boolean }) {
  if (!at) return null;
  const mins = Math.round((Date.now() - at) / 60000);
  const ago = mins < 1 ? 'just now' : mins < 60 ? `${mins} min ago` : mins < 48 * 60 ? `${Math.round(mins / 60)} h ago` : `${Math.round(mins / 1440)} days ago`;
  return <span className={`saved-at ${stale ? 'stale' : ''}`}>{stale ? `Saved copy from ${ago}` : `Updated ${ago}`}</span>;
}
