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
 * Reference ("Atskaita"): 'wheels' (default) — the higher wheel stays put, the lower wheel is raised,
 * the hitch goes up or down (jockey wheel), corners move by what the plane says; 'top' — every point
 * is raised to the highest one. Calibration ("Kalibruoti") = 2 readings with the phone turned 180°
 * on the same spot: the average is the phone's own offset (camera bump, sensor bias), the surface
 * tilt cancels out, so it works right on the caravan floor.
 *
 * Remote: modes local / master / follower. Angles, calibration and commands go ONLY over a direct
 * WebRTC data channel. ntfy.sh (Ponas.net) is used just for the handshake: one offer from the
 * follower, one answer from the master (free ntfy.sh: 250 msgs per day per IP). Retries are sparse.
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
  var zero = P.load('cal', null);            // phone offset {b, g, a} from calibration, or null
  var calStep = 0, calFirst = null;          // 1 = first reading taken, waiting for the 180° one
  var refMode = P.load('ref', 'wheels') === 'top' ? 'top' : 'wheels';
  var VIEWS = ['top', 'back', 'side'], VIEW_LT = { top: 'iš viršaus', back: 'iš galo', side: 'iš šono (kairės)' };
  var viewMode = P.load('view', 'top'); if (VIEWS.indexOf(viewMode) < 0) viewMode = 'top';
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
    // target height everything is brought to: the higher wheel, or the highest point
    var target = refMode === 'top' ? Math.max.apply(null, hs) : Math.max(hs[3], hs[4]);
    var ch = hs.map(function (h) { return target - h; });     // + raise, − lower
    var big = Math.max.apply(null, ch.map(Math.abs));
    var prof = viewMode !== 'top';
    $('left').classList.toggle('prof', prof);
    $('car').style.display = prof ? 'none' : '';
    $('vSide').style.display = viewMode === 'side' ? '' : 'none';
    $('vBack').style.display = viewMode === 'back' ? '' : 'none';
    $('viewChip').textContent = '⟳ ' + VIEW_LT[viewMode];
    if (prof) { drawProfile(r ? sl : null, ch, big); return sl; }
    for (i = 0; i < POINTS.length; i++) {
      var el = $('p' + i), pt = POINTS[i], d = ch[i];
      el.textContent = !r ? '—' : Math.abs(d) < 0.0005 ? '0.0' : (d > 0 ? '↑' : '↓') + cm(Math.abs(d));
      el.className = 'pt' + (!r ? '' : Math.abs(d) < 0.0005 ? ' ok' : big >= 0.0005 && Math.abs(d) === big ? ' hi' : '');
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

  // ---------- back / side views (tilt exaggerated so it can be seen; the numbers are real) ----------
  function fmtCh(d) { return Math.abs(d) < 0.0005 ? '0.0' : (d > 0 ? '↑' : '↓') + cm(Math.abs(d)); }
  function attr(id, o) { var e = $(id); for (var k in o) e.setAttribute(k, typeof o[k] === 'number' ? o[k].toFixed(1) : o[k]); }
  function poly(pts) { return pts.map(function (p) { return p[0].toFixed(1) + ',' + p[1].toFixed(1); }).join(' '); }
  function label(id, html, x, y, hot) {
    var el = $(id);
    el.innerHTML = html;
    el.className = 'pt q' + (hot === 2 ? ' hi' : hot === 1 ? ' ok' : '');
    var hw = el.offsetWidth / 2, hh = el.offsetHeight / 2;
    x = Math.max(hw + 1, Math.min(box.w - hw - 1, x)); y = Math.max(hh + 1, Math.min(box.h - hh - 1, y));
    el.style.transform = 'translate(' + (x - hw).toFixed(1) + 'px,' + (y - hh).toFixed(1) + 'px)';
  }
  function hot(list, ch, big) {   // 2 = has the biggest move, 1 = all zero
    var any = false, top = false;
    list.forEach(function (i) { if (Math.abs(ch[i]) >= 0.0005) any = true; if (big >= 0.0005 && Math.abs(ch[i]) === big) top = true; });
    return top ? 2 : any ? 0 : 1;
  }
  var BODY_H = 1.9, WHEEL_R = 0.3, CHASSIS = 0.45;   // drawing only (metres)
  function drawProfile(sl, ch, big) {
    var se = sl ? sl.se : 0, sn = sl ? sl.sn : 0, ex, maxDev, s, i;
    var none = !sl;
    if (viewMode === 'side') {
      // centre-line height at station n, relative to the axle
      var hc = function (n) { return (n - AXLE_N) * sn; };
      maxDev = Math.max(Math.abs(hc(0)), Math.abs(hc(HITCH_N)));
      ex = maxDev > 1e-6 ? Math.min(30, 0.6 / maxDev) : 30;
      var dev = Math.min(0.6, maxDev * ex);
      var lab = 42;                                  // label row under the ground line
      s = Math.min((box.w - 30) / (HITCH_N + 0.6), (box.h - lab - 52) / (BODY_H + CHASSIS + 2 * dev + 0.1));
      var x = function (n) { return box.w / 2 - s * (n - N_MID); };     // hitch on the left
      var contentH = s * (BODY_H + CHASSIS + 2 * dev) + lab + 6;
      var groundY = (box.h + contentH) / 2 - lab - 6 - s * dev;
      var cy = function (n) { return groundY - s * CHASSIS - s * ex * hc(n); };   // chassis line
      attr('sLvl', { x1: 4, x2: box.w - 4, y1: groundY, y2: groundY });
      attr('sBody', { points: poly([[x(0), cy(0)], [x(BOX_L), cy(BOX_L)], [x(BOX_L), cy(BOX_L) - s * BODY_H * 0.9],
        [x(BOX_L) - s * 0.3, cy(BOX_L) - s * BODY_H], [x(0), cy(0) - s * BODY_H]]) });
      attr('sWin', { points: poly([[x(1.2), cy(1.2) - s * 1.3], [x(3.6), cy(3.6) - s * 1.3], [x(3.6), cy(3.6) - s * 0.9], [x(1.2), cy(1.2) - s * 0.9]]) });
      attr('sBar', { points: poly([[x(BOX_L), cy(BOX_L)], [x(HITCH_N), cy(HITCH_N)]]) });
      attr('sWheel', { cx: x(AXLE_N), cy: cy(AXLE_N) + s * (CHASSIS - WHEEL_R), r: s * WHEEL_R });
      attr('sHitch', { cx: x(HITCH_N), cy: cy(HITCH_N) });
      attr('sLegR', { x1: x(0) - 3, x2: x(0) - 3, y1: cy(0), y2: groundY });
      attr('sLegF', { x1: x(BOX_L) + 3, x2: x(BOX_L) + 3, y1: cy(BOX_L), y2: groundY });
      attr('sJock', { x1: x(HITCH_N - 0.2), x2: x(HITCH_N - 0.2), y1: cy(HITCH_N - 0.2), y2: groundY });
      var ly = groundY + s * dev + lab / 2 + 4;
      var two = function (a, b, name) { return none ? '—' : '<small>' + name + '</small>K ' + fmtCh(ch[a]) + '<br>D ' + fmtCh(ch[b]); };
      label('q0', none ? '—' : '<small>kablys</small>' + fmtCh(ch[0]), x(HITCH_N), ly, none ? 0 : hot([0], ch, big));
      label('q1', two(1, 2, 'priekis'), x(BOX_L), ly, none ? 0 : hot([1, 2], ch, big));
      label('q2', two(3, 4, 'ratai'), x(AXLE_N), ly, none ? 0 : hot([3, 4], ch, big));
      label('q3', two(5, 6, 'galas'), x(0), ly, none ? 0 : hot([5, 6], ch, big));
    } else {
      // seen from behind: left on the left. Roll only (relative to the centre line)
      var hb = function (e) { return e * se; };
      maxDev = Math.abs(hb(BOX_W / 2));
      ex = maxDev > 1e-6 ? Math.min(30, 0.35 / maxDev) : 30;
      var devb = Math.min(0.35, maxDev * ex);
      var col = 84, bottom = 34;
      s = Math.min((box.w - 2 * col) / 2.9, (box.h - bottom - 52) / (BODY_H + CHASSIS + 2 * devb));
      var cxb = box.w / 2;
      var bx = function (e) { return cxb + s * e; };
      var contentHb = s * (BODY_H + CHASSIS + 2 * devb) + bottom;
      var gY = (box.h + contentHb) / 2 - bottom - s * devb;
      var by = function (e) { return gY - s * CHASSIS - s * ex * hb(e); };
      var L = -BOX_W / 2, R = BOX_W / 2, tilt = function (e, up) { return by(e) - s * up; };
      attr('bLvl', { x1: 4, x2: box.w - 4, y1: gY, y2: gY });
      attr('bBody', { points: poly([[bx(L), by(L)], [bx(R), by(R)], [bx(R), tilt(R, BODY_H * 0.92)],
        [bx(R - 0.25), tilt(R - 0.25, BODY_H)], [bx(L + 0.25), tilt(L + 0.25, BODY_H)], [bx(L), tilt(L, BODY_H * 0.92)]]) });
      attr('bWin', { points: poly([[bx(-0.5), tilt(-0.5, 1.45)], [bx(0.5), tilt(0.5, 1.45)], [bx(0.5), tilt(0.5, 1.0)], [bx(-0.5), tilt(-0.5, 1.0)]]) });
      var wheel = function (e0, e1) {
        return poly([[bx(e0), by(e0) + s * 0.05], [bx(e1), by(e1) + s * 0.05], [bx(e1), by(e1) + s * CHASSIS], [bx(e0), by(e0) + s * CHASSIS]]);
      };
      attr('bWL', { points: wheel(L - 0.05, L + 0.22) });
      attr('bWR', { points: wheel(R - 0.22, R + 0.05) });
      var three = function (a, b, c, side) {
        return none ? '—' : '<small>' + side + '</small>priekis ' + fmtCh(ch[a]) + '<br>ratas ' + fmtCh(ch[b]) + '<br>galas ' + fmtCh(ch[c]);
      };
      var midY = by(0) - s * BODY_H / 2;
      label('q0', three(1, 3, 5, 'kairė'), bx(L) - 8 - 40, midY, none ? 0 : hot([1, 3, 5], ch, big));
      label('q1', three(2, 4, 6, 'dešinė'), bx(R) + 8 + 40, midY, none ? 0 : hot([2, 4, 6], ch, big));
      label('q2', none ? '—' : '<small>kablys</small>' + fmtCh(ch[0]), cxb, gY + s * devb + bottom / 2 + 2, none ? 0 : hot([0], ch, big));
      $('q3').style.transform = 'translate(-999px,0)';
    }
    $('legend').textContent = (refMode === 'top' ? '↑ kelti, cm' : '↑ kelti / ↓ nuleisti, cm') + ' · pokrypis piešinyje ×' + Math.round(ex);
  }

  function render() {
    var v = view();
    var r = v ? rel(v) : null;
    var sl = drawCaravan(v, r);
    if (!v) {
      ['ax', 'ay', 'az', 'rx', 'ry', 'rz', 'dl', 'dw', 'dlWhich', 'dwWhich'].forEach(function (id) { $(id).textContent = '—'; });
      $('sum').textContent = '';
    } else {
      $('ax').textContent = fmtDeg(v.b);
      $('ay').textContent = fmtDeg(v.g);
      $('az').textContent = fmtDeg(v.za != null ? v.za : v.a, 1);
      $('rx').textContent = fmtDeg(r.x);
      $('ry').textContent = fmtDeg(r.y);
      $('rz').textContent = r.z == null ? '—' : fmtDeg(r.z);
      var fb = sl.sn * BOX_L, lr = -sl.se * BOX_W;   // front − rear, left − right
      var pitch = Math.atan(Math.abs(sl.sn)) * 180 / Math.PI, roll = Math.atan(Math.abs(sl.se)) * 180 / Math.PI;
      var cs = mode === 'follow' ? v.calStep : calStep;
      // differences along (front − rear over 4.9 m) and across (left − right over 2.2 m)
      $('dl').textContent = cm(Math.abs(fb)) + ' cm';
      $('dw').textContent = cm(Math.abs(lr)) + ' cm';
      $('dlWhich').textContent = (Math.abs(fb) < 0.0005 ? 'lygu' : (fb > 0 ? 'priekis ↑' : 'galas ↑')) + ' ' + pitch.toFixed(2) + '°';
      $('dwWhich').textContent = (Math.abs(lr) < 0.0005 ? 'lygu' : (lr > 0 ? 'kairė ↑' : 'dešinė ↑')) + ' ' + roll.toFixed(2) + '°';
      $('sum').innerHTML = cs
        ? '<b class="warn">Kalibravimas: apsuk telefoną 180° toje pačioje vietoje ir dar kartą spausk „Kalibruoti“.</b>'
        : v.zero ? '' : '<span class="warn">Nekalibruota: telefono kreivumas gali pridėti kelis cm. Spausk „Kalibruoti“.</span>';
    }
    $('bSwapS').textContent = ORIENT_LT[v && mode === 'follow' ? v.orient : orient];
    var calNow = mode === 'follow' ? (v ? v.calStep : 0) : calStep, calOk = mode === 'follow' ? v && v.zero : zero;
    $('bZeroS').textContent = calNow ? '2/2: apsukus 180°' : calOk ? 'kalibruota ✓' : '1/2: pradėti';
    $('bClearS').textContent = refMode === 'top' ? 'aukščiausias' : 'ratai';
    if (viewMode === 'top') $('legend').textContent = (refMode === 'top' ? '↑ kelti, cm · 0.0 = aukščiausias taškas' : '↑ kelti / ↓ nuleisti, cm · 0.0 = aukštesnis ratas');
    renderStatus();
  }

  function renderStatus() {
    var s = '';
    if (mode === 'follow') {
      if (fOpen) s = 'P2P tiesiogiai · ' + rxHz + ' Hz · vėlinimas ~' + Math.max(1, Math.round(rttMs / 2)) + ' ms';
      else {
        s = !online ? 'Jungiamasi prie ntfy…' : 'Nėra tiesioginio ryšio su master';
        if (remote) s += ' · paskutiniai duomenys prieš ' + Math.max(0, Math.round((Date.now() - remoteT) / 1000)) + ' s';
        if (!RTC_OK) s += ' · P2P nepalaikomas';
        else if (fState) s += ' · P2P: ' + fState;
        if (RTC_OK && nextRtcAt > Date.now() && fState && !/laukiama|jungiamasi/.test(fState)) s += ' · kitas bandymas po ' + Math.ceil((nextRtcAt - Date.now()) / 1000) + ' s';
        s += ' · ntfy šiandien ' + sentToday() + '/' + DAY_LIMIT;
      }
    } else {
      s = own ? 'Jutiklis ' + hz + ' Hz' + (own.za != null ? ' · Z kompasas' : ' · Z giroskopas') : 'Nėra jutiklio duomenų';
      if (mode === 'master') {
        var np = openPeers();
        if (np) s += ' · P2P sekėjų: ' + np + ' (iki 20 Hz)';
        if (!np) s += ' · laukiama sekėjų';
        if (mState) s += ' · P2P: ' + mState;
        s += ' · ntfy šiandien ' + sentToday() + '/' + DAY_LIMIT;
      }
    }
    if (mode !== 'local') s += ntfyWarn();
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
  // ntfy.sh refuses (429) once the daily 250 per IP is used up; show it plainly
  var ntfyFailT = 0;
  function ntfyOk(ok) { if (!ok) { ntfyFailT = Date.now(); dirty = true; } else ntfyFailT = 0; }
  function ntfyWarn() { return ntfyFailT && Date.now() - ntfyFailT < 120000 ? ' · ⚠ ntfy atmeta žinutes (dienos limitas 250/IP?)' : ''; }
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
    });
    chan.on(onMsg);
  }
  function applyRemote(d) {
    if (typeof d.b !== 'number' || typeof d.g !== 'number') return false;
      remote = {
        b: d.b, g: d.g, a: typeof d.a === 'number' ? d.a : 0,
        za: typeof d.za === 'number' ? d.za : null,
        zero: d.z && typeof d.z.b === 'number' ? { b: d.z.b, g: d.z.g, a: d.z.a || 0 } : null,
        calStep: d.cs === 1 ? 1 : 0,
        orient: d.o === 1 || d.o === 2 || d.o === 3 ? d.o : 0
      };
      remoteT = Date.now(); dirty = true;
      return true;
  }
  function onMsg(d, msg) {
    if (!d || typeof d !== 'object') return;
    var from = msg && typeof msg.from === 'string' ? msg.from : '';
    // ntfy carries only the WebRTC handshake; data never goes through it
    if (mode === 'follow' && d.k === 'a' && chan && d.to === chan.me) onAnswer(d);
    else if (mode === 'master' && d.k === 'o' && from) onOffer(d, from);
  }
  function snap() {
    return {
      k: 's', b: r2(own.b), g: r2(own.g), a: r2(own.a), za: own.za == null ? null : r2(own.za),
      z: zero ? { b: r2(zero.b), g: r2(zero.g), a: r2(zero.a) } : null, o: orient, cs: calStep
    };
  }
  function changed(a, b, th) {
    if (!b) return true;
    th = th || MIN_CHANGE;
    if (Math.abs(a.b - b.b) >= th || Math.abs(a.g - b.g) >= th) return true;
    if (Math.abs(wrap180(a.a - b.a)) >= th * 10) return true;
    if (JSON.stringify(a.z) !== JSON.stringify(b.z) || a.o !== b.o || a.cs !== b.cs) return true;
    return false;
  }
  function tick() {
    if (P.paused) return;
    var now = Date.now();
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

  // Keep the handshake message small (ntfy body must stay under ~3.8 KB): drop TCP candidates,
  // keep at most 6 UDP candidates, drop end-of-candidates / ice-options lines.
  function slimSdp(sdp) {
    var n = 0;
    return sdp.split('\r\n').filter(function (l) {
      if (/^a=candidate:/.test(l)) {
        if (/ tcp /i.test(l)) return false;
        return ++n <= 6;
      }
      return !/^a=(end-of-candidates|ice-options)/.test(l);
    }).join('\r\n');
  }
  var SDP_MAX = 3500;
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
  function backoff() { return [20000, 60000, 180000][rtcTries - 1] || 600000; }   // 2 ntfy msgs per try
  function rtcStop() {
    if (fpc) { try { fpc.close(); } catch (e) { /* ignore */ } }
    fpc = fdc = null; fOpen = false;
  }
  function rtcReset() {
    rtcStop(); fState = ''; rtcTries = 0; nextRtcAt = Date.now() + 800; rttMs = 0;
    for (var k in peers) { try { peers[k].pc.close(); } catch (e) { /* ignore */ } }
    peers = {}; lastP2P = null; mState = '';
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
        if (fpc === pc && chan) {
          fState = 'laukiama master';
          var sdp = slimSdp(pc.localDescription.sdp);
          if (sdp.length > SDP_MAX) { fState = 'prisijungimo aprašas per didelis (' + sdp.length + ' B)'; return; }
          countNtfy();
          chan.send({ k: 'o', sdp: sdp }).then(function (ok) {
            ntfyOk(ok);
            if (fpc !== pc) return;
            fState = ok ? 'laukiama master atsakymo (išsiųsta ' + new Date().toLocaleTimeString('lt-LT') + ')' : 'ntfy atmetė prisijungimą';
            dirty = true;
          });
        }
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
  function followerCmd(c) {                // only over the direct link
    if (fOpen && fdc) { try { fdc.send(JSON.stringify({ k: 'c', c: c })); return true; } catch (e) { /* closed */ } }
    return false;
  }
  function countNtfy() { sentToday(); sentLog.n++; P.save('sent', sentLog); dirty = true; }

  // master: answer offers, then stream at up to 20 Hz to every open channel
  var mState = '';                          // master: last handshake event, shown in the status line
  function prunePeers() {                   // forget peers that never opened within 30 s
    var now = Date.now();
    for (var k in peers) {
      if (!peers[k].open && now - peers[k].t > 30000) { try { peers[k].pc.close(); } catch (e) { /* ignore */ } delete peers[k]; }
    }
  }
  function onOffer(d, from) {
    if (!RTC_OK) { mState = 'gautas prašymas, bet P2P nepalaikomas'; return; }
    if (typeof d.sdp !== 'string' || d.sdp.length > 3800) { mState = 'gautas netinkamas prašymas'; return; }
    mState = 'gautas prisijungimo prašymas'; dirty = true;
    prunePeers();
    if (peers[from]) { try { peers[from].pc.close(); } catch (e) { /* ignore */ } delete peers[from]; }
    if (Object.keys(peers).length >= MAX_PEERS) {       // still full: drop the oldest one that isn't open
      var old = null;
      for (var k in peers) if (!peers[k].open && (!old || peers[k].t < peers[old].t)) old = k;
      if (!old) { mState = 'per daug sekėjų (max ' + MAX_PEERS + ')'; return; }
      try { peers[old].pc.close(); } catch (e) { /* ignore */ }
      delete peers[old];
    }
    var pc = newPc(), peer = peers[from] = { pc: pc, dc: null, open: false, t: Date.now() };
    pc.ondatachannel = function (e) {
      var dc = peer.dc = e.channel;
      dc.onopen = function () { peer.open = true; lastP2P = null; mState = ''; dirty = true; };
      dc.onclose = function () { peer.open = false; if (peers[from] === peer) delete peers[from]; dirty = true; };
      dc.onmessage = function (ev) {
        var m; try { m = JSON.parse(ev.data); } catch (x) { return; }
        if (!m) return;
        if (m.k === 'p' && typeof m.t === 'number') { try { dc.send(JSON.stringify({ k: 'P', t: m.t })); } catch (x) { /* ignore */ } }
        else if (m.k === 'c' && m.c === 'cal') calibrate();
      };
    };
    pc.onconnectionstatechange = function () {
      if ((pc.connectionState === 'failed' || pc.connectionState === 'closed') && peers[from] === peer) {
        if (pc.connectionState === 'failed') mState = 'tiesioginis ryšys nepavyko (skirtingi tinklai?)';
        delete peers[from]; dirty = true;
      }
    };
    pc.setRemoteDescription({ type: 'offer', sdp: d.sdp })
      .then(function () { return pc.createAnswer(); })
      .then(function (a) { return pc.setLocalDescription(a); })
      .then(function () { return gathered(pc); })
      .then(function () {
        if (chan && peers[from] === peer) {
          countNtfy();
          chan.send({ k: 'a', to: from, sdp: slimSdp(pc.localDescription.sdp) }).then(function (ok) {
            ntfyOk(ok);
            mState = ok ? 'atsakyta, jungiamasi…' : 'ntfy atmetė atsakymą';
            dirty = true;
          });
        }
      })
      .catch(function () { mState = 'nepavyko sukurti atsakymo'; if (peers[from] === peer) delete peers[from]; });
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
  // two-position (reversal) calibration: reading = surface tilt + offset; turned 180° = −tilt + offset
  function calibrate() {
    if (!own) return;
    if (calStep === 0) {
      calFirst = { b: own.b, g: own.g };
      calStep = 1;
      Ponas.tone(660, { dur: 0.12 });
    } else {
      zero = { b: (calFirst.b + own.b) / 2, g: (calFirst.g + own.g) / 2, a: own.a };
      calStep = 0; calFirst = null;
      P.save('cal', zero);
      orient = (orient + 2) % 4; P.save('orient', orient);   // the phone now lies turned 180°
      Ponas.tone(660, { dur: 0.12 }); Ponas.tone(990, { delay: 0.1, dur: 0.15 });
    }
    wantSend = true; lastP2P = null; dirty = true;
  }
  function btn(id, fn) {
    $(id).addEventListener('pointerdown', function (e) {
      e.preventDefault();
      if (P.paused) return;
      fn();
    });
  }
  btn('bZero', function () {
    if (mode === 'follow') { if (followerCmd('cal')) Ponas.tone(660, { dur: 0.1 }); else Ponas.tone(260, { dur: 0.2 }); }
    else calibrate();
  });
  btn('bClear', function () {
    refMode = refMode === 'top' ? 'wheels' : 'top';
    P.save('ref', refMode); dirty = true;
    Ponas.tone(600, { dur: 0.1 });
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
  $('left').addEventListener('pointerdown', function (e) {
    if (P.paused || $('perm').classList.contains('show')) return;
    e.preventDefault();
    viewMode = VIEWS[(VIEWS.indexOf(viewMode) + 1) % VIEWS.length];
    P.save('view', viewMode); dirty = true;
    Ponas.tone(700, { dur: 0.08 });
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
    calStep = 0; calFirst = null;
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
