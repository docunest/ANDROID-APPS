// Linkėjimai — send a picture to the other player (mum's phone or another tablet).
// Multiplayer test for Ponas.net (ntfy.sh). Open the same game on both devices: a tap on one
// shows the picture big on the other.
(function () {
  'use strict';
  var P = window.Ponas;

  var PICS = {
    heart: '<svg viewBox="0 0 24 24"><path d="M12 21s-7.5-4.6-9.6-9.3C.8 8 3 4 6.8 4c2.2 0 3.6 1.3 5.2 3.2C13.6 5.3 15 4 17.2 4 21 4 23.2 8 21.6 11.7 19.5 16.4 12 21 12 21z" fill="#E53935"/></svg>',
    star: '<svg viewBox="0 0 24 24"><path d="M12 1.8l3.1 6.6 7.2.9-5.3 5 1.4 7.1L12 17.9l-6.4 3.5L7 14.3l-5.3-5 7.2-.9z" fill="#FFC107" stroke="#F57F17" stroke-width=".8" stroke-linejoin="round"/></svg>',
    sun: '<svg viewBox="0 0 24 24"><g stroke="#FB8C00" stroke-width="2" stroke-linecap="round"><path d="M12 1.5v3M12 19.5v3M1.5 12h3M19.5 12h3M4.6 4.6l2.1 2.1M17.3 17.3l2.1 2.1M4.6 19.4l2.1-2.1M17.3 6.7l2.1-2.1"/></g><circle cx="12" cy="12" r="5.5" fill="#FFB300"/></svg>',
    smile: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10.5" fill="#FDD835"/><circle cx="8.5" cy="10" r="1.5" fill="#3E2723"/><circle cx="15.5" cy="10" r="1.5" fill="#3E2723"/><path d="M7.5 14.5q4.5 4.5 9 0" stroke="#3E2723" stroke-width="1.6" fill="none" stroke-linecap="round"/></svg>'
  };
  var NOTES = { heart: [659, 784], star: [784, 1047], sun: [523, 659], smile: [587, 880] };
  var MIN_GAP_MS = 3000; // free ntfy.sh allows ~1 message / 5 s after a burst; don't flood it

  var pics = document.getElementById('pics');
  var bigEl = document.querySelector('#got .big');
  var netEl = document.getElementById('net');

  var chan = P.net.channel();
  chan.onStatus(function (ok) { netEl.classList.toggle('on', ok); });

  var lastSent = 0;
  Object.keys(PICS).forEach(function (name) {
    var b = document.createElement('div');
    b.className = 'pic';
    b.innerHTML = PICS[name];
    b.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      if (P.paused) return;
      chime(name);
      flyUp(b, name);
      var now = Date.now();
      if (now - lastSent >= MIN_GAP_MS) {
        lastSent = now;
        chan.send({ pic: name });
      }
    });
    pics.appendChild(b);
  });

  function chime(name) {
    NOTES[name].forEach(function (f, i) { P.tone(f, { delay: i * 0.12, dur: 0.25, type: 'triangle', vol: 0.22 }); });
  }

  // Sent: a copy of the picture floats up and away.
  function flyUp(from, name) {
    var r = from.getBoundingClientRect();
    var f = document.createElement('div');
    f.className = 'fly';
    f.innerHTML = PICS[name];
    f.style.left = r.left + 'px'; f.style.top = r.top + 'px';
    f.style.width = r.width + 'px'; f.style.height = r.height + 'px';
    document.body.appendChild(f);
    requestAnimationFrame(function () { requestAnimationFrame(function () {
      f.style.transform = 'translateY(' + (-r.bottom - 40) + 'px) scale(.5)';
      f.style.opacity = '0';
    }); });
    setTimeout(function () { f.remove(); }, 1200);
  }

  // Received: the picture pops up big in the middle.
  var hideTimer = 0;
  chan.on(function (data) {
    if (!data || !PICS[data.pic]) return;
    bigEl.innerHTML = PICS[data.pic];
    bigEl.classList.remove('show'); void bigEl.offsetWidth; bigEl.classList.add('show');
    chime(data.pic); chime(data.pic);
    P.save('received', P.load('received', 0) + 1);
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function () { bigEl.classList.remove('show'); }, 2500);
  });
})();
