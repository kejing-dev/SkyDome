# Context

How this app was made (spatial-app-factory, 2026-10-20 to 2026-10-22). The binding spec is
[design-package.json](design-package.json) (states S00 - S13 + M1 - M7, panels, entities, palette, copy).

| What | Where |
|---|---|
| PRD (Claude Doc, private) | https://claude.ai/artifact/WmHDarW7kL79gdmvDzyqbS |
| Interactive sample (gate B) | https://claude.ai/artifact/3xqsmiUESWbbKZbehdiNQr |
| QA report #1 (5 findings, rework) | https://claude.ai/artifact/18yKxPptPomEjSX5mHqpbb |
| QA report #2 (pass, 8 known issues) | https://claude.ai/artifact/MeMBMZCxJkEjGWnfZHk9pM |
| Developer notes, platform facts, debug commands, screenshot tour | [../../AGENTS.md](../../AGENTS.md) |
| Constellation figure provenance | [../../tools/lines-source.md](../../tools/lines-source.md) |

History: design (gate B passed with no changes) -> build v0.1.0 -> QA #1 sent back 5 findings (bright stars were
the faintest: the engine reads texture V from the bitmap's bottom row; a black band under the horizon in all-dark;
sky tags half the designed size; the glass box too heavy and crossing the card; no English name on the body card)
-> build v0.1.1 fixed all five and added the thumbstick turn -> QA #2 passed.

Open for the user: the licences of the star catalogue (CDS: free for scientific use, cite; not literally public
domain) and of the constellation figures (recorded against the IAU / Sky & Telescope charts, attributed).
