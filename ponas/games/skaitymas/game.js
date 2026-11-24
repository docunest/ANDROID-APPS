/* Skaitymas: Lithuanian reading and spelling levels on one runner.
 * Branch A (owner): 1 syllables by ear, 2 build a word from letters, 3 write the word.
 * Branch B (Montessori): 11 first/last sound, 12 moveable alphabet, 13 pink, 14 blue, 15 green series.
 * Every answer is logged (level, item, options, answer, per-letter result, hint, time) and sent to
 * MapPal (/api/reading/attempts) when the parent has entered the reading token. */
(function () {
  'use strict';
  var P = window.Ponas, D = window.SK_DATA;
  var API = 'https://map-pal-mike-base.vercel.app';   // allowed: meta.json "network" + the APK's ALLOWED_HOSTS
  var SESSION_MS = 10 * 60 * 1000;   // about 10 minutes, then a calm stop
  var ROUNDS = 10;                   // rounds per level visit, then back to the menu
  var MAX_DIFF = 6;

  var menuEl = document.getElementById('menu');
  var stageEl = document.getElementById('stage');
  var side = document.getElementById('side');
  var work = document.getElementById('work');
  var doneEl = document.getElementById('done');
  var panel = document.getElementById('panel');
  var progressEl = document.getElementById('progress');

  // ---------------------------------------------------------------- small helpers
  function rnd(n) { return Math.floor(Math.random() * n); }
  function shuffle(a) { for (var i = a.length - 1; i > 0; i--) { var j = rnd(i + 1), t = a[i]; a[i] = a[j]; a[j] = t; } return a; }
  function el(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }
  function uniq(a) { return a.filter(function (x, i) { return a.indexOf(x) === i; }); }
  function chars(w) { return Array.from(w); }
  var busy = false;
  function tap(node, fn) {
    node.addEventListener('pointerdown', function (e) { e.preventDefault(); if (P.paused || busy || over) return; fn(e); });
  }
  var ICON = {
    speaker: '<svg viewBox="0 0 24 24"><path d="M4 9h4l5-4v14l-5-4H4z"/><path d="M16 8.5a5 5 0 0 1 0 7l-1.4-1.4a3 3 0 0 0 0-4.2z"/></svg>',
    home: '<svg viewBox="0 0 24 24"><path d="M12 3 2 12h3v8h5v-5h4v5h5v-8h3z"/></svg>',
    check: '<svg viewBox="0 0 24 24"><path d="M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z"/></svg>',
    erase: '<svg viewBox="0 0 24 24"><path d="M19 6.4 17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"/></svg>',
    pencil: '<svg viewBox="0 0 24 24" fill="#5D4037"><path d="M3 17.25V21h3.75L17.8 9.94l-3.75-3.75zM20.7 7.04a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75z"/></svg>',
    star: '<svg viewBox="0 0 24 24" fill="#FFB300"><path d="M12 17.3 18.2 21l-1.6-7 5.4-4.7-7.2-.6L12 2 9.2 8.7l-7.2.6 5.4 4.7-1.6 7z"/></svg>'
  };

  // ---------------------------------------------------------------- sound (Ponas.audio, WebAudio)
  var buffers = {}, bufferOrder = [], current = null;
  function loadBuffer(url, cb) {
    if (buffers[url]) return cb(buffers[url]);
    var ctx = P.audio();
    if (!ctx) return cb(null);
    fetch(url).then(function (r) { return r.ok ? r.arrayBuffer() : null; }).then(function (ab) {
      if (!ab) return cb(null);
      ctx.decodeAudioData(ab, function (b) {
        buffers[url] = b; bufferOrder.push(url);
        if (bufferOrder.length > 60) delete buffers[bufferOrder.shift()];   // little memory on the tablet
        cb(b);
      }, function () { cb(null); });
    }).catch(function () { cb(null); });
  }
  function stopSound() {
    if (current) { try { current.onended = null; current.stop(); } catch (e) { /* already stopped */ } current = null; }
  }
  function play(url, done) {
    stopSound();
    loadBuffer(url, function (b) {
      var ctx = P.audio();
      if (!b || !ctx || P.paused) { if (done) done(); return; }
      var s = ctx.createBufferSource();
      s.buffer = b; s.connect(ctx.destination);
      s.onended = function () { if (current === s) { current = null; if (done) done(); } };
      current = s; s.start();
    });
  }
  function playSeq(urls, done) {
    var i = 0;
    (function next() { if (i >= urls.length) { if (done) done(); return; } play(urls[i++], next); })();
  }
  function good() { P.tone(660, { dur: 0.12 }); P.tone(880, { delay: 0.1, dur: 0.22 }); }
  function soft() { P.tone(220, { dur: 0.22, type: 'sine', vol: 0.12 }); }   // a mistake is soft, never a penalty
  var A = {
    syl: function (s) { return D.audio('syl', s); },
    word: function (w) { return D.audio('word', w); },
    letter: function (c) { return D.audio('letter', c.toLowerCase()); },
    group: function (g) { return D.audio('group', g); },
    prompt: function (k) { return 'audio/prompt-' + k + '.mp3'; }
  };

  // ---------------------------------------------------------------- per-level state: difficulty, weak items, deck
  function lv(id) { return P.load('lv' + id, { diff: 0, recent: [], miss: {} }); }
  function saveLv(id, s) { P.save('lv' + id, s); }
  // Aim for 80 to 85 % first-try success: harder after a strong run, easier after a weak one.
  function score(id, s, ok) {
    s.recent.push(ok ? 1 : 0);
    if (s.recent.length > 8) s.recent = s.recent.slice(-8);
    if (s.recent.length >= 6) {
      var rate = s.recent.reduce(function (a, b) { return a + b; }, 0) / s.recent.length;
      if (rate >= 0.9 && s.diff < MAX_DIFF) { s.diff++; s.recent = []; }
      else if (rate < 0.7 && s.diff > 0) { s.diff--; s.recent = []; }
    }
    saveLv(id, s);
  }
  function missed(id, s, item) {
    s.miss[item] = (s.miss[item] || 0) + 1;
    saveLv(id, s);
    var deck = P.load('deck' + id, []);   // a weak item comes back a few rounds later
    if (deck.indexOf(item) < 0) { deck.splice(Math.min(3, deck.length), 0, item); P.save('deck' + id, deck); }
  }
  function hit(id, s, item) { if (s.miss[item]) { s.miss[item]--; if (!s.miss[item]) delete s.miss[item]; saveLv(id, s); } }
  // Cycle through every item (a deck), weak items first when a new deck is dealt; never the same twice in a row.
  function nextItem(id, items, last) {
    var s = lv(id);
    var deck = P.load('deck' + id, []).filter(function (x) { return items.indexOf(x) >= 0; });
    if (!deck.length) {
      deck = shuffle(items.slice()).sort(function (a, b) { return (s.miss[b] || 0) - (s.miss[a] || 0); });
    }
    var i = deck[0] === last && deck.length > 1 ? 1 : 0;
    var item = deck.splice(i, 1)[0];
    P.save('deck' + id, deck);
    return item;
  }

  // ---------------------------------------------------------------- attempts: log and send
  var queue = P.load('queue', []);
  var sending = false;
  function uid() { return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10); }
  function log(a) {
    a.id = uid();
    a.at = new Date().toISOString();
    queue.push(a);
    if (queue.length > 3000) queue = queue.slice(-3000);
    P.save('queue', queue);
  }
  function setSync(st) { P.save('sync', st); }
  function send() {
    var token = P.load('token', '');
    if (!token || sending || !queue.length) return;
    sending = true;
    var batch = queue.slice(0, 50);
    var ids = {};
    batch.forEach(function (b) { ids[b.id] = 1; });
    fetch(API + '/api/reading/attempts', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + token },
      body: JSON.stringify({ attempts: batch })
    }).then(function (r) {
      sending = false;
      // 400 = the server can't take these rows; drop them so one bad row can't block the queue forever.
      if (r.ok || r.status === 400) {
        queue = queue.filter(function (q) { return !ids[q.id]; });
        P.save('queue', queue);
        setSync({ ok: r.ok, at: Date.now(), code: r.status });
        if (queue.length && r.ok) setTimeout(send, 400);
      } else {
        setSync({ ok: false, at: Date.now(), code: r.status });
      }
    }).catch(function () { sending = false; setSync({ ok: false, at: Date.now(), code: 0 }); });
  }

  // ---------------------------------------------------------------- session clock (paused with the game)
  var activeMs = 0, lastTick = Date.now(), clock = null, over = false;
  function startClock() { stopClock(); lastTick = Date.now(); clock = setInterval(tick, 1000); }
  function stopClock() { if (clock) { clearInterval(clock); clock = null; } }
  function tick() {
    var now = Date.now();
    activeMs += now - lastTick; lastTick = now;
    if (activeMs >= SESSION_MS && !over && !level) finishSession();
  }
  function finishSession() {
    over = true;
    stopSound();
    menuEl.classList.add('hidden'); stageEl.classList.add('hidden');
    doneEl.innerHTML = ICON.star;
    doneEl.classList.add('show');
    play(A.prompt('done'));
    send();
  }

  // ---------------------------------------------------------------- level frame
  var level = null;   // { def, rounds, last }
  function frame(opts) {
    side.innerHTML = ''; work.innerHTML = '';
    if (opts.picture) {
      var pic = el('div', 'pic');
      var img = el('img');
      img.src = D.image(opts.picture); img.alt = '';
      pic.appendChild(img);
      side.appendChild(pic);
    }
    if (opts.text) side.appendChild(el('div', 'word', opts.text));
    if (opts.sound) {
      var sp = el('button', 'round', ICON.speaker);
      tap(sp, opts.sound);
      side.appendChild(sp);
    }
    var home = el('button', 'round home', ICON.home);
    tap(home, goMenu);
    side.appendChild(home);
  }
  function roundDone() {
    send();
    level.rounds++;
    progressEl.style.width = (100 * level.rounds / ROUNDS) + '%';
    busy = true;
    setTimeout(function () {
      busy = false;
      if (over) return;
      if (level.rounds >= ROUNDS || activeMs >= SESSION_MS) { goMenu(); return; }
      level.def.run();
    }, 900);
  }
  function startLevel(def) {
    if (over) return;
    level = { def: def, rounds: 0, last: null };
    menuEl.classList.add('hidden');
    stageEl.classList.remove('hidden');
    progressEl.style.width = '0';
    def.run();
  }
  function goMenu() {
    stopSound();
    level = null;
    progressEl.style.width = '0';
    stageEl.classList.add('hidden');
    if (activeMs >= SESSION_MS) { finishSession(); return; }
    menuEl.classList.remove('hidden');
  }
  function tilesBox(parent) { var t = el('div', 'tiles'); parent.appendChild(t); return t; }
  function letterTile(ch, colored) {
    var b = el('button', 'tile' + (colored ? (D.isVowel(ch) ? ' vowel' : ' cons') : ''), ch);
    b.setAttribute('data-ch', ch);
    return b;
  }
  function cellsRow(n) {
    var row = el('div', 'cells'), cells = [];
    for (var i = 0; i < n; i++) { var c = el('div', 'cellbox'); row.appendChild(c); cells.push(c); }
    return { row: row, cells: cells };
  }
  function similarLetters(ch) { return (D.similar[ch] || []).slice(); }
  function randomLetters(exclude, n) {
    return shuffle(D.alphabet.filter(function (c) { return exclude.indexOf(c) < 0; })).slice(0, n);
  }
  // Decoy letters for a word: look-alike/sound-alike letters once the child is doing well.
  function letterDecoys(word, n, diff) {
    var w = chars(word), out = [];
    if (diff >= 2) shuffle(w.slice()).forEach(function (c) { similarLetters(c).forEach(function (s) { if (w.indexOf(s) < 0 && out.indexOf(s) < 0 && s.length === 1) out.push(s); }); });
    out = shuffle(out).slice(0, n);
    return out.concat(randomLetters(w.concat(out), n - out.length));
  }

  // ---------------------------------------------------------------- A1: hear a pair, tap it
  function a1Options(t, n, diff) {
    var pool = [], isDiph = D.diphthongs.indexOf(t) >= 0;
    var c = t[0], v = t[1];
    if (diff >= 4) {   // hardest: reversed and sound-alike pairs
      pool.push(t[1] + t[0]);
      if (isDiph) pool = pool.concat(D.diphSimilar[t] || []);
      else { similarLetters(c).forEach(function (s) { if (s.length === 1) pool.push(s + v); }); similarLetters(v).forEach(function (s) { pool.push(c + s); }); }
    }
    if (diff >= 2) {   // medium: same consonant or same vowel
      if (isDiph) pool = pool.concat(D.diphthongs.filter(function (d) { return d[0] === t[0] || d[1] === t[1]; }));
      else {
        D.vowels.forEach(function (x) { pool.push(c + x); });
        D.consonants.forEach(function (x) { pool.push(x + v); });
      }
    }
    var set = [t];
    shuffle(uniq(pool)).forEach(function (s) { if (set.length < Math.min(n, 3 + diff) && set.indexOf(s) < 0) set.push(s); });
    shuffle(D.syllables.filter(function (s) { return s[0] !== c && s[1] !== v; })).forEach(function (s) {
      if (set.length < n && set.indexOf(s) < 0) set.push(s);
    });
    return shuffle(set.slice(0, n));
  }
  function runA1() {
    var id = 1, s = lv(id);
    var t = nextItem(id, D.syllables, level.last); level.last = t;
    var opts = a1Options(t, Math.min(9, 3 + s.diff), s.diff);
    var t0 = Date.now(), first = true, done = false;
    frame({ sound: function () { play(A.syl(t)); } });
    var box = tilesBox(work);
    opts.forEach(function (o) {
      var b = el('button', 'tile', o);
      tap(b, function () {
        if (done || b.disabled) return;
        var ok = o === t;
        log({ level: id, item: t, options: opts, answer: o, correct: ok, first_try: first, hint_used: false, response_ms: Date.now() - t0 });
        if (ok) {
          done = true; b.classList.add('ok'); good();
          if (first) { hit(id, s, t); } score(id, s, first);
          roundDone();
        } else {
          if (first) { missed(id, s, t); }
          first = false; b.classList.add('no'); b.disabled = true; b.style.opacity = '.35'; soft();
        }
      });
      box.appendChild(b);
    });
    play(A.syl(t));
  }

  // ---------------------------------------------------------------- A2: picture, build the word from letters (instant feedback)
  function runA2() {
    var id = 2, s = lv(id);
    var word = nextItem(id, D.words5, level.last); level.last = word;
    var w = chars(word);
    var decoys = letterDecoys(word, Math.min(4, 2 + Math.floor(s.diff / 2)), s.diff);
    var tiles = shuffle(w.concat(decoys));
    var pos = 0, firstTaps = [], letterOk = [], wrongHere = 0, hint = false, t0 = Date.now(), done = false;
    frame({ picture: word, sound: function () { play(A.word(word)); } });
    var cr = cellsRow(w.length); work.appendChild(cr.row);
    var box = tilesBox(work), buttons = [];
    tiles.forEach(function (ch) {
      var b = letterTile(ch, false);
      buttons.push(b);
      tap(b, function () {
        if (done || b.classList.contains('used')) return;
        play(A.letter(ch));   // every tap says the letter's sound
        if (firstTaps[pos] === undefined) { firstTaps[pos] = ch; letterOk[pos] = ch === w[pos] ? 1 : 0; }
        if (ch === w[pos]) {
          b.classList.add('used');
          cr.cells[pos].textContent = ch; cr.cells[pos].classList.add('filled');
          pos++; wrongHere = 0;
          buttons.forEach(function (x) { x.classList.remove('hint'); });
          if (pos === w.length) finish();
        } else {
          soft(); b.classList.remove('no'); void b.offsetWidth; b.classList.add('no');
          if (++wrongHere >= 2) {   // after two misses the right letter pulses (logged as a hint)
            hint = true;
            for (var i = 0; i < buttons.length; i++) {
              if (!buttons[i].classList.contains('used') && buttons[i].getAttribute('data-ch') === w[pos]) { buttons[i].classList.add('hint'); break; }
            }
          }
        }
      });
      box.appendChild(b);
    });
    function finish() {
      done = true;
      var allOk = letterOk.every(function (x) { return x === 1; });
      log({ level: id, item: word, options: tiles, answer: firstTaps.join(''), correct: allOk, first_try: true, hint_used: hint, response_ms: Date.now() - t0, letter_ok: letterOk.join('') });
      if (allOk) hit(id, s, word); else missed(id, s, word);
      score(id, s, allOk);
      good();
      setTimeout(function () { play(A.word(word), roundDone); }, 350);
    }
    play(A.word(word));
  }

  // ---------------------------------------------------------------- A3: picture, write each letter (JS recognizer, no Google services)
  var G = 32, templates = null;
  function inkGrid(draw) {
    var c = document.createElement('canvas'); c.width = G; c.height = G;
    var x = c.getContext('2d'); draw(x);
    var d = x.getImageData(0, 0, G, G).data, g = new Uint8Array(G * G);
    for (var i = 0; i < G * G; i++) g[i] = d[i * 4 + 3] > 90 ? 1 : 0;
    return g;
  }
  function distMap(g) {
    var ink = [], i, j, m = new Float32Array(G * G);
    for (i = 0; i < G * G; i++) if (g[i]) ink.push(i);
    for (i = 0; i < G * G; i++) {
      var best = 99, x = i % G, y = (i / G) | 0;
      for (j = 0; j < ink.length; j++) {
        var dx = (ink[j] % G) - x, dy = ((ink[j] / G) | 0) - y, d = dx * dx + dy * dy;
        if (d < best) best = d;
      }
      m[i] = Math.sqrt(best);
    }
    return { grid: g, ink: ink, dist: m };
  }
  function glyph(ch) {
    var big = document.createElement('canvas'); big.width = 160; big.height = 160;
    var x = big.getContext('2d');
    x.font = '110px sans-serif'; x.textAlign = 'center'; x.textBaseline = 'middle'; x.fillStyle = '#000';
    x.fillText(ch, 80, 84);
    var d = x.getImageData(0, 0, 160, 160).data, x0 = 160, y0 = 160, x1 = -1, y1 = -1;
    for (var p = 0; p < 160 * 160; p++) if (d[p * 4 + 3] > 90) { var px = p % 160, py = (p / 160) | 0; if (px < x0) x0 = px; if (px > x1) x1 = px; if (py < y0) y0 = py; if (py > y1) y1 = py; }
    if (x1 < 0) return null;
    return distMap(inkGrid(function (g) { fitInto(g, x0, y0, x1 - x0 + 1, y1 - y0 + 1, function (sx, sy, sw, sh, dx, dy, dw, dh) { g.drawImage(big, sx, sy, sw, sh, dx, dy, dw, dh); }); }));
  }
  function fitInto(g, bx, by, bw, bh, draw) {
    var k = 26 / Math.max(bw, bh), dw = bw * k, dh = bh * k;
    draw(bx, by, bw, bh, (G - dw) / 2, (G - dh) / 2, dw, dh);
  }
  function buildTemplates() {
    if (templates) return;
    templates = [];
    D.alphabet.forEach(function (ch) {
      [ch, ch.toUpperCase()].forEach(function (form) { var t = glyph(form); if (t) templates.push({ ch: ch, t: t }); });
    });
  }
  function recognize(strokes) {
    var pts = [].concat.apply([], strokes);
    var x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    pts.forEach(function (p) { if (p[0] < x0) x0 = p[0]; if (p[0] > x1) x1 = p[0]; if (p[1] < y0) y0 = p[1]; if (p[1] > y1) y1 = p[1]; });
    var bw = Math.max(1, x1 - x0), bh = Math.max(1, y1 - y0);
    var user = distMap(inkGrid(function (g) {
      var k = 26 / Math.max(bw, bh), ox = (G - bw * k) / 2, oy = (G - bh * k) / 2;
      g.lineWidth = 2.6; g.lineCap = 'round'; g.lineJoin = 'round'; g.strokeStyle = '#000';
      strokes.forEach(function (s) {
        g.beginPath();
        s.forEach(function (p, i) { var X = ox + (p[0] - x0) * k, Y = oy + (p[1] - y0) * k; if (i) g.lineTo(X, Y); else { g.moveTo(X, Y); g.lineTo(X + 0.1, Y); } });
        g.stroke();
      });
    }));
    if (!user.ink.length) return [];
    var best = {};
    templates.forEach(function (tp) {
      var a = 0, b = 0;
      user.ink.forEach(function (i) { a += tp.t.dist[i]; });
      tp.t.ink.forEach(function (i) { b += user.dist[i]; });
      var sc = (a / user.ink.length + b / tp.t.ink.length) / 2;
      if (best[tp.ch] === undefined || sc < best[tp.ch]) best[tp.ch] = sc;
    });
    return Object.keys(best).map(function (ch) { return { ch: ch, score: best[ch] }; }).sort(function (p, q) { return p.score - q.score; });
  }
  var FAMILY = { 'ą': 'a', 'ę': 'e', 'ė': 'e', 'į': 'i', 'y': 'i', 'ų': 'u', 'ū': 'u', 'č': 'c', 'š': 's', 'ž': 'z' };
  function fam(c) { return FAMILY[c] || c; }
  // Generous with a 6-year-old's handwriting: the expected letter may be in the top three, and a missing
  // or wobbly accent mark (a/ą, e/ė, s/š…) still counts, since tiny marks are what a recognizer gets wrong most.
  function accepts(rank, expected) {
    if (!rank.length) return false;
    if (rank[0].ch === expected || fam(rank[0].ch) === fam(expected)) return true;
    for (var i = 1; i < Math.min(3, rank.length); i++) {
      if ((rank[i].ch === expected || fam(rank[i].ch) === fam(expected)) && rank[i].score <= rank[0].score * 1.3 && rank[i].score < 4) return true;
    }
    return false;
  }
  function runA3() {
    var id = 3, s = lv(id);
    buildTemplates();
    var word = nextItem(id, D.words5, level.last); level.last = word;
    var w = chars(word);
    var pos = 0, tries = 0, firstRec = [], letterOk = [], hint = false, t0 = Date.now(), done = false;
    frame({ picture: word, sound: function () { play(A.word(word)); } });
    var cr = cellsRow(w.length); work.appendChild(cr.row);
    cr.cells[0].classList.add('target');
    var pad = el('div'); pad.id = 'pad';
    var cv = el('canvas'); pad.appendChild(cv);
    var btns = el('div', 'btns');
    var okB = el('button', 'round', ICON.check), clrB = el('button', 'round home', ICON.erase);
    btns.appendChild(okB); btns.appendChild(clrB); pad.appendChild(btns);
    work.appendChild(pad);
    var ctx, strokes = [], cur = null, ratio = window.devicePixelRatio || 1;
    function sizeCanvas() {
      var r = cv.getBoundingClientRect();
      cv.width = Math.max(1, Math.round(r.width * ratio)); cv.height = Math.max(1, Math.round(r.height * ratio));
      ctx = cv.getContext('2d'); ctx.scale(ratio, ratio);
      ctx.lineWidth = 9; ctx.lineCap = 'round'; ctx.lineJoin = 'round'; ctx.strokeStyle = '#263238';
      strokes = [];
    }
    requestAnimationFrame(sizeCanvas);
    function point(e) { var r = cv.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; }
    cv.addEventListener('pointerdown', function (e) {
      e.preventDefault(); if (P.paused || done) return;
      cv.setPointerCapture(e.pointerId);
      cur = [point(e)]; strokes.push(cur);
      ctx.beginPath(); ctx.moveTo(cur[0][0], cur[0][1]); ctx.lineTo(cur[0][0] + 0.1, cur[0][1]); ctx.stroke();
    });
    cv.addEventListener('pointermove', function (e) {
      if (!cur) return;
      var p = point(e), q = cur[cur.length - 1];
      cur.push(p);
      ctx.beginPath(); ctx.moveTo(q[0], q[1]); ctx.lineTo(p[0], p[1]); ctx.stroke();   // draw only the new piece
    });
    function endStroke() { cur = null; }
    cv.addEventListener('pointerup', endStroke); cv.addEventListener('pointercancel', endStroke);
    function clear() { ctx.clearRect(0, 0, cv.width, cv.height); strokes = []; }
    tap(clrB, clear);
    tap(okB, function () {
      if (done || !strokes.length) return;
      var rank = recognize(strokes), exp = w[pos];
      if (firstRec[pos] === undefined) firstRec[pos] = rank.length ? rank[0].ch : '?';
      if (accepts(rank, exp)) {
        if (letterOk[pos] === undefined) letterOk[pos] = 1;
        fill(exp);
      } else {
        if (letterOk[pos] === undefined) letterOk[pos] = 0;
        tries++; soft(); clear();
        if (tries === 3) { hint = true; play(A.letter(exp)); }   // hint: hear the sound
        if (tries >= 5) { hint = true; fill(exp); }              // then help, so he isn't stuck
      }
    });
    function fill(ch) {
      play(A.letter(ch));
      cr.cells[pos].textContent = ch; cr.cells[pos].classList.remove('target'); cr.cells[pos].classList.add('filled');
      pos++; tries = 0; clear();
      if (pos < w.length) { cr.cells[pos].classList.add('target'); return; }
      done = true;
      var allOk = letterOk.every(function (x) { return x === 1; });
      log({ level: id, item: word, options: [], answer: firstRec.join('').slice(0, 16), correct: allOk, first_try: true, hint_used: hint, response_ms: Date.now() - t0, letter_ok: letterOk.join('') });
      if (allOk) hit(id, s, word); else missed(id, s, word);
      score(id, s, allOk);
      good();
      setTimeout(function () { play(A.word(word), roundDone); }, 400);
    }
    play(A.prompt('write'), function () { play(A.word(word)); });
  }

  // ---------------------------------------------------------------- B1 (Montessori sound game): first or last sound of a word
  var SOUND_WORDS = D.words5.concat(D.pink);
  function runB1() {
    var id = 11, s = lv(id);
    var word = nextItem(id, SOUND_WORDS, level.last); level.last = word;
    var w = chars(word);
    var mode = s.diff >= 2 && Math.random() < 0.5 ? 'last' : 'first';
    var target = mode === 'first' ? w[0] : w[w.length - 1];
    var n = s.diff >= 3 ? 4 : 3;
    var opts = [target];
    if (s.diff >= 2) similarLetters(target).forEach(function (c) { if (c.length === 1 && opts.length < n && opts.indexOf(c) < 0) opts.push(c); });
    randomLetters(opts.concat(w), n).forEach(function (c) { if (opts.length < n) opts.push(c); });
    opts = shuffle(opts);
    var item = word + '@' + mode, t0 = Date.now(), first = true, done = false;
    function say() { playSeq([A.prompt(mode), A.word(word)]); }
    frame({ picture: word, sound: say });
    var cr = cellsRow(w.length); work.appendChild(cr.row);   // the word's shape, the asked sound marked
    cr.cells[mode === 'first' ? 0 : w.length - 1].classList.add('target');
    var box = tilesBox(work);
    opts.forEach(function (c) {
      var b = letterTile(c, false);
      tap(b, function () {
        if (done || b.disabled) return;
        play(A.letter(c));
        var ok = c === target;
        log({ level: id, item: item, options: opts, answer: c, correct: ok, first_try: first, hint_used: false, response_ms: Date.now() - t0 });
        if (ok) {
          done = true; b.classList.add('ok'); good();
          var cell = cr.cells[mode === 'first' ? 0 : w.length - 1]; cell.textContent = c; cell.classList.add('filled');
          if (first) hit(id, s, word); score(id, s, first);
          setTimeout(roundDone, 500);
        } else {
          if (first) missed(id, s, word);
          first = false; b.disabled = true; b.style.opacity = '.35'; soft();
        }
      });
      box.appendChild(b);
    });
    say();
  }

  // ---------------------------------------------------------------- B2 (moveable alphabet): place letters freely, then check
  var BUILD_WORDS = D.pink.concat(D.words5);
  function runB2() {
    var id = 12, s = lv(id);
    var word = nextItem(id, BUILD_WORDS, level.last); level.last = word;
    var w = chars(word);
    var nDecoys = Math.max(0, Math.min(9 - w.length, 3 + s.diff));
    var tiles = shuffle(w.concat(letterDecoys(word, nDecoys, s.diff + 2)));
    var placed = new Array(w.length), checks = 0, firstCheck = null, t0 = Date.now(), done = false;
    frame({ picture: word, sound: function () { play(A.word(word)); } });
    var cr = cellsRow(w.length); work.appendChild(cr.row);
    var box = tilesBox(work), buttons = [];
    tiles.forEach(function (ch) {
      var b = letterTile(ch, true);   // Montessori colours: vowels blue, consonants red
      buttons.push(b);
      tap(b, function () {
        if (done || b.classList.contains('used')) return;
        var slot = -1;
        for (var i = 0; i < w.length; i++) if (!placed[i]) { slot = i; break; }   // next empty cell
        if (slot < 0) return;
        play(A.letter(ch));
        placed[slot] = b; b.classList.add('used');
        cr.cells[slot].textContent = ch;
      });
      box.appendChild(b);
    });
    cr.cells.forEach(function (cell, i) {   // tap a placed letter to take it back
      tap(cell, function () {
        if (done || !placed[i] || cell.classList.contains('filled')) return;
        placed[i].classList.remove('used'); placed[i] = null; cell.textContent = ''; cell.classList.remove('bad');
      });
    });
    var check = el('button', 'tile', ICON.check);
    check.style.background = '#26A69A'; check.querySelector('svg').style.cssText = 'width:60%;height:60%;fill:#fff';
    box.appendChild(check);
    tap(check, function () {
      if (done || !placed.every(Boolean)) return;   // check only when every cell has a letter
      checks++;
      var got = cr.cells.map(function (c) { return c.textContent; });
      if (!firstCheck) firstCheck = got.slice();
      var wrong = [];
      got.forEach(function (ch, i) {
        if (cr.cells[i].classList.contains('filled')) return;
        if (ch === w[i]) { cr.cells[i].classList.add('filled'); cr.cells[i].classList.remove('bad'); }
        else { cr.cells[i].classList.add('bad'); wrong.push(i); }
      });
      if (!wrong.length) {
        done = true;
        var letterOk = firstCheck.map(function (ch, i) { return ch === w[i] ? 1 : 0; });
        var allOk = checks === 1;
        log({ level: id, item: word, options: tiles, answer: firstCheck.join('').slice(0, 16), correct: allOk, first_try: true, hint_used: false, response_ms: Date.now() - t0, letter_ok: letterOk.join('') });
        if (allOk) hit(id, s, word); else missed(id, s, word);
        score(id, s, allOk);
        good();
        setTimeout(function () { play(A.word(word), roundDone); }, 350);
        return;
      }
      soft();
      setTimeout(function () {   // wrong letters go back to the box; right ones stay
        wrong.forEach(function (i) { if (placed[i]) { placed[i].classList.remove('used'); placed[i] = null; } cr.cells[i].textContent = ''; cr.cells[i].classList.remove('bad'); });
      }, 900);
    });
    playSeq([A.prompt('build'), A.word(word)]);
  }

  // ---------------------------------------------------------------- B3 pink / B4 blue: read the word, find its picture
  function runRead(id, list) {
    return function () {
      var s = lv(id);
      var word = nextItem(id, list, level.last); level.last = word;
      var n = s.diff >= 2 ? 4 : 3;
      var others = list.filter(function (x) { return x !== word; });
      // Harder: pictures whose words start with the same letter or have the same length.
      var near = s.diff >= 1 ? shuffle(others.filter(function (x) { return x[0] === word[0] || x.length === word.length; })) : [];
      var opts = [word];
      near.concat(shuffle(others)).forEach(function (x) { if (opts.length < n && opts.indexOf(x) < 0) opts.push(x); });
      opts = shuffle(opts);
      var t0 = Date.now(), first = true, done = false;
      frame({ text: word });   // no sound before answering: this is reading
      var box = el('div', 'cards'); work.appendChild(box);
      opts.forEach(function (x) {
        var b = el('button', 'card');
        var img = el('img'); img.src = D.image(x); img.alt = '';
        b.appendChild(img);
        tap(b, function () {
          if (done || b.disabled) return;
          var ok = x === word;
          log({ level: id, item: word, options: opts, answer: x, correct: ok, first_try: first, hint_used: false, response_ms: Date.now() - t0 });
          if (ok) {
            done = true; b.classList.add('ok'); good();
            if (first) hit(id, s, word); score(id, s, first);
            setTimeout(function () { play(A.word(word), roundDone); }, 300);   // the word is heard only after
          } else {
            if (first) missed(id, s, word);
            first = false; b.disabled = true; b.classList.add('no'); soft();
          }
        });
        box.appendChild(b);
      });
    };
  }

  // ---------------------------------------------------------------- B5 green series: which letter group is in the word?
  var GREEN_WORDS = D.green.map(function (g) { return g[0]; });
  var GROUP_NEAR = { uo: ['ui', 'au'], ie: ['ei', 'ui'], au: ['ai', 'uo'], ai: ['ei', 'au'], ei: ['ie', 'ai'], ui: ['uo', 'ie'], 'dž': ['ch', 'ie'], ch: ['dž', 'au'] };
  function runB5() {
    var id = 15, s = lv(id);
    var word = nextItem(id, GREEN_WORDS, level.last); level.last = word;
    var target = D.green.filter(function (g) { return g[0] === word; })[0][1];
    var n = s.diff >= 2 ? 4 : 3;
    var opts = [target];
    (s.diff >= 1 ? GROUP_NEAR[target] || [] : []).concat(shuffle(D.phonograms.slice())).forEach(function (g) {
      if (opts.length < n && opts.indexOf(g) < 0 && word.indexOf(g) < 0) opts.push(g);
    });
    opts = shuffle(opts);
    var t0 = Date.now(), first = true, done = false;
    function say() { playSeq([A.prompt('group'), A.word(word)]); }
    frame({ picture: word, sound: say });
    var box = tilesBox(work);
    opts.forEach(function (g) {
      var b = el('button', 'tile big', g);
      tap(b, function () {
        if (done || b.disabled) return;
        var ok = g === target;
        log({ level: id, item: word, options: opts, answer: g, correct: ok, first_try: first, hint_used: false, response_ms: Date.now() - t0 });
        if (ok) {
          done = true; b.classList.add('ok'); good();
          if (first) hit(id, s, word); score(id, s, first);
          setTimeout(function () { playSeq([A.group(g), A.word(word)], roundDone); }, 300);
        } else {
          if (first) missed(id, s, word);
          first = false; b.disabled = true; b.style.opacity = '.35'; soft();
        }
      });
      box.appendChild(b);
    });
    say();
  }

  // ---------------------------------------------------------------- menu
  var LEVELS = [
    { id: 1, tag: 'A1', color: '#FF8A65', face: '<b style="font-size:calc(var(--t)*.5)">ma</b>', run: runA1 },
    { id: 2, tag: 'A2', color: '#FFD54F', face: '<b style="font-size:calc(var(--t)*.3);letter-spacing:.1em">m□d□s</b>', run: runA2 },
    { id: 3, tag: 'A3', color: '#AED581', face: ICON.pencil, run: runA3 },
    { id: 11, tag: 'B1', color: '#B39DDB', face: '<b style="font-size:calc(var(--t)*.36)">m…</b>', run: runB1 },
    { id: 12, tag: 'B2', color: '#FFFFFF', face: '<b style="font-size:calc(var(--t)*.42)"><span style="color:#C62828">b</span><span style="color:#1565C0">a</span></b>', run: runB2 },
    { id: 13, tag: 'B3', color: '#F8BBD0', face: '<b style="font-size:calc(var(--t)*.28)">katė</b>', run: runRead(13, D.pink) },
    { id: 14, tag: 'B4', color: '#90CAF9', face: '<b style="font-size:calc(var(--t)*.24)">medis</b>', run: runRead(14, D.words5) },
    { id: 15, tag: 'B5', color: '#A5D6A7', face: '<b style="font-size:calc(var(--t)*.4)">uo</b>', run: runB5 }
  ];
  function drawMenu() {
    menuEl.innerHTML = '';
    [LEVELS.slice(0, 3), LEVELS.slice(3)].forEach(function (group) {
      var row = el('div', 'row');
      group.forEach(function (def) {
        var b = el('button', 'lvl', def.face + '<small>' + def.tag + '</small>');
        b.style.background = def.color;
        tap(b, function () { startLevel(def); });
        row.appendChild(b);
      });
      menuEl.appendChild(row);
    });
    var corner = el('div'); corner.id = 'parent';   // parent: hold the bottom-right corner for 3 s
    var hold = null;
    corner.addEventListener('pointerdown', function (e) { e.preventDefault(); hold = setTimeout(openPanel, 3000); });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (ev) { corner.addEventListener(ev, function () { clearTimeout(hold); }); });
    menuEl.appendChild(corner);
  }

  // ---------------------------------------------------------------- parent panel: reading token, sending, photo credits
  function fmt(t) { return t ? new Date(t).toLocaleString('lt-LT') : 'niekada'; }
  function openPanel() {
    var st = P.load('sync', null);
    panel.innerHTML = '';
    panel.appendChild(el('b', null, 'Tėvams'));
    var input = el('input'); input.type = 'text'; input.autocomplete = 'off'; input.placeholder = 'Skaitymo raktas (token)';
    input.value = P.load('token', '');
    panel.appendChild(input);
    var status = el('div', 'muted', 'Neišsiųsta atsakymų: ' + queue.length + '<br>Paskutinis siuntimas: ' +
      (st ? fmt(st.at) + (st.ok ? ' (gerai)' : ' (klaida ' + st.code + ')') : 'niekada'));
    panel.appendChild(status);
    var row = el('div', 'row');
    var save = el('button', 'act', 'Išsaugoti ir siųsti');
    save.addEventListener('click', function () { P.save('token', input.value.trim()); send(); setTimeout(openPanel, 1500); });
    var credits = el('button', 'act', 'Nuotraukų autoriai');
    credits.addEventListener('click', showCredits);
    var close = el('button', 'act', 'Uždaryti');
    close.addEventListener('click', function () { panel.classList.remove('show'); });
    row.appendChild(save); row.appendChild(credits); row.appendChild(close);
    panel.appendChild(row);
    panel.appendChild(el('div', 'muted', 'Garsai: kompiuterio balsas (Mbrola lt1, espeak-ng). Nuotraukos: Wikimedia Commons.'));
    panel.classList.add('show');
  }
  function showCredits() {
    fetch('img/credits.json').then(function (r) { return r.json(); }).then(function (c) {
      var list = el('div', 'muted');
      list.style.cssText = 'max-height:55vh;overflow:auto;text-align:left;font-size:13px';
      Object.keys(c).forEach(function (w) {
        var x = c[w];
        list.appendChild(el('div', null, '<b>' + w + '</b>: ' + (x.artist || '?') + ', ' + (x.license || '?') + '<br><small>' + x.page + '</small>'));
      });
      panel.innerHTML = '';
      panel.appendChild(list);
      var back = el('button', 'act', 'Atgal'); back.addEventListener('click', openPanel);
      panel.appendChild(back);
    }).catch(function () { /* no credits file: nothing to show */ });
  }

  // ---------------------------------------------------------------- Ponas lifecycle
  drawMenu();
  P.onStart(function () { startClock(); send(); });
  P.onPause(function () { stopClock(); stopSound(); });
  P.onResume(function () { startClock(); });
})();
