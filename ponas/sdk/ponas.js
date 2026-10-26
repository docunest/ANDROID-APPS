/*
 * ponas.js — shared kit for every Ponas game. Do not copy it into a game folder:
 * tools/build-site.mjs puts it next to each game's index.html. Include it with
 *
 *   <script src="ponas.js"></script>
 *
 * It adds, on every game:
 *   - an orange PAUSE button (top-left) and a CLOSE button (top-right), always visible;
 *   - a start overlay with a big ▶ (this first tap also unlocks sound);
 *   - a pause overlay with a big ▶ to resume (also shown when the app goes to background).
 *
 * API (all optional):
 *   Ponas.id                      game id, from the URL /games/<id>/
 *   Ponas.inApp                   true inside the tablet app, false in a normal browser
 *   Ponas.onStart(fn)             called once, after the first ▶ tap
 *   Ponas.onPause(fn) / Ponas.onResume(fn)
 *   Ponas.paused                  true while paused
 *   Ponas.save(key, value) / Ponas.load(key, fallback) / Ponas.clear()   localStorage, prefixed "<id>:"
 *   Ponas.audio()                 shared AudioContext (null before the first tap)
 *   Ponas.tone(freq, {delay, dur, type, vol})   quick beep
 *   Ponas.onResize(fn)            fn(width, height, orientation) now and on every resize/rotation
 *   Ponas.orientation()           'landscape' | 'portrait'
 *   Ponas.close()                 back to the home screen (app) or the game list (browser)
 *   Ponas.net.channel(room?)      multiplayer via ntfy.sh (needs "network": ["ntfy.sh"] in meta.json)
 *        -> { send(data), on(fn(data, msg)), onStatus(fn(ok)), close(), topic, me }
 */
