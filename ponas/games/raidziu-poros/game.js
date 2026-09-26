// Raidžių poros — a big letter is shown (either UPPER or lower case). Tap the matching letter,
// in the other case, among 3 choices. No words, no reading: pure letter-shape matching, both
// directions (Aa and aA), building on what "Raidžių memory" already teaches.
//  - Letters unlock in groups as the child gets more right: common shapes first, look-alike
//    letters (S/Š, Z/Ž, C/Č, vowels with marks) mixed in later as harder distractors.
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

  var starsEl = document.getElementById('stars');
  var promptEl = document.getElementById('prompt');
  var promptLetterEl = document.getElementById('promptLetter');
  var optsEl = document.getElementById('opts');

  var correct = P.load('correct', 0);
  var stars = P.load('stars', 0);
  var recent = P.load('recent', []);

  var round = null, timers = [], idleTimer = 0, busy = false;

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

  function makeRound() {
    var letters = unlocked();
    var pool = letters.filter(function (l) { return recent.indexOf(l) < 0; });
    if (!pool.length) pool = letters;
    var target = pool[rnd(pool.length)];
    recent.push(target);
    if (recent.length > 6) recent.shift();

    var toLower = Math.random() < 0.5;              // which direction this round
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

    return { promptCase: promptCase, answerCase: answerCase, opts: opts };
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
  }

  function drawStars() {
    starsEl.innerHTML = '';
    for (var i = 0; i < 5; i++) {
      var s = document.createElement('div');
      s.className = 'star' + (i < stars ? ' on' : '');
      starsEl.appendChild(s);
    }
  }

  function armIdle() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function () {
      if (busy || P.paused) return;
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
    if (busy || P.paused) return;
    armIdle();
    if (L === round.answerCase) {
      busy = true;
      clearTimeout(idleTimer);
      el.classList.add('ok');
      yay();
      var r = el.getBoundingClientRect();
      sparkle(r.left + r.width / 2, r.top + r.height / 2);
      correct++; stars++;
      drawStars();
      save();
      if (stars >= 5) { stars = 0; drawStars(); save(); later(celebrate, 500); later(newRound, 3100); }
      else later(newRound, 1600);
    } else {
      el.classList.add('bad', 'shake');
      oops();
      later(function () { el.classList.remove('shake'); }, 400);
      later(function () { el.classList.remove('bad'); }, 900);
    }
  }

  function save() { P.save('correct', correct); P.save('stars', stars); P.save('recent', recent); }

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

  P.onPause(function () { clearTimers(); });
  P.onResume(function () { if (!busy) armIdle(); });

  drawStars();
  round = makeRound();
  draw();                       // first round sits behind the ▶ overlay
  P.onStart(armIdle);
})();
