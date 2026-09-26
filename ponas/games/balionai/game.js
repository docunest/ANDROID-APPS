// Balionai — tap the balloon with as many dots as the card.
// No reading needed: the card "counts" its dots with rising beeps. Tap the card to hear it again.
(function () {
  'use strict';
  var P = window.Ponas;

  var COLORS = ['#E53935', '#FB8C00', '#FDD835', '#43A047', '#1E88E5', '#8E24AA', '#EC407A'];
  var PATTERNS = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] };
  var NOTES = [523, 587, 659, 784, 880, 1047];

  var card = document.getElementById('card');
  var cardDots = card.querySelector('.dots');
  var starsEl = document.getElementById('stars');
  var balloonsEl = document.getElementById('balloons');

  var correct = P.load('correct', 0);
  var stars = P.load('stars', 0);
  var target = 1, busy = false, timers = [], cardDotEls = [], hintTimer = 0, hints = 0;

  // ---- sounds ----
  function popSound() {
    var ac = P.audio();
    if (!ac || P.paused) return;
    var len = Math.floor(ac.sampleRate * 0.15), buf = ac.createBuffer(1, len, ac.sampleRate), d = buf.getChannelData(0);
    for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / len);
    var s = ac.createBufferSource(), f = ac.createBiquadFilter();
    s.buffer = buf; f.type = 'bandpass'; f.frequency.value = 1400;
    s.connect(f); f.connect(ac.destination); s.start();
  }
  function yay() { [523, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: 0.12 + i * 0.1, dur: 0.25, type: 'triangle', vol: 0.2 }); }); }
  function oops() { P.tone(260, { dur: 0.18, type: 'triangle', vol: 0.2 }); P.tone(200, { delay: 0.16, dur: 0.25, type: 'triangle', vol: 0.2 }); }
  function fanfare() { [523, 659, 784, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: i * 0.14, dur: 0.3, type: 'square', vol: 0.08 }); }); }

  // ---- drawing ----
  function drawDots(el, n) {
    el.innerHTML = '';
    var list = [];
    for (var i = 0; i < 9; i++) {
      var cell = document.createElement('span');
      if (PATTERNS[n].indexOf(i) >= 0) { var dot = document.createElement('i'); cell.appendChild(dot); list.push(dot); }
      el.appendChild(cell);
    }
    return list;
  }
  function drawStars() {
    starsEl.innerHTML = '';
    for (var i = 0; i < 5; i++) {
      var s = document.createElement('div');
      s.className = 'star' + (i < stars ? ' on' : '');
      starsEl.appendChild(s);
    }
  }
  function sparkle(x, y) {
    for (var i = 0; i < 8; i++) {
      (function (i) {
        var s = document.createElement('div');
        s.className = 'spark';
        s.style.left = (x - 9) + 'px'; s.style.top = (y - 9) + 'px';
        document.body.appendChild(s);
        var a = i / 8 * Math.PI * 2, r = 60 + Math.random() * 30;
        requestAnimationFrame(function () { requestAnimationFrame(function () {
          s.style.transform = 'translate(' + Math.cos(a) * r + 'px,' + Math.sin(a) * r + 'px) scale(.4)';
          s.style.opacity = '0';
        }); });
        setTimeout(function () { s.remove(); }, 700);
      })(i);
    }
  }

  // ---- game ----
  function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
  function clearTimers() { timers.forEach(clearTimeout); timers = []; }
  function maxNumber() { return correct < 8 ? 3 : correct < 20 ? 4 : correct < 35 ? 5 : 6; }
  function shuffle(a) {
    for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var t = a[i]; a[i] = a[j]; a[j] = t; }
    return a;
  }

  function countOut() {
    if (P.paused) return;
    clearTimers();
    cardDotEls.forEach(function (d) { d.classList.remove('lit'); });
    cardDotEls.forEach(function (d, i) {
      later(function () { d.classList.add('lit'); P.tone(NOTES[i], { dur: 0.3, vol: 0.3 }); }, 300 + i * 480);
    });
  }
  function armHint() {
    clearTimeout(hintTimer);
    if (hints >= 2) return;
    hintTimer = setTimeout(function () { if (P.paused) return; hints++; countOut(); armHint(); }, 12000);
  }

  function newRound() {
    busy = false; hints = 0;
    var pool = [];
    for (var n = 1; n <= maxNumber(); n++) pool.push(n);
    var nums = shuffle(pool).slice(0, 3);
    target = nums[Math.floor(Math.random() * 3)];
    cardDotEls = drawDots(cardDots, target);

    var cols = shuffle(COLORS.slice());
    balloonsEl.innerHTML = '';
    nums.forEach(function (n, i) {
      var b = document.createElement('div');
      b.className = 'balloon enter';
      b.style.setProperty('--c', cols[i]);
      b.innerHTML = '<div class="inner"><div class="string"></div><div class="knot"></div><div class="body"><div class="dots"></div></div></div>';
      drawDots(b.querySelector('.body .dots'), n);
      b.addEventListener('pointerdown', function (e) { e.preventDefault(); tapBalloon(b, n); });
      balloonsEl.appendChild(b);
      requestAnimationFrame(function () { requestAnimationFrame(function () { b.classList.remove('enter'); }); });
    });
    countOut();
    armHint();
  }

  function tapBalloon(b, n) {
    if (busy || P.paused) return;
    if (n !== target) {
      oops();
      b.classList.remove('shake'); void b.offsetWidth; b.classList.add('shake');
      setTimeout(function () { b.classList.remove('shake'); }, 500);
      return;
    }
    busy = true;
    clearTimeout(hintTimer); clearTimers();
    popSound(); yay();
    var r = b.getBoundingClientRect();
    b.classList.add('popped');
    sparkle(r.left + r.width / 2, r.top + r.height * 0.34);
    correct++; stars++;
    P.save('correct', correct);
    drawStars();
    if (stars >= 5) {
      P.save('stars', 0);
      setTimeout(celebrate, 700);
    } else {
      P.save('stars', stars);
      setTimeout(newRound, 1000);
    }
  }

  function celebrate() {
    fanfare();
    Array.prototype.forEach.call(balloonsEl.children, function (b) { b.classList.add('fly'); });
    setTimeout(function () { stars = 0; drawStars(); newRound(); }, 1800);
  }

  card.addEventListener('pointerdown', function (e) { e.preventDefault(); if (!busy) countOut(); });

  P.onPause(function () { clearTimers(); clearTimeout(hintTimer); });
  P.onResume(function () { if (!busy) { countOut(); armHint(); } });

  drawStars();
  newRound();                 // draw the first round behind the ▶ overlay
  clearTimers(); clearTimeout(hintTimer);
  P.onStart(function () { countOut(); armHint(); });
})();
