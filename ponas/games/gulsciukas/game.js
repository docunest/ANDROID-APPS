/*
 * Gulsčiukas — a spirit level for the parent (adult tool, Lithuanian labels).
 *
 * Sensors: 'deviceorientation' (gyro-fused beta/gamma/alpha) + 'deviceorientationabsolute'
 * (compass alpha, Android Chrome/WebView) or webkitCompassHeading (iOS). iOS needs a permission,
 * which must come from a real click, so there is a "Leisti jutiklius" button.
 *
 * Axes (W3C device frame, device lying flat, screen up):
 *   X = beta  (rotation around device x): + when the TOP edge of the device goes up
 *   Y = gamma (rotation around device y): + when the RIGHT edge goes down (left edge up)
 *   Z = alpha (rotation around the screen normal)
 * Plate 6 × 2.2 m: by default its 6 m side lies along the device's long (y) axis, so X tilts
 * the 6 m side and Y the 2.2 m side; ⇄ swaps that.
 *
 * Remote: modes local / master / follower over Ponas.net (ntfy.sh). Free ntfy.sh: ~1 msg per 5 s
 * per device and 250 msgs per day per IP, so the master sends only when the angle changed,
 * at most once every 5 s, and followers ask for the current value when they join.
 */
(function () {
  'use strict';
  var P = window.Ponas;
  var $ = function (id) { return document.getElementById(id); };

  var L_LONG = 6.0, L_SHORT = 2.2;          // plate, metres
  var SEND_MS = 5000;                        // ntfy.sh free: 1 request / 5 s sustained
  var DAY_LIMIT = 250;                       // ntfy.sh free: messages / day / IP
  var MIN_CHANGE = 0.02;                     // degrees; below this the master doesn't resend
  var TAU = 0.25;                            // smoothing time constant, seconds
  var MODES = ['local', 'master', 'follow'];
  var MODE_LT = { local: 'vietinis', master: 'master', follow: 'sekėjas' };

  // ---------- state ----------
  var mode = P.load('mode', 'local'); if (MODES.indexOf(mode) < 0) mode = 'local';
  var room = String(P.load('room', '1'));
  var zero = P.load('zero', null);           // {b, g, a} or null = absolute
  var swap = !!P.load('swap', false);        // true: 6 m side along the device's short (x) axis

  var own = null;                            // smoothed local sensor {b, g, a, za, src}
  var lastEvT = 0, hz = 0, evCount = 0, hzT0 = 0;
  var remote = null, remoteT = 0;            // last data from master
  var chan = null, online = false;
  var sentLog = P.load('sent', { d: '', n: 0 });
  var lastSent = null, nextSendAt = 0, wantSend = false, lastAskT = 0;
  var timers = [], raf = 0, dirty = true, lastRender = 0;
  var wakeLock = null;

  // ---------- helpers ----------
  function wrap180(d) { d = ((d + 180) % 360 + 360) % 360 - 180; return d === -180 ? 180 : d; }
  function norm360(d) { return ((d % 360) + 360) % 360; }
  function r2(v) { return Math.round(v * 100) / 100; }
  function today() { var d = new Date(); return d.getFullYear() + '-' + (d.getMonth() + 1) + '-' + d.getDate(); }
  function sentToday() { if (sentLog.d !== today()) sentLog = { d: today(), n: 0 }; return sentLog.n; }
  function fmtDeg(v, dec) { if (v == null || isNaN(v)) return '—'; var s = v.toFixed(dec == null ? 2 : dec); return (v > 0 && dec !== 1 ? '+' : '') + s + '°'; }
  function fmtMm(m) {
    var mm = m * 1000, a = Math.abs(mm);
    var s = a >= 1000 ? (a / 1000).toFixed(2) + ' m' : a >= 100 ? a.toFixed(0) + ' mm' : a.toFixed(1) + ' mm';
    return (mm > 0.05 ? '+' : mm < -0.05 ? '−' : '') + s;
  }

  // ---------- sensors ----------
  function onOrient(e) {
    if (e.beta == null || e.gamma == null) return;
    var now = performance.now();
    evCount++;
    if (!hzT0) hzT0 = now;
    if (now - hzT0 >= 1000) { hz = Math.round(evCount * 1000 / (now - hzT0)); evCount = 0; hzT0 = now; }
    var dt = lastEvT ? Math.min(0.5, (now - lastEvT) / 1000) : 1;
    lastEvT = now;
    var k = 1 - Math.exp(-dt / TAU);
    var a = e.alpha == null ? 0 : e.alpha;
    var za = null;
    if (typeof e.webkitCompassHeading === 'number' && e.webkitCompassHeading >= 0) za = e.webkitCompassHeading;
    if (!own) {
      own = { b: e.beta, g: e.gamma, a: a, za: own && own.za, src: 'gyro' };
    } else {
      own.b += k * (e.beta - own.b);
      own.g += k * (e.gamma - own.g);
      own.a = norm360(own.a + k * wrap180(a - own.a));
    }
    if (za != null) { own.za = za; own.src = 'kompasas'; }
    hidePerm();
    dirty = true;
  }
  function onAbs(e) {
    if (e.alpha == null || !own) return;
    // compass heading, clockwise from north (alpha is counter-clockwise)
    var h = norm360(360 - e.alpha);
    own.za = own.za == null ? h : norm360(own.za + 0.2 * wrap180(h - own.za));
    own.src = 'kompasas';
  }
  function listen() {
    window.addEventListener('deviceorientation', onOrient);
    if ('ondeviceorientationabsolute' in window) window.addEventListener('deviceorientationabsolute', onAbs);
  }
  var needsPerm = typeof DeviceOrientationEvent !== 'undefined' && typeof DeviceOrientationEvent.requestPermission === 'function';
  function showPerm(txt, btn) {
    $('permTxt').textContent = txt;
    $('permBtn').style.display = btn ? '' : 'none';
    $('perm').classList.add('show');
  }
  function hidePerm() { $('perm').classList.remove('show'); }
  // iOS 13+: the permission prompt only opens from a real user gesture (touchend / click —
  // pointerdown on touch doesn't count). So we ask on the very first tap (the ▶ start tap),
  // and the "Leisti jutiklius" button asks again if that didn't work.
  var permState = needsPerm ? 'ask' : 'none';   // ask | pending | granted | denied | none
  function requestPerm() {
    if (!needsPerm || permState === 'pending' || permState === 'granted') return;
    permState = 'pending';
    var asks = [DeviceOrientationEvent.requestPermission()];
    if (typeof DeviceMotionEvent !== 'undefined' && typeof DeviceMotionEvent.requestPermission === 'function') {
      asks.push(DeviceMotionEvent.requestPermission().catch(function () { return 'granted'; }));
    }
    Promise.all(asks).then(function (r) {
      if (r[0] === 'granted') { permState = 'granted'; listen(); later(checkSensor, 1500); }
      else { permState = 'denied'; showPerm('Leidimas nesuteiktas. Uždarykite ir vėl atidarykite puslapį, arba Nustatymai → Safari → „Motion & Orientation Access“.', true); }
    }).catch(function () { permState = 'ask'; showPerm('Reikia leidimo naudoti judesio jutiklius', true); });
  }
  function onGesture() { if (permState === 'ask') requestPerm(); }
  document.addEventListener('touchend', onGesture, true);
  document.addEventListener('click', onGesture, true);
  $('permBtn').addEventListener('click', function () { permState = permState === 'pending' ? 'pending' : 'ask'; requestPerm(); });

  // Android Chrome has no prompt: motion sensors are allowed by default, but can be blocked per site.
  function androidBlocked(cb) {
    try {
      if (!navigator.permissions || !navigator.permissions.query) return cb(false);
      navigator.permissions.query({ name: 'accelerometer' }).then(function (s) { cb(s.state === 'denied'); }, function () { cb(false); });
    } catch (e) { cb(false); }
  }
  function checkSensor() {
    if (own || mode === 'follow') { hidePerm(); return; }
    if (needsPerm) {
      if (permState !== 'pending') showPerm(permState === 'denied'
        ? 'Leidimas nesuteiktas. Uždarykite ir vėl atidarykite puslapį, arba Nustatymai → Safari → „Motion & Orientation Access“.'
        : 'Reikia leidimo naudoti judesio jutiklius', true);
      return;
    }
    if (!window.isSecureContext) { showPerm('Jutikliai veikia tik per https://', false); return; }
    androidBlocked(function (blocked) {
      if (own) return;
      showPerm(blocked
        ? 'Judesio jutikliai užblokuoti šiai svetainei. Chrome: ⋮ → Nustatymai → Svetainės nustatymai → Judesio jutikliai → Leisti, tada perkraukite puslapį.'
        : 'Šis įrenginys nesiunčia giroskopo duomenų (kompiuteris?). Galite naudoti režimą „sekėjas“.', false);
    });
  }

  // ---------- what is shown ----------
  // view = {b, g, a, za, src, zero, swap} from this device or from the master
  function view() {
    if (mode === 'follow') return remote;
    if (!own) return null;
    return { b: own.b, g: own.g, a: own.a, za: own.za, src: own.src, zero: zero, swap: swap, dims: plateDevDims(swap) };
  }
  function rel(v) {
    var z = v.zero;
    return {
      x: z ? v.b - z.b : v.b,
      y: z ? v.g - z.g : v.g,
      z: z ? wrap180(v.a - z.a) : null
    };
  }

  // ---------- layout of the plate drawing ----------
  var box = { w: 0, h: 0, pw: 0, ph: 0, s: 1 };
  function screenAngle() {
    var a = (screen.orientation && typeof screen.orientation.angle === 'number') ? screen.orientation.angle
      : (typeof window.orientation === 'number' ? window.orientation : 0);
    return norm360(a);
  }
  // device-frame vector (x right, y toward the top edge) -> screen (x right, y DOWN)
  function toScreen(dx, dy) {
    var t = screenAngle() * Math.PI / 180, c = Math.round(Math.cos(t)), s = Math.round(Math.sin(t));
    var sx = dx * c - dy * s, sy = dx * s + dy * c;
    return [sx, -sy];
  }
  // Is the device's long side its y axis? (phones: yes; tablets that are landscape by nature: no)
  var longY = null;
  function longIsY() {
    // while the on-screen keyboard is open (room number) the window shape lies: keep the last answer
    if (longY !== null && document.activeElement && document.activeElement.tagName === 'INPUT') return longY;
    var a = screenAngle(), w = window.innerWidth, h = window.innerHeight;
    longY = (a % 180 === 0) === (h >= w);
    return longY;
  }
  // plate metres along [device x, device y]: 6 m along the device's long side unless swapped
  function plateDevDims(sw) {
    var alongY = longIsY() !== !!sw;
    return alongY ? [L_SHORT, L_LONG] : [L_LONG, L_SHORT];
  }
  function layout() {
    var el = $('left');
    box.w = el.clientWidth; box.h = el.clientHeight;
    dirty = true;
  }

  function drawPlate(v, r) {
    var d = v && v.dims ? v.dims : plateDevDims(swap);
    var sd = toScreen(d[0], d[1]);
    var mw = Math.abs(sd[0]), mh = Math.abs(sd[1]);   // metres across / down the screen
    var wide = mw >= mh;
    var padX = wide ? 14 : 70, padY = wide ? 34 : 14;  // room for corner labels on the thin side
    var s = Math.min((box.w - 2 * padX) / mw, (box.h - 2 * padY) / mh);
    var pw = mw * s, ph = mh * s, cx = box.w / 2, cy = box.h / 2;
    var pl = $('plate').style;
    pl.left = (cx - pw / 2) + 'px'; pl.top = (cy - ph / 2) + 'px'; pl.width = pw + 'px'; pl.height = ph + 'px';
    var rr = Math.max(28, Math.min(pw, ph) * 0.42);
    var ring = $('ring').style;
    ring.width = ring.height = 2 * rr + 'px'; ring.left = (cx - rr) + 'px'; ring.top = (cy - rr) + 'px';
    var br = rr * 0.28, bub = $('bubble');
    bub.style.width = bub.style.height = 2 * br + 'px';
    bub.style.left = (cx - br) + 'px'; bub.style.top = (cy - br) + 'px';

    // corners: heights relative to the lowest corner
    var corners = [[-1, 1], [1, 1], [1, -1], [-1, -1]];
    var hs = [], i;
    var tx = r ? Math.tan(r.x * Math.PI / 180) : 0, ty = r ? Math.tan(r.y * Math.PI / 180) : 0;
    for (i = 0; i < 4; i++) {
      var dx = corners[i][0] * d[0] / 2, dy = corners[i][1] * d[1] / 2;
      hs.push(dy * tx - dx * ty);           // top up with +X, left up with +Y
    }
    var mn = Math.min.apply(null, hs), mx = Math.max.apply(null, hs);
    for (i = 0; i < 4; i++) {
      var c = $('c' + i);
      var p = toScreen(corners[i][0] * d[0] / 2 * s, corners[i][1] * d[1] / 2 * s);
      var ox = wide ? 0 : (p[0] < 0 ? -38 : 38), oy = wide ? (p[1] < 0 ? -18 : 18) : 0;
      c.style.left = (cx + p[0] + ox) + 'px'; c.style.top = (cy + p[1] + oy) + 'px';
      if (!r) { c.textContent = ''; c.className = 'cl'; continue; }
      var h = hs[i] - mn;
      c.textContent = h < 0.00005 ? '0' : fmtMm(h);
      c.className = 'cl' + (mx - mn > 0.0005 ? (hs[i] === mx ? ' hi' : hs[i] === mn ? ' lo' : '') : '');
      // keep the label inside the panel
      var hw = c.offsetWidth / 2 + 2, lx0 = parseFloat(c.style.left);
      c.style.left = Math.max(hw, Math.min(box.w - hw, lx0)) + 'px';
    }

    // bubble drifts to the HIGH side: gradient of height in the device frame = (-tanY, tanX)
    var ox2 = 0, oy2 = 0, ok = false;
    if (r) {
      var gx = -r.y, gy = r.x, mag = Math.sqrt(gx * gx + gy * gy);
      ok = mag < 0.1;
      if (mag > 1e-6) {
        var f = (rr - br) * (mag / (mag + 1.5)) / mag;   // ~half way at 1.5°
        var q = toScreen(gx * f, gy * f); ox2 = q[0]; oy2 = q[1];
      }
    }
    bub.style.transform = 'translate(' + ox2.toFixed(1) + 'px,' + oy2.toFixed(1) + 'px)';
    bub.classList.toggle('ok', ok);
  }

  function render() {
    var v = view();
    var r = v ? rel(v) : null;
    drawPlate(v, r);
    var sw = v ? v.swap : swap;
    var dd = v && v.dims ? v.dims : plateDevDims(swap);
    var lx = dd[1], ly = dd[0];  // X tilts the side along device y, Y the side along device x
    if (!v) {
      ['ax', 'ay', 'az', 'rx', 'ry', 'rz', 'hx', 'hy', 'hz'].forEach(function (id) { $(id).textContent = '—'; });
    } else {
      $('ax').textContent = fmtDeg(v.b);
      $('ay').textContent = fmtDeg(v.g);
      $('az').textContent = fmtDeg(v.za != null ? v.za : v.a, 1);
      $('rx').textContent = fmtDeg(r.x);
      $('ry').textContent = fmtDeg(r.y);
      $('rz').textContent = r.z == null ? '—' : fmtDeg(r.z);
      $('hx').innerHTML = fmtMm(lx * Math.tan(r.x * Math.PI / 180)) + ' <small>/' + String(lx).replace('.', ',') + ' m ↑ viršus</small>';
      $('hy').innerHTML = fmtMm(-ly * Math.tan(r.y * Math.PI / 180)) + ' <small>/' + String(ly).replace('.', ',') + ' m → dešinė</small>';
      $('hz').innerHTML = r.z == null ? '—' : '↔ ' + fmtMm(Math.abs(lx * Math.sin(r.z * Math.PI / 180))).replace('+', '') + ' <small>/' + String(lx).replace('.', ',') + ' m</small>';
    }
    $('bSwapS').textContent = sw ? '6 m skersai' : '6 m išilgai';
    renderStatus();
  }

  function renderStatus() {
    var s = '';
    if (mode === 'follow') {
      if (!remote) s = online ? 'Laukiama master įrenginio…' : 'Jungiamasi…';
      else s = 'Master duomenys prieš ' + Math.max(0, Math.round((Date.now() - remoteT) / 1000)) + ' s';
    } else {
      s = own ? 'Jutiklis ' + hz + ' Hz' + (own.za != null ? ' · Z kompasas' : ' · Z giroskopas') : 'Nėra jutiklio duomenų';
      if (mode === 'master') {
        var left = Math.max(0, Math.ceil((nextSendAt - Date.now()) / 1000));
        s += ' · siunčia kas ' + (sendInterval() / 1000) + ' s' + (left ? ' (' + left + ')' : '') + ' · šiandien ' + sentToday() + '/' + DAY_LIMIT;
      }
    }
    $('stat').textContent = s;
    $('dot').className = mode === 'local' ? '' : 'show' + (online ? ' on' : '');
  }

  function loop(t) {
    raf = 0;
    if (P.paused) return;
    if (dirty && t - lastRender >= 45) { dirty = false; lastRender = t; render(); }  // ~20 fps max (weak tablet)
    raf = requestAnimationFrame(loop);
  }
  function startLoop() { if (!raf) raf = requestAnimationFrame(loop); }

  // ---------- remote ----------
  function sendInterval() {
    var n = sentToday();
    return n >= DAY_LIMIT - 10 ? 60000 : n >= 200 ? 15000 : SEND_MS;
  }
  function openChannel() {
    if (chan) { chan.close(); chan = null; }
    online = false; remote = null;
    if (mode === 'local') { dirty = true; return; }
    chan = P.net.channel('r' + room);
    chan.onStatus(function (ok) {
      online = ok; dirty = true;
      if (ok && mode === 'follow') ask();
      if (ok && mode === 'master') wantSend = true;
    });
    chan.on(onMsg);
  }
  function onMsg(d) {
    if (!d || typeof d !== 'object') return;
    if (mode === 'follow' && d.k === 's' && typeof d.b === 'number' && typeof d.g === 'number') {
      remote = {
        b: d.b, g: d.g, a: typeof d.a === 'number' ? d.a : 0,
        za: typeof d.za === 'number' ? d.za : null,
        zero: d.z && typeof d.z.b === 'number' ? { b: d.z.b, g: d.z.g, a: d.z.a || 0 } : null,
        swap: !!d.sw,
        dims: Array.isArray(d.d) && d.d.length === 2 && d.d.every(function (n) { return n === L_LONG || n === L_SHORT; }) ? d.d : null
      };
      remoteT = Date.now(); dirty = true;
      Ponas.tone(880, { dur: 0.05, vol: 0.05 });
    } else if (mode === 'master') {
      if (d.k === 'q') wantSend = true;
      else if (d.k === 'c' && d.c === 'zero') { setZero(); }
      else if (d.k === 'c' && d.c === 'clear') { clearZero(); }
    }
  }
  function ask() {                         // follower: "send me the current value"
    if (!chan || Date.now() - lastAskT < 20000) return;
    lastAskT = Date.now();
    chan.send({ k: 'q' });
  }
  function snap() {
    return {
      k: 's', b: r2(own.b), g: r2(own.g), a: r2(own.a), za: own.za == null ? null : r2(own.za),
      z: zero ? { b: r2(zero.b), g: r2(zero.g), a: r2(zero.a) } : null, sw: swap ? 1 : 0, d: plateDevDims(swap)
    };
  }
  function changed(a, b) {
    if (!b) return true;
    if (Math.abs(a.b - b.b) >= MIN_CHANGE || Math.abs(a.g - b.g) >= MIN_CHANGE) return true;
    if (Math.abs(wrap180(a.a - b.a)) >= 0.2) return true;
    if (JSON.stringify(a.z) !== JSON.stringify(b.z) || a.sw !== b.sw || String(a.d) !== String(b.d)) return true;
    return false;
  }
  function tick() {
    if (P.paused) return;
    var now = Date.now();
    if (mode === 'master' && chan && own && now >= nextSendAt) {
      var cur = snap();
      if (wantSend || changed(cur, lastSent)) {
        wantSend = false; lastSent = cur;
        nextSendAt = now + sendInterval();
        sentToday(); sentLog.n++; P.save('sent', sentLog);
        chan.send(cur).then(function (ok) {
          if (!ok) { nextSendAt = Date.now() + 15000; wantSend = true; }  // 429 / offline: back off
        });
      }
    }
    if (mode === 'follow' && online && (!remote || now - remoteT > 60000)) ask();
    dirty = true;   // refresh "prieš N s" / countdown
  }

  // ---------- buttons ----------
  function setZero() {
    if (!own) return;
    zero = { b: own.b, g: own.g, a: own.a };
    P.save('zero', zero); wantSend = true; dirty = true;
    Ponas.tone(660, { dur: 0.12 }); Ponas.tone(990, { delay: 0.1, dur: 0.15 });
  }
  function clearZero() {
    zero = null; P.save('zero', null); wantSend = true; dirty = true;
    Ponas.tone(520, { dur: 0.12 });
  }
  function btn(id, fn) {
    $(id).addEventListener('pointerdown', function (e) {
      e.preventDefault();
      if (P.paused) return;
      fn();
    });
  }
  btn('bZero', function () {
    if (mode === 'follow') { if (chan) chan.send({ k: 'c', c: 'zero' }); Ponas.tone(660, { dur: 0.1 }); }
    else setZero();
  });
  btn('bClear', function () {
    if (mode === 'follow') { if (chan) chan.send({ k: 'c', c: 'clear' }); Ponas.tone(520, { dur: 0.1 }); }
    else clearZero();
  });
  btn('bMode', function () {
    mode = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
    P.save('mode', mode);
    applyMode();
    Ponas.tone(740, { dur: 0.1 });
  });
  btn('bSwap', function () {
    if (mode === 'follow') return;
    swap = !swap; P.save('swap', swap); wantSend = true; dirty = true;
    Ponas.tone(600, { dur: 0.1 });
  });
  var roomIn = $('roomIn');
  roomIn.value = room;
  roomIn.addEventListener('change', function () {
    var v = String(parseInt(roomIn.value, 10) || 1).slice(0, 4);
    roomIn.value = v;
    if (v === room) return;
    room = v; P.save('room', room);
  });

  function applyMode() {
    $('modeLbl').textContent = mode === 'local' ? 'Vietinis' : (mode === 'master' ? 'Master' : 'Sekėjas') + ' · kambarys ' + room;
    $('bModeS').textContent = MODE_LT[mode];
    $('room').classList.toggle('show', mode !== 'local');
    $('bSwap').classList.toggle('off', mode === 'follow');
    $('bClear').innerHTML = 'Absoliutus<small>be nulio</small>';
    $('bZero').innerHTML = 'Nulis<small>' + (mode === 'follow' ? 'master’yje' : 'nuo čia') + '</small>';
    lastSent = null; nextSendAt = 0; lastAskT = 0;
    openChannel();
    later(checkSensor, 1500);
    dirty = true;
  }
  roomIn.addEventListener('change', applyMode);

  // ---------- keep the screen on (follower watching from afar) ----------
  function lockScreen() {
    try {
      if (navigator.wakeLock && !wakeLock) navigator.wakeLock.request('screen').then(function (l) {
        wakeLock = l; l.addEventListener('release', function () { wakeLock = null; });
      }).catch(function () {});
    } catch (e) { /* not supported */ }
  }

  // ---------- lifecycle ----------
  function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
  function startTimers() { timers.push(setInterval(tick, 250)); startLoop(); }
  function stopTimers() {
    timers.forEach(function (t) { clearTimeout(t); clearInterval(t); });
    timers = [];
    if (raf) { cancelAnimationFrame(raf); raf = 0; }
  }

  if (!needsPerm) listen();
  P.onResize(layout);
  if (screen.orientation && screen.orientation.addEventListener) screen.orientation.addEventListener('change', layout);
  applyMode();
  render();

  P.onStart(function () { startTimers(); lockScreen(); later(checkSensor, 1500); });
  P.onPause(stopTimers);
  P.onResume(function () { startTimers(); lockScreen(); later(checkSensor, 1500); });
})();
