// Balionai — tap the balloon with the same number of dots as the card.
// No reading needed: the card counts its dots out loud with rising beeps.
(function () {
  'use strict';

  var KEY = 'balionai:'; // localStorage prefix (all games share one origin)
  var COLORS = ['#E53935', '#FB8C00', '#FDD835', '#43A047', '#1E88E5', '#8E24AA', '#EC407A'];
  // Dice-style positions in a 3×3 grid, so small numbers can be seen at a glance.
  var PATTERNS = {
    1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8]
  };
  var NOTES = [523, 587, 659, 784, 880, 1047];
  var WORDS = ['', 'vienas', 'du', 'trys', 'keturi', 'penki', 'šeši'];
  var X = [220, 358, 496];

  var stage = document.getElementById('stage');
  var card = document.getElementById('card');
  var cardDots = card.querySelector('.dots');
  var starsEl = document.getElementById('stars');
  var balloonsEl = document.getElementById('balloons');
  var startEl = document.getElementById('start');

  // ---------- storage ----------
  function load(k, d) {
    try { var v = localStorage.getItem(KEY + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; }
  }
  function save(k, v) {
    try { localStorage.setItem(KEY + k, JSON.stringify(v)); } catch (e) { /* ignore */ }
  }
  var correct = load('correct', 0);
  var stars = load('stars', 0);

  // ---------- layout ----------
  function fit() {
    var s = Math.min(window.innerWidth / 640, window.innerHeight / 375);
    stage.style.transform = 'translate(-50%, -50%) scale(' + s + ')';
  }
  window.addEventListener('resize', fit);
  fit();

  // ---------- sound (WebAudio, unlocked on first tap) ----------
  var ac = null, noise = null;
  function unlock() {
    if (!ac) {
      var C = window.AudioContext || window.webkitAudioContext;
      if (!C) return;
      ac = new C();
      var len = Math.floor(ac.sampleRate * 0.2);
      noise = ac.createBuffer(1, len, ac.sampleRate);
      var d = noise.getChannelData(0);
      for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / len);
    }
    if (ac.state === 'suspended') ac.resume();
  }
  function tone(freq, delay, dur, type, vol) {
    if (!ac) return;
    var t = ac.currentTime + (delay || 0);
    var o = ac.createOscillator(), g = ac.createGain();
    o.type = type || 'sine';
    o.frequency.setValueAtTime(freq, t);
    g.gain.setValueAtTime(0.0001, t);
    g.gain.exponentialRampToValueAtTime(vol || 0.25, t + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
    o.connect(g); g.connect(ac.destination);
    o.start(t); o.stop(t + dur + 0.05);
  }
  function popSound() {
    if (!ac) return;
    var s = ac.createBufferSource(), f = ac.createBiquadFilter(), g = ac.createGain();
    s.buffer = noise; f.type = 'bandpass'; f.frequency.value = 1400; g.gain.value = 0.9;
    s.connect(f); f.connect(g); g.connect(ac.destination); s.start();
  }
  function yay() { [523, 659, 784, 1047].forEach(function (f, i) { tone(f, 0.12 + i * 0.1, 0.25, 'triangle', 0.2); }); }
  function oops() { tone(260, 0, 0.18, 'triangle', 0.2); tone(200, 0.16, 0.25, 'triangle', 0.2); }
  function fanfare() {
    [523, 659, 784, 659, 784, 1047].forEach(function (f, i) { tone(f, i * 0.14, 0.3, 'square', 0.08); });
  }
  document.addEventListener('visibilitychange', function () {
    if (!ac) return;
    if (document.hidden) ac.suspend(); else ac.resume();
  });

  // Optional spoken number, only if the device has a Lithuanian voice.
  function say(n) {
    try {
      var ss = window.speechSynthesis;
      if (!ss) return;
      var v = ss.getVoices().filter(function (x) { return /^lt/i.test(x.lang); })[0];
      if (!v) return;
      var u = new SpeechSynthesisUtterance(WORDS[n]);
      u.voice = v; u.lang = v.lang; u.rate = 0.9;
      ss.cancel(); ss.speak(u);
    } catch (e) { /* no voice: the beeps are enough */ }
  }

  // ---------- drawing ----------
  function drawDots(el, n) {
    el.innerHTML = '';
    var on = PATTERNS[n];
    var list = [];
    for (var i = 0; i < 9; i++) {
      var cell = document.createElement('span');
      if (on.indexOf(i) >= 0) { var dot = document.createElement('i'); cell.appendChild(dot); list.push(dot); }
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
        stage.appendChild(s);
        var a = i / 8 * Math.PI * 2, r = 60 + Math.random() * 30;
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            s.style.transform = 'translate(' + Math.cos(a) * r + 'px,' + Math.sin(a) * r + 'px) scale(.4)';
            s.style.opacity = '0';
          });
        });
        setTimeout(function () { s.remove(); }, 700);
      })(i);
    }
  }

  // ---------- game ----------
  var target = 1, busy = false, timers = [], cardDotEls = [], hintTimer = 0, hints = 0;

  function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
  function clearTimers() { timers.forEach(clearTimeout); timers = []; }

  function maxNumber() { return correct < 8 ? 3 : correct < 20 ? 4 : correct < 35 ? 5 : 6; }
  function shuffle(a) {
    for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)); var t = a[i]; a[i] = a[j]; a[j] = t; }
    return a;
  }

  function countOut() {
    clearTimers();
    cardDotEls.forEach(function (d) { d.classList.remove('lit'); });
    cardDotEls.forEach(function (d, i) {
      later(function () { d.classList.add('lit'); tone(NOTES[i], 0, 0.3, 'sine', 0.3); }, 300 + i * 480);
    });
    later(function () { say(target); }, 300 + cardDotEls.length * 480);
  }

  function armHint() {
    clearTimeout(hintTimer);
    if (hints >= 2) return;
    hintTimer = setTimeout(function () { hints++; countOut(); armHint(); }, 12000);
  }

  function newRound() {
    busy = false; hints = 0;
    var max = maxNumber();
    var pool = []; for (var n = 1; n <= max; n++) pool.push(n);
    shuffle(pool);
    var nums = pool.slice(0, 3);
    target = nums[Math.floor(Math.random() * 3)];
    cardDotEls = drawDots(cardDots, target);

    var cols = shuffle(COLORS.slice());
    balloonsEl.innerHTML = '';
    nums.forEach(function (n, i) {
      var b = document.createElement('div');
      b.className = 'balloon enter';
      b.style.left = X[i] + 'px';
      b.style.setProperty('--c', cols[i]);
      b.innerHTML = '<div class="inner"><div class="string"></div><div class="knot"></div><div class="body"><div class="dots"></div></div></div>';
      drawDots(b.querySelector('.body .dots'), n);
      b.addEventListener('pointerdown', function (e) { e.preventDefault(); tapBalloon(b, n, i); });
      balloonsEl.appendChild(b);
      requestAnimationFrame(function () { requestAnimationFrame(function () { b.classList.remove('enter'); }); });
    });
    countOut();
    armHint();
  }

  function tapBalloon(b, n, i) {
    unlock();
    if (busy) return;
    if (n !== target) {
      oops();
      b.classList.remove('shake'); void b.offsetWidth; b.classList.add('shake');
      setTimeout(function () { b.classList.remove('shake'); }, 500);
      return;
    }
    busy = true;
    clearTimeout(hintTimer);
    clearTimers();
    popSound(); yay();
    b.classList.add('popped');
    sparkle(X[i] + 62, 40 + 76);
    correct++; stars++;
    save('correct', correct);
    drawStars();
    if (stars >= 5) {
      save('stars', 0);
      setTimeout(celebrate, 700);
    } else {
      save('stars', stars);
      setTimeout(newRound, 1000);
    }
  }

  function celebrate() {
    fanfare();
    Array.prototype.forEach.call(balloonsEl.children, function (b) { b.classList.add('fly'); });
    setTimeout(function () { stars = 0; drawStars(); newRound(); }, 1800);
  }

  card.addEventListener('pointerdown', function (e) { e.preventDefault(); unlock(); if (!busy) countOut(); });
  document.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  startEl.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    unlock();
    startEl.classList.add('hidden');
    newRound();
  });

  drawStars();
})();
