/* Skaitymas content: the single source of truth for levels, audio clips and pictures.
 * .tools/make-audio.py and .tools/make-images.py read this file. File names use fileKey()
 * because Ponas allows only ASCII file names (š → u0161). */
(function (root) {
  'use strict';

  function fileKey(s) {
    var out = '';
    for (var i = 0; i < s.length; i++) {
      var c = s.charCodeAt(i);
      out += c < 128 ? s[i] : 'u' + ('000' + c.toString(16)).slice(-4);
    }
    return out;
  }

  var CONSONANTS = ['m', 'n', 'l', 'r', 's', 'š', 'p', 'b', 'd', 't', 'k', 'g', 'v', 'j', 'z', 'ž'];
  var VOWELS = ['a', 'o', 'u', 'e', 'i'];
  var DIPHTHONGS = ['ai', 'au', 'ei', 'ui', 'ie', 'uo'];

  // Level A1 targets: consonant + vowel, plus the diphthongs he also has to tell apart.
  var syllables = [];
  CONSONANTS.forEach(function (c) { VOWELS.forEach(function (v) { syllables.push(c + v); }); });
  syllables = syllables.concat(DIPHTHONGS);

  // Letters that sound or look alike: the source of harder decoys.
  var SIMILAR = {
    b: ['p', 'd'], p: ['b', 't'], d: ['t', 'b'], t: ['d', 'p'], g: ['k'], k: ['g'],
    s: ['š', 'z'], 'š': ['s', 'ž'], z: ['ž', 's'], 'ž': ['z', 'š'], m: ['n'], n: ['m'],
    l: ['r'], r: ['l'], v: ['b'], j: ['i'],
    a: ['ą', 'e'], 'ą': ['a'], e: ['ė', 'ę', 'i'], 'ė': ['e', 'ę'], 'ę': ['e', 'ė'],
    i: ['y', 'į', 'e'], y: ['i', 'į'], 'į': ['i', 'y'], o: ['u'], u: ['ū', 'ų', 'o'], 'ū': ['u', 'ų'], 'ų': ['u', 'ū'],
    c: ['č'], 'č': ['c'], h: ['ch'], f: ['v']
  };
  var DIPH_SIMILAR = { ai: ['ei', 'au'], au: ['ai', 'uo'], ei: ['ie', 'ai'], ui: ['uo', 'ie'], ie: ['ei', 'ui'], uo: ['ui', 'au'] };

  var ALPHABET = ['a', 'ą', 'b', 'c', 'č', 'd', 'e', 'ę', 'ė', 'f', 'g', 'h', 'i', 'į', 'y', 'j', 'k', 'l', 'm', 'n',
    'o', 'p', 'r', 's', 'š', 't', 'u', 'ų', 'ū', 'v', 'z', 'ž'];
  var LT_VOWELS = 'aąeęėiįyouųū';

  // Five-letter picture words (owner's levels A2 and A3; Montessori blue series).
  var WORDS5 = ['medis', 'namas', 'lapas', 'ranka', 'lempa', 'batas', 'sūris', 'duona', 'varlė', 'žuvis',
    'meška', 'karvė', 'rogės', 'ratas', 'saulė', 'akmuo', 'tulpė', 'slyva', 'morka', 'nosis',
    'ausis', 'durys', 'pilis', 'višta', 'antis', 'gulbė', 'voras', 'knyga', 'ledai', 'dešra'];
  // Short phonetic words (Montessori pink series).
  var PINK = ['katė', 'pelė', 'bitė', 'lėlė', 'kėdė', 'lova', 'avis', 'ežys', 'rožė', 'musė', 'šuo', 'upė'];
  // Phonogram words (Montessori green series): the word and the letter group it practises.
  var GREEN = [['duona', 'uo'], ['akmuo', 'uo'], ['šuo', 'uo'], ['pienas', 'ie'], ['saulė', 'au'], ['ausis', 'au'],
    ['laivas', 'ai'], ['gaidys', 'ai'], ['ledai', 'ai'], ['peilis', 'ei'], ['muilas', 'ui'], ['džemas', 'dž'], ['džinsai', 'dž']];
  var PHONOGRAMS = ['uo', 'ie', 'au', 'ai', 'ei', 'ui', 'dž', 'ch'];

  // Spoken prompts (Lithuanian), played where a child who can't read needs to know the task.
  var PROMPTS = {
    first: 'Koks pirmas garsas?',
    last: 'Koks paskutinis garsas?',
    build: 'Sudėk žodį.',
    write: 'Parašyk žodį.',
    read: 'Perskaityk ir surask paveikslėlį.',
    group: 'Kokią raidžių porą girdi?',
    done: 'Šaunuolis! Užteks šiandien.'
  };

  var pictureWords = WORDS5.concat(PINK, GREEN.map(function (g) { return g[0]; }))
    .filter(function (w, i, a) { return a.indexOf(w) === i; });

  root.SK_DATA = {
    fileKey: fileKey,
    syllables: syllables,
    diphthongs: DIPHTHONGS,
    consonants: CONSONANTS,
    vowels: VOWELS,
    similar: SIMILAR,
    diphSimilar: DIPH_SIMILAR,
    alphabet: ALPHABET,
    isVowel: function (ch) { return LT_VOWELS.indexOf(ch) >= 0; },
    words5: WORDS5,
    pink: PINK,
    green: GREEN,
    phonograms: PHONOGRAMS,
    prompts: PROMPTS,
    pictureWords: pictureWords,
    // Audio file for a syllable, word, letter or prompt key.
    audio: function (kind, text) { return 'audio/' + kind + '-' + fileKey(text) + '.mp3'; },
    image: function (word) { return 'img/' + fileKey(word) + '.jpg'; }
  };
})(typeof window !== 'undefined' ? window : globalThis);
