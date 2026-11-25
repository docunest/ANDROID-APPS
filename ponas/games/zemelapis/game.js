/* Žemėlapis — Montessori puzzle map of Spain and Portugal, from real border data
 * (Natural Earth, see mapdata.js and .tools/make-map.py) and real photos (Wikimedia Commons,
 * credits.json).
 * Pieces round: drag Spain and Portugal into their dashed outlines; they snap when close.
 * Cities round: a photo of a city is shown and named; tap its dot on the map (3 dots to choose from).
 * One-level test (owner 2026-11-25): more pieces (regions) only if this one works. */
(function () {
  'use strict';
  var P = window.Ponas, K = window.K, M = window.MAP;
  var NS = 'http://www.w3.org/2000/svg';
  var game = document.getElementById('game'), svg = document.getElementById('map'), photo = document.getElementById('photo');
  var COLORS = { ispanija: '#FFB74D', portugalija: '#81C784' };
  var CITIES = ['lisabona', 'portas', 'madridas', 'barselona', 'sevilija', 'santjagas'];
  var roundNo = P.load('round', 0), busy = false, started = false, mode;
  var scale = 1, mapX = 0, mapY = 0, side = {}, pieces = {}, dots = [], cityQueue, city, misses = 0;

  function sv(tag, attrs) { var e = document.createElementNS(NS, tag); for (var k in attrs) e.setAttribute(k, attrs[k]); return e; }

  // ---------------------------------------------------------------- base map (sea, neighbours, outlines)
  svg.setAttribute('viewBox', '0 0 ' + M.w + ' ' + M.h);
  svg.appendChild(sv('rect', { 'class': 'sea', x: 0, y: 0, width: M.w, height: M.h, rx: 30 }));
  svg.appendChild(sv('path', { 'class': 'land', d: M.others }));
  var slots = {};
  Object.keys(M.pieces).forEach(function (k) {
    slots[k] = sv('path', { 'class': 'slot', d: M.pieces[k].d });
    svg.appendChild(slots[k]);
  });

  // a piece is its own small SVG cut to its bounding box, drawn at the map's scale
  Object.keys(M.pieces).forEach(function (k) {
    var b = M.pieces[k].box, s = sv('svg', { 'class': 'piece', viewBox: b[0] + ' ' + b[1] + ' ' + (b[2] - b[0]) + ' ' + (b[3] - b[1]) });
    s.appendChild(sv('path', { d: M.pieces[k].d, fill: COLORS[k] }));
    game.appendChild(s);
    pieces[k] = { el: s, placed: false, x: 0, y: 0 };
    drag(k);
  });

  function layout() {
    var W = game.clientWidth, H = game.clientHeight, port = H > W;
    var mw = port ? W - 16 : W - 190, mh = port ? H - 190 : H - 12;
    scale = Math.min(mw / M.w, mh / M.h);
    var w = M.w * scale, h = M.h * scale;
    mapX = port ? (W - w) / 2 : 8 + (mw - w) / 2; mapY = port ? 4 : (H - h) / 2;
    svg.style.left = mapX + 'px'; svg.style.top = mapY + 'px'; svg.style.width = w + 'px'; svg.style.height = h + 'px';
    // the side area (right in landscape, bottom in portrait): loose pieces or the photo
    side = port ? { x: 8, y: mapY + h + 8, w: W - 16, h: H - (mapY + h + 8) - 6 } : { x: mapX + w + 10, y: 6, w: W - (mapX + w + 10) - 8, h: H - 12 };
    var ph = Math.min(side.w, side.h, 220);
    photo.style.width = ph + 'px'; photo.style.height = ph + 'px';
    photo.style.left = (side.x + (side.w - ph) / 2) + 'px'; photo.style.top = (side.y + (side.h - ph) / 2) + 'px';
    var keys = Object.keys(pieces), i = 0;
    keys.forEach(function (k) {
      var p = pieces[k], b = M.pieces[k].box, pw = (b[2] - b[0]) * scale, phh = (b[3] - b[1]) * scale;
      p.el.style.width = pw + 'px'; p.el.style.height = phh + 'px';
      p.home = { x: mapX + b[0] * scale, y: mapY + b[1] * scale };       // where it belongs
      // loose pieces wait in the side area, shrunk to fit; they grow to map size when picked up
      var cw = port ? side.w / keys.length - 10 : side.w, ch = port ? side.h : side.h / keys.length - 10;
      var ts = Math.min(1, cw / pw, ch / phh);
      if (port) p.start = { x: side.x + (i + 0.5) * side.w / keys.length - pw * ts / 2, y: side.y + (side.h - phh * ts) / 2, s: ts };
      else p.start = { x: side.x + (side.w - pw * ts) / 2, y: side.y + (i + 0.5) * side.h / keys.length - phh * ts / 2, s: ts };
      i++;
      moveTo(p, p.placed ? p.home : p.start);
    });
    dots.forEach(placeDot);
  }
  function moveTo(p, at) {
    p.x = at.x; p.y = at.y; p.s = at.s || 1;
    p.el.style.transform = 'translate(' + at.x + 'px,' + at.y + 'px) scale(' + p.s + ')';
  }

  // ---------------------------------------------------------------- dragging pieces
  function drag(k) {
    var p = pieces[k], el = p.el, sx, sy, ox, oy, moved, id = null;
    el.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      if (P.paused || !started || busy || p.placed || mode !== 'pieces') return;
      game.appendChild(el);                                // on top of the other piece (before capturing the finger)
      id = e.pointerId; el.setPointerCapture(id); el.classList.remove('back');
      var g = game.getBoundingClientRect();           // piece positions are relative to #game
      sx = e.clientX - g.left; sy = e.clientY - g.top; moved = false;
      // grow to map size around the finger
      ox = sx - (sx - p.x) / p.s; oy = sy - (sy - p.y) / p.s;
      moveTo(p, { x: ox, y: oy });
    });
    el.addEventListener('pointermove', function (e) {
      if (e.pointerId !== id) return;
      var g = game.getBoundingClientRect(), dx = e.clientX - g.left - sx, dy = e.clientY - g.top - sy;
      if (Math.abs(dx) + Math.abs(dy) > 6) moved = true;
      moveTo(p, { x: ox + dx, y: oy + dy });
    });
    function up(e) {
      if (e.pointerId !== id) return;
      id = null;
      if (!moved) { el.classList.add('back'); moveTo(p, p.start); K.say('name-' + k); return; }
      var d = Math.hypot(p.x - p.home.x, p.y - p.home.y);
      if (d < Math.max(36, 70 * scale)) return snap(k);
      misses++;
      el.classList.add('back'); moveTo(p, p.start);
      K.oops();
      // dropped over the map in the wrong place: say so; after 2 misses its outline pulses
      K.say('notthere');
      if (misses >= 2) { slots[k].style.fill = '#FFF59D'; K.later(function () { slots[k].style.fill = ''; }, 1800); }
    }
    el.addEventListener('pointerup', up);
    el.addEventListener('pointercancel', up);
  }
  function snap(k) {
    var p = pieces[k];
    p.placed = true; misses = 0;
    p.el.classList.add('back', 'placed'); moveTo(p, p.home);
    slots[k].classList.add('full');
    K.ok();
    var all = Object.keys(pieces).every(function (q) { return pieces[q].placed; });
    if (!all) { K.say('placed-' + k); return; }
    busy = true; K.noHint();
    K.say(['placed-' + k, 'done-pieces'], finish);
  }

  // ---------------------------------------------------------------- cities
  function placeDot(d) { d.el.style.left = (mapX + d.pos[0] * scale) + 'px'; d.el.style.top = (mapY + d.pos[1] * scale) + 'px'; }
  function shuffle(a) {
    a = a.slice();
    for (var i = a.length - 1; i > 0; i--) { var j = Math.floor(Math.random() * (i + 1)), t = a[i]; a[i] = a[j]; a[j] = t; }
    return a;
  }
  function nextCity() {
    dots.forEach(function (d) { d.el.remove(); }); dots = [];
    city = cityQueue.shift(); misses = 0;
    photo.querySelector('img').src = 'img/' + city + '.jpg';
    var others = shuffle(CITIES.filter(function (c) { return c !== city; })).slice(0, 2);
    shuffle([city].concat(others)).forEach(function (c) {
      var e = document.createElement('div'); e.className = 'dot'; e.innerHTML = '<i></i>';
      var d = { el: e, c: c, pos: M.cities[c] };
      e.addEventListener('pointerdown', function (ev) { ev.preventDefault(); if (!P.paused && started && !busy) tapDot(d); });
      game.appendChild(e); dots.push(d); placeDot(d);
    });
    if (started) promptCity();
  }
  function promptCity() {
    K.say(['city-' + city, 'intro-cities']);
    K.hint(function () {
      K.say(['city-' + city, 'intro-cities']);
      dots.forEach(function (d) { if (d.c === city) K.pulse(d.el); });
    });
  }
  function tapDot(d) {
    if (d.c !== city) {
      misses++; K.oops(); K.shake(d.el); K.say('wrongdot');
      if (misses >= 2) dots.forEach(function (x) { if (x.c === city) K.pulse(x.el); });
      return;
    }
    busy = true; K.ok(); d.el.classList.add('ok');
    K.say('right', function () {
      if (cityQueue.length) { busy = false; nextCity(); }
      else K.say('done-cities', finish);
    });
  }

  // ---------------------------------------------------------------- rounds
  function newRound() {
    busy = false; misses = 0;
    mode = roundNo % 2 === 0 ? 'pieces' : 'cities';
    dots.forEach(function (d) { d.el.remove(); }); dots = [];
    var inPlace = mode === 'cities';
    Object.keys(pieces).forEach(function (k) {
      pieces[k].placed = inPlace;
      pieces[k].el.classList.remove('back');
      pieces[k].el.classList.toggle('placed', inPlace);
      slots[k].classList.toggle('full', inPlace);
    });
    photo.classList.toggle('hidden', mode !== 'cities');
    layout();
    if (mode === 'cities') { cityQueue = shuffle(CITIES).slice(0, 3); nextCity(); }
    else if (started) promptPieces();
  }
  function promptPieces() { K.say('intro-pieces'); K.hint(function () { K.say('intro-pieces'); }); }
  function finish() {
    busy = true; K.noHint();
    roundNo++; P.save('round', roundNo);
    if (K.addStar()) { K.say('bravo'); K.celebrate(function () { K.later(newRound, 400); }); }
    else K.later(newRound, 900);
  }

  K.stars(document.getElementById('stars'));
  P.onResize(function () { layout(); });
  P.onStart(function () { started = true; if (mode === 'pieces') promptPieces(); else promptCity(); });
  P.onResume(function () { if (busy) newRound(); });
  newRound();
})();
