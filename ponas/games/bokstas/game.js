/* Bokštas — Montessori Pink Tower, Brown Stair (boards) and Colour Box 3 (shades).
 * The child taps the biggest / thickest / darkest piece left; it flies onto the build.
 * A wrong pick shakes back with a soft sound (control of error); after 2 misses the right one pulses. */
(function () {
  'use strict';
  var P = window.Ponas, K = window.K;
  var tray = document.getElementById('tray'), stack = document.getElementById('stack'), build = document.getElementById('build');

  // Round plan: easy towers first, then boards and colours mixed in, then a cycle of the full sets.
  var PLAN = [['tower', 5], ['tower', 5], ['tower', 7], ['tower', 7], ['boards', 6], ['colours', 5],
              ['tower', 10], ['boards', 10], ['colours', 7]];
  var CYCLE_FROM = 6;
  var HUES = [[0, 70], [45, 90], [215, 70], [130, 50], [28, 90], [280, 50], [20, 45], [200, 8], [330, 70]];

  var round = P.load('round', 0);
  var kind, items, next, misses, busy = false, started = false;

  function plan() {
    var i = round < PLAN.length ? round : CYCLE_FROM + (round - CYCLE_FROM) % (PLAN.length - CYCLE_FROM);
    return PLAN[i];
  }
  function values(n) {          // which of the ten sizes are in play: spread out when there are few
    var v = [];
    for (var i = 0; i < n; i++) v.push(Math.round(1 + i * 9 / (n - 1)));
    return v;
  }
  function shuffle(a) {
    for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)), t = a[i]; a[i] = a[j]; a[j] = t; }
    return a;
  }

  // size of a piece at "unit" u: [width, height]
  function dims(v, u) {
    if (kind === 'tower') { var s = u * (v + 2); return [s, s]; }
    if (kind === 'boards') return [u * 13, u * (v * 0.75 + 0.8)];
    return [u * 7, u * 5];
  }

  function newRound() {
    var p = plan();
    kind = p[0];
    var vs = values(p[1]);
    var hue = HUES[Math.floor(Math.random() * HUES.length)];
    items = vs.map(function (v, i) {
      var el = document.createElement('div');
      el.className = 'it ' + (kind === 'tower' ? 'cube' : kind === 'boards' ? 'board' : 'tab');
      if (kind === 'colours') {   // v=10 darkest → lightness 28 %, v=1 lightest → 88 %
        el.style.background = 'hsl(' + hue[0] + ',' + hue[1] + '%,' + Math.round(88 - (v - 1) * 60 / 9) + '%)';
      }
      var it = { v: v, el: el, placed: false };
      el.addEventListener('pointerdown', function (e) { e.preventDefault(); tap(it); });
      return it;
    });
    next = items.slice().sort(function (a, b) { return b.v - a.v; });   // biggest / thickest / darkest first
    misses = 0;
    stack.innerHTML = ''; tray.innerHTML = '';
    stack.className = kind === 'colours' ? 'row' : '';
    shuffle(items).forEach(function (it) { tray.appendChild(it.el); });
    layout();
    if (started) prompt('intro');
  }

  // Lay the loose pieces in the tray (shelf packing, shrinking until everything fits) and size the build.
  var trayU = 10, buildU = 10;
  function layout() {
    if (!items) return;
    var W = tray.clientWidth - 16, H = tray.clientHeight - 16, gap = 14;
    var loose = items.filter(function (it) { return !it.placed; });
    for (var u = 16; u >= 3; u -= 0.5) {
      var x = 0, y = 0, rowH = 0, pos = [], ok = true;
      for (var i = 0; i < loose.length; i++) {
        var d = dims(loose[i].v, u);
        if (x + d[0] > W) { x = 0; y += rowH + gap; rowH = 0; }
        if (d[0] > W) { ok = false; break; }
        pos.push([x, y, d]); x += d[0] + gap; rowH = Math.max(rowH, d[1]);
      }
      if (ok && y + rowH <= H) break;
    }
    trayU = u;
    // centre the block vertically, rows already spread across the width
    var used = 0; pos.forEach(function (p) { used = Math.max(used, p[1] + p[2][1]); });
    var oy = 8 + (H - used) / 2;
    var rowEnd = {};   // centre each row horizontally
    pos.forEach(function (p) { rowEnd[p[1]] = Math.max(rowEnd[p[1]] || 0, p[0] + p[2][0]); });
    loose.forEach(function (it, i) {
      var p = pos[i], el = it.el;
      p = [p[0] + (W - rowEnd[p[1]]) / 2, p[1], p[2]];
      el.style.width = p[2][0] + 'px'; el.style.height = p[2][1] + 'px';
      el.style.left = (8 + p[0]) + 'px'; el.style.top = (oy + p[1]) + 'px';
    });
    // the build keeps the true size ratio between pieces, scaled to fit its area
    var BW = build.clientWidth - 20, BH = build.clientHeight - 34, all = items.map(function (it) { return it.v; });
    for (var b = 16; b >= 2; b -= 0.25) {
      var tw = 0, th = 0;
      all.forEach(function (v) {
        var d = dims(v, b);
        if (kind === 'colours') { tw += d[0] + 4; th = Math.max(th, d[1]); } else { th += d[1]; tw = Math.max(tw, d[0]); }
      });
      if (tw <= BW && th <= BH) break;
    }
    buildU = b;
    items.forEach(function (it) {
      if (!it.placed) return;
      var d = dims(it.v, buildU);
      it.el.style.width = d[0] + 'px'; it.el.style.height = d[1] + 'px';
    });
  }

  function tap(it) {
    if (P.paused || busy || it.placed || !started) return;
    var want = next[0];
    if (it !== want) {
      misses++;
      K.oops(); K.shake(it.el);
      K.say('more-' + kind);
      if (misses >= 2) K.pulse(want.el);
      return;
    }
    misses = 0;
    next.shift();
    place(it);
    K.ok();
    var count = items.length - next.length;
    if (!next.length) { finish(count); return; }
    K.say('n' + count);
    K.hint(function () { K.say('next-' + kind); K.pulse(next[0].el); });
  }

  // FLIP: remember where it was, move it to the build, animate from the old spot
  function place(it) {
    var el = it.el, a = el.getBoundingClientRect();
    it.placed = true;
    el.classList.remove('kpulse', 'kshake');
    var d = dims(it.v, buildU);
    el.style.width = d[0] + 'px'; el.style.height = d[1] + 'px';
    stack.appendChild(el);
    var b = el.getBoundingClientRect();
    el.style.transform = 'translate(' + (a.left - b.left) + 'px,' + (a.top - b.top) + 'px) scale(' + (a.width / b.width) + ',' + (a.height / b.height) + ')';
    el.style.transformOrigin = '0 0';
    void el.offsetWidth;
    el.classList.add('fly');
    el.style.transform = '';
    K.later(function () { el.classList.remove('fly'); }, 500);
  }

  function finish(count) {
    busy = true; K.noHint();
    round++; P.save('round', round);
    var five = K.addStar();
    K.say(['n' + count, 'done-' + kind], function () {
      if (five) { K.say('bravo'); K.celebrate(function () { K.later(again, 600); }); }
      else K.later(again, 700);
    });
  }
  function again() { busy = false; newRound(); }

  function prompt() {
    K.say('intro-' + kind);
    K.hint(function () { K.say('next-' + kind); if (next[0]) K.pulse(next[0].el); });
  }

  K.stars(document.getElementById('stars'));
  P.onResize(function () { layout(); });
  P.onStart(function () { started = true; prompt(); });
  P.onResume(function () { if (!busy && started) K.hint(function () { K.say('next-' + kind); }); else if (busy) again(); });
  newRound();
})();
