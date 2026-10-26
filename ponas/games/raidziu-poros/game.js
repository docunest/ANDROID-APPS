// Raidžių poros — a big letter is shown (either UPPER or lower case). Tap the matching letter,
// in the other case, among 3 choices. No words, no reading: pure letter-shape matching.
//  - Mode button (bottom-left): cycles Mixed -> A->a only -> a->A only -> Mixed.
//  - Stats button (bottom-right, parent-only): shown count, average and last-5-moving-average
//    correct response time per direction, and the letters missed most.
//  - Letters unlock in groups as the child gets more right: common shapes first, look-alike
//    letters (S/Š, Z/Ž, C/Č, vowels with marks) mixed in later as harder distractors. All 32
//    Lithuanian letters are covered by the time everything is unlocked.
//  - A letter that's been missed more often shows up more: its pick-weight grows ~20% per past
//    mistake, so practice naturally leans towards what's hard.
//  - Wrong tap: soft shake + tone, try again. Right tap: sparkle + happy tones + a star.
//  - 5 stars -> short celebration, then keep going. Progress is saved.
(function () {
  'use strict';
  var P = window.Ponas;

  var GROUPS = [
    { from: 0, letters: 'A O I U E M T K' },
    { from: 10, letters: 'S N R L P D V G' },
    { from: 24, letters: 'B Y J H F C Z' },
    { from: 40, letters: 'Č Š Ž Ę Ė Į Ų Ū Ą' }
  ];
  var TRAPS = [
    ['A', 'Ą'], ['E', 'Ę', 'Ė'], ['I', 'Į', 'Y'], ['U', 'Ų', 'Ū'],
    ['S', 'Š'], ['Z', 'Ž'], ['C', 'Č']
  ];
  var MODES = ['mixed', 'u2l', 'l2u'];
  var MODE_LABEL = { mixed: 'Aa⇄aA', u2l: 'A→a', l2u: 'a→A' };
  var MISTAKE_WEIGHT = 1.2;   // pick-chance grows ~20% per past mistake on that letter
  var MISTAKE_CAP = 15;       // cap so a very-missed letter doesn't crowd out everything else

  var starsEl = document.getElementById('stars');
  var promptEl = document.getElementById('prompt');
  var promptLetterEl = document.getElementById('promptLetter');
  var optsEl = document.getElementById('opts');
  var modeBtn = document.getElementById('modeBtn');
  var statsBtn = document.getElementById('statsBtn');
  var statsOv = document.getElementById('statsOv');
  var statsClose = document.getElementById('statsClose');
  var statU2lEl = document.getElementById('statU2l');
  var statL2uEl = document.getElementById('statL2u');
  var hardLettersEl = document.getElementById('hardLetters');

  var correct = P.load('correct', 0);
  var stars = P.load('stars', 0);
  var recent = P.load('recent', []);
  var mistakes = P.load('mistakes', {});                 // { LETTER: count }
  var mode = P.load('mode', 'mixed');
  var dirStats = P.load('dirStats', {
    u2l: { shown: 0, correctCount: 0, totalMs: 0, last5: [] },
    l2u: { shown: 0, correctCount: 0, totalMs: 0, last5: [] }
  });

  var round = null, timers = [], idleTimer = 0, busy = false, statsOpen = false, shownAt = 0;

  function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
  function clearTimers() { timers.forEach(clearTimeout); timers = []; clearTimeout(idleTimer); }
  function rnd(n) { return Math.floor(Math.random() * n); }
  function shuffle(a) { for (var i = a.length - 1; i > 0; i--) { var j = rnd(i + 1), t = a[i]; a[i] = a[j]; a[j] = t; } return a; }

  function unlocked() {
    var set = [];
    GROUPS.forEach(function (g) { if (correct >= g.from) set = set.concat(g.letters.split(' ')); });
    return set;
  }

  function trapsFor(letter) {
    for (var i = 0; i < TRAPS.length; i++) if (TRAPS[i].indexOf(letter) >= 0) return TRAPS[i];
    return null;
  }

  // Letters missed more often are weighted ~20% heavier per mistake (capped), so practice
  // naturally leans towards what's still shaky.
  function weightOf(letter) {
    var m = Math.min(mistakes[letter] || 0, MISTAKE_CAP);
    return Math.pow(MISTAKE_WEIGHT, m);
  }

  function pickWeighted(list) {
    var weights = list.map(weightOf);
    var total = weights.reduce(function (a, b) { return a + b; }, 0);
    var r = Math.random() * total;
    for (var i = 0; i < list.length; i++) {
      r -= weights[i];
      if (r <= 0) return list[i];
    }
    return list[list.length - 1];
  }

  function makeRound() {
    var letters = unlocked();
    var pool = letters.filter(function (l) { return recent.indexOf(l) < 0; });
    if (!pool.length) pool = letters;
    var target = pickWeighted(pool);
    recent.push(target);
    if (recent.length > 6) recent.shift();

    var dir;
    if (mode === 'u2l') dir = 'u2l';
    else if (mode === 'l2u') dir = 'l2u';
    else dir = Math.random() < 0.5 ? 'u2l' : 'l2u';
    var toLower = dir === 'u2l';                    // u2l: prompt is UPPER, answer is lower
    var promptCase = toLower ? target : target.toLowerCase();
    var answerCase = toLower ? target.toLowerCase() : target;

    var others = shuffle(letters.filter(function (l) { return l !== target; }));
    var trap = trapsFor(target);
    var picks = [];
    if (trap) {
      var t = trap.filter(function (l) { return l !== target && letters.indexOf(l) >= 0; });
      if (t.length) picks.push(t[rnd(t.length)]);
    }
    while (picks.length < 2 && others.length) {
      var cand = others.pop();
      if (picks.indexOf(cand) < 0) picks.push(cand);
    }
    var wrongCased = picks.map(function (l) { return toLower ? l.toLowerCase() : l; });
    var opts = shuffle([answerCase].concat(wrongCased));

    dirStats[dir].shown++;
    save();

    return { target: target, dir: dir, promptCase: promptCase, answerCase: answerCase, opts: opts };
  }

  function draw() {
    promptLetterEl.textContent = round.promptCase;
    optsEl.innerHTML = '';
    round.opts.forEach(function (L) {
      var b = document.createElement('div');
      b.className = 'opt';
      b.innerHTML = '<span>' + L + '</span>';
      b.addEventListener('pointerdown', function (e) { e.preventDefault(); tap(b, L); });
      optsEl.appendChild(b);
    });
    shownAt = Date.now();
  }

  function drawStars() {
    starsEl.innerHTML = '';
    for (var i = 0; i < 5; i++) {
      var s = document.createElement('div');
      s.className = 'star' + (i < stars ? ' on' : '');
      starsEl.appendChild(s);
    }
  }

  function drawModeBtn() { modeBtn.textContent = MODE_LABEL[mode]; }

  function armIdle() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function () {
      if (busy || P.paused || statsOpen) return;
      promptEl.classList.remove('nudge'); void promptEl.offsetWidth; promptEl.classList.add('nudge');
    }, 12000);
  }

  function newRound() {
    clearTimers();
    busy = false;
    round = makeRound();
    draw();
    armIdle();
  }

  function tap(el, L) {
    if (busy || P.paused || statsOpen) return;
    armIdle();
    if (L === round.answerCase) {
      busy = true;
      clearTimeout(idleTimer);
      el.classList.add('ok');
      yay();
      var r = el.getBoundingClientRect();
      sparkle(r.left + r.width / 2, r.top + r.height / 2);

      var ms = Date.now() - shownAt;
      var ds = dirStats[round.dir];
      ds.correctCount++;
      ds.totalMs += ms;
      ds.last5.push(ms);
      if (ds.last5.length > 5) ds.last5.shift();

      correct++; stars++;
      drawStars();
      save();
      if (stars >= 5) { stars = 0; drawStars(); save(); later(celebrate, 500); later(newRound, 3100); }
      else later(newRound, 1600);
    } else {
      mistakes[round.target] = (mistakes[round.target] || 0) + 1;
      save();
      el.classList.add('bad', 'shake');
      oops();
      later(function () { el.classList.remove('shake'); }, 400);
      later(function () { el.classList.remove('bad'); }, 900);
    }
  }

  function save() {
    P.save('correct', correct); P.save('stars', stars); P.save('recent', recent);
    P.save('mistakes', mistakes); P.save('mode', mode); P.save('dirStats', dirStats);
  }

  function yay() { [523, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: i * 0.09, dur: 0.22, type: 'triangle', vol: 0.2 }); }); }
  function oops() { P.tone(260, { dur: 0.18, type: 'triangle', vol: 0.2 }); P.tone(200, { delay: 0.16, dur: 0.25, type: 'triangle', vol: 0.2 }); }
  function celebrate() { [523, 659, 784, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: i * 0.13, dur: 0.28, type: 'square', vol: 0.07 }); }); }

  function sparkle(x, y) {
    for (var i = 0; i < 8; i++) {
      (function (i) {
        var s = document.createElement('div');
        s.className = 'spark';
        s.style.left = (x - 9) + 'px'; s.style.top = (y - 9) + 'px';
        document.body.appendChild(s);
        var a = i / 8 * Math.PI * 2, r = 55 + Math.random() * 25;
        requestAnimationFrame(function () { requestAnimationFrame(function () {
          s.style.transform = 'translate(' + Math.cos(a) * r + 'px,' + Math.sin(a) * r + 'px) scale(.4)';
          s.style.opacity = '0';
        }); });
        setTimeout(function () { s.remove(); }, 700);
      })(i);
    }
  }

  // ---- mode button ----
  modeBtn.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    if (P.paused || statsOpen) return;
    mode = MODES[(MODES.indexOf(mode) + 1) % MODES.length];
    drawModeBtn();
    save();
    newRound();
  });

  // ---- stats overlay (parent-only, behind its own button) ----
  function fmtSec(ms) { return (ms / 1000).toFixed(1) + 's'; }
  function dirSummary(ds) {
    var el = document.createElement('div');
    var avg = ds.correctCount ? ds.totalMs / ds.correctCount : null;
    var last5 = ds.last5.length ? ds.last5.reduce(function (a, b) { return a + b; }, 0) / ds.last5.length : null;
    el.innerHTML =
      'Rodyta: ' + ds.shown + '<br>' +
      (avg !== null ? fmtSec(avg) : '—') + '<small>vid. laikas</small>' +
      (last5 !== null ? fmtSec(last5) : '—') + '<small>paskutinių 5 vid.</small>';
    return el.innerHTML;
  }

  function openStats() {
    statsOpen = true;
    clearTimeout(idleTimer);
    statU2lEl.innerHTML = dirSummary(dirStats.u2l);
    statL2uEl.innerHTML = dirSummary(dirStats.l2u);
    var pairs = Object.keys(mistakes).map(function (k) { return [k, mistakes[k]]; })
      .filter(function (p) { return p[1] > 0; })
      .sort(function (a, b) { return b[1] - a[1]; })
      .slice(0, 6);
    hardLettersEl.textContent = pairs.length ? pairs.map(function (p) { return p[0]; }).join('  ') : '—';
    statsOv.classList.remove('hidden');
  }
  function closeStats() {
    statsOpen = false;
    statsOv.classList.add('hidden');
    armIdle();
  }
  statsBtn.addEventListener('pointerdown', function (e) { e.preventDefault(); if (!P.paused) openStats(); });
  statsClose.addEventListener('pointerdown', function (e) { e.preventDefault(); closeStats(); });
  statsOv.addEventListener('pointerdown', function (e) { if (e.target === statsOv) closeStats(); });

  P.onPause(function () { clearTimers(); });
  P.onResume(function () { if (!busy && !statsOpen) armIdle(); });

  drawStars();
  drawModeBtn();
  round = makeRound();
  draw();                       // first round sits behind the ▶ overlay
  P.onStart(armIdle);
})();
