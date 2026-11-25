/* Darbai — Montessori Practical Life.
 * Sequence round: four picture cards of a real job (sandwich, brushing teeth, setting up camp…)
 * are mixed up; the child taps them in the order they happen and each one moves to its numbered slot.
 * Sorting round: three things to throw away, tap the right street bin (yellow, blue, green, brown).
 * Rounds alternate. Mistakes: soft sound, the card shakes, after two misses the right one pulses. */
(function () {
  'use strict';
  var P = window.Ponas, K = window.K, D = window.DARBAI;
  var $ = function (id) { return document.getElementById(id); };
  var roundNo = P.load('round', 0), busy = false, started = false;
  var job, next, misses, sortLeft, sortItem;

  function shuffle(a) {
    a = a.slice();
    for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)), t = a[i]; a[i] = a[j]; a[j] = t; }
    return a;
  }
  function el(cls, html) { var e = document.createElement('div'); e.className = cls; if (html) e.textContent = html; return e; }
  function tapOn(e, fn) { e.addEventListener('pointerdown', function (ev) { ev.preventDefault(); if (!P.paused && started) fn(); }); }

  // ---------------------------------------------------------------- sequence
  function newSeq() {
    var recent = P.load('recent', []);
    var pool = D.jobs.filter(function (j) { return recent.indexOf(j.id) < 0; });
    job = pool[Math.floor(Math.random() * pool.length)];
    recent.push(job.id); if (recent.length > 4) recent.shift(); P.save('recent', recent);
    $('seq').classList.remove('hidden'); $('sort').classList.add('hidden');
    var slots = $('slots'), cards = $('cards');
    slots.innerHTML = ''; cards.innerHTML = '';
    job.steps.forEach(function (s, i) {
      var sl = el('slot'), num = el('num');
      for (var k = 0; k <= i; k++) num.appendChild(document.createElement('i'));
      sl.appendChild(num); slots.appendChild(sl);
    });
    var order = shuffle([0, 1, 2, 3]);
    while (order.join() === '0,1,2,3') order = shuffle(order);   // never already in order
    order.forEach(function (i) {
      var c = el('card', job.steps[i][0]);
      c.dataset.step = i;
      tapOn(c, function () { tapCard(c, i); });
      cards.appendChild(c);
    });
    next = 0; misses = 0;
    if (started) promptSeq();
  }
  function promptSeq() {
    K.say('intro-seq');
    K.hint(function () { K.say('notyet'); pulseNext(); });
  }
  function pulseNext() {
    var c = $('cards').querySelector('[data-step="' + next + '"]');
    if (c) K.pulse(c);
  }
  function tapCard(c, i) {
    if (busy) return;
    if (c.parentNode !== $('cards')) { K.say('job-' + job.id + '-' + (i + 1)); return; }   // placed: replay its step
    if (i !== next) {
      misses++; K.oops(); K.shake(c); K.say('notyet');
      if (misses >= 2) pulseNext();
      return;
    }
    misses = 0; K.ok();
    var slot = $('slots').children[i], a = c.getBoundingClientRect();
    var ghost = el('card gone'); $('cards').replaceChild(ghost, c);   // keep the row from jumping
    slot.appendChild(c);
    var b = c.getBoundingClientRect();
    c.style.transform = 'translate(' + (a.left - b.left) + 'px,' + (a.top - b.top) + 'px)';
    void c.offsetWidth; c.classList.add('fly'); c.style.transform = '';
    next++;
    if (next < 4) { K.say('job-' + job.id + '-' + next); return; }
    busy = true; K.noHint();
    K.say(['job-' + job.id + '-4', 'done-seq'], finish);
  }

  // ---------------------------------------------------------------- sorting
  function newSort() {
    $('seq').classList.add('hidden'); $('sort').classList.remove('hidden');
    var bins = $('bins'); bins.innerHTML = '';
    D.bins.forEach(function (b) {
      var e = el('bin'); e.style.setProperty('--b', b.color); e.dataset.bin = b.id;
      tapOn(e, function () { tapBin(b, e); });
      bins.appendChild(e);
    });
    var items = shuffle(D.items.map(function (it, i) { return [it, i]; }));
    sortLeft = [items[0], items[1], items[2]];
    nextItem();
  }
  function nextItem() {
    sortItem = sortLeft.shift(); misses = 0;
    var it = $('item'); it.textContent = sortItem[0][0]; it.style.transition = 'none'; it.style.transform = ''; it.style.opacity = 1;
    if (started) promptSort();
  }
  function promptSort() {
    K.say(['item-' + sortItem[1], 'intro-sort']);
    K.hint(function () {
      K.say(['item-' + sortItem[1], 'intro-sort']);
      K.pulse($('bins').querySelector('[data-bin="' + sortItem[0][1] + '"]'));
    });
  }
  function tapBin(b, e) {
    if (busy) return;
    if (b.id !== sortItem[0][1]) {
      misses++; K.oops(); K.shake(e); K.say('wrongbin');
      if (misses >= 2) K.pulse($('bins').querySelector('[data-bin="' + sortItem[0][1] + '"]'));
      return;
    }
    busy = true; K.ok();
    var it = $('item'), a = it.getBoundingClientRect(), r = e.getBoundingClientRect();
    it.style.transition = 'transform .5s ease-in, opacity .5s ease-in';
    it.style.transform = 'translate(' + (r.left + r.width / 2 - a.left - a.width / 2) + 'px,' + (r.top + r.height / 3 - a.top - a.height / 2) + 'px) scale(.3)';
    it.style.opacity = 0;
    K.say(['right', 'bin-' + b.id], function () {
      busy = false;
      if (sortLeft.length) nextItem(); else { busy = true; K.noHint(); finish(); }
    });
  }

  // ---------------------------------------------------------------- rounds
  function finish() {
    roundNo++; P.save('round', roundNo);
    var five = K.addStar();
    if (five) { K.say('bravo'); K.celebrate(function () { K.later(newRound, 400); }); }
    else K.later(newRound, 800);
  }
  function newRound() {
    busy = false;
    if (roundNo % 2 === 0) newSeq(); else newSort();
  }

  K.stars($('stars'));
  P.onStart(function () { started = true; if (roundNo % 2 === 0) promptSeq(); else promptSort(); });
  P.onResume(function () { if (busy) newRound(); });
  newRound();
})();
