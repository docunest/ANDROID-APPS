# Game ideas (owner's verdicts, 2026-11-25)

Montessori-style games for the kid (5–6, Lithuanian). Built ones live in `games/`.

| Idea | Montessori material | Verdict | Where |
|---|---|---|---|
| Bokštas: pieces from biggest to smallest (tower, boards, colour shades) | Pink Tower, Brown Stair, Colour Box 3 | liked, built | `games/bokstas` |
| Karoliukai: build numbers from golden beads, road-life tasks | Golden Beads, bank game | liked, built | `games/karoliukai` |
| Darbai: camper jobs in order + sorting rubbish into street bins | Practical Life, sequencing | liked, built | `games/darbai` |
| Garsai: find sound pairs, quietest to loudest (real recordings) | Sound Cylinders | liked, built | `games/garsai` |
| Žemėlapis: Spain/Portugal puzzle map + city photos | Puzzle maps | refine: real data and photos, one-level test first | `games/zemelapis` |
| Raidės: trace Lithuanian letters, build trip words | Sandpaper Letters, Movable Alphabet | **parked** (partly covered by Rašyk / Dėliok) | — |
| Kortėlės: match picture cards of things seen nearby | Three-part cards | dropped | — |

Content rule (owner): no AI-made pictures or made-up facts. Real photos (own or Wikimedia Commons with
credits), real recordings, real map data; voice prompts are Azure Ona text-to-speech, generated once
and stored in each game's `audio/` (the lines are in `.tools/voice.json`).

Open for Žemėlapis: pins for **our own stops** need a private source (the repo and Pages site are
public, so the family's route must not be committed). Next level if the test works: Spain's big regions.
