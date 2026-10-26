// Matematika — a task (3 + 4, 7 − 2, 4 × 3) and three answers to tap.
//  - Difficulty = largest possible answer. Starts at 5, +1 per correct answer (up to 20),
//    halves on a mistake. Operations are mixed at random; in × the second number is 1–3.
//  - Stars count correct answers. Hearts: one lost per mistake; when all are gone they refill
//    (never a game over). A heart comes back every 5 correct answers.
//  - Food: the raised hand shows a picture of the task (dots) and costs one apple.
//    An apple comes back every 3 correct answers.
//  - Multiplayer: the player broadcasts the task and stats. In a browser (the parent's phone) an
//    eye button switches to watching; it is never shown on the tablet.
(function () {
  'use strict';
  var P = window.Ponas;

  var START_MAX = 5, TOP_MAX = 20, HEARTS = 5, FOOD = 3, OPTIONS = 3;
  var SEND_GAP_MS = 3000; // ntfy.sh free tier: keep it gentle

  var watch = /[?&]watch\b/.test(location.search);
  if (watch) document.body.classList.add('watch');
  // Eye button (browser only): switch between playing and watching the tablet.
  if (!P.inApp) {
    document.body.classList.add('browser');
    document.getElementById('watchBtn').addEventListener('pointerdown', function (e) {
      e.preventDefault();
      location.replace(location.pathname + (watch ? '' : '?watch'));
    });
  } else {
    watch = false; // the tablet always plays
    document.body.classList.remove('watch');
  }

  var el = function (id) { return document.getElementById(id); };
  var taskEl = el('task'), dotsEl = el('dots'), visEl = el('vis'), optsEl = el('opts');
  var handEl = el('hand'), foodEl = el('food'), heartsEl = el('hearts'), starsEl = el('stars'), starN = el('starN');

  var ICON_HEART = '<svg viewBox="0 0 24 24"><path d="M12 21s-7.5-4.6-9.6-9.3C.8 8 3 4 6.8 4c2.2 0 3.6 1.3 5.2 3.2C13.6 5.3 15 4 17.2 4 21 4 23.2 8 21.6 11.7 19.5 16.4 12 21 12 21z" fill="#E53935"/></svg>';
  var ICON_APPLE = '<svg viewBox="0 0 24 24"><path d="M12 7c-1.5-1.2-4.8-1.6-6.6.6C3.2 10.3 4.4 16 7 19c1.4 1.6 2.8 2.3 5 1.4 2.2.9 3.6.2 5-1.4 2.6-3 3.8-8.7 1.6-11.4C16.8 5.4 13.5 5.8 12 7z" fill="#E53935"/><path d="M12 7c0-2 .6-3.5 2-4.5" stroke="#6D4C41" stroke-width="1.5" fill="none" stroke-linecap="round"/><path d="M12.6 4.6c1.6-1.6 4-1.5 4.8-1-.5 1.6-2.6 2.6-4.8 1z" fill="#66BB6A"/></svg>';

  // ---- state (saved) ----
  var S = {
    max: P.load('max', START_MAX),
    stars: P.load('stars', 0),
    hearts: P.load('hearts', HEARTS),
    food: P.load('food', FOOD),
    streak: P.load('streak', 0) // correct answers since the last heart/apple refill counters
  };
  function persist() {
    if (watch) return;
    P.save('max', S.max); P.save('stars', S.stars); P.save('hearts', S.hearts);
    P.save('food', S.food); P.save('streak', S.streak);
  }

  var task = null;        // {a, op, b, ans, opts}
  var busy = false, helped = false, timers = [], idleTimer = 0, nudges = 0;
  var last = null, lastPick = null, ev = 0, seenEv = -1; // ev: answer counter, so the watcher reacts once per answer

  function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
  function clearTimers() { timers.forEach(clearTimeout); timers = []; clearTimeout(idleTimer); }
  function rnd(a, b) { return a + Math.floor(Math.random() * (b - a + 1)); }
  function shuffle(a) { for (var i = a.length - 1; i > 0; i--) { var j = rnd(0, i), t = a[i]; a[i] = a[j]; a[j] = t; } return a; }

  // ---- tasks ----
  function makeTask(M) {
    var ops = ['+', '−', '×'];
    for (var tries = 0; tries < 20; tries++) {
      var op = ops[rnd(0, 2)], a, b, ans;
      if (op === '+') { ans = rnd(2, M); a = rnd(1, ans - 1); b = ans - a; }
      else if (op === '−') { a = rnd(2, M); b = rnd(1, a - 1); ans = a - b; }
      else {
        b = Math.random() < 0.15 ? 1 : rnd(2, 3);
        if (Math.floor(M / b) < 1) continue;
        a = rnd(1, Math.min(10, Math.floor(M / b)));
        if (a === 1 && b === 1) continue;
        ans = a * b;
      }
      if (task && task.a === a && task.b === b && task.op === op) continue; // no instant repeats
      return { a: a, op: op, b: b, ans: ans, opts: options(ans, M) };
    }
    return { a: 1, op: '+', b: 1, ans: 2, opts: options(2, M) };
  }
  function options(ans, M) {
    var hi = Math.max(M, ans + 2, OPTIONS), set = [ans];
    var near = shuffle([-1, 1, -2, 2, -3, 3]);
    for (var i = 0; i < near.length && set.length < OPTIONS; i++) {
      var v = ans + near[i];
      if (v >= 1 && v <= hi && set.indexOf(v) < 0) set.push(v);
    }
    while (set.length < OPTIONS) { var r = rnd(1, hi); if (set.indexOf(r) < 0) set.push(r); }
    return shuffle(set);
  }

  // ---- drawing ----
  function drawStats() {
    starN.textContent = S.stars;
    heartsEl.innerHTML = '';
    for (var i = 0; i < HEARTS; i++) {
      heartsEl.insertAdjacentHTML('beforeend', ICON_HEART);
      if (i >= S.hearts) heartsEl.lastChild.classList.add('lost');
    }
    foodEl.innerHTML = '';
    for (var j = 0; j < FOOD; j++) {
      foodEl.insertAdjacentHTML('beforeend', ICON_APPLE);
      if (j >= S.food) foodEl.lastChild.classList.add('empty');
    }
    handEl.classList.toggle('off', S.food <= 0 || helped);
  }

  function drawTask() {
    if (!task) { taskEl.innerHTML = '<span class="q">…</span>'; optsEl.innerHTML = ''; return; }
    taskEl.innerHTML = task.a + ' ' + task.op + ' ' + task.b + ' = <span class="q">?</span>';
    taskEl.classList.add('new');
    requestAnimationFrame(function () { requestAnimationFrame(function () { taskEl.classList.remove('new'); }); });
    optsEl.innerHTML = '';
    task.opts.forEach(function (v) {
      var b = document.createElement('div');
      b.className = 'opt';
      b.textContent = v;
      b.dataset.v = v;
      b.addEventListener('pointerdown', function (e) { e.preventDefault(); answer(b, v); });
      optsEl.appendChild(b);
    });
    hideHelp();
  }

  // Help pictures: + → a dots, "+", b dots; − → a dots with the last b crossed out; × → b rows of a dots.
  function showHelp() {
    if (!task) return;
    var r = visEl.getBoundingClientRect();
    var W = Math.max(60, r.width - 8), H = Math.max(40, r.height - 8);
    dotsEl.innerHTML = '';
    dotsEl.className = '';
    var s, g;
    if (task.op === '×') {
      var cols = task.a, rows = task.b;
      g = 6;
      s = Math.floor(Math.min(42, (W - (cols - 1) * g) / cols, (H - (rows - 1) * g) / rows));
      dotsEl.classList.add('grid');
      dotsEl.style.gridTemplateColumns = 'repeat(' + cols + ', var(--s))';
      for (var k = 0; k < cols * rows; k++) dotsEl.appendChild(document.createElement('i'));
    } else {
      dotsEl.style.gridTemplateColumns = '';
      var items = [];
      if (task.op === '+') {
        for (var i = 0; i < task.a; i++) items.push('dot');
        items.push('sign');
        for (var j = 0; j < task.b; j++) items.push('dot');
      } else {
        for (var m = 0; m < task.a; m++) items.push(m >= task.a - task.b ? 'x' : 'dot');
      }
      var fit = fitRow(items.length, W, H);
      s = fit.s; g = fit.g;
      dotsEl.style.maxWidth = (fit.perRow * (s + g)) + 'px';
      items.forEach(function (t) {
        if (t === 'sign') { var sign = document.createElement('b'); sign.textContent = '+'; dotsEl.appendChild(sign); return; }
        var d = document.createElement('i');
        if (t === 'x') d.className = 'x';
        dotsEl.appendChild(d);
      });
    }
    dotsEl.style.setProperty('--s', Math.max(8, s) + 'px');
    dotsEl.style.setProperty('--g', g + 'px');
    requestAnimationFrame(function () { dotsEl.classList.add('show'); });
  }
  function fitRow(n, W, H) {
    for (var s = 42; s >= 8; s--) {
      var g = Math.max(3, Math.round(s / 4));
      var perRow = Math.max(1, Math.floor((W + g) / (s + g)));
      var rows = Math.ceil(n / perRow);
      if (rows * (s + g) - g <= H) {
        perRow = Math.ceil(n / rows); // balance the rows
        return { s: s, g: g, perRow: perRow };
      }
    }
    return { s: 8, g: 3, perRow: Math.floor((W + 3) / 11) };
  }
  function hideHelp() { dotsEl.classList.remove('show'); dotsEl.innerHTML = ''; }

  // ---- sounds ----
  function yay() { [523, 659, 784].forEach(function (f, i) { P.tone(f, { delay: i * 0.09, dur: 0.22, type: 'triangle', vol: 0.2 }); }); }
  function oops() { P.tone(260, { dur: 0.18, type: 'triangle', vol: 0.2 }); P.tone(200, { delay: 0.16, dur: 0.25, type: 'triangle', vol: 0.2 }); }
  function ding() { P.tone(880, { dur: 0.15, vol: 0.15 }); }
  function fanfare() { [523, 659, 784, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: i * 0.13, dur: 0.28, type: 'square', vol: 0.07 }); }); }
  function helpSound() { P.tone(659, { dur: 0.15, vol: 0.18 }); P.tone(988, { delay: 0.12, dur: 0.25, vol: 0.18 }); }
  function refill() { [392, 523, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: i * 0.15, dur: 0.25, type: 'triangle', vol: 0.15 }); }); }

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

  // ---- play ----
  function newRound() {
    busy = false; helped = false; nudges = 0; lastPick = null;
    task = makeTask(S.max);
    drawTask(); drawStats();
    ding();
    armIdle();
    broadcast();
  }

  function armIdle() {
    clearTimeout(idleTimer);
    if (nudges >= 2 || watch) return;
    idleTimer = setTimeout(function () {
      if (P.paused || busy) return;
      nudges++;
      if (S.food > 0 && !helped) { handEl.classList.remove('nudge'); void handEl.offsetWidth; handEl.classList.add('nudge'); }
      armIdle();
    }, 11000);
  }

  function answer(btn, v) {
    if (busy || P.paused || watch || !task || btn.classList.contains('bad')) return;
    armIdle();
    lastPick = v; ev++;
    if (v === task.ans) {
      busy = true;
      clearTimeout(idleTimer);
      btn.classList.add('ok');
      yay();
      var r = btn.getBoundingClientRect();
      sparkle(r.left + r.width / 2, r.top + r.height / 2);
      S.stars++; S.streak++;
      S.max = Math.min(TOP_MAX, S.max + 1);
      if (S.streak % 5 === 0 && S.hearts < HEARTS) S.hearts++;
      if (S.streak % 3 === 0 && S.food < FOOD) S.food++;
      starsEl.classList.add('bump'); later(function () { starsEl.classList.remove('bump'); }, 250);
      if (S.stars % 10 === 0) later(fanfare, 300);
      last = 'ok';
      persist(); drawStats();
      broadcast();
      later(newRound, S.stars % 10 === 0 ? 1600 : 1000);
    } else {
      oops();
      btn.classList.add('bad', 'shake');
      later(function () { btn.classList.remove('shake'); }, 500);
      S.max = Math.max(3, Math.ceil(S.max / 2));
      S.hearts = Math.max(0, S.hearts - 1);
      last = 'miss';
      if (S.hearts === 0) {
        // No game over: rest a moment, then the hearts fill up again.
        busy = true;
        later(function () { S.hearts = HEARTS; S.streak = 0; refill(); persist(); drawStats(); broadcast(); busy = false; }, 1200);
      }
      persist(); drawStats();
      broadcast();
    }
  }

  handEl.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    if (busy || P.paused || watch || helped || S.food <= 0 || !task) return;
    helped = true;
    S.food--;
    persist(); drawStats();
    helpSound();
    showHelp();
    broadcast();
  });

  P.onResize(function () { if (helped) showHelp(); });
  P.onPause(clearTimers);
  P.onResume(function () { if (!busy) armIdle(); else if (task) later(newRound, 300); });

  // ---- multiplayer: the player broadcasts, ?watch shows it ----
  var chan = P.net.channel();
  document.body.classList.add('mp');
  chan.onStatus(function (ok) { el('net').classList.toggle('on', ok); });

  var lastSend = 0, sendTimer = 0;
  function state() {
    return { task: task && { a: task.a, op: task.op, b: task.b, opts: task.opts },
      level: S.max, stars: S.stars, hearts: S.hearts, food: S.food, help: helped, last: last, pick: lastPick, ev: ev };
  }
  function broadcast() {
    if (watch) return;
    clearTimeout(sendTimer);
    var wait = SEND_GAP_MS - (Date.now() - lastSend);
    if (wait <= 0) { lastSend = Date.now(); chan.send(state()); }
    else sendTimer = setTimeout(broadcast, wait); // trailing send: the watcher always gets the latest state
  }

  if (watch) {
    chan.on(function (d) {
      if (!d || typeof d !== 'object') return;
      var t = d.task;
      var changed = !task || !t || task.a !== t.a || task.b !== t.b || task.op !== t.op;
      if (t && typeof t.a === 'number' && typeof t.b === 'number' && Array.isArray(t.opts)) {
        task = { a: t.a, op: String(t.op), b: t.b, ans: null, opts: t.opts.slice(0, 6) };
        if (changed) { drawTask(); ding(); }
      }
      S.stars = +d.stars || 0; S.hearts = +d.hearts || 0; S.food = +d.food || 0; S.max = +d.level || S.max;
      helped = !!d.help;
      drawStats();
      if (helped && !dotsEl.classList.contains('show')) showHelp();
      Array.prototype.forEach.call(optsEl.children, function (b) {
        var v = +b.dataset.v;
        b.classList.toggle('pick', d.pick === v);
        b.classList.toggle('ok', d.last === 'ok' && d.pick === v);
        b.classList.toggle('bad', d.last === 'miss' && d.pick === v);
      });
      if (d.ev !== seenEv && d.pick != null) {
        if (seenEv !== -1) { if (d.last === 'ok') yay(); else if (d.last === 'miss') oops(); }
      }
      seenEv = d.ev;
    });
    drawTask(); drawStats();
  } else {
    // First task sits behind the ▶ overlay; sounds and hints start after ▶.
    task = makeTask(S.max);
    drawTask(); drawStats();
    P.onStart(function () { ding(); armIdle(); broadcast(); });
  }
})();
