// Abėcėlė — a calm lesson: every Lithuanian letter in alphabet order, as an uppercase + lowercase
// pair (Aa, Ąą, Bb … Žž). The big arrows step forward / back through the alphabet and the current
// pair is shown large. The strip underneath shows all 32 pairs with the current one lit.
// Nothing to get wrong: no score, no stars.
(function () {
  'use strict';
  var P = window.Ponas;

  var LETTERS = 'A Ą B C Č D E Ę Ė F G H I Į Y J K L M N O P R S Š T U Ų Ū V Z Ž'.split(' ');
  var SCALE = [523, 587, 659, 784, 880];   // gentle pentatonic: any order sounds nice

  var cardEl = document.getElementById('card');
  var upEl = document.getElementById('up');
  var loEl = document.getElementById('lo');
  var stripEl = document.getElementById('strip');
  var i = 0;
  var cells = [];

  LETTERS.forEach(function (L) {
    var c = document.createElement('div');
    c.className = 'cell';
    c.textContent = L + L.toLowerCase();
    stripEl.appendChild(c);
    cells.push(c);
  });

  function chime() {
    var f = SCALE[i % SCALE.length];
    P.tone(f, { dur: 0.22, type: 'triangle', vol: 0.2 });
    P.tone(f * 1.25, { delay: 0.16, dur: 0.28, type: 'triangle', vol: 0.2 });
  }
  function fanfare() {
    [523, 659, 784, 1047, 784, 1047].forEach(function (f, k) {
      P.tone(f, { delay: k * 0.12, dur: 0.26, type: 'triangle', vol: 0.18 });
    });
  }

  function show() {
    var L = LETTERS[i];
    upEl.textContent = L;
    loEl.textContent = L.toLowerCase();
    cells.forEach(function (c, k) {
      c.className = 'cell' + (k < i ? ' done' : (k === i ? ' cur' : ''));
    });
    cardEl.classList.remove('pop'); void cardEl.offsetWidth; cardEl.classList.add('pop');
  }

  function go(step) {
    if (P.paused) return;
    var wrapped = false;
    i += step;
    if (i >= LETTERS.length) { i = 0; wrapped = true; }   // after Žž, a little fanfare and back to Aa
    if (i < 0) i = LETTERS.length - 1;
    show();
    if (wrapped) fanfare(); else chime();
  }

  function press(id, fn) {
    document.getElementById(id).addEventListener('pointerdown', function (e) { e.preventDefault(); fn(); });
  }
  press('next', function () { go(1); });
  press('prev', function () { go(-1); });
  cardEl.addEventListener('pointerdown', function (e) {   // tap the pair to hear/see it again
    e.preventDefault();
    if (P.paused) return;
    show(); chime();
  });

  show();
  P.onStart(chime);
})();
