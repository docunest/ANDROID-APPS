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
 * Caravan model (top view, metres): box 4.9 × 2.2, hitch 7.2 m from the rear (on the centre line),
 * axle 2.3 m from the rear. "Right" = right side when standing behind it, facing the hitch.
 * The phone lies flat in the caravan; ⟳ tells which way its top edge points (hitch/right/rear/left).
 * Heights of 7 points (4 corner legs, 2 wheels, hitch) come from the plane h = e·SE + n·SN;
 * "kelti" = how much to raise each point so all are level with the highest one (rigid body).
 *
 * Remote: modes local / master / follower over Ponas.net (ntfy.sh). Free ntfy.sh: ~1 msg per 5 s
 * per device and 250 msgs per day per IP, so the master sends only when the angle changed,
 * at most once every 5 s, and followers ask for the current value when they join.
 */
(function () {
  'use strict';
  var P = window.Ponas;
  var $ = function (id) { return document.getElementById(id); };

  var BOX_L = 4.9, BOX_W = 2.2, HITCH_N = 7.2, AXLE_N = 2.3;   // caravan, metres (n from rear)
  var ORIENT_LT = ['viršus → kablys', 'viršus → dešinė', 'viršus → galas', 'viršus → kairė'];
  var POINTS = [                             // [e (right +), n (forward from rear), name]
    [0, HITCH_N, 'kablys'],
    [-BOX_W / 2, BOX_L, 'priekis kairė'], [BOX_W / 2, BOX_L, 'priekis dešinė'],
    [-BOX_W / 2, AXLE_N, 'ratas kairė'], [BOX_W / 2, AXLE_N, 'ratas dešinė'],
    [-BOX_W / 2, 0, 'galas kairė'], [BOX_W / 2, 0, 'galas dešinė']
  ];
  var N_MID = HITCH_N / 2;                   // drawing centre along the caravan
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
  var orient = P.load('orient', 0) | 0;      // phone top edge: 0 hitch, 1 right, 2 rear, 3 left
  if (orient < 0 || orient > 3) orient = 0;

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
  function cm(m) { return (Math.abs(m) < 0.0005 ? 0 : m * 100).toFixed(1); }   // metres -> "12.3" (cm)

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
    return { b: own.b, g: own.g, a: own.a, za: own.za, src: own.src, zero: zero, orient: orient };
  }
  function rel(v) {
    var z = v.zero;
    return {
      x: z ? v.b - z.b : v.b,
      y: z ? v.g - z.g : v.g,
      z: z ? wrap180(v.a - z.a) : null
    };
  }

  // ---------- caravan drawing ----------
  var box = { w: 0, h: 0 };
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
  function layout() {
    var el = $('left');
    box.w = el.clientWidth; box.h = el.clientHeight;
    dirty = true;
  }
  // slopes of the caravan plane: SE (per metre to the right), SN (per metre forward)
  function slopes(r, o) {
    var tX = Math.tan(r.x * Math.PI / 180), tY = Math.tan(r.y * Math.PI / 180);
    var th = o * Math.PI / 2, sn = Math.round(Math.sin(th)), cs = Math.round(Math.cos(th));
    // phone frame: dy = e·sinθ + n·cosθ, dx = e·cosθ − n·sinθ; h = dy·tanX − dx·tanY
    return { se: sn * tX - cs * tY, sn: cs * tX + sn * tY };
  }
  // how the drawing is turned on screen (clockwise degrees of "forward" from screen-up)
  function drawAngle(v) {
    if (mode === 'follow') return box.w > box.h * 1.3 ? 90 : 0;   // no physical link: just fit it
    var th = orient * Math.PI / 2;
    var fs = toScreen(-Math.round(Math.sin(th)), Math.round(Math.cos(th)));  // caravan forward, in device frame
    return norm360(Math.round(Math.atan2(fs[0], -fs[1]) * 180 / Math.PI));
  }
  var lastPhi = null, lastScale = 0, lastBox = '';
  function drawCaravan(v, r) {
    var phi = drawAngle(v), along = phi % 180 === 0;
    var Wm = 2.8, Hm = HITCH_N + 0.5;          // drawing size in metres (wheels stick out a bit)
    var padCross = 2 * 66, padAlong = 2 * 24;   // room for the labels
    var s = along ? Math.min((box.w - padCross) / Wm, (box.h - padAlong) / Hm)
                  : Math.min((box.w - padAlong) / Hm, (box.h - padCross) / Wm);
    s = Math.max(8, s);
    var cx = box.w / 2, cy = box.h / 2;
    var key = phi + '|' + s.toFixed(2) + '|' + box.w + 'x' + box.h;
    if (key !== lastBox) {
      lastBox = key;
      $('car').setAttribute('transform', 'translate(' + cx + ' ' + cy + ') rotate(' + phi + ') scale(' + s + ')');
    }
    var rad = phi * Math.PI / 180, c = Math.cos(rad), sn = Math.sin(rad);
    function scr(e, n) { var x = e, y = -(n - N_MID); return [cx + s * (x * c - y * sn), cy + s * (x * sn + y * c)]; }
    var eDir = [c, sn], fDir = [sn, -c];

    // heights and how much to raise
    var hs = [], i, sl = r ? slopes(r, v ? v.orient : orient) : null;
    for (i = 0; i < POINTS.length; i++) hs.push(sl ? POINTS[i][0] * sl.se + POINTS[i][1] * sl.sn : 0);
    var mx = Math.max.apply(null, hs), mn = Math.min.apply(null, hs), flat = mx - mn < 0.0005;
    for (i = 0; i < POINTS.length; i++) {
      var el = $('p' + i), pt = POINTS[i], raise = mx - hs[i];
      el.textContent = !r ? '—' : (raise < 0.0005 ? '0.0' : '↑' + cm(raise));
      el.className = 'pt' + (!r ? '' : flat || raise < 0.0005 ? ' ok' : hs[i] === mn ? ' hi' : '');
      var q = scr(pt[0], pt[1]), hw = el.offsetWidth / 2, hh = el.offsetHeight / 2, ox = 0, oy = 0;
      if (pt[0] !== 0) {
        var sg = pt[0] > 0 ? 1 : -1;
        ox = sg * eDir[0] * (hw + 0.2 * s + 6); oy = sg * eDir[1] * (hh + 0.2 * s + 6);
      } else { ox = fDir[0] * (hw + 0.15 * s + 6); oy = fDir[1] * (hh + 0.15 * s + 6); }
      var lx = Math.max(hw + 1, Math.min(box.w - hw - 1, q[0] + ox));
      var ly = Math.max(hh + 1, Math.min(box.h - hh - 1, q[1] + oy));
      el.style.transform = 'translate(' + (lx - hw).toFixed(1) + 'px,' + (ly - hh).toFixed(1) + 'px)';
    }

    // bubble (inside the box, drifts to the HIGH side). Uphill direction in caravan metres = (SE, SN)
    var bx = 0, by = 0, ok = false;
    if (sl) {
      var ge = Math.atan(sl.se) * 180 / Math.PI, gn = Math.atan(sl.sn) * 180 / Math.PI;
      var mag = Math.sqrt(ge * ge + gn * gn);
      ok = mag < 0.1;
      if (mag > 1e-6) { var f = 0.55 * (mag / (mag + 1.5)) / mag; bx = ge * f; by = -gn * f; }
    }
    $('bubble').setAttribute('transform', 'translate(' + bx.toFixed(3) + ' ' + by.toFixed(3) + ')');
    $('bubble').setAttribute('class', ok ? 'ok' : '');
    return sl;
  }

  function render() {
    var v = view();
    var r = v ? rel(v) : null;
    var sl = drawCaravan(v, r);
    if (!v) {
      ['ax', 'ay', 'az', 'rx', 'ry', 'rz'].forEach(function (id) { $(id).textContent = '—'; });
      $('sum').textContent = '';
    } else {
      $('ax').textContent = fmtDeg(v.b);
      $('ay').textContent = fmtDeg(v.g);
      $('az').textContent = fmtDeg(v.za != null ? v.za : v.a, 1);
      $('rx').textContent = fmtDeg(r.x);
      $('ry').textContent = fmtDeg(r.y);
      $('rz').textContent = r.z == null ? '—' : fmtDeg(r.z);
      var fb = sl.sn * BOX_L, lr = -sl.se * BOX_W;   // front − rear, left − right
      $('sum').innerHTML = 'Išilgai (4,9 m): <b>' + (Math.abs(fb) < 0.0005 ? 'lygu' : (fb > 0 ? 'priekis' : 'galas') + ' aukščiau ' + cm(Math.abs(fb)) + ' cm') +
        '</b><br>Skersai (2,2 m): <b>' + (Math.abs(lr) < 0.0005 ? 'lygu' : (lr > 0 ? 'kairė' : 'dešinė') + ' aukščiau ' + cm(Math.abs(lr)) + ' cm') + '</b>';
    }
    $('bSwapS').textContent = ORIENT_LT[v && mode === 'follow' ? v.orient : orient];
    renderStatus();
  }

  function renderStatus() {
    var s = '';
    if (mode === 'follow') {
      if (fOpen) s = 'P2P tiesiogiai · ' + rxHz + ' Hz · vėlinimas ~' + Math.max(1, Math.round(rttMs / 2)) + ' ms';
      else {
        if (!remote) s = online ? 'Laukiama master įrenginio…' : 'Jungiamasi…';
        else s = 'ntfy · master duomenys prieš ' + Math.max(0, Math.round((Date.now() - remoteT) / 1000)) + ' s';
        if (!RTC_OK) s += ' · P2P nepalaikomas';
        else if (fState) s += ' · P2P: ' + fState;
      }
    } else {
      s = own ? 'Jutiklis ' + hz + ' Hz' + (own.za != null ? ' · Z kompasas' : ' · Z giroskopas') : 'Nėra jutiklio duomenų';
      if (mode === 'master') {
        var np = openPeers();
        if (np) s += ' · P2P sekėjų: ' + np + ' (iki 20 Hz)';
        var left = Math.max(0, Math.ceil((nextSendAt - Date.now()) / 1000));
        if (!np || Date.now() - lastQT < 120000) s += ' · ntfy kas ' + (sendInterval() / 1000) + ' s' + (left ? ' (' + left + ')' : '') + ' · šiandien ' + sentToday() + '/' + DAY_LIMIT;
      }
    }
    $('stat').textContent = s;
    $('dot').className = mode === 'local' ? '' : 'show' + (online || fOpen || openPeers() ? ' on' : '');
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
    rtcReset();
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
  function applyRemote(d) {
    if (typeof d.b !== 'number' || typeof d.g !== 'number') return false;
      remote = {
        b: d.b, g: d.g, a: typeof d.a === 'number' ? d.a : 0,
        za: typeof d.za === 'number' ? d.za : null,
        zero: d.z && typeof d.z.b === 'number' ? { b: d.z.b, g: d.z.g, a: d.z.a || 0 } : null,
        orient: d.o === 1 || d.o === 2 || d.o === 3 ? d.o : 0
      };
      remoteT = Date.now(); dirty = true;
      return true;
  }
  function onMsg(d, msg) {
    if (!d || typeof d !== 'object') return;
    var from = msg && typeof msg.from === 'string' ? msg.from : '';
    if (mode === 'follow' && d.k === 's') {
      if (applyRemote(d) && !fOpen) Ponas.tone(880, { dur: 0.05, vol: 0.05 });
    } else if (mode === 'follow' && d.k === 'a' && chan && d.to === chan.me) {
      onAnswer(d);
    } else if (mode === 'master') {
      if (d.k === 'q') { wantSend = true; lastQT = Date.now(); }
      else if (d.k === 'o' && from) onOffer(d, from);
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
      z: zero ? { b: r2(zero.b), g: r2(zero.g), a: r2(zero.a) } : null, o: orient
    };
  }
  function changed(a, b, th) {
    if (!b) return true;
    th = th || MIN_CHANGE;
    if (Math.abs(a.b - b.b) >= th || Math.abs(a.g - b.g) >= th) return true;
    if (Math.abs(wrap180(a.a - b.a)) >= th * 10) return true;
    if (JSON.stringify(a.z) !== JSON.stringify(b.z) || a.o !== b.o) return true;
    return false;
  }
  function tick() {
    if (P.paused) return;
    var now = Date.now();
    if (mode === 'master' && chan && own && now >= nextSendAt && (openPeers() === 0 || now - lastQT < 120000)) {
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
    if (mode === 'follow' && online && !fOpen && (!remote || now - remoteT > 60000)) ask();
    if (mode === 'follow' && online && !fOpen && now >= nextRtcAt) rtcStart();
    if (mode === 'follow' && fOpen && now - lastPingT > 2000) ping();
    if (now - rxT0 >= 2000) { rxHz = Math.round(rxCount * 1000 / (now - rxT0 || 1)); rxCount = 0; rxT0 = now; }
    dirty = true;   // refresh "prieš N s" / countdown
  }

  // ---------- direct link: WebRTC data channel; ntfy carries only the handshake ----------
  // No trickle ICE: each side waits for candidate gathering and sends ONE ntfy message
  // (offer / answer), so a connection costs 2 messages of the 250/day budget.
  // ICE_SERVERS is empty: works on the same Wi-Fi or phone hotspot. Across different networks a
  // STUN server would be needed — that is a new network host (ask before adding).
  var ICE_SERVERS = [];
  var RTC_OK = typeof window.RTCPeerConnection === 'function';
  var MAX_PEERS = 4;
  var peers = {};                          // master: follower id -> {pc, dc, open}
  var fpc = null, fdc = null, fOpen = false, fState = '', rtcTries = 0, nextRtcAt = 0;
  var rttMs = 0, lastPingT = 0, rxCount = 0, rxHz = 0, rxT0 = 0;
  var lastP2P = null, lastP2PT = 0, lastQT = 0;

  function newPc() { return new RTCPeerConnection({ iceServers: ICE_SERVERS }); }
  function gathered(pc) {
    return new Promise(function (res) {
      if (pc.iceGatheringState === 'complete') return res();
      var t = setTimeout(res, 3000);
      pc.addEventListener('icegatheringstatechange', function () {
        if (pc.iceGatheringState === 'complete') { clearTimeout(t); res(); }
      });
    });
  }
  function backoff() { return [15000, 30000, 60000][rtcTries - 1] || 300000; }
  function rtcStop() {
    if (fpc) { try { fpc.close(); } catch (e) { /* ignore */ } }
    fpc = fdc = null; fOpen = false;
  }
  function rtcReset() {
    rtcStop(); fState = ''; rtcTries = 0; nextRtcAt = Date.now() + 800; rttMs = 0;
    for (var k in peers) { try { peers[k].pc.close(); } catch (e) { /* ignore */ } }
    peers = {}; lastP2P = null;
  }

  // follower: offer a data channel to whoever is master in this room
  function rtcStart() {
    if (!RTC_OK || !chan) return;
    rtcStop();
    rtcTries++; fState = 'jungiamasi'; nextRtcAt = Date.now() + backoff(); dirty = true;
    var pc = fpc = newPc();
    var dc = fdc = pc.createDataChannel('lvl', { ordered: false, maxRetransmits: 0 });
    dc.onopen = function () { if (fpc !== pc) return; fOpen = true; fState = 'p2p'; rtcTries = 0; dirty = true; ping(); };
    dc.onclose = function () {
      if (fpc !== pc) return;
      fOpen = false; fState = 'nutrūko, jungiamasi iš naujo'; nextRtcAt = Date.now() + 3000; dirty = true;
    };
    dc.onmessage = function (e) { onP2P(e.data); };
    pc.onconnectionstatechange = function () {
      if (fpc === pc && pc.connectionState === 'failed') { fOpen = false; fState = 'nepavyko (kiti tinklai?)'; dirty = true; }
    };
    pc.createOffer()
      .then(function (o) { return pc.setLocalDescription(o); })
      .then(function () { return gathered(pc); })
      .then(function () {
        if (fpc === pc && chan) { fState = 'laukiama master'; chan.send({ k: 'o', sdp: pc.localDescription.sdp }); }
      })
      .catch(function () { if (fpc === pc) fState = 'klaida'; });
  }
  function onAnswer(d) {
    if (!fpc || typeof d.sdp !== 'string' || fpc.signalingState !== 'have-local-offer') return;
    fState = 'jungiamasi tiesiogiai';
    fpc.setRemoteDescription({ type: 'answer', sdp: d.sdp }).catch(function () { fState = 'klaida'; });
  }
  function onP2P(raw) {
    var d; try { d = JSON.parse(raw); } catch (e) { return; }
    if (!d || typeof d !== 'object') return;
    if (d.k === 's') { if (applyRemote(d)) rxCount++; }
    else if (d.k === 'P' && typeof d.t === 'number') rttMs = performance.now() - d.t;
  }
  function ping() {
    lastPingT = Date.now();
    if (fdc && fOpen) try { fdc.send(JSON.stringify({ k: 'p', t: performance.now() })); } catch (e) { /* ignore */ }
  }
  function followerCmd(c) {
    if (fOpen && fdc) { try { fdc.send(JSON.stringify({ k: 'c', c: c })); return; } catch (e) { /* fall back */ } }
    if (chan) chan.send({ k: 'c', c: c });
  }

  // master: answer offers, then stream at up to 20 Hz to every open channel
  function onOffer(d, from) {
    if (!RTC_OK || typeof d.sdp !== 'string' || d.sdp.length > 3600) return;
    if (peers[from]) { try { peers[from].pc.close(); } catch (e) { /* ignore */ } delete peers[from]; }
    if (Object.keys(peers).length >= MAX_PEERS) return;
    var pc = newPc(), peer = peers[from] = { pc: pc, dc: null, open: false };
    pc.ondatachannel = function (e) {
      var dc = peer.dc = e.channel;
      dc.onopen = function () { peer.open = true; lastP2P = null; dirty = true; };
      dc.onclose = function () { peer.open = false; if (peers[from] === peer) delete peers[from]; dirty = true; };
      dc.onmessage = function (ev) {
        var m; try { m = JSON.parse(ev.data); } catch (x) { return; }
        if (!m) return;
        if (m.k === 'p' && typeof m.t === 'number') { try { dc.send(JSON.stringify({ k: 'P', t: m.t })); } catch (x) { /* ignore */ } }
        else if (m.k === 'c' && m.c === 'zero') setZero();
        else if (m.k === 'c' && m.c === 'clear') clearZero();
      };
    };
    pc.onconnectionstatechange = function () {
      if ((pc.connectionState === 'failed' || pc.connectionState === 'closed') && peers[from] === peer) { delete peers[from]; dirty = true; }
    };
    pc.setRemoteDescription({ type: 'offer', sdp: d.sdp })
      .then(function () { return pc.createAnswer(); })
      .then(function (a) { return pc.setLocalDescription(a); })
      .then(function () { return gathered(pc); })
      .then(function () {
        if (chan && peers[from] === peer) {
          sentToday(); sentLog.n++; P.save('sent', sentLog);
          chan.send({ k: 'a', to: from, sdp: pc.localDescription.sdp });
        }
      })
      .catch(function () { if (peers[from] === peer) delete peers[from]; });
  }
  function openPeers() { var n = 0; for (var k in peers) if (peers[k].open) n++; return n; }
  function p2pBroadcast(now) {
    if (!own || !openPeers()) return;
    var cur = snap();
    if (!changed(cur, lastP2P, 0.005) && now - lastP2PT < 1000) return;   // heartbeat 1 Hz when still
    lastP2P = cur; lastP2PT = now;
    var msg = JSON.stringify(cur);
    for (var k in peers) if (peers[k].open) { try { peers[k].dc.send(msg); } catch (e) { /* ignore */ } }
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
    if (mode === 'follow') { followerCmd('zero'); Ponas.tone(660, { dur: 0.1 }); }
    else setZero();
  });
  btn('bClear', function () {
    if (mode === 'follow') { followerCmd('clear'); Ponas.tone(520, { dur: 0.1 }); }
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
    orient = (orient + 1) % 4; P.save('orient', orient); wantSend = true; dirty = true;
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
  function startTimers() {
    timers.push(setInterval(tick, 250));
    timers.push(setInterval(function () { if (!P.paused && mode === 'master') p2pBroadcast(Date.now()); }, 50));
    startLoop();
  }
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
