/* Skaitymas content from MapPal (domains/reading): the words, letters, pairs and triples the parent
 * manages, with their chosen photos and every allowed voice.
 *  - Starts instantly from the last saved content (offline works); the built-in lists in words.js
 *    stay as the fallback for any list the backend doesn't fill.
 *  - Files are fetched once by hash and kept in the Cache API, shown as blob: URLs (the tablet's
 *    CSP allows blob: for images and sound; the MapPal host is in meta.json "network").
 *  - Checks for a newer content version on start and every 15 minutes while the game runs; new
 *    lists apply from the next start, new files are fetched right away.
 * Loaded after words.js and before data.js, so data.js sees the content-based lists. */
(function (root) {
  'use strict';
  var P = root.Ponas;
  var API = 'https://map-pal-mike-base.vercel.app';
  var CACHE = 'skaitymas-media-v1';
  var saved = P.load('content', null);
  var items = (saved && saved.items) || [];
  var byKey = {};
  items.forEach(function (it) { byKey[it.kind + ':' + it.text] = it; });
  var urls = {};        // hash → blob: URL (this session)
  var pending = {};     // hash → Promise

  var GROUPS = ['uo', 'ie', 'au', 'ai', 'ei', 'ui', 'dž'];
  function len(t) { return Array.from(t).length; }
  function phonogram(it) { return it.phonogram || GROUPS.filter(function (g) { return it.text.indexOf(g) >= 0; })[0] || null; }

  // Word lists from the content. A list the backend can't fill keeps the built-in one, so every
  // level runs even with a single approved word.
  var W = root.SK_WORDS || {};
  var words = items.filter(function (it) { return it.kind === 'word' && it.image; });
  var five = words.filter(function (it) { return len(it.text) === 5; }).map(function (it) { return it.text; });
  var pink = words.filter(function (it) { return len(it.text) <= 4; }).map(function (it) { return it.text; });
  var green = words.filter(phonogram).map(function (it) { return [it.text, phonogram(it)]; });
  var pairs = items.filter(function (it) { return it.kind === 'pair'; }).map(function (it) { return it.text; });
  if (words.length) {
    root.SK_WORDS = {
      photos: true,
      five: five.length ? five : W.five,
      pink: pink.length ? pink : W.pink,
      green: green.length ? green : W.green,
      pairs: pairs.length ? pairs : null,
      fromBackend: true
    };
  }

  function mediaUrl(hash) {
    if (urls[hash]) return Promise.resolve(urls[hash]);
    if (pending[hash]) return pending[hash];
    var src = API + '/api/reading/media/' + hash;
    var p = (root.caches ? root.caches.open(CACHE).then(function (c) {
      return c.match(src).then(function (hit) {
        if (hit) return hit.blob();
        return fetch(src).then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return c.put(src, r.clone()).then(function () { return r.blob(); });
        });
      });
    }) : fetch(src).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.blob(); }))
      .then(function (b) { urls[hash] = URL.createObjectURL(b); delete pending[hash]; return urls[hash]; })
      .catch(function (e) { delete pending[hash]; throw e; });
    pending[hash] = p;
    return p;
  }

  /** Allowed voices for a clip, the parent's default first: [{voice, hash}]. */
  function options(kind, text, clip) {
    var it = byKey[kind + ':' + text];
    return (it && it.audio && it.audio[clip]) || [];
  }

  function prefetch(list) {
    var hashes = [];
    list.forEach(function (it) {
      if (it.image) hashes.push(it.image);
      Object.keys(it.audio || {}).forEach(function (k) { if (it.audio[k][0]) hashes.push(it.audio[k][0].hash); });   // defaults now, other voices when played
    });
    var i = 0;
    function next() { if (i >= hashes.length) return; mediaUrl(hashes[i++]).then(next, next); }
    next(); next(); next();   // three at a time: kind to the tablet's Wi-Fi
  }

  var checking = false, timer = null;
  function refresh() {
    if (checking) return;
    checking = true;
    var v = (P.load('content', null) || {}).version || '';
    fetch(API + '/api/reading/content?v=' + encodeURIComponent(v)).then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        checking = false;
        if (!d || d.unchanged || !d.items) return;
        P.save('content', { version: d.version, items: d.items, at: Date.now() });
        prefetch(d.items);
      })
      .catch(function () { checking = false; /* offline: keep what we have */ });
  }

  root.SK_CONTENT = {
    has: words.length > 0,
    version: saved ? saved.version : 0,
    item: function (kind, text) { return byKey[kind + ':' + text] || null; },
    id: function (kind, text) { var it = byKey[kind + ':' + text]; return it ? it.id : null; },
    /** How often the games show it: Extra ×3, Mokomės 1, Išmokta now and then (default 1 for built-in). */
    weight: function (kind, text) { var it = byKey[kind + ':' + text]; return it ? it.weight : 1; },
    image: function (text) { var it = byKey['word:' + text]; return it && it.image ? it.image : null; },
    options: options,
    mediaUrl: mediaUrl,
    refresh: refresh,
    start: function () { refresh(); if (!timer) timer = setInterval(refresh, 15 * 60 * 1000); if (items.length) prefetch(items); },
    stop: function () { if (timer) { clearInterval(timer); timer = null; } }
  };
})(window);