(function () {
  'use strict';
  if (window.Ponas) return;

  var m = location.pathname.match(/\/games\/([a-z0-9-]+)\//);
  var id = m ? m[1] : 'game';
  var inApp = typeof window.PonasApp !== 'undefined';
  var NTFY = 'https://ntfy.sh/';
  var TOPIC_PREFIX = 'bimmer-car-93204-lit-';

  var P = {
    id: id,
    inApp: inApp,
    paused: false,
    started: false,
    version: '1'
  };
  var startFns = [], pauseFns = [], resumeFns = [], resizeFns = [];
  function call(list, args) {
    for (var i = 0; i < list.length; i++) {
      try { list[i].apply(null, args || []); } catch (e) { console.error(e); }
    }
  }

  // ---------- storage ----------
  P.save = function (key, value) {
    try { localStorage.setItem(id + ':' + key, JSON.stringify(value)); } catch (e) { /* full or blocked */ }
  };
  P.load = function (key, fallback) {
    try {
      var v = localStorage.getItem(id + ':' + key);
      return v === null ? fallback : JSON.parse(v);
    } catch (e) { return fallback; }
  };
  P.clear = function () {
    try {
      for (var i = localStorage.length - 1; i >= 0; i--) {
        var k = localStorage.key(i);
        if (k && k.indexOf(id + ':') === 0) localStorage.removeItem(k);
      }
    } catch (e) { /* ignore */ }
  };

  // ---------- audio ----------
  var ac = null;
  function unlockAudio() {
    if (!ac) {
      var C = window.AudioContext || window.webkitAudioContext;
      if (C) ac = new C();
    }
    if (ac && ac.state === 'suspended' && !P.paused) ac.resume();
  }
  P.audio = function () { return ac; };
  P.tone = function (freq, o) {
    o = o || {};
    if (!ac || P.paused) return;
    var t = ac.currentTime + (o.delay || 0), dur = o.dur || 0.2;
    var osc = ac.createOscillator(), g = ac.createGain();
    osc.type = o.type || 'sine';
    osc.frequency.setValueAtTime(freq, t);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(o.vol || 0.25, t + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    osc.connect(g); g.connect(ac.destination);
    osc.start(t); osc.stop(t + dur + 0.05);
  };

  // ---------- layout ----------
  P.orientation = function () { return window.innerWidth >= window.innerHeight ? 'landscape' : 'portrait'; };
  P.onResize = function (fn) {
    resizeFns.push(fn);
    fn(window.innerWidth, window.innerHeight, P.orientation());
  };
  var resizeTimer = 0;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      call(resizeFns, [window.innerWidth, window.innerHeight, P.orientation()]);
    }, 60);
  });

  // ---------- lifecycle ----------
  P.onStart = function (fn) { if (P.started) fn(); else startFns.push(fn); };
  P.onPause = function (fn) { pauseFns.push(fn); };
  P.onResume = function (fn) { resumeFns.push(fn); };

  P.pause = function () {
    if (!P.started || P.paused) return;
    P.paused = true;
    document.documentElement.classList.add('ponas-paused');
    if (ac && ac.state === 'running') ac.suspend();
    show(pauseOverlay);
    call(pauseFns);
  };
  P.resume = function () {
    if (!P.paused) return;
    P.paused = false;
    document.documentElement.classList.remove('ponas-paused');
    hide(pauseOverlay);
    unlockAudio();
    call(resumeFns);
  };
  P.close = function () {
    if (inApp) {
      try { window.PonasApp.close(); return; } catch (e) { /* fall through */ }
    }
    location.href = '../../index.html';
  };

  function start() {
    if (P.started) return;
    P.started = true;
    unlockAudio();
    hide(startOverlay);
    var fns = startFns; startFns = [];
    call(fns);
  }

  document.addEventListener('visibilitychange', function () {
    if (document.hidden) P.pause();
  });
  document.addEventListener('contextmenu', function (e) { e.preventDefault(); });
  // Any tap unlocks audio (some games play sounds before ▶ in the browser).
  document.addEventListener('pointerdown', unlockAudio, true);

  // ---------- UI: buttons + overlays (icons only) ----------
  var css =
    'html,body{margin:0;height:100%;overflow:hidden;touch-action:none;-webkit-user-select:none;user-select:none;' +
    '-webkit-tap-highlight-color:transparent;-webkit-touch-callout:none;overscroll-behavior:none}' +
    'html.ponas-paused *{animation-play-state:paused!important}' +
    '.ponas-btn{position:fixed;top:8px;z-index:2147483600;width:56px;height:56px;border-radius:50%;border:0;padding:0;' +
    'display:flex;align-items:center;justify-content:center;box-shadow:0 3px 0 rgba(0,0,0,.25);touch-action:none}' +
    '.ponas-btn svg{width:30px;height:30px;display:block;pointer-events:none}' +
    '#ponas-pause{left:8px;background:#FB8C00}' +
    '#ponas-close{right:8px;background:#ECEFF1}' +
    '.ponas-ov{position:fixed;inset:0;z-index:2147483640;background:rgba(0,0,0,.45);display:flex;' +
    'align-items:center;justify-content:center;touch-action:none}' +
    '.ponas-ov.ponas-hidden{display:none}' +
    '.ponas-play{width:min(44vmin,180px);height:min(44vmin,180px);border-radius:50%;background:#43A047;' +
    'box-shadow:0 8px 0 #2E7D32;display:flex;align-items:center;justify-content:center;' +
    'animation:ponas-pulse 1.6s ease-in-out infinite}' +
    '.ponas-play svg{width:52%;height:52%;margin-left:8%}' +
    '#ponas-start .ponas-play{background:#43A047}' +
    '.ponas-ov .ponas-mini{position:fixed;top:8px;right:8px}' +
    '@keyframes ponas-pulse{0%,100%{transform:scale(1)}50%{transform:scale(1.07)}}' +
    'html.ponas-paused .ponas-play{animation-play-state:running!important}';

  var ICON_PAUSE = '<svg viewBox="0 0 24 24"><rect x="5" y="4" width="5" height="16" rx="1.5" fill="#fff"/><rect x="14" y="4" width="5" height="16" rx="1.5" fill="#fff"/></svg>';
  var ICON_CLOSE = '<svg viewBox="0 0 24 24"><path d="M6 6L18 18M18 6L6 18" stroke="#455A64" stroke-width="3.4" stroke-linecap="round"/></svg>';
  var ICON_PLAY = '<svg viewBox="0 0 24 24"><path d="M6 3.5v17a1 1 0 0 0 1.5.86l14-8.5a1 1 0 0 0 0-1.72l-14-8.5A1 1 0 0 0 6 3.5z" fill="#fff"/></svg>';

  var startOverlay, pauseOverlay;
  function el(tag, attrs, html) {
    var e = document.createElement(tag);
    for (var k in attrs) e.setAttribute(k, attrs[k]);
    if (html) e.innerHTML = html;
    return e;
  }
  function show(e) { if (e) e.classList.remove('ponas-hidden'); }
  function hide(e) { if (e) e.classList.add('ponas-hidden'); }
  function tap(e, fn) {
    e.addEventListener('pointerdown', function (ev) { ev.preventDefault(); ev.stopPropagation(); fn(); });
  }

  function buildUi() {
    var style = el('style', {});
    style.textContent = css;
    document.head.appendChild(style);

    var pauseBtn = el('button', { id: 'ponas-pause', 'class': 'ponas-btn', 'aria-label': 'pause' }, ICON_PAUSE);
    var closeBtn = el('button', { id: 'ponas-close', 'class': 'ponas-btn', 'aria-label': 'close' }, ICON_CLOSE);
    tap(pauseBtn, function () { if (P.started) P.pause(); });
    tap(closeBtn, P.close);

    startOverlay = el('div', { id: 'ponas-start', 'class': 'ponas-ov' }, '<div class="ponas-play">' + ICON_PLAY + '</div>');
    pauseOverlay = el('div', { id: 'ponas-paused', 'class': 'ponas-ov ponas-hidden' }, '<div class="ponas-play">' + ICON_PLAY + '</div>');
    tap(startOverlay, start);
    tap(pauseOverlay, P.resume);

    document.body.appendChild(startOverlay);
    document.body.appendChild(pauseOverlay);
    // Buttons last so they stay on top of the game; close stays usable on overlays.
    document.body.appendChild(pauseBtn);
    document.body.appendChild(closeBtn);
    closeBtn.style.zIndex = '2147483647';
  }
  if (document.body) buildUi();
  else document.addEventListener('DOMContentLoaded', buildUi);

  // ---------- multiplayer (ntfy.sh) ----------
  // Topics are public: anyone who knows the name can read and write. Never send personal data.
  // Free server limits: ~60 messages burst, then ~1 per 5 s per device, max 4 KB per message.
  P.net = {
    topic: function (room) {
      var t = TOPIC_PREFIX + id + (room && room !== 'main' ? '-' + room : '');
      return t.replace(/[^-_A-Za-z0-9]/g, '').slice(0, 64);
    },
    channel: function (room) {
      var topic = P.net.topic(room);
      var me = Math.random().toString(36).slice(2, 10);
      var listeners = [], statusFns = [], es = null, closed = false, ok = false;
      function setOk(v) { if (v !== ok) { ok = v; call(statusFns, [ok]); } }
      function connect() {
        if (closed || !window.EventSource) return;
        es = new EventSource(NTFY + topic + '/sse');
        es.onopen = function () { setOk(true); };
        es.onerror = function () { setOk(false); }; // EventSource retries by itself
        es.onmessage = function (ev) {
          try {
            var msg = JSON.parse(ev.data);
            if (msg.event !== 'message') return;
            var body = JSON.parse(msg.message);
            if (!body || body.app !== 'ponas' || body.from === me) return;
            call(listeners, [body.data, body]);
          } catch (e) { /* not ours */ }
        };
      }
      connect();
      return {
        topic: topic,
        me: me,
        send: function (data) {
          var body = JSON.stringify({ app: 'ponas', game: id, from: me, t: Date.now(), data: data });
          if (body.length > 3800) { console.warn('Ponas.net: message too big'); return Promise.resolve(false); }
          return fetch(NTFY + topic, { method: 'POST', body: body })
            .then(function (r) { return r.ok; })
            .catch(function () { return false; });
        },
        on: function (fn) { listeners.push(fn); },
        onStatus: function (fn) { statusFns.push(fn); fn(ok); },
        close: function () { closed = true; if (es) es.close(); setOk(false); }
      };
    }
  };

  window.Ponas = P;
})();
