# Battle Cats Emulator (web)

A browser version of [Battle Cats Ultimate](https://github.com/battlecatsultimate) (BCU), a fan-made
Battle Cats emulator. BCU's shared Java core is compiled to JavaScript with [TeaVM](https://teavm.org)
and runs entirely in the visitor's browser, so the site is plain static files.

> Private project. BCU's repositories have no licence and Battle Cats content is © PONOS, so this
> stays private until the BCU developers give permission.

## How it fits together

| Folder | What |
|---|---|
| `bcu-core/` | [BCU_java_util_common](https://github.com/battlecatsultimate/BCU_java_util_common) as a git submodule, **never edited**. Pinned to the `data_focus` branch (core 0.7.20.0: the released branch plus "cost increase limit" parsing), because BCU's newest asset packs (game version 15.5) need that core version. Battles don't apply the new cost-increase rule yet (BCU itself doesn't either). |
| `patches/` | Patches applied to a copy of the core at build time (currently none) |
| `platform/` | Browser platform code: entry point, Canvas drawing (`bcuweb.web`), the battle painter copied from BCU Android (`bcuweb.battle`), TeaVM build plugins, small JDK stand-ins |
| `tools/build_index.py` | Builds `site/data/index.json`: where each game file sits inside BCU's asset packs |
| `site/` | The static website (TeaVM output goes to `site/teavm/`); `js/worker.js` runs the core in a Web Worker |
| `platform/src/jvm/` | Test harness: the same battle code on a normal Java VM, to compare with the browser |

Game data is **not** stored here. The browser downloads only the files it needs from
[bcu-assets](https://github.com/battlecatsultimate/bcu-assets) (HTTP range requests) and decrypts
them itself, like the official BCU apps do.

The core runs under TeaVM without changes because:
- `BcuReflection` keeps reflection data for the core's classes (the battle engine reads ability data by reflection);
- `BcuTeaVMPlugin`, at build time, rewrites `Field.getInt`/`setBoolean`/... (missing in TeaVM) to `FieldAccess`
  helpers, replaces a short list of desktop-only methods (pack writing, disk saves) with "not available in the
  browser", works around a TeaVM reflection bug with abstract classes, and points the core's `String.split`
  and `Class.getMethods` calls at faster equivalents (`bcuweb.shim.Strings`, `bcuweb.shim.Reflect`; same results,
  but loading the game takes about 6 s instead of 14 s);
- `TAtomicIntegerArray` and `TInetAddress` supply JDK classes Gson needs.

### Saving and offline

- Every byte range downloaded from the packs is saved in the browser (Cache Storage `bcu-assets-v1`). On each
  visit the worker gets a disk-backed Blob per saved range, so a saved file is read without the network,
  even synchronously (FileReaderSync) when the game needs it at once. "Download everything" saves the rest.
- The start-up data is also saved decrypted in one piece (`bcu-startup-v1`, rebuilt when `index.json`
  changes), so a return visit starts in about 7 s and downloads nothing.
- `site/sw.js` (service worker) keeps the site's own files for offline use; it always checks the server first
  (a cheap "not modified" answer when nothing changed), so new versions show up immediately when online.
- Returning visitors load the game automatically; "Delete saved game data" clears it all.

### Info pages

`bcuweb.info.Info` gives cat, enemy and stage data as JSON, worked out like BCU's desktop info tables (same level,
talent and treasure handling as battles). Ability descriptions come from the core's ProcLang/Formatter (BCU's
`proc.json` texts, English or Korean); trait and simple-ability names (from BCU Android's strings) are in
`site/js/i18n.js`. The page side is `site/js/info.js`.

### Battle setup

`bcuweb.Setup` takes the page's treasures and power-ups, cat base parts (cannon, style, foundation and their
levels) and battle items (Rich Cat, Sniper the Cat: the two BCU has) as JSON and puts them into BCU's own
`Treasure` (so battles and the info pages use them) and the battle's castle and item settings. Lineup slots carry
talent levels and orbs (`BattleSession.start`); super talents count only from level 60, as in the game. Each lineup
row is packed to the left before a battle (BCU's combos and controls expect no gaps). Combos come from BCU
(`LineUp.coms`), with names from bcu-assets' `xx-ComboName.txt`. The page keeps the setup and saved lineups in the
browser (`site/js/setup.js`). Korean names for talents, orbs, combo effects, castle parts and items are the Korean
game's own texts.

### Sound

BCU's music and sound effects are plain `.ogg` files in bcu-assets' `music/` folder (`tools/build_index.py`
lists them in `index.json`). The core asks for sound effects itself (`CommonStatic.setSE` → `WebItf` →
`BattleSession`, sent with each battle step); like BCU's apps, the page picks the music: the stage's tune, its
boss tune once the enemy base is below the stage's health threshold, and the win/lose jingles.
`site/js/audio.js` plays them with Web Audio and saves the files for offline play.

Known difference: TeaVM's JavaScript computes Java `float`s in double precision, so tiny rounding
differences can appear (e.g. a few money points over a long battle). Battles play out the same.

## Build

Needs Java 21 and Python 3 with `cryptography`.

```sh
git submodule update --init
./gradlew generateJavaScript -Pdev          # -Pdev = readable JS; omit for the small production build
python tools/build_index.py                  # writes site/data/index.json
python tools/dev_server.py 8000              # then open http://localhost:8000
```

`http://localhost:8000/?assets=local` gets game data through the dev server (cached in
`tools/.cache/`) instead of straight from GitHub: useful offline, on slow networks and for tests.
Add `&seed=1` to repeat a battle exactly.

### Checking the browser build against Java

`./gradlew runJvmBattle --args="000003 9 20 600"` (collection, map, stage, seconds) runs the same
battle code on a normal Java VM (needs the dev server running). With `?seed=1` the browser should
reach the same result on the same frame.

## Updating BCU

```sh
cd bcu-core && git pull origin master && cd ..
git add bcu-core && git commit -m "Update BCU core"
```
Then rebuild. If a patch in `patches/` no longer applies, the build stops and says which one.

## Roadmap

Done: M0–M6 (engine, graphics, battles, offline, sound, info pages), M8 (battle setup: treasures, power-ups,
talents, orbs, cat base parts, items, combos, saved lineups, crowns).

Next steps, in order (M7, custom packs, comes last):

- **M9 Battle extras:** more speeds, results screen, statistics, scoring.
- **M10 Replays.**
- **M11 Info extras:** filters, stage search, compare, descriptions, combo list.
- **M12 Viewers:** animation viewer, music player.
- **M13 Settings:** languages, display options, key bindings, level rules.
- **M14 Editors.**
- **M7 Custom packs.**

### Korean data

The site uses the Korean game's versions of files that differ from BCU's Japanese ones (`site/data/kr`,
made by `tools/kr_overlay.py`):
- stage files (so backgrounds match the Korean game);
- talents and orb slots (`SkillAcquisition.csv`, `equipmentslot.csv`, made with `--prefix ./org/data/`): the Korean
  game has talents for some cats the Japanese data doesn't, and lacks a few;
- the Empire of Cats enemy bases (`--castles`) and other enemy bases and backgrounds whose Korean picture differs,
  mostly Korean text instead of Japanese (`--images`; pictures laid out differently are left as BCU's), from the
  Korean server packs in github.com/fieryhenry/BCData (`kr_server/`).

The data files are from the Korean 15.6.0 `DataLocal` at git.battlecatsmodding.org/fieryhenry/BCData (commit
2a49665d). Compared with BCU's, the Korean cat stats, max levels, combos, orbs and castle-part data match, apart from
rows for cats (or forms) BCU doesn't have yet, so only the two files above are used.
