/* Darbai content. Each job: 4 steps in order, a picture (emoji, can be 2 side by side) and a voice clip.
 * Sorting: Spain and Portugal use the same street bins: yellow = plastic and cans, blue = paper and
 * cardboard, green = glass bottles and jars (juice cartons go to yellow; drinking glasses don't go to green). Brown = food waste (not everywhere yet, but common in Spain). */
window.DARBAI = {
  jobs: [
    { id: 'sumustinis', steps: [['🍞', 'Paimk duonos riekę.'], ['🧈', 'Užtepk sviesto.'], ['🧀', 'Uždėk sūrio.'], ['😋', 'Skanaus!']] },
    { id: 'dantys', steps: [['🪥', 'Paimk šepetėlį ir dantų pastą.'], ['😁', 'Valyk dantis.'], ['💦', 'Išskalauk burną vandeniu.'], ['✨', 'Dantys švarūs!']] },
    { id: 'rankos', steps: [['💧', 'Sušlapink rankas.'], ['🧼', 'Muiluok rankas.'], ['🚿', 'Nuplauk muilą.'], ['🧻', 'Nusišluostyk rankas.']] },
    { id: 'kempingas', steps: [['🚐', 'Atvažiuojame į kempingą.'], ['🪑', 'Išnešame kėdes ir stalą.'], ['🍳', 'Gaminame vakarienę.'], ['😴', 'Einame miegoti.']] },
    { id: 'gele', steps: [['🌰', 'Pasodink sėklą.'], ['🌱', 'Išdygo daigelis.'], ['🌿', 'Augalas auga.'], ['🌻', 'Pražydo gėlė.']] },
    { id: 'visciukas', steps: [['🥚', 'Višta padėjo kiaušinį.'], ['🐣', 'Išsirito viščiukas.'], ['🐥', 'Viščiukas auga.'], ['🐔', 'Užaugo višta.']] },
    { id: 'skalbiniai', steps: [['🧺', 'Sudėk drabužius į skalbyklę.'], ['🫧', 'Skalbyklė skalbia.'], ['👕', 'Pakabink džiūti saulėje.'], ['👚', 'Sulankstyk švarius drabužius.']] },
    { id: 'pusryciai', steps: [['🥣', 'Įsiberk dribsnių į dubenėlį.'], ['🥛', 'Įpilk pieno.'], ['🥄', 'Valgyk šaukštu.'], ['🧽', 'Išplauk dubenėlį.']] }
  ],
  bins: [
    { id: 'geltonas', color: '#FDD835', say: 'Geltonas konteineris: plastikas ir skardinės.' },
    { id: 'melynas', color: '#1E88E5', say: 'Mėlynas konteineris: popierius ir kartonas.' },
    { id: 'zalias', color: '#43A047', say: 'Žalias konteineris: stiklas.' },
    { id: 'rudas', color: '#795548', say: 'Rudas konteineris: maisto likučiai.' }
  ],
  items: [
    ['🥫', 'geltonas', 'Skardinė.'], ['🧴', 'geltonas', 'Plastikinis buteliukas.'], ['🧃', 'geltonas', 'Sulčių pakelis.'],
    ['📰', 'melynas', 'Laikraštis.'], ['📦', 'melynas', 'Kartoninė dėžė.'], ['✉️', 'melynas', 'Popierinis vokas.'],
    ['🍾', 'zalias', 'Stiklinis butelis.'], ['🫙', 'zalias', 'Stiklainis.'],
    ['🍌', 'rudas', 'Banano žievė.'], ['🍎', 'rudas', 'Obuolio graužtukas.'], ['🥔', 'rudas', 'Bulvių lupenos.']
  ]
};
