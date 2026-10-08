// laikas.js — 10 min play, then a 2 min break (same file in games/minos and games/ateiviai).
// Play time counts only while the game is started, not paused and visible. It is saved, so
// closing and reopening the game doesn't skip the break. If the child stopped playing for at
// least 2 minutes on their own, the next session starts fresh.
// Testing: add ?laikas=20,10 to the URL for 20 s play / 10 s break.
(function () {
  'use strict';
  var P = window.Ponas;
  var PLAY_MS = 10 * 60 * 1000, BREAK_MS = 2 * 60 * 1000, WARN_MS = 60 * 1000;
  var q = location.search.match(/[?&]laikas=(\d+),(\d+)/);
  if (q) { PLAY_MS = +q[1] * 1000; BREAK_MS = +q[2] * 1000; WARN_MS = Math.min(WARN_MS, PLAY_MS / 3); }

  var st = P.load('laikas', null) || {};
  var used = +st.used || 0, breakUntil = +st.breakUntil || 0, last = +st.last || 0;
  var now = Date.now();
  if (breakUntil && breakUntil <= now) { used = 0; breakUntil = 0; }
  else if (!breakUntil && last && now - last >= BREAK_MS) used = 0;
  if (breakUntil > now + BREAK_MS) breakUntil = now + BREAK_MS; // clock moved back

  var L = window.Laikas = { blocked: false };
  var blockFns = [], unblockFns = [];
  L.onBlock = function (fn) { blockFns.push(fn); };
  L.onUnblock = function (fn) { unblockFns.push(fn); };
  function call(list) { for (var i = 0; i < list.length; i++) { try { list[i](); } catch (e) { console.error(e); } } }

  var css =
    '#lk-bar{position:fixed;top:50px;left:50%;width:120px;height:9px;margin-left:-60px;border-radius:5px;' +
    'background:#00000026;overflow:hidden;z-index:50;pointer-events:none}' +
    '#lk-bar i{display:block;width:100%;height:100%;border-radius:5px;background:#66BB6A;' +
    'transform-origin:left center;transition:transform 1s linear,background-color .5s}' +
    '#lk-bar.warn i{background:#FFA726}' +
    '#lk-break{position:fixed;inset:0;z-index:2147483000;display:flex;align-items:center;justify-content:center;' +
    'flex-direction:column;background:linear-gradient(#1A237E,#3949AB);touch-action:none}' +
    '#lk-break.lk-hidden{display:none}' +
    '#lk-ring{position:relative;width:min(56vmin,240px);height:min(56vmin,240px)}' +
    '#lk-ring svg{position:absolute;inset:0;width:100%;height:100%}' +
    '#lk-ring #lk-moon{inset:20%;width:60%;height:60%}' +
    '#lk-go{position:absolute;inset:12%;border-radius:50%;background:#43A047;box-shadow:0 8px 0 #2E7D32;' +
    'display:none;align-items:center;justify-content:center;animation:lk-pulse 1.6s ease-in-out infinite}' +
    '#lk-go svg{position:static;width:50%;height:50%;margin-left:8%}' +
    '#lk-break.ready #lk-go{display:flex}#lk-break.ready #lk-moon,#lk-break.ready .lk-z{display:none}' +
    '.lk-z{position:absolute;color:#C5CAE9;font:bold 28px sans-serif;opacity:0;animation:lk-z 3s ease-out infinite}' +
    '.lk-z:nth-of-type(2){animation-delay:1s;font-size:22px}.lk-z:nth-of-type(3){animation-delay:2s;font-size:17px}' +
    '#lk-label{margin-top:14px;color:#E8EAF6;font:bold 22px sans-serif;letter-spacing:1px}' +
    '@keyframes lk-z{0%{opacity:0;transform:translate(0,0)}30%{opacity:1}100%{opacity:0;transform:translate(30px,-50px)}}' +
    '@keyframes lk-pulse{0%,100%{transform:scale(1)}50%{transform:scale(1.07)}}';

  var R = 46, CIRC = 2 * Math.PI * R;
  var html =
    '<div id="lk-ring">' +
    '<svg viewBox="0 0 100 100"><circle cx="50" cy="50" r="' + R + '" fill="none" stroke="#ffffff22" stroke-width="7"/>' +
    '<circle id="lk-arc" cx="50" cy="50" r="' + R + '" fill="none" stroke="#FFD54F" stroke-width="7" stroke-linecap="round" ' +
    'transform="rotate(-90 50 50)" stroke-dasharray="' + CIRC + '" stroke-dashoffset="' + CIRC + '"/></svg>' +
    '<svg id="lk-moon" viewBox="0 0 100 100"><path d="M62 8a44 44 0 1 0 30 62A36 36 0 0 1 62 8z" fill="#FFF59D"/>' +
    '<path d="M38 52q6 5 12 0M58 60q-8 8-18 2" stroke="#8D6E63" stroke-width="3.5" fill="none" stroke-linecap="round"/></svg>' +
    '<span class="lk-z" style="right:8%;top:14%">z</span><span class="lk-z" style="right:2%;top:4%">z</span><span class="lk-z" style="right:-4%;top:-6%">z</span>' +
    '<div id="lk-go"><svg viewBox="0 0 24 24"><path d="M6 3.5v17a1 1 0 0 0 1.5.86l14-8.5a1 1 0 0 0 0-1.72l-14-8.5A1 1 0 0 0 6 3.5z" fill="#fff"/></svg></div>' +
    '</div><div id="lk-label">Pertrauka</div>';

  var bar, fill, ov, arc, ready = false, warned = false;
  function build() {
    var s = document.createElement('style'); s.textContent = css; document.head.appendChild(s);
    bar = document.createElement('div'); bar.id = 'lk-bar'; bar.innerHTML = '<i></i>';
    fill = bar.firstChild;
    ov = document.createElement('div'); ov.id = 'lk-break'; ov.className = 'lk-hidden'; ov.innerHTML = html;
    arc = ov.querySelector('#lk-arc');
    document.body.appendChild(bar); document.body.appendChild(ov);
    ov.addEventListener('pointerdown', function (e) {
      e.preventDefault(); e.stopPropagation();
      if (ready && !P.paused) endBreak();
    });
    if (breakUntil) { L.blocked = true; showBreak(); } else drawBar();
  }

  function save() { P.save('laikas', { used: used, breakUntil: breakUntil, last: last }); }
  function drawBar() {
    var left = Math.max(0, 1 - used / PLAY_MS);
    fill.style.transform = 'scaleX(' + left.toFixed(3) + ')';
    bar.classList.toggle('warn', PLAY_MS - used <= WARN_MS);
  }
  function showBreak() {
    ready = false; ov.classList.remove('ready'); ov.classList.remove('lk-hidden');
    bar.style.display = 'none'; drawRing(Date.now());
  }
  function drawRing(t) {
    var frac = Math.min(1, Math.max(0, 1 - (breakUntil - t) / BREAK_MS));
    arc.setAttribute('stroke-dashoffset', (CIRC * (1 - frac)).toFixed(1));
    if (frac >= 1 && !ready) {
      ready = true; ov.classList.add('ready');
      [523, 659, 784].forEach(function (f, i) { P.tone(f, { delay: i * 0.15, dur: 0.3, type: 'triangle', vol: 0.18 }); });
    }
  }
  function startBreak() {
    breakUntil = Date.now() + BREAK_MS; L.blocked = true; save();
    [784, 659, 523, 392].forEach(function (f, i) { P.tone(f, { delay: i * 0.2, dur: 0.4, type: 'sine', vol: 0.2 }); });
    showBreak(); call(blockFns);
  }
  function endBreak() {
    used = 0; breakUntil = 0; warned = false; L.blocked = false; last = Date.now(); save();
    ov.classList.add('lk-hidden'); bar.style.display = ''; drawBar();
    P.tone(784, { dur: 0.15 }); call(unblockFns);
  }

  var lastTick = Date.now(), lastSave = 0;
  setInterval(function () {
    var t = Date.now();
    if (L.blocked) { lastTick = t; drawRing(t); return; }
    if (P.started && !P.paused && !document.hidden) {
      used += Math.min(Math.max(0, t - lastTick), 2000); last = t;
      if (used >= PLAY_MS) { startBreak(); lastTick = t; return; }
      if (!warned && PLAY_MS - used <= WARN_MS) {
        warned = true; P.tone(880, { dur: 0.2, type: 'triangle', vol: 0.15 }); P.tone(660, { delay: 0.2, dur: 0.3, type: 'triangle', vol: 0.15 });
      }
      drawBar();
      if (t - lastSave > 5000) { lastSave = t; save(); }
    }
    lastTick = t;
  }, 1000);
  P.onPause(save);
  document.addEventListener('visibilitychange', function () { if (document.hidden) save(); });

  if (document.body) build(); else document.addEventListener('DOMContentLoaded', build);
})();
