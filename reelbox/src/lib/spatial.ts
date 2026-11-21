// D-pad / arrow-key spatial navigation for TV remotes (Samsung projector), keyboards and
// Android TV-style remotes. Moves focus to the nearest focusable element in the pressed
// direction. Components that want arrow keys themselves call e.preventDefault() first
// (capture phase) — we skip events that are already handled.

const SELECTOR = 'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])';

type Dir = 'left' | 'right' | 'up' | 'down';
const DIRS: Record<number, Dir> = { 37: 'left', 38: 'up', 39: 'right', 40: 'down' };

function visible(el: HTMLElement): boolean {
  if (el.closest('[inert],[aria-hidden="true"]')) return false;
  const r = el.getBoundingClientRect();
  if (r.width === 0 && r.height === 0) return false;
  const st = getComputedStyle(el);
  return st.visibility !== 'hidden' && st.display !== 'none';
}

function scope(): ParentNode {
  // The topmost open scope (dialogs, player controls) traps focus.
  const scopes = document.querySelectorAll<HTMLElement>('[data-nav-scope]');
  for (let i = scopes.length - 1; i >= 0; i--) if (visible(scopes[i])) return scopes[i];
  return document;
}

function candidates(): HTMLElement[] {
  return [...scope().querySelectorAll<HTMLElement>(SELECTOR)].filter((el) => !el.closest('[data-nav-skip]') && visible(el));
}

function best(from: DOMRect, dir: Dir, els: HTMLElement[], current: Element | null): HTMLElement | null {
  const cx = from.left + from.width / 2;
  const cy = from.top + from.height / 2;
  let winner: HTMLElement | null = null;
  let score = Infinity;
  for (const el of els) {
    if (el === current) continue;
    const r = el.getBoundingClientRect();
    const ex = r.left + r.width / 2;
    const ey = r.top + r.height / 2;
    let main: number;
    let cross: number;
    let overlap: boolean;
    switch (dir) {
      case 'right':
        main = r.left - from.right;
        cross = Math.abs(ey - cy);
        overlap = r.top < from.bottom && r.bottom > from.top;
        if (ex <= cx + 1) continue;
        break;
      case 'left':
        main = from.left - r.right;
        cross = Math.abs(ey - cy);
        overlap = r.top < from.bottom && r.bottom > from.top;
        if (ex >= cx - 1) continue;
        break;
      case 'down':
        main = r.top - from.bottom;
        cross = Math.abs(ex - cx);
        overlap = r.left < from.right && r.right > from.left;
        if (ey <= cy + 1) continue;
        break;
      case 'up':
        main = from.top - r.bottom;
        cross = Math.abs(ex - cx);
        overlap = r.left < from.right && r.right > from.left;
        if (ey >= cy - 1) continue;
        break;
    }
    const s = Math.max(main, -from.width) + cross * (overlap ? 0.5 : 2.5) + (overlap ? 0 : 40);
    if (s < score) {
      score = s;
      winner = el;
    }
  }
  return winner;
}

export function focusFirst(root: ParentNode = document) {
  const el =
    root.querySelector<HTMLElement>('[data-autofocus]') ??
    [...root.querySelectorAll<HTMLElement>(SELECTOR)].find((e) => visible(e) && !e.closest('[data-nav-skip]'));
  el?.focus({ preventScroll: false });
}

function isTextInput(el: Element | null): el is HTMLInputElement | HTMLTextAreaElement {
  if (!el) return false;
  if (el instanceof HTMLTextAreaElement) return true;
  return el instanceof HTMLInputElement && !['button', 'checkbox', 'radio', 'range', 'submit'].includes(el.type);
}

export function installSpatialNav() {
  const onKey = (e: KeyboardEvent) => {
    if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
    const dir = DIRS[e.keyCode];
    if (!dir) return;
    const active = document.activeElement as HTMLElement | null;
    if (isTextInput(active) && (dir === 'left' || dir === 'right')) {
      // let the caret move unless it's at the edge
      const pos = active.selectionStart ?? 0;
      const len = active.value.length;
      if ((dir === 'left' && pos > 0) || (dir === 'right' && pos < len)) return;
    }
    if (active instanceof HTMLSelectElement && (dir === 'up' || dir === 'down')) return;
    const els = candidates();
    if (!els.length) return;
    const sc = scope();
    if (!active || active === document.body || !els.includes(active) || (sc !== document && !(sc as HTMLElement).contains(active))) {
      e.preventDefault();
      focusFirst(sc);
      return;
    }
    const target = best(active.getBoundingClientRect(), dir, els, active);
    if (target) {
      e.preventDefault();
      target.focus({ preventScroll: true });
      target.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
    } else if (dir === 'up' || dir === 'down') {
      // nothing further — let the page scroll so long text stays readable
    }
  };
  window.addEventListener('keydown', onKey);
  return () => window.removeEventListener('keydown', onKey);
}
