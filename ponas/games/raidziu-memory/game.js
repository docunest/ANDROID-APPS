// Raidžių memory — a picture and letter cards (Aa, Kk…) are shown for 5 seconds, then the cards
// turn over. Tap the card with the first letter of the picture's word.
//  - 3 cards at the start, one more every 3 correct words, up to 6.
//  - Letters unlock in groups (see words.js): common, easy letters first; look-alikes (S/Š, Z/Ž, C/Č)
//    and rare letters later. Once unlocked, look-alike letters are often put next to each other.
//  - A wrong card opens briefly and turns back; after 2 wrong taps all cards peek open for 2 s.
//  - After the right card, the word is shown under the picture with its first letter highlighted.
(function () {
  'use strict';
  var P = window.Ponas;

  var LOOK_MS = 5000, MIN_CARDS = 3, MAX_CARDS = 6, WORDS_PER_CARD = 3;
  var TWINS = { S: 'Š', 'Š': 'S', Z: 'Ž', 'Ž': 'Z', C: 'Č', 'Č': 'C', E: 'Ė' };

  var $ = function (id) { return document.getElementById(id); };
  var picEl = $('pic'), barEl = $('bar'), wordEl = $('word'), cardsEl = $('cards'), starsEl = $('stars');

  var correct = P.load('correct', 0);   // total right answers: unlocks letters
  var run = P.load('run', 0);           // right answers since the last extra card
  var nCards = P.load('cards', MIN_CARDS);
  var stars = P.load('stars', 0);
  var recent = P.load('recent', []);

  var round = null, phase = 'idle', mistakes = 0, timers = [], idleTimer = 0;

  function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
  function clearTimers() { timers.forEach(clearTimeout); timers = []; clearTimeout(idleTimer); }
  function rnd(n) { return Math.floor(Math.random() * n); }
  function shuffle(a) { for (var i = a.length - 1; i > 0; i--) { var j = rnd(i + 1), t = a[i]; a[i] = a[j]; a[j] = t; } return a; }
  function first(w) { return w.charAt(0).toUpperCase(); }

  function unlocked() {
    var set = [];
    LETTER_LEVELS.forEach(function (l) { if (correct >= l.from) set = set.concat(l.letters.split(' ')); });
    return set;
  }

  // ---- round ----
  function makeRound() {
    var letters = unlocked();
    var pool = WORDS.filter(function (w) { return letters.indexOf(first(w[0])) >= 0 && recent.indexOf(w[0]) < 0; });
    if (!pool.length) pool = WORDS.filter(function (w) { return letters.indexOf(first(w[0])) >= 0; });
    // Lean towards the newest letters so they get practised.
    var newest = LETTER_LEVELS.filter(function (l) { return correct >= l.from; }).pop().letters.split(' ');
    var fresh = pool.filter(function (w) { return newest.indexOf(first(w[0])) >= 0; });
    var src = fresh.length && Math.random() < 0.5 ? fresh : pool;
    var pick = src[rnd(src.length)];
    var target = first(pick[0]);

    var cards = [target];
    var twin = TWINS[target];
    if (twin && letters.indexOf(twin) >= 0 && Math.random() < 0.6) cards.push(twin);
    var others = shuffle(letters.filter(function (l) { return cards.indexOf(l) < 0; }));
    while (cards.length < nCards && others.length) cards.push(others.pop());

    recent.push(pick[0]);
    if (recent.length > 12) recent.shift();
    return { word: pick[0], pic: pick[1], target: target, cards: shuffle(cards) };
  }

  function newRound() {
    clearTimers();
    mistakes = 0;
    round = makeRound();
    layout();
    drawPicture();
    drawCards();
    wordEl.classList.remove('show');
    phase = 'look';
    if (P.started && !P.paused) startLook();
  }

  function startLook() {
    phase = 'look';
    openAll(true);
    barEl.classList.remove('hide', 'run');
    var bar = barEl.firstElementChild;
    bar.style.transitionDuration = '0ms';
    bar.style.transform = 'scaleX(1)';
    void bar.offsetWidth;
    barEl.classList.add('run');
    bar.style.transitionDuration = LOOK_MS + 'ms';
    bar.style.transform = 'scaleX(0)';
    later(function () {
      openAll(false);
      barEl.classList.add('hide');
      phase = 'guess';
      P.tone(660, { dur: 0.12, vol: 0.15 }); P.tone(520, { delay: 0.1, dur: 0.16, vol: 0.15 });
      armIdle();
    }, LOOK_MS);
  }

  function armIdle() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(function () {
      if (phase !== 'guess' || P.paused) return;
      picEl.classList.remove('nudge'); void picEl.offsetWidth; picEl.classList.add('nudge');
    }, 12000);
  }

  // ---- drawing ----
  function drawStars() {
    starsEl.innerHTML = '';
    for (var i = 0; i < 5; i++) {
      var s = document.createElement('div');
      s.className = 'star' + (i < stars ? ' on' : '');
      starsEl.appendChild(s);
    }
  }

  function drawPicture() {
    picEl.innerHTML = '';
    if (/\.png$/i.test(round.pic)) {
      var img = document.createElement('img'); img.src = round.pic; img.alt = ''; picEl.appendChild(img);
    } else {
      var e = document.createElement('span'); e.className = 'emoji'; e.textContent = round.pic; picEl.appendChild(e);
    }
  }

  var BACK = '<svg viewBox="0 0 24 24"><path d="M12 2.5l2.7 5.8 6.3.8-4.6 4.4 1.2 6.3L12 16.7l-5.6 3.1 1.2-6.3L3 9.1l6.3-.8z" fill="#ffffffcc"/></svg>';
  function drawCards() {
    cardsEl.innerHTML = '';
    round.cards.forEach(function (L) {
      var c = document.createElement('div');
      c.className = 'card';
      c.innerHTML = '<div class="in"><div class="face">' + L + '<small>' + L.toLowerCase() + '</small></div><div class="back">' + BACK + '</div></div>';
      c.addEventListener('pointerdown', function (e) { e.preventDefault(); tap(c, L); });
      cardsEl.appendChild(c);
    });
  }

  function openAll(open) {
    Array.prototype.forEach.call(cardsEl.children, function (c) { c.classList.toggle('closed', !open); });
  }

  // Sizes for the picture and the card grid, for this screen and number of cards.
  function layout() {
    var W = window.innerWidth, H = window.innerHeight - 72 - 12;
    var land = W >= window.innerHeight;
    var n = round ? round.cards.length : nCards;
    var cols = n <= 3 ? n : (n === 4 ? 2 : 3);
    if (!land && n === 4) cols = 2;
    var rows = Math.ceil(n / cols);
    var gap = 12;
    var pic, areaW, areaH;
    if (land) {
      pic = Math.min(H * 0.62, W * 0.34, 210);
      areaW = W - pic - 24 - 36; areaH = H - 8;
    } else {
      pic = Math.min(W * 0.5, H * 0.3, 210);
      areaW = W - 24; areaH = H - pic - 10 - 38 - 40;
    }
    var ch = Math.min((areaH - (rows - 1) * gap) / rows, ((areaW - (cols - 1) * gap) / cols) / 0.82, 170);
    var cw = ch * 0.82;
    var root = document.documentElement.style;
    root.setProperty('--pic', Math.round(pic) + 'px');
    root.setProperty('--ch', Math.round(ch) + 'px');
    root.setProperty('--cw', Math.round(cw) + 'px');
    root.setProperty('--gap', gap + 'px');
    cardsEl.style.gridTemplateColumns = 'repeat(' + cols + ', var(--cw))';
  }

  // ---- play ----
  function tap(card, L) {
    if (phase !== 'guess' || P.paused || !card.classList.contains('closed')) return;
    armIdle();
    if (L === round.target) {
      phase = 'done';
      clearTimeout(idleTimer);
      card.classList.remove('closed');
      card.classList.add('ok');
      yay();
      var r = card.getBoundingClientRect();
      sparkle(r.left + r.width / 2, r.top + r.height / 2);
      wordEl.innerHTML = '<b>' + round.word.charAt(0) + '</b>' + round.word.slice(1);
      wordEl.classList.add('show');
      correct++; run++; stars++;
      if (run >= WORDS_PER_CARD && nCards < MAX_CARDS) { nCards++; run = 0; }
      if (run >= WORDS_PER_CARD) run = 0;
      drawStars();
      if (stars >= 5) { stars = 0; later(celebrate, 600); }
      save();
      later(function () { openAll(true); }, 900); // show all letters again for a moment
      later(function () { if (stars === 0) drawStars(); newRound(); }, stars === 0 ? 3000 : 2400);
    } else {
      mistakes++;
      oops();
      card.classList.remove('closed');
      card.classList.add('bad', 'shake');
      later(function () { card.classList.remove('shake'); }, 450);
      later(function () { card.classList.add('closed'); card.classList.remove('bad'); }, 1100);
      if (mistakes === 2) {
        phase = 'peek';
        later(function () { openAll(true); P.tone(784, { dur: 0.15, vol: 0.12 }); }, 1300);
        later(function () { openAll(false); phase = 'guess'; }, 3300);
      }
    }
  }

  function save() {
    P.save('correct', correct); P.save('run', run); P.save('cards', nCards);
    P.save('stars', stars); P.save('recent', recent);
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

  // ---- lifecycle ----
  P.onResize(function () { if (round) layout(); });
  // Pausing during the look phase restarts the 5 seconds on resume (the cards open again).
  P.onPause(function () { clearTimers(); });
  P.onResume(function () {
    if (phase === 'look' || phase === 'peek') startLook();
    else if (phase === 'done') newRound();
    else armIdle();
  });

  drawStars();
  newRound();                         // first round sits behind the ▶ overlay, cards open
  openAll(true); barEl.classList.add('hide');
  P.onStart(startLook);
})();
