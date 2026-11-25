/* Karoliukai — Montessori golden beads (place value).
 * Build: hear a number from road life ("Iki kempingo liko 37 kilometrai"), lay it out with
 * beads (1), bars (10) and squares (100), then press the green tick. Ten of a kind are
 * exchanged automatically, with the voice saying why (the "bank game").
 * Count: the beads are laid out; pick the number among three cards.
 * Montessori colours on the number cards: hundreds red, tens blue, units green. */
(function () {
  'use strict';
  var P = window.Ponas, K = window.K;
  var $ = function (id) { return document.getElementById(id); };
  var colH = $('colH'), colT = $('colT'), colU = $('colU'), card = $('card'), choices = $('choices'), ctxEl = $('ctx');

  // level: [min, max, has hundreds]; move up after 6 first-try answers in a level
  var LEVELS = [[1, 9], [11, 39], [10, 99], [100, 999]];
  var lvl = Math.min(P.load('lvl', 0), LEVELS.length - 1), streak = P.load('streak', 0), roundNo = 0;
  var target, mode, have = { h: 0, t: 0, u: 0 }, tries, busy = false, started = false;
  var CTX = [['km', '🚐'], ['l', '⛽'], ['st', '🪨']];
  var ctx;

  function rnd(a, b) { return a + Math.floor(Math.random() * (b - a + 1)); }
  function digits(n) { return { h: Math.floor(n / 100), t: Math.floor(n / 10) % 10, u: n % 10 }; }
  function words(n) {                       // clips for a number: "h1" + "n37"
    var w = [], d = digits(n);
    if (d.h) w.push('h' + d.h);
    if (n % 100) w.push('n' + (n % 100));
    return w;
  }
  function form(n) { var r = n % 100; return (r % 10 === 0 || (r > 10 && r < 20)) ? '0' : (r % 10 === 1 ? '1' : '2'); }
  function cardHtml(n) {
    var s = String(n), cls = ['du', 'dt', 'dh'], out = '';
    for (var i = 0; i < s.length; i++) out += '<span class="' + cls[s.length - 1 - i] + '">' + s[i] + '</span>';
    return out;
  }

  // ---------------------------------------------------------------- drawing the mat
  function el(cls) { var e = document.createElement('div'); e.className = cls; return e; }
  function draw(anim) {
    fill(colH, 'sq', have.h, anim === 'h');
    fill(colT, 'bar', have.t, anim === 't');
    fill(colU, 'bead', have.u, anim === 'u');
  }
  function fill(col, cls, n, popLast) {
    while (col.children.length > n) col.removeChild(col.lastChild);
    while (col.children.length < n) col.appendChild(el(cls));
    if (popLast && n) col.lastChild.classList.add('pop');
  }
  function sizeBeads() {        // one bead size so a bar (10 beads) and a square fit the mat
    var m = $('mat'), H = m.clientHeight - 32, W = m.clientWidth - 40;
    var hasH = LEVELS[lvl][0] >= 100;
    var d = Math.min(H / 10.4, 30);
    if (hasH) d = Math.min(d, (W - 60) / (10 + 8 + 9 * 1.35 + 2.6 + 3));
    else d = Math.min(d, (W - 40) / (9 * 1.35 + 2.6 + 2));
    document.body.style.setProperty('--d', Math.max(8, Math.floor(d)) + 'px');
    colH.classList.toggle('hidden', !hasH);
    $('addH').classList.toggle('hidden', !hasH);
    $('addT').classList.toggle('hidden', lvl === 0);
    colT.classList.toggle('hidden', lvl === 0);
  }

  // ---------------------------------------------------------------- rounds
  function newRound() {
    var L = LEVELS[lvl];
    var n; do { n = rnd(L[0], L[1]); } while (n === target);
    target = n; tries = 0; busy = false;
    mode = (roundNo % 3 === 2) ? 'count' : 'build';
    roundNo++;
    ctx = CTX[rnd(0, CTX.length - 1)];
    sizeBeads();
    choices.innerHTML = '';
    if (mode === 'build') {
      have = { h: 0, t: 0, u: 0 };
      card.classList.remove('hidden'); card.innerHTML = cardHtml(target);
      ctxEl.textContent = ctx[1];
      $('supply').classList.remove('hidden');
    } else {
      have = digits(target);
      card.classList.add('hidden'); ctxEl.textContent = '';
      $('supply').classList.add('hidden');
      var opts = [target], L2 = LEVELS[lvl];
      while (opts.length < 3) {
        var o = Math.random() < 0.6 ? target + [-10, 10, -1, 1, -100, 100][rnd(0, lvl >= 3 ? 5 : lvl >= 1 ? 3 : 1)] : rnd(L2[0], L2[1]);
        if (o >= L2[0] && o <= L2[1] && opts.indexOf(o) < 0) opts.push(o);
      }
      opts.sort(function () { return Math.random() - 0.5; }).forEach(function (o) {
        var c = el('card'); c.innerHTML = cardHtml(o);
        c.addEventListener('pointerdown', function (e) { e.preventDefault(); pick(o, c); });
        choices.appendChild(c);
      });
    }
    draw();
    if (started) prompt();
  }
  function prompt() {
    if (mode === 'build') {
      K.say(['ctx-' + ctx[0]].concat(words(target), [ctx[0] + form(target), 'build']).concat(roundNo <= 1 ? ['help-build'] : []));
      K.hint(function () { K.say(words(target).concat(['build'])); });
    } else {
      K.say('count');
      K.hint(function () { K.say('count'); });
    }
  }

  function add(kind) {
    if (P.paused || busy || !started || mode !== 'build') return;
    if ((kind === 'h' && have.h >= 9) || (kind === 'u' && lvl === 0 && have.u >= 9)) { K.oops(); K.say('many'); return; }
    have[kind]++;
    P.tone(kind === 'u' ? 880 : kind === 't' ? 660 : 520, { dur: 0.08 });
    draw(kind);
    if (kind === 'u' && have.u === 10) exchange('u', 't', 'swap');
    else if (kind === 't' && have.t === 10) {
      if (LEVELS[lvl][0] >= 100) exchange('t', 'h', 'swap100');
      else { have.t = 9; draw(); K.oops(); K.say('many'); }
    }
  }
  function exchange(from, to, clip) {
    busy = true;
    var col = from === 'u' ? colU : colT;
    Array.prototype.forEach.call(col.children, function (c) { c.classList.add('glow'); });
    K.say(clip, function () {
      have[from] = 0; have[to]++; draw(to); busy = false;
      if (to === 't' && have.t === 10 && LEVELS[lvl][0] >= 100) exchange('t', 'h', 'swap100');
    });
  }
  function remove(kind) {
    if (P.paused || busy || !started || mode !== 'build' || !have[kind]) return;
    have[kind]--; P.tone(330, { dur: 0.08, type: 'triangle' }); draw();
  }

  function check() {
    if (P.paused || busy || !started || mode !== 'build') return;
    var v = have.h * 100 + have.t * 10 + have.u;
    tries++;
    if (v === target) return success(tries === 1);
    K.oops(); K.shake($('mat'));
    K.say(v < target ? 'few' : 'many');
    if (tries >= 2) {        // show which column is off
      var d = digits(target);
      [['h', colH], ['t', colT], ['u', colU]].forEach(function (p) { if (have[p[0]] !== d[p[0]]) K.pulse(p[1]); });
    }
  }
  function pick(o, c) {
    if (P.paused || busy || !started) return;
    tries++;
    if (o === target) { K.say(words(o)); return success(tries === 1, 900); }
    K.oops(); K.shake(c); K.say(words(o).concat(['tryagain']));
  }

  function success(first, delay) {
    busy = true; K.noHint(); K.ok();
    if (first) { streak++; if (streak >= 6 && lvl < LEVELS.length - 1) { lvl++; streak = 0; } }
    P.save('lvl', lvl); P.save('streak', streak);
    var five = K.addStar();
    K.later(function () {
      K.say(mode === 'build' ? ['right'].concat(words(target)) : ['right'], function () {
        if (five) { K.say('bravo'); K.celebrate(function () { K.later(newRound, 500); }); }
        else K.later(newRound, 600);
      });
    }, delay || 0);
  }

  function tapOn(id, fn) { $(id).addEventListener('pointerdown', function (e) { e.preventDefault(); fn(); }); }
  tapOn('addU', function () { add('u'); }); tapOn('addT', function () { add('t'); }); tapOn('addH', function () { add('h'); });
  tapOn('colU', function () { remove('u'); }); tapOn('colT', function () { remove('t'); }); tapOn('colH', function () { remove('h'); });
  tapOn('ok', check);
  tapOn('card', function () { if (started && !busy) K.say(words(target)); });

  K.stars($('stars'));
  P.onResize(function () { sizeBeads(); });
  P.onStart(function () { started = true; prompt(); });
  P.onResume(function () { if (busy) newRound(); else prompt(); });
  newRound();
})();
