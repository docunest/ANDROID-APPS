// Minos — a gentle minesweeper. Dig the grass; dots show how many bombs are hiding next to that
// spot. A flag tool marks bombs. Digging up a bomb is only a soft "boop": it gets marked and
// play goes on. Open every safe spot → a star. 5 stars → celebration. Bombs grow 3 → 6 with wins.
(function () {
  'use strict';
  var P = window.Ponas;
  var L = window.Laikas || { blocked: false, onBlock: function () {}, onUnblock: function () {} };

  var ROWS = 4, COLS = 6, N = ROWS * COLS;          // logical board; portrait shows it transposed
  var PATTERNS = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8],
    6: [0, 2, 3, 5, 6, 8], 7: [0, 2, 3, 4, 5, 6, 8], 8: [0, 1, 2, 3, 5, 6, 7, 8] };
  var DOT_COLORS = { 1: '#1E88E5', 2: '#43A047', 3: '#E53935', 4: '#8E24AA', 5: '#6D4C41', 6: '#00897B', 7: '#37474F', 8: '#F4511E' };

  var SVG_FLAG = '<svg viewBox="0 0 100 100"><rect x="30" y="12" width="7" height="78" rx="3" fill="#5D4037"/>' +
    '<path d="M37 14 L84 30 L37 48 Z" fill="#E53935"/><ellipse cx="34" cy="90" rx="20" ry="6" fill="#00000030"/></svg>';
  var SVG_BOMB = '<svg viewBox="0 0 100 100"><circle cx="46" cy="58" r="30" fill="#37474F"/>' +
    '<circle cx="36" cy="48" r="8" fill="#ffffff55"/><rect x="52" y="20" width="16" height="14" rx="3" fill="#546E7A" transform="rotate(35 60 27)"/>' +
    '<path d="M66 20 q8 -10 16 -6" stroke="#8D6E63" stroke-width="4" fill="none" stroke-linecap="round"/>' +
    '<circle cx="84" cy="13" r="7" fill="#FFB300"/><circle cx="84" cy="13" r="3.5" fill="#FFF59D"/></svg>';
  var SVG_SHOVEL = '<svg viewBox="0 0 100 100"><rect x="45" y="6" width="10" height="50" rx="4" fill="#8D6E63"/>' +
    '<rect x="34" y="4" width="32" height="10" rx="5" fill="#6D4C41"/>' +
    '<path d="M30 52 h40 v18 q0 24 -20 28 q-20 -4 -20 -28 z" fill="#90A4AE"/><path d="M38 58 h8 v14 q0 10 -8 14 z" fill="#ffffff55"/></svg>';

  var boardEl = document.getElementById('board');
  var starsEl = document.getElementById('stars');
  var digBtn = document.getElementById('t-dig'), flagBtn = document.getElementById('t-flag');
  digBtn.innerHTML = SVG_SHOVEL; flagBtn.innerHTML = SVG_FLAG;

  var wins = P.load('wins', 0), stars = P.load('stars', 0);
  var cells = [], els = [], placed = false, busy = false, tool = 'dig', portrait = false;
  var timers = [], hintTimer = 0, hints = 0;

  function later(fn, ms) { var t = setTimeout(function () { timers.splice(timers.indexOf(t), 1); fn(); }, ms); timers.push(t); return t; }
  function clearTimers() { timers.forEach(clearTimeout); timers = []; clearTimeout(hintTimer); hintTimer = 0; }
  function locked() { return P.paused || L.blocked || busy; }

  // ---- sounds ----
  function digSound() { P.tone(330, { dur: 0.08, type: 'triangle', vol: 0.18 }); P.tone(494, { delay: 0.06, dur: 0.12, type: 'triangle', vol: 0.15 }); }
  function floodSound(n) { for (var i = 0; i < Math.min(n, 6); i++) P.tone(523 + i * 90, { delay: i * 0.05, dur: 0.12, type: 'sine', vol: 0.12 }); }
  function flagSound(on) { P.tone(on ? 698 : 440, { dur: 0.12, type: 'square', vol: 0.06 }); }
  function boop() { P.tone(220, { dur: 0.22, type: 'triangle', vol: 0.22 }); P.tone(165, { delay: 0.18, dur: 0.3, type: 'triangle', vol: 0.2 }); }
  function yay() { [523, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: 0.1 + i * 0.1, dur: 0.25, type: 'triangle', vol: 0.2 }); }); }
  function fanfare() { [523, 659, 784, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: i * 0.14, dur: 0.3, type: 'square', vol: 0.08 }); }); }

  // ---- board ----
  function mineCount() { return wins < 2 ? 3 : wins < 5 ? 4 : wins < 9 ? 5 : 6; }
  function rc(i) { return [Math.floor(i / COLS), i % COLS]; }
  function neighbours(i) {
    var p = rc(i), out = [];
    for (var dr = -1; dr <= 1; dr++) for (var dc = -1; dc <= 1; dc++) {
      if (!dr && !dc) continue;
      var r = p[0] + dr, c = p[1] + dc;
      if (r >= 0 && r < ROWS && c >= 0 && c < COLS) out.push(r * COLS + c);
    }
    return out;
  }
  function newRound() {
    cells = [];
    for (var i = 0; i < N; i++) cells.push({ mine: false, n: 0, open: false, flag: false, boom: false });
    placed = false; hints = 0; busy = false;
    for (var j = 0; j < N; j++) drawCell(j);
  }
  function placeMines(safe) {
    var keep = neighbours(safe).concat([safe]), pool = [];
    for (var i = 0; i < N; i++) if (keep.indexOf(i) < 0) pool.push(i);
    for (var k = 0; k < mineCount() && pool.length; k++) {
      var idx = pool.splice(Math.floor(Math.random() * pool.length), 1)[0];
      cells[idx].mine = true;
    }
    for (var m = 0; m < N; m++) cells[m].n = neighbours(m).filter(function (x) { return cells[x].mine; }).length;
    placed = true;
  }

  function drawCell(i) {
    var c = cells[i], el = els[i];
    if (!el || !c) return;
    el.classList.remove('wiggle', 'shake');
    if (c.open && !c.mine) {
      el.className = 'cell open';
      if (c.n) {
        var h = '<div class="dots" style="--dc:' + DOT_COLORS[c.n] + '">';
        for (var k = 0; k < 9; k++) h += '<span>' + (PATTERNS[c.n].indexOf(k) >= 0 ? '<i></i>' : '') + '</span>';
        el.innerHTML = h + '</div>';
      } else el.innerHTML = '';
    } else if (c.boom) { el.className = 'cell open boom'; el.innerHTML = SVG_BOMB; }
    else { el.className = 'cell hid'; el.innerHTML = c.flag ? SVG_FLAG : ''; }
  }

  function buildBoard() {
    boardEl.innerHTML = ''; els = [];
    var dr = portrait ? COLS : ROWS, dc = portrait ? ROWS : COLS;
    boardEl.style.gridTemplateColumns = 'repeat(' + dc + ', var(--cell))';
    for (var r = 0; r < dr; r++) for (var c = 0; c < dc; c++) {
      var i = portrait ? c * COLS + r : r * COLS + c;     // transpose keeps neighbours the same
      var el = document.createElement('div');
      el.dataset.i = i;
      boardEl.appendChild(el);
      els[i] = el;
    }
    for (var j = 0; j < N; j++) drawCell(j);
  }

  P.onResize(function (w, h, o) {
    var p = o === 'portrait', gap = Math.min(w, h) * 0.04, cell;
    var gh = h - 72 - 8, extra = 12 + 4 * 3;
    if (p) cell = Math.min((w - 16 - extra) / ROWS, (gh - 86 - gap - 12 - 4 * (COLS - 1)) / COLS);
    else cell = Math.min((gh - extra) / ROWS, (w - 16 - 86 - gap - 12 - 4 * (COLS - 1)) / COLS);
    document.body.style.setProperty('--cell', Math.floor(Math.min(cell, 96)) + 'px');
    if (p !== portrait || !els.length) { portrait = p; buildBoard(); }
  });

  // ---- play ----
  function reveal(start) {
    var stack = [start], n = 0;
    while (stack.length) {
      var i = stack.pop(), c = cells[i];
      if (c.open || c.flag) continue;
      c.open = true; n++; drawCell(i);
      if (c.n === 0) neighbours(i).forEach(function (x) { if (!cells[x].open && !cells[x].mine) stack.push(x); });
    }
    return n;
  }
  function won() { for (var i = 0; i < N; i++) if (!cells[i].mine && !cells[i].open) return false; return true; }

  boardEl.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    var t = e.target.closest('.cell');
    if (!t || locked()) return;
    var i = +t.dataset.i, c = cells[i];
    armHint();
    if (c.open || c.boom) return;
    if (tool === 'flag') { c.flag = !c.flag; flagSound(c.flag); drawCell(i); return; }
    if (c.flag) { t.classList.add('shake'); P.tone(300, { dur: 0.1, type: 'triangle', vol: 0.12 }); return; }
    if (!placed) placeMines(i);
    if (c.mine) {
      c.boom = true; drawCell(i); els[i].classList.add('shake'); boop();
      return;
    }
    var n = reveal(i);
    if (n > 1) floodSound(n); else digSound();
    if (won()) win();
  });

  function setTool(t) {
    if (locked()) return;
    tool = t; digBtn.classList.toggle('on', t === 'dig'); flagBtn.classList.toggle('on', t === 'flag');
    P.tone(t === 'dig' ? 523 : 659, { dur: 0.1, type: 'sine', vol: 0.15 });
  }
  digBtn.addEventListener('pointerdown', function (e) { e.preventDefault(); setTool('dig'); });
  flagBtn.addEventListener('pointerdown', function (e) { e.preventDefault(); setTool('flag'); });

  function sparkle(x, y) {
    for (var i = 0; i < 8; i++) (function (i) {
      var s = document.createElement('div');
      s.className = 'spark'; s.style.left = (x - 9) + 'px'; s.style.top = (y - 9) + 'px';
      document.body.appendChild(s);
      var a = i / 8 * Math.PI * 2, r = 60 + Math.random() * 40;
      requestAnimationFrame(function () { requestAnimationFrame(function () {
        s.style.transform = 'translate(' + Math.cos(a) * r + 'px,' + Math.sin(a) * r + 'px) rotate(180deg)';
        s.style.opacity = '0';
      }); });
      setTimeout(function () { s.remove(); }, 800);
    })(i);
  }
  function drawStars() {
    starsEl.innerHTML = '';
    for (var i = 0; i < 5; i++) { var s = document.createElement('div'); s.className = 'star' + (i < stars ? ' on' : ''); starsEl.appendChild(s); }
  }

  function win() {
    busy = true; clearTimeout(hintTimer);
    for (var i = 0; i < N; i++) if (cells[i].mine && !cells[i].boom) { cells[i].flag = true; drawCell(i); }
    wins++; stars++; P.save('wins', wins);
    var b = boardEl.getBoundingClientRect();
    sparkle(b.left + b.width / 2, b.top + b.height / 2);
    yay();
    if (stars >= 5) {
      drawStars(); stars = 0; P.save('stars', 0);
      later(celebrate, 700);
      later(function () { drawStars(); newRound(); armHint(); }, 3200);
    } else {
      P.save('stars', stars); drawStars();
      later(function () { newRound(); armHint(); }, 1800);
    }
  }
  function celebrate() {
    fanfare();
    var w = window.innerWidth, h = window.innerHeight;
    for (var k = 0; k < 5; k++) later(function () { sparkle(Math.random() * w, 90 + Math.random() * (h - 140)); }, k * 220);
  }

  // ---- idle hint: wiggle a safe spot ----
  function hintCell() {
    if (!placed) return Math.floor(ROWS / 2) * COLS + Math.floor(COLS / 2);
    var best = -1;
    for (var i = 0; i < N; i++) {
      var c = cells[i];
      if (c.mine || c.open) continue;
      if (neighbours(i).some(function (x) { return cells[x].open; })) return i;
      if (best < 0) best = i;
    }
    return best;
  }
  function armHint() {
    clearTimeout(hintTimer);
    if (hints >= 2 || P.paused || L.blocked) return;
    hintTimer = setTimeout(function () {
      if (locked()) return;
      var i = hintCell();
      if (i < 0 || !els[i]) return;
      if (tool !== 'dig') setTool('dig');
      els[i].classList.remove('wiggle'); void els[i].offsetWidth; els[i].classList.add('wiggle');
      P.tone(880, { dur: 0.1, vol: 0.1 }); P.tone(988, { delay: 0.12, dur: 0.12, vol: 0.1 });
      hints++; armHint();
    }, 12000);
  }

  P.onStart(armHint);
  P.onPause(clearTimers);
  P.onResume(function () { if (busy) { busy = false; drawStars(); if (won()) newRound(); } armHint(); });
  L.onBlock(clearTimers);
  L.onUnblock(function () { if (busy) { busy = false; drawStars(); newRound(); } armHint(); });

  newRound();
  drawStars();
})();
