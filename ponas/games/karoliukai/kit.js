/* kit.js — small helpers shared by the Montessori games (a copy lives in each game folder,
 * because every game must be self-contained). Needs ponas.js first.
 *   K.later(fn, ms)          timeout that is cleared on pause (K.clearTimers)
 *   K.say(name|[names], done, {gain})   plays audio/<name>.mp3 (WebAudio), one after another
 *   K.stop()                 stops the voice
 *   K.ok() / K.oops()        short tones
 *   K.stars(container) → K.addStar() returns true on the 5th star (then K.celebrate())
 *   K.shake(el) / K.pulse(el)
 *   K.hint(fn)               calls fn after ~11 s without input, at most twice per K.hint() call
 */
(function () {
  'use strict';
  var P = window.Ponas;
  var K = {};
  var timers = [];
  K.later = function (fn, ms) {
    var t = setTimeout(function () { timers.splice(timers.indexOf(t), 1); fn(); }, ms);
    timers.push(t); return t;
  };
  K.clearTimers = function () { while (timers.length) clearTimeout(timers.pop()); };

  // ---------------------------------------------------------------- voice and sounds
  var buffers = {}, order = [], current = null, seqId = 0;
  function load(name, cb) {
    if (buffers[name]) return cb(buffers[name]);
    var ctx = P.audio();
    if (!ctx) return cb(null);
    fetch('audio/' + name + '.mp3').then(function (r) { return r.ok ? r.arrayBuffer() : null; }).then(function (ab) {
      if (!ab) return cb(null);
      ctx.decodeAudioData(ab, function (b) {
        buffers[name] = b; order.push(name);
        if (order.length > 50) delete buffers[order.shift()];   // little memory on the tablet
        cb(b);
      }, function () { cb(null); });
    }).catch(function () { cb(null); });
  }
  K.preload = function (names) { names.forEach(function (n) { load(n, function () {}); }); };
  K.stop = function () {
    seqId++;
    if (current) { try { current.onended = null; current.stop(); } catch (e) { /* stopped */ } current = null; }
  };
  K.say = function (names, done, opt) {
    K.stop();
    var my = seqId, i = 0;
    names = [].concat(names);
    var gain = opt && opt.gain != null ? opt.gain : 1;
    (function next() {
      if (my !== seqId) return;
      if (i >= names.length) { if (done) done(); return; }
      var n = names[i++];
      load(n, function (b) {
        var ctx = P.audio();
        if (my !== seqId) return;
        if (!b || !ctx || P.paused) { next(); return; }
        var s = ctx.createBufferSource(), g = ctx.createGain();
        g.gain.value = gain;
        s.buffer = b; s.connect(g); g.connect(ctx.destination);
        s.onended = function () { if (current === s) current = null; K.later(next, 120); };
        current = s; s.start();
      });
    })();
  };

  K.ok = function () { P.tone(660, { dur: 0.12 }); P.tone(880, { delay: 0.1, dur: 0.18 }); };
  K.oops = function () { P.tone(220, { dur: 0.18, type: 'triangle', vol: 0.25 }); };
  K.fanfare = function () {
    [523, 659, 784, 1047].forEach(function (f, i) { P.tone(f, { delay: i * 0.13, dur: 0.22 }); });
    P.tone(1047, { delay: 0.6, dur: 0.5 });
  };

  // ---------------------------------------------------------------- stars and celebration
  var starBox = null, stars = 0;
  K.stars = function (el) {
    starBox = el; stars = P.load('stars', 0) % 5;
    el.innerHTML = '';
    for (var i = 0; i < 5; i++) { var s = document.createElement('i'); s.className = 'kstar' + (i < stars ? ' on' : ''); el.appendChild(s); }
  };
  K.addStar = function () {
    stars++;
    P.save('stars', stars % 5);
    P.save('starsTotal', P.load('starsTotal', 0) + 1);
    if (starBox) starBox.children[Math.min(stars, 5) - 1].className = 'kstar on';
    if (stars >= 5) { stars = 0; return true; }
    return false;
  };
  K.celebrate = function (done) {
    K.fanfare();
    var cols = ['#FFC107', '#FF7043', '#66BB6A', '#42A5F5', '#AB47BC'];
    var w = innerWidth, h = innerHeight, bits = [];
    for (var i = 0; i < 14; i++) {
      var b = document.createElement('div');
      b.className = 'kbit';
      b.style.left = (w / 2 - 10) + 'px'; b.style.top = (h / 2 - 10) + 'px';
      b.style.background = cols[i % cols.length];
      document.body.appendChild(b); bits.push(b);
    }
    requestAnimationFrame(function () { requestAnimationFrame(function () {
      bits.forEach(function (b) {
        var a = Math.random() * Math.PI * 2, r = 0.35 * Math.max(w, h) + Math.random() * 80;
        b.style.transform = 'translate(' + Math.cos(a) * r + 'px,' + Math.sin(a) * r + 'px) rotate(' + (Math.random() * 540) + 'deg)';
        b.style.opacity = '0';
      });
    }); });
    K.later(function () {
      bits.forEach(function (b) { b.remove(); });
      if (starBox) Array.prototype.forEach.call(starBox.children, function (s) { s.className = 'kstar'; });
      if (done) done();
    }, 1500);
  };

  K.shake = function (el) { el.classList.remove('kshake'); void el.offsetWidth; el.classList.add('kshake'); };
  K.pulse = function (el) { el.classList.remove('kpulse'); void el.offsetWidth; el.classList.add('kpulse'); };

  // ---------------------------------------------------------------- idle hint (max 2 per round)
  var hintFn = null, hintLeft = 0, hintT = null;
  function arm() {
    clearTimeout(hintT);
    if (!hintFn || hintLeft <= 0 || P.paused) return;
    hintT = setTimeout(function () { if (P.paused || !hintFn) return; hintLeft--; hintFn(); arm(); }, 11000);
  }
  K.hint = function (fn) { hintFn = fn; hintLeft = 2; arm(); };
  K.noHint = function () { hintFn = null; clearTimeout(hintT); };
  document.addEventListener('pointerdown', function () { arm(); }, true);

  P.onPause(function () { K.clearTimers(); K.stop(); clearTimeout(hintT); });
  P.onResume(function () { arm(); });

  window.K = K;
})();
