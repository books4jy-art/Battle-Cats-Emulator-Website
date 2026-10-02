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
| `platform/` | Browser platform code: entry point, TeaVM build plugins, small JDK stand-ins |
| `tools/build_index.py` | Builds `site/data/index.json`: where each game file sits inside BCU's asset packs |
| `site/` | The static website (TeaVM output goes to `site/teavm/`) |

Game data is **not** stored here. The browser downloads only the files it needs from
[bcu-assets](https://github.com/battlecatsultimate/bcu-assets) (HTTP range requests) and decrypts
them itself, like the official BCU apps do.

The core runs under TeaVM without changes because:
- `BcuReflection` keeps reflection data for the core's classes (the battle engine reads ability data by reflection);
- `BcuTeaVMPlugin` rewrites `Field.getInt`/`setBoolean`/... (missing in TeaVM) to `FieldAccess` helpers at build time;
- `TAtomicIntegerArray` supplies a JDK class Gson needs.

## Build

Needs Java 21 and Python 3 with `cryptography`.

```sh
git submodule update --init
./gradlew generateJavaScript -Pdev          # -Pdev = readable JS; omit for the small production build
python tools/build_index.py                  # writes site/data/index.json
python -m http.server -d site 8000           # then open http://localhost:8000
```

## Updating BCU

```sh
cd bcu-core && git pull origin master && cd ..
git add bcu-core && git commit -m "Update BCU core"
```
Then rebuild. If a patch in `patches/` no longer applies, the build stops and says which one.
