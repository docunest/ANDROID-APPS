// Skiemenys — a calm lesson: every consonant + vowel syllable in order, as an uppercase + lowercase
// pair (BA ba, CA ca, ČA ča … ŽŪ žū). All 20 consonants with the first vowel (A), then all with the
// second vowel (Ą), and so on up to Ū: 240 syllables. The big arrows step forward / back; the strips
// underneath show which vowel and which consonant we're on. No score, nothing to get wrong.
// The place is remembered, so closing the game and coming back continues where it stopped.
(function () {
  'use strict';
  var P = window.Ponas;

  var CONS = 'B C Č D F G H J K L M N P R S Š T V Z Ž'.split(' ');
  var VOWS = 'A Ą E Ę Ė I Į Y O U Ų Ū'.split(' ');
  var TOTAL = CONS.length * VOWS.length;
  var SCALE = [523, 587, 659, 784, 880];

  var cardEl = document.getElementById('card');
  var upEl = document.getElementById('up');
  var loEl = document.getElementById('lo');
  var vowsEl = document.getElementById('vows');
  var consEl = document.getElementById('cons');

  var n = P.load('pos', 0);
  if (typeof n !== 'number' || n < 0 || n >= TOTAL) n = 0;

  var vowCells = VOWS.map(function (V) {
    var c = document.createElement('div'); c.className = 'cell vow'; c.textContent = V;
    vowsEl.appendChild(c); return c;
  });
  var consCells = CONS.map(function (C) {
    var c = document.createElement('div'); c.className = 'cell'; c.textContent = C + C.toLowerCase();
    consEl.appendChild(c); return c;
  });

  function parts() { return { v: Math.floor(n / CONS.length), c: n % CONS.length }; }

  function chime() {
    var p = parts();
    P.tone(SCALE[p.c % 5], { dur: 0.2, type: 'triangle', vol: 0.2 });
    P.tone(SCALE[p.v % 5] * 1.25, { delay: 0.16, dur: 0.28, type: 'triangle', vol: 0.2 });
  }
  function fanfare() {
    [523, 659, 784, 1047, 784, 1047].forEach(function (f, k) {
      P.tone(f, { delay: k * 0.12, dur: 0.26, type: 'triangle', vol: 0.18 });
    });
  }

  function show(animate) {
    var p = parts(), C = CONS[p.c], V = VOWS[p.v];
    upEl.textContent = C + V;
    loEl.textContent = (C + V).toLowerCase();
    vowCells.forEach(function (c, k) { c.className = 'cell vow' + (k < p.v ? ' done' : (k === p.v ? ' cur' : '')); });
    consCells.forEach(function (c, k) { c.className = 'cell' + (k < p.c ? ' done' : (k === p.c ? ' cur' : '')); });
    if (animate) { cardEl.classList.remove('pop'); void cardEl.offsetWidth; cardEl.classList.add('pop'); }
    P.save('pos', n);
  }

  function go(step) {
    if (P.paused) return;
    var wrapped = false;
    n += step;
    if (n >= TOTAL) { n = 0; wrapped = true; }    // after ŽŪ: a little fanfare, back to BA
    if (n < 0) n = TOTAL - 1;
    show(true);
    if (wrapped) fanfare(); else chime();
  }

  function press(id, fn) {
    document.getElementById(id).addEventListener('pointerdown', function (e) { e.preventDefault(); fn(); });
  }
  press('next', function () { go(1); });
  press('prev', function () { go(-1); });
  cardEl.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    if (P.paused) return;
    show(true); chime();
  });

  show(false);
  P.onStart(chime);
})();
