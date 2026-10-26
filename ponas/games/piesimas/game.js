// Piešimas — finger painting. Several fingers can draw at once. The picture is saved automatically.
(function () {
  'use strict';
  var P = window.Ponas;

  var COLORS = ['#E53935', '#FDD835', '#43A047', '#1E88E5', '#8E24AA'];
  var NOTES = [523, 587, 659, 784, 880];
  var WIDTH = 14; // brush width in CSS px

  var paper = document.getElementById('paper');
  var canvas = document.getElementById('c');
  var palette = document.getElementById('palette');

  // The canvas is a square as big as the screen's longest side, so rotating the tablet never
  // squashes or loses the drawing — the paper area just shows a different part of it.
  var side = Math.max(window.screen.width, window.screen.height, window.innerWidth, window.innerHeight, 640);
  var dpr = Math.min(window.devicePixelRatio || 1, 2);
  canvas.width = Math.round(side * dpr);
  canvas.height = Math.round(side * dpr);
  canvas.style.width = side + 'px';
  canvas.style.height = side + 'px';
  var ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  ctx.lineCap = 'round';
  ctx.lineJoin = 'round';

  function blank() { ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, side, side); }
  blank();

  // ---- restore the saved picture ----
  var saved = P.load('picture', null);
  if (saved && saved.src && saved.side) {
    var img = new Image();
    img.onload = function () { ctx.drawImage(img, 0, 0, saved.side, saved.side); };
    img.src = saved.src;
  }
  var saveTimer = 0;
  function saveSoon() {
    clearTimeout(saveTimer);
    saveTimer = setTimeout(function () {
      try { P.save('picture', { side: side, src: canvas.toDataURL('image/webp', 0.7) }); } catch (e) { /* ignore */ }
    }, 800);
  }

  // ---- palette ----
  var color = P.load('color', COLORS[0]);
  var picks = [];
  COLORS.forEach(function (c, i) {
    var b = document.createElement('div');
    b.className = 'pick';
    b.style.setProperty('--c', c);
    b.innerHTML = '<i></i>';
    b.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      color = c; P.save('color', c);
      P.tone(NOTES[i], { dur: 0.2 });
      update();
    });
    palette.appendChild(b);
    picks.push([b, c]);
  });
  // "new paper" button: a blank sheet icon
  var fresh = document.createElement('div');
  fresh.className = 'pick new';
  fresh.innerHTML = '<i><svg viewBox="0 0 24 24"><path d="M7 3h7l4 4v14H7z" fill="#fff" stroke="#8E24AA" stroke-width="1.6" stroke-linejoin="round"/><path d="M14 3v4h4" fill="none" stroke="#8E24AA" stroke-width="1.6" stroke-linejoin="round"/><path d="M12.5 11v7M9 14.5h7" stroke="#43A047" stroke-width="2" stroke-linecap="round"/></svg></i>';
  fresh.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    blank(); saveSoon();
    P.tone(880, { dur: 0.12 }); P.tone(660, { delay: 0.1, dur: 0.2 });
  });
  palette.appendChild(fresh);
  function update() { picks.forEach(function (p) { p[0].classList.toggle('on', p[1] === color); }); }
  update();

  // ---- drawing (multi-touch) ----
  var strokes = {}; // pointerId -> {x, y}
  function pos(e) {
    var r = canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }
  paper.addEventListener('pointerdown', function (e) {
    if (P.paused || !P.started) return;
    e.preventDefault();
    try { paper.setPointerCapture(e.pointerId); } catch (x) { /* ignore */ }
    var p = pos(e);
    strokes[e.pointerId] = p;
    ctx.fillStyle = color;
    ctx.beginPath(); ctx.arc(p.x, p.y, WIDTH / 2, 0, Math.PI * 2); ctx.fill();
  });
  paper.addEventListener('pointermove', function (e) {
    var last = strokes[e.pointerId];
    if (!last || P.paused) return;
    var p = pos(e);
    ctx.strokeStyle = color; ctx.lineWidth = WIDTH;
    ctx.beginPath(); ctx.moveTo(last.x, last.y); ctx.lineTo(p.x, p.y); ctx.stroke();
    strokes[e.pointerId] = p;
  });
  function end(e) {
    if (strokes[e.pointerId]) { delete strokes[e.pointerId]; saveSoon(); }
  }
  paper.addEventListener('pointerup', end);
  paper.addEventListener('pointercancel', end);

  P.onPause(function () { strokes = {}; saveSoon(); });
})();
