/* Garsai — Montessori sound cylinders, with real recordings (Wikimedia Commons, see credits.json).
 * Pairs round: closed cylinders; tap one to hear it, tap another — same sound = they open and
 * show what made it. Loudness round: four cylinders with the same sound at different loudness;
 * tap them from the quietest to the loudest. No reading, no timer. */
(function () {
  'use strict';
  var P = window.Ponas, K = window.K;
  var SOUNDS = [['suo', '🐕'], ['cikada', '🦗'], ['jura', '🌊'], ['varpas', '🔔'],
    ['lietus', '🌧️'], ['traukinys', '🚂'], ['gaidys', '🐓'], ['varle', '🐸'],
    ['avis', '🐑'], ['karve', '🐄'], ['arklys', '🐴'], ['ozka', '🐐']];
  var LOUD = ['jura', 'lietus', 'varpas', 'cikada'];        // steady sounds: easy to compare loudness
  var GAINS = [0.1, 0.28, 0.6, 1];
  var grid = document.getElementById('grid');
  var roundNo = P.load('round', 0), busy = false, started = false;
  var mode, boxes, sel, found, nextLoud, misses;

  function shuffle(a) {
    a = a.slice();
    for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)), t = a[i]; a[i] = a[j]; a[j] = t; }
    return a;
  }

  function layout() {
    if (!boxes) return;
    var n = boxes.length, port = P.orientation() === 'portrait';
    var cols = n === 4 ? (port ? 2 : 4) : n === 6 ? (port ? 2 : 3) : n === 8 ? (port ? 2 : 4) : (port ? 3 : 4);
    var rows = Math.ceil(n / cols);
    var W = innerWidth - 24, H = innerHeight - 72 - 20;
    var b = Math.floor(Math.min((W - (cols - 1) * 12) / cols, (H - (rows - 1) * 12) / rows, 150));
    grid.style.gridTemplateColumns = 'repeat(' + cols + ', ' + b + 'px)';
    grid.style.setProperty('--b', b + 'px');
  }

  function makeBox(data) {
    var e = document.createElement('div');
    e.className = 'box';
    e.innerHTML = '<div class="cyl"></div><div class="cap"></div><div class="face"></div><div class="dots"></div>';
    var bx = { el: e, data: data, done: false };
    e.addEventListener('pointerdown', function (ev) { ev.preventDefault(); if (!P.paused && started && !busy) tap(bx); });
    return bx;
  }
  function play(bx, gain, done) {
    bx.el.classList.remove('play'); void bx.el.offsetWidth; bx.el.classList.add('play');
    K.say('s-' + bx.data.s, done, { gain: gain });
  }

  // ---------------------------------------------------------------- rounds
  function newRound() {
    busy = false; sel = null; found = 0; misses = 0; grid.innerHTML = '';
    mode = (roundNo >= 3 && roundNo % 3 === 2) ? 'loud' : 'pairs';
    if (mode === 'pairs') {
      var pairs = roundNo < 3 ? 3 : roundNo < 7 ? 4 : 6;
      var pick = shuffle(SOUNDS).slice(0, pairs), list = [];
      pick.forEach(function (s) { list.push({ s: s[0], pic: s[1] }, { s: s[0], pic: s[1] }); });
      boxes = shuffle(list).map(makeBox);
    } else {
      var s = LOUD[Math.floor(Math.random() * LOUD.length)];
      boxes = shuffle(GAINS.map(function (g, i) { return { s: s, gain: g, rank: i }; })).map(makeBox);
      boxes.forEach(function (bx) { bx.el.classList.add('loud'); });
      nextLoud = 0;
    }
    boxes.forEach(function (bx) { bx.el.querySelector('.face').textContent = bx.data.pic || ''; grid.appendChild(bx.el); });
    layout();
    if (started) prompt();
  }
  function prompt() {
    K.say(mode === 'pairs' ? 'intro-pairs' : 'intro-loud');
    K.hint(function () { K.say(mode === 'pairs' ? 'intro-pairs' : 'intro-loud'); });
  }

  function tap(bx) {
    if (bx.done) { if (mode === 'pairs') K.say(['s-' + bx.data.s, 'name-' + bx.data.s]); return; }
    if (mode === 'loud') return tapLoud(bx);
    if (sel === bx) { play(bx, 1); return; }                       // listen again
    if (!sel) { sel = bx; bx.el.classList.add('sel'); play(bx, 1); return; }
    var a = sel; sel = null;
    bx.el.classList.add('sel');
    if (a.data.s === bx.data.s) {
      busy = true;
      play(bx, 1, function () {
        a.done = bx.done = true;
        a.el.classList.add('done'); bx.el.classList.add('done'); K.ok();
        found++;
        var last = found * 2 === boxes.length;
        K.say(['match', 'name-' + bx.data.s].concat(last ? ['done-pairs'] : []), function () { busy = false; if (last) finish(); });
        if (last) busy = true;
      });
    } else {
      play(bx, 1);
      K.later(function () {          // not the same: both close again (unless tapped anew meanwhile)
        [a, bx].forEach(function (x) { if (sel !== x) x.el.classList.remove('sel'); });
      }, 1000);
    }
  }

  function tapLoud(bx) {
    play(bx, bx.data.gain);
    if (bx.data.rank !== nextLoud) {
      misses++; K.shake(bx.el);
      if (misses >= 2) { misses = 0; K.later(function () { K.say('notyet-loud'); }, 1400); }
      return;
    }
    misses = 0;
    bx.done = true; bx.el.classList.add('locked');
    var dots = bx.el.querySelector('.dots');
    for (var i = 0; i <= nextLoud; i++) dots.appendChild(document.createElement('i'));
    nextLoud++;
    if (nextLoud === boxes.length) {
      busy = true; K.noHint();
      K.later(function () { K.ok(); K.say('done-loud', finish); }, 1600);
    }
  }

  function finish() {
    busy = true; K.noHint();
    roundNo++; P.save('round', roundNo);
    if (K.addStar()) { K.say('bravo'); K.celebrate(function () { K.later(newRound, 400); }); }
    else K.later(newRound, 900);
  }

  K.stars(document.getElementById('stars'));
  P.onResize(layout);
  P.onStart(function () { started = true; prompt(); });
  P.onResume(function () { if (busy) newRound(); });
  newRound();
})();
