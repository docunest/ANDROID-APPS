// Ateiviai — a gentle space invaders. The rocket follows the finger; touching (and holding) shoots.
// Aliens march side to side and creep down. If they reach the rocket they just bounce back up —
// no lives, no game over. Clear the sky → a star; 5 stars → celebration. More rows and speed with wins.
(function () {
  'use strict';
  var P = window.Ponas;
  var L = window.Laikas || { blocked: false, onBlock: function () {}, onUnblock: function () {} };

  var COLORS = ['#76FF03', '#FF4081', '#40C4FF', '#FFD740'];
  function alienSvg(col) {
    return '<svg viewBox="0 0 100 100">' +
      '<path d="M50 14c-22 0-36 15-36 34v12h72V48c0-19-14-34-36-34z" fill="' + col + '"/>' +
      '<path d="M30 14 L22 2 M70 14 L78 2" stroke="' + col + '" stroke-width="6" stroke-linecap="round"/>' +
      '<circle cx="36" cy="42" r="10" fill="#fff"/><circle cx="64" cy="42" r="10" fill="#fff"/>' +
      '<circle cx="38" cy="44" r="5" fill="#1A237E"/><circle cx="66" cy="44" r="5" fill="#1A237E"/>' +
      '<g class="a" stroke="' + col + '" stroke-width="8" stroke-linecap="round"><path d="M24 60 L14 84 M42 60 L38 86 M58 60 L62 86 M76 60 L86 84"/></g>' +
      '<g class="b" stroke="' + col + '" stroke-width="8" stroke-linecap="round"><path d="M24 60 L28 86 M42 60 L46 84 M58 60 L54 84 M76 60 L72 86"/></g>' +
      '</svg>';
  }
  var SHIP_SVG = '<svg viewBox="0 0 100 100">' +
    '<path d="M50 4c12 12 18 30 18 50v22H32V54c0-20 6-38 18-50z" fill="#ECEFF1"/>' +
    '<path d="M32 56 L12 80 L14 92 L32 82z M68 56 L88 80 L86 92 L68 82z" fill="#EF5350"/>' +
    '<circle cx="50" cy="40" r="10" fill="#4FC3F7" stroke="#90A4AE" stroke-width="4"/>' +
    '<path d="M38 76h24l-4 12H42z" fill="#78909C"/><path d="M44 88h12l-6 10z" fill="#FFB300"/></svg>';

  var game = document.getElementById('game');
  var swarmEl = document.getElementById('swarm');
  var ship = document.getElementById('ship');
  var starsEl = document.getElementById('stars');
  var shotEls = Array.prototype.slice.call(document.querySelectorAll('.shot'));
  ship.insertAdjacentHTML('beforeend', SHIP_SVG);

  var waves = P.load('waves', 0), stars = P.load('stars', 0);
  var W = 640, H = 300, cols = 7, rows = 2, s = 40, sp = 56, vsp = 50, ss = 70, shipTop = 0;
  var aliens = [], alive = 0, total = 0, gx = 0, gy = 0, dir = 1, frame = false;
  var shipX = 320, targetX = 320, holding = false, lastShot = 0, shots = [], pending = 0;
  var running = false, raf = 0, stepT = 0, busy = false, lastT = 0;
  var timers = [], hintTimer = 0, hints = 0, lastFire = 0;

  function later(fn, ms) { var t = setTimeout(function () { timers.splice(timers.indexOf(t), 1); fn(); }, ms); timers.push(t); }

  // ---- sky (one element, many box-shadows, never repainted) ----
  (function () {
    var sh = [];
    for (var i = 0; i < 60; i++) sh.push(Math.round(Math.random() * 1400) + 'px ' + Math.round(Math.random() * 1400) + 'px 0 ' + (Math.random() < 0.2 ? 1 : 0) + 'px #ffffff' + (Math.random() < 0.5 ? '99' : 'dd'));
    document.getElementById('sky').style.boxShadow = sh.join(',');
  })();

  // ---- sounds ----
  function pew() { P.tone(1200, { dur: 0.07, type: 'square', vol: 0.04 }); P.tone(800, { delay: 0.04, dur: 0.07, type: 'square', vol: 0.03 }); }
  function popSound() {
    var ac = P.audio();
    if (!ac || P.paused) return;
    var len = Math.floor(ac.sampleRate * 0.12), buf = ac.createBuffer(1, len, ac.sampleRate), d = buf.getChannelData(0);
    for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / len);
    var src = ac.createBufferSource(), f = ac.createBiquadFilter(), g = ac.createGain();
    src.buffer = buf; f.type = 'bandpass'; f.frequency.value = 1800; g.gain.value = 0.6;
    src.connect(f); f.connect(g); g.connect(ac.destination); src.start();
    P.tone(660 + Math.random() * 300, { dur: 0.12, type: 'triangle', vol: 0.12 });
  }
  function march() { P.tone(frame ? 110 : 98, { dur: 0.09, type: 'triangle', vol: 0.14 }); }
  function whoosh() { [600, 500, 400, 300].forEach(function (f, i) { P.tone(f, { delay: i * 0.07, dur: 0.1, type: 'sine', vol: 0.15 }); }); }
  function yay() { [523, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: 0.1 + i * 0.1, dur: 0.25, type: 'triangle', vol: 0.2 }); }); }
  function fanfare() { [523, 659, 784, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: i * 0.14, dur: 0.3, type: 'square', vol: 0.08 }); }); }

  // ---- layout ----
  function measure() {
    var p = W < H;
    cols = p ? 5 : 7;
    rows = waves < 2 ? 2 : waves < 5 ? 3 : 4;
    ss = Math.round(Math.min(78, H * 0.22, W * 0.2));
    shipTop = H - ss - 6;
    s = Math.floor(Math.min(W / (cols * 1.45 + 1.2), (shipTop * 0.55) / (rows * 1.3), 52));
    sp = Math.round(s * 1.42); vsp = Math.round(s * 1.25);
    ship.style.width = ss + 'px'; ship.style.height = ss + 'px';
  }
  function placeAliens() {
    aliens.forEach(function (a) {
      a.el.style.left = (a.c * sp) + 'px'; a.el.style.top = (a.r * vsp) + 'px';
      a.el.style.width = s + 'px'; a.el.style.height = s + 'px';
    });
  }
  function setSwarm() { swarmEl.style.transform = 'translate(' + gx + 'px,' + gy + 'px)'; }
  function setShip() { ship.style.transform = 'translate(' + (shipX - ss / 2) + 'px,' + shipTop + 'px)'; }

  function newWave() {
    measure();
    swarmEl.innerHTML = ''; aliens = [];
    for (var r = 0; r < rows; r++) for (var c = 0; c < cols; c++) {
      var el = document.createElement('div');
      el.className = 'alien'; el.innerHTML = alienSvg(COLORS[r % COLORS.length]);
      swarmEl.appendChild(el);
      aliens.push({ r: r, c: c, alive: true, el: el });
    }
    alive = total = aliens.length;
    placeAliens();
    gx = Math.round((W - ((cols - 1) * sp + s)) / 2); gy = 6; dir = 1;
    setSwarm(); hints = 0; busy = false;
  }

  P.onResize(function (w, h) {
    var oldCols = cols;
    W = w; H = h - 72;
    measure();
    if (!aliens.length || cols !== oldCols) newWave();
    else {
      placeAliens();
      var span = (cols - 1) * sp + s;
      gx = Math.max(0, Math.min(gx, W - span)); gy = Math.min(gy, 6 + vsp); setSwarm();
    }
    shipX = targetX = Math.max(ss / 2, Math.min(W - ss / 2, shipX || W / 2));
    setShip();
  });

  // ---- no browser zoom: fast repeated taps must never be read as double-tap / pinch zoom ----
  ['touchstart', 'touchend', 'touchmove'].forEach(function (t) {
    document.addEventListener(t, function (e) { if (e.cancelable) e.preventDefault(); }, { passive: false });
  });
  ['dblclick', 'gesturestart', 'gesturechange'].forEach(function (t) {
    document.addEventListener(t, function (e) { e.preventDefault(); }, { passive: false });
  });

  // ---- input ----
  function pointerX(e) { return e.clientX; }
  game.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    if (P.paused || L.blocked) return;
    try { game.setPointerCapture(e.pointerId); } catch (err) { /* ignore */ }
    holding = true; targetX = pointerX(e);
    pending = performance.now(); lastShot = 0; armHint();
  });
  game.addEventListener('pointermove', function (e) { if (holding) targetX = pointerX(e); });
  function up() { holding = false; }
  game.addEventListener('pointerup', up);
  game.addEventListener('pointercancel', up);

  function fire(t) {
    if (shots.length >= 3) return;
    var el = shotEls.filter(function (x) { return shots.every(function (sh) { return sh.el !== x; }); })[0];
    if (!el) return;
    shots.push({ x: shipX, y: shipTop, el: el });
    el.style.display = 'block';
    lastShot = t; lastFire = t; pew();
  }

  // ---- loop ----
  function loop(t) {
    raf = requestAnimationFrame(loop);
    var dt = Math.min(0.05, (t - (lastT || t)) / 1000); lastT = t;
    targetX = Math.max(ss / 2, Math.min(W - ss / 2, targetX));
    shipX += (targetX - shipX) * Math.min(1, dt * 14);
    setShip();
    if (pending && !busy && (Math.abs(targetX - shipX) < ss / 3 || t - pending > 250)) { pending = 0; fire(t); }
    else if (holding && !pending && !busy && t - lastShot > 380) fire(t);
    var speed = H * 1.4;
    for (var i = shots.length - 1; i >= 0; i--) {
      var sh = shots[i];
      sh.y -= speed * dt;
      var hit = sh.y < -24 ? true : collide(sh);
      if (hit) { sh.el.style.display = 'none'; shots.splice(i, 1); }
      else sh.el.style.transform = 'translate(' + sh.x + 'px,' + sh.y + 'px)';
    }
  }
  function collide(sh) {
    for (var i = 0; i < aliens.length; i++) {
      var a = aliens[i];
      if (!a.alive) continue;
      var ax = gx + a.c * sp, ay = gy + a.r * vsp;
      if (sh.x > ax - 4 && sh.x < ax + s + 4 && sh.y < ay + s && sh.y + 22 > ay) { kill(a); return true; }
    }
    return false;
  }
  function kill(a) {
    a.alive = false; alive--;
    a.el.classList.add('pop');
    var r = game.getBoundingClientRect();
    sparkle(r.left + gx + a.c * sp + s / 2, r.top + gy + a.r * vsp + s / 2, 6);
    popSound();
    if (!alive) waveDone();
  }

  function stepMs() { return Math.max(320, 820 - waves * 35) * (0.35 + 0.65 * alive / total); }
  function step() {
    stepT = setTimeout(step, stepMs());
    if (busy || !alive) return;
    var minC = cols, maxC = -1, maxR = -1;
    aliens.forEach(function (a) { if (a.alive) { minC = Math.min(minC, a.c); maxC = Math.max(maxC, a.c); maxR = Math.max(maxR, a.r); } });
    var dx = Math.max(6, Math.round(s * 0.35)), left = gx + minC * sp + dir * dx, right = gx + maxC * sp + s + dir * dx;
    if (left < 4 || right > W - 4) { dir = -dir; gy += Math.round(s * 0.5); }
    else gx += dir * dx;
    frame = !frame; swarmEl.classList.toggle('f2', frame);
    swarmEl.classList.remove('jump');
    if (gy + maxR * vsp + s >= shipTop - 4) {     // reached the rocket: bounce back up, no penalty
      gy = 6; swarmEl.classList.add('jump'); whoosh();
      ship.classList.remove('wobble'); void ship.offsetWidth; ship.classList.add('wobble');
    } else march();
    setSwarm();
  }

  function startRun() {
    if (running || P.paused || L.blocked || !P.started) return;
    running = true; lastT = 0;
    raf = requestAnimationFrame(loop);
    stepT = setTimeout(step, stepMs());
    armHint();
  }
  function stopRun() {
    running = false; holding = false; pending = 0;
    cancelAnimationFrame(raf); clearTimeout(stepT); clearTimeout(hintTimer);
    timers.forEach(clearTimeout); timers = [];
    if (busy) { busy = false; drawStars(); newWave(); }
  }

  // ---- rewards ----
  function sparkle(x, y, n) {
    for (var i = 0; i < n; i++) (function (i) {
      var e = document.createElement('div');
      e.className = 'spark'; e.style.left = (x - 8) + 'px'; e.style.top = (y - 8) + 'px';
      document.body.appendChild(e);
      var a = i / n * Math.PI * 2, r = 40 + Math.random() * 30;
      requestAnimationFrame(function () { requestAnimationFrame(function () {
        e.style.transform = 'translate(' + Math.cos(a) * r + 'px,' + Math.sin(a) * r + 'px) rotate(180deg)';
        e.style.opacity = '0';
      }); });
      setTimeout(function () { e.remove(); }, 700);
    })(i);
  }
  function drawStars() {
    starsEl.innerHTML = '';
    for (var i = 0; i < 5; i++) { var e = document.createElement('div'); e.className = 'star' + (i < stars ? ' on' : ''); starsEl.appendChild(e); }
  }
  function waveDone() {
    busy = true; waves++; stars++; P.save('waves', waves);
    later(yay, 200);
    if (stars >= 5) {
      drawStars(); stars = 0; P.save('stars', 0);
      later(function () {
        fanfare();
        for (var k = 0; k < 6; k++) later(function () { sparkle(Math.random() * window.innerWidth, 90 + Math.random() * (window.innerHeight - 150), 8); }, k * 220);
      }, 700);
      later(function () { drawStars(); newWave(); }, 3300);
    } else {
      P.save('stars', stars); drawStars();
      later(newWave, 1600);
    }
  }

  // ---- idle hint: pulse a ring around the rocket ----
  function armHint() {
    clearTimeout(hintTimer);
    if (hints >= 2 || !running) return;
    hintTimer = setTimeout(function () {
      if (!running || busy || holding) { armHint(); return; }
      ship.classList.remove('hint'); void ship.offsetWidth; ship.classList.add('hint');
      P.tone(880, { dur: 0.1, vol: 0.1 }); P.tone(988, { delay: 0.12, dur: 0.12, vol: 0.1 });
      hints++; armHint();
    }, 10000);
  }

  P.onStart(startRun);
  P.onPause(stopRun);
  P.onResume(startRun);
  L.onBlock(stopRun);
  L.onUnblock(startRun);

  newWave();
  drawStars();
})();
