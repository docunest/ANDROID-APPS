import { useState } from 'react';
import type { Diagnosis } from '../lib/diagnose';
import { toast } from './ui';

/** Make https links in a fix clickable. */
const linkify = (text: string) =>
  text.split(/(https:\/\/\S+)/).map((part, i) =>
    part.startsWith('https://') ? (
      <a key={i} href={part} target="_blank" rel="noreferrer">
        {part}
      </a>
    ) : (
      part
    ),
  );

export function ConnectionDebug({ d }: { d: Diagnosis }) {
  const [open, setOpen] = useState(d.verdict !== 'ok');
  const copy = () =>
    navigator.clipboard?.writeText(d.report).then(
      () => toast('Details copied (password hidden)'),
      () => toast('Copy failed — long-press the text to copy'),
    );
  return (
    <section className={`debug debug-${d.verdict}`} aria-label="Connection check">
      <p className="debug-summary">
        <b>{d.verdict === 'ok' ? 'Connection check passed' : 'What’s wrong'}:</b> {d.summary}
      </p>
      {d.fixes.length > 0 && (
        <ul className="debug-fixes">
          {d.fixes.map((f) => (
            <li key={f}>{linkify(f)}</li>
          ))}
        </ul>
      )}
      <div className="debug-actions">
        <button type="button" className="btn btn-small" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
          {open ? 'Hide details' : 'Show details'}
        </button>
        <button type="button" className="btn btn-small" onClick={copy}>
          Copy details
        </button>
      </div>
      {open && (
        <ol className="debug-checks">
          {d.checks.map((c, i) => (
            <li key={i} className={c.ok === null ? 'info' : c.ok ? 'pass' : 'fail'}>
              <span className="debug-mark">{c.ok === null ? '•' : c.ok ? '✓' : '✗'}</span>
              <span>
                <b>{c.label}</b>
                <br />
                <code>{c.detail}</code>
              </span>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
