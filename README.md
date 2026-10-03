# Battle Cats Emulator (web)

A browser version of [Battle Cats Ultimate](https://github.com/battlecatsultimate) (BCU), a fan-made
Battle Cats emulator. BCU's shared Java core is compiled to JavaScript with [TeaVM](https://teavm.org)
and runs entirely in the visitor's browser, so the site is plain static files.

> Private project. BCU's repositories have no licence and Battle Cats content is © PONOS, so this
> stays private until the BCU developers give permission.

## How it fits together

| Folder | What |
|---|---|
| `bcu-core/` | [BCU_java_util_common](https://github.com/battlecatsultimate/BCU_java_util_common) as a git submodule, **never edited** |
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
