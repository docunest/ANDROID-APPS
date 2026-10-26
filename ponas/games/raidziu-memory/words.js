// Picture words for Raidžių memory: [word, emoji].
// The first letter of the word is the answer. Emoji are drawn by the device's own emoji font
// (Android 14 has all of these; only Unicode ≤ 12 emoji are used). To use your own picture instead,
// put a PNG in pics/ and write it as [word, 'pics/katė.png'] — anything ending in .png is shown as an image.
var WORDS = [
  // A
  ['Arbūzas', '🍉'], ['Antis', '🦆'], ['Avis', '🐑'], ['Ananasas', '🍍'], ['Apelsinas', '🍊'],
  ['Arklys', '🐴'], ['Autobusas', '🚌'], ['Aštuonkojis', '🐙'], ['Angelas', '👼'], ['Avokadas', '🥑'],
  // B
  ['Bananas', '🍌'], ['Batas', '👞'], ['Bitė', '🐝'], ['Balionas', '🎈'], ['Braškė', '🍓'],
  ['Beždžionė', '🐒'], ['Blynai', '🥞'], ['Bulvė', '🥔'], ['Būgnas', '🥁'], ['Boružė', '🐞'],
  // C, Č
  ['Citrina', '🍋'], ['Cirkas', '🎪'],
  ['Česnakas', '🧄'], ['Čiulptukas', '🍭'],
  // D
  ['Dantis', '🦷'], ['Drugelis', '🦋'], ['Dovana', '🎁'], ['Duona', '🍞'], ['Debesis', '☁️'],
  ['Dviratis', '🚲'], ['Delfinas', '🐬'], ['Dinozauras', '🦕'], ['Durys', '🚪'], ['Dramblys', '🐘'],
  // E
  ['Eglė', '🎄'], ['Ežys', '🦔'], ['Elnias', '🦌'], ['Erelis', '🦅'],
  // F
  ['Fotoaparatas', '📷'], ['Flamingas', '🦩'], ['Fejerverkai', '🎆'],
  // G
  ['Gėlė', '🌸'], ['Grybas', '🍄'], ['Gaidys', '🐓'], ['Gyvatė', '🐍'], ['Gitara', '🎸'],
  ['Gulbė', '🦢'], ['Gorila', '🦍'],
  // H
  ['Helikopteris', '🚁'], ['Hamburgeris', '🍔'], ['Hipopotamas', '🦛'],
  // J
  ['Jūra', '🌊'], ['Jautis', '🐂'],
  // K
  ['Katė', '🐱'], ['Karvė', '🐄'], ['Kiaušinis', '🥚'], ['Kamuolys', '⚽'], ['Krokodilas', '🐊'],
  ['Kengūra', '🦘'], ['Kaktusas', '🌵'], ['Kėdė', '🪑'], ['Knyga', '📖'], ['Kepurė', '🧢'],
  ['Kiaulė', '🐷'], ['Kupranugaris', '🐫'], ['Krabas', '🦀'], ['Kalnas', '⛰️'], ['Karūna', '👑'],
  // L
  ['Lapė', '🦊'], ['Liūtas', '🦁'], ['Lėktuvas', '✈️'], ['Laivas', '🚢'], ['Lokys', '🐻'],
  ['Lietus', '🌧️'], ['Lemputė', '💡'], ['Ledai', '🍦'], ['Lapas', '🍁'], ['Laikrodis', '⏰'],
  // M
  ['Mėnulis', '🌙'], ['Morka', '🥕'], ['Medis', '🌳'], ['Mašina', '🚗'], ['Medus', '🍯'],
  ['Muilas', '🧼'], ['Mergaitė', '👧'], ['Mokykla', '🏫'], ['Motociklas', '🏍️'],
  // N
  ['Namas', '🏠'], ['Nosis', '👃'], ['Naktis', '🌃'],
  // O
  ['Obuolys', '🍎'], ['Ožka', '🐐'],
  // P
  ['Pelė', '🐭'], ['Pingvinas', '🐧'], ['Pica', '🍕'], ['Paukštis', '🐦'], ['Pienas', '🥛'],
  ['Pieštukas', '✏️'], ['Povas', '🦚'], ['Planeta', '🪐'], ['Pomidoras', '🍅'], ['Policija', '🚓'],
  ['Pinigai', '💰'],
  // R
  ['Raktas', '🔑'], ['Ranka', '✋'], ['Robotas', '🤖'], ['Raketa', '🚀'], ['Ryklys', '🦈'],
  ['Rožė', '🌹'], ['Raganosis', '🦏'],
  // S
  ['Saulė', '☀️'], ['Sūris', '🧀'], ['Sraigė', '🐌'], ['Snaigė', '❄️'], ['Sausainis', '🍪'],
  ['Skruzdėlė', '🐜'], ['Saldainis', '🍬'], ['Sviestas', '🧈'], ['Sultys', '🧃'],
  // Š
  ['Šuo', '🐶'], ['Šokoladas', '🍫'], ['Širdis', '❤️'], ['Šaukštas', '🥄'], ['Šluota', '🧹'],
  ['Šikšnosparnis', '🦇'],
  // T
  ['Traukinys', '🚂'], ['Tortas', '🎂'], ['Telefonas', '📱'], ['Tigras', '🐯'], ['Traktorius', '🚜'],
  ['Televizorius', '📺'], ['Tulpė', '🌷'], ['Taksi', '🚕'], ['Trimitas', '🎺'],
  // U
  ['Ugnis', '🔥'], ['Uodas', '🦟'],
  // V
  ['Vilkas', '🐺'], ['Varlė', '🐸'], ['Vėžlys', '🐢'], ['Vynuogės', '🍇'], ['Voras', '🕷️'],
  ['Vaivorykštė', '🌈'], ['Višta', '🐔'], ['Varpas', '🔔'], ['Vanduo', '💧'],
  // Z, Ž
  ['Zebras', '🦓'], ['Zuikis', '🐰'],
  ['Žuvis', '🐟'], ['Žirafa', '🦒'], ['Žvaigždė', '⭐'], ['Žiedas', '💍'], ['Žirklės', '✂️'],
  ['Žaibas', '⚡'], ['Žvakė', '🕯️'], ['Žemė', '🌍'], ['Žiurkė', '🐀']
];

// Letters unlock in this order as he gets more right (easy, common shapes first;
// look-alikes S/Š, Z/Ž, C/Č and rare letters later).
var LETTER_LEVELS = [
  { from: 0, letters: 'A M O S T K L P' },
  { from: 9, letters: 'B D E R N V U G' },
  { from: 21, letters: 'Š Ž Č C Z J' },
  { from: 36, letters: 'H F' }
];
