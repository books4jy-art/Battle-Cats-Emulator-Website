// Web Worker that runs BCU's core (compiled by TeaVM into ../teavm/js/bcu.js).
//
// Game files come from BCU's own asset packs on GitHub: data/index.json says which pack holds each
// file, where, and with which key. Files are fetched with HTTP range requests, decrypted here, and
// handed to the Java side when it asks (bcuReadFile). Start-up data is downloaded up front in bulk; any
// other file is fetched the moment the game needs it (synchronous request, allowed inside workers).
// Downloaded ranges are kept in the browser's Cache Storage, so a second visit doesn't download again.
"use strict";

importScripts("aes.js");

const IV = Uint8Array.from("5af764bb8e1a80e202fe24a3d56add1d".match(/../g), (h) => parseInt(h, 16)); // md5("battlecatsultimate")
// Small files BCU's apps bundle themselves (ability and animation text), from BCU_Android at a fixed commit.
const EXTRA_URL = "https://raw.githubusercontent.com/battlecatsultimate/BCU_Android/86400116299340f24aea5e80fc38951a8bb08d93/app/src/main/res/raw/";
const EXTRA_FILES = ["proc.json", "proc_kr.json", "proc_jp.json", "proc_es.json", "proc_zh.json", "animation_type.json"];
// Stage, cat and enemy names (English, Korean, and Japanese as the fallback BCU uses), from bcu-assets/lang.
const NAMES_URL = "https://raw.githubusercontent.com/battlecatsultimate/bcu-assets/master/lang/";
const NAME_FILES = ["en", "kr", "jp"].flatMap((l) => ["StageName", "UnitName", "EnemyName"].map((f) => `${l}-${f}.txt`));
const CACHE = "bcu-assets-v1";
// ?assets=local: get game data through tools/dev_server.py instead of GitHub (testing, slow networks)
const LOCAL = new URL(self.location.href).searchParams.get("assets") === "local";
// Downloaded in bulk before the game starts: all text data, plus every animation model and sprite-sheet
// layout (loading the game reads one model per cat form and enemy). About 39 MB in ~3,500 requests, once.
const STARTUP = /\.(csv|tsv|json|ini|preset|mamodel|imgcut)$/;
const MERGE_GAP = 16 * 1024; // fetch neighbouring files in one request when the gap between them is small
const PARALLEL = 16;

let index = null;      // {asset_url, zips: [[asset, keyHex, base]], files: {path: [zip, offset, size]}}
const files = new Map(); // path -> decrypted Uint8Array
const extra = new Map(); // "lang/proc.json" -> Uint8Array
const stats = { requests: 0, cached: 0, bytes: 0, onDemand: 0 };
const onDemandLog = []; // files the game asked for that weren't downloaded in bulk (to tune what's prefetched)

const post = (type, data) => self.postMessage({ type, data });
const regulate = (n) => (n % 16 === 0 ? n : (n | 15) + 1);

// ---------------------------------------------------------------- functions the Java side calls
let javaReady = null; // resolved when the Java side's main() has run (TeaVM starts it asynchronously)
self.bcuPost = (type, text) => {
  if (type === "ready" && javaReady) javaReady();
  else post(type, text);
};
self.bcuFileList = () => Object.entries(index.files).map(([p, f]) => p + "\t" + f[2]).join("\n");
self.bcuReadExtra = (name) => {
  const b = extra.get(name);
  return b ? new Int8Array(b.buffer, b.byteOffset, b.length) : null;
};
self.bcuReadFile = (path) => {
  let b = files.get(path);
  if (!b) {
    b = fetchNow(path);
    if (!b) return null;
  }
  return new Int8Array(b.buffer, b.byteOffset, b.length);
};

// ---------------------------------------------------------------- downloading
function assetUrl(zip) {
  return index.asset_url.replace("{id}", index.zips[zip][0]);
}

function decryptFile(path, buf, bufStart) {
  const [zip, off, size] = index.files[path];
  const start = index.zips[zip][2] + off - bufStart;
  const plain = AES.decryptCBC(index.zips[zip][1], IV, buf.subarray(start, start + regulate(size)));
  files.set(path, plain.subarray(0, size));
}

/** Synchronous download of one file (only possible in a worker); used when the game asks for a file not fetched yet. */
function fetchNow(path) {
  const f = index.files[path];
  if (!f) return null;
  const [zip, off, size] = f;
  const start = index.zips[zip][2] + off, end = start + regulate(size) - 1;
  const xhr = new XMLHttpRequest();
  xhr.open("GET", assetUrl(zip), false);
  xhr.responseType = "arraybuffer";
  xhr.setRequestHeader("Range", `bytes=${start}-${end}`);
  xhr.send();
  if (xhr.status !== 206 && xhr.status !== 200) throw new Error(`couldn't download ${path} (HTTP ${xhr.status})`);
  stats.requests++; stats.onDemand++; stats.bytes += xhr.response.byteLength;
  onDemandLog.push(path);
  const buf = new Uint8Array(xhr.response);
  decryptFile(path, xhr.status === 200 ? buf : buf, xhr.status === 200 ? 0 : start);
  return files.get(path);
}

/** Groups files into as few range requests as reasonable: same pack, sorted by position, small gaps merged. */
function planRanges(paths) {
  const byZip = new Map();
  for (const p of paths) {
    const f = index.files[p];
    if (!f || files.has(p)) continue;
    if (!byZip.has(f[0])) byZip.set(f[0], []);
    byZip.get(f[0]).push(p);
  }
  const ranges = [];
  for (const [zip, list] of byZip) {
    const base = index.zips[zip][2];
    list.sort((a, b) => index.files[a][1] - index.files[b][1]);
    let cur = null;
    for (const p of list) {
      const s = base + index.files[p][1], e = s + regulate(index.files[p][2]);
      if (cur && s - cur.end <= MERGE_GAP) {
        cur.end = Math.max(cur.end, e); cur.paths.push(p);
      } else {
        cur = { zip, start: s, end: e, paths: [p] };
        ranges.push(cur);
      }
    }
  }
  return ranges;
}

async function fetchRange(cache, r) {
  const url = assetUrl(r.zip);
  const key = `${url}?bytes=${r.start}-${r.end - 1}`;
  let res = cache && (await cache.match(key));
  if (res) {
    stats.cached++;
  } else {
    for (let attempt = 0; ; attempt++) {
      try {
        res = await fetch(url, { headers: { Range: `bytes=${r.start}-${r.end - 1}` } });
        if (res.status !== 206) throw new Error(`HTTP ${res.status}`);
        break;
      } catch (e) {
        if (attempt >= 3) throw new Error(`couldn't download from ${url}: ${e.message}`);
        await new Promise((ok) => setTimeout(ok, 1000 * (attempt + 1)));
      }
    }
    stats.requests++;
    if (cache) await cache.put(key, res.clone()).catch(() => {});
  }
  const buf = new Uint8Array(await res.arrayBuffer());
  stats.bytes += buf.length;
  for (const p of r.paths) decryptFile(p, buf, r.start);
}

/** Downloads many files with a few requests in parallel, reporting progress. */
async function prefetch(paths, label) {
  const ranges = planRanges(paths);
  const total = ranges.reduce((n, r) => n + (r.end - r.start), 0);
  let done = 0, next = 0;
  const cache = self.caches ? await caches.open(CACHE).catch(() => null) : null;
  const work = async () => {
    while (next < ranges.length) {
      const r = ranges[next++];
      await fetchRange(cache, r);
      done += r.end - r.start;
      post("download", { label, done, total });
    }
  };
  await Promise.all(Array.from({ length: PARALLEL }, work));
}

async function loadExtras() {
  const get = async (url, key) => {
    const res = await fetch(url);
    if (res.ok) extra.set(key, new Uint8Array(await res.arrayBuffer()));
  };
  await Promise.all([
    ...EXTRA_FILES.map((name) => get((LOCAL ? "/bcu-extra/" : EXTRA_URL) + name, "lang/" + name)),
    ...NAME_FILES.map((name) => get((LOCAL ? "/bcu-lang/" : NAMES_URL) + name, "names/" + name)),
  ]);
}

// ---------------------------------------------------------------- messages from the page
self.onmessage = async (ev) => {
  const { cmd, args } = ev.data;
  try {
    if (cmd === "load") {
      const t0 = performance.now();
      post("progress", "index");
      index = await (await fetch("../data/index.json")).json();
      if (LOCAL) index.asset_url = "/bcu-assets/{id}.asset.bcuzip";
      await loadExtras();
      await prefetch(Object.keys(index.files).filter((p) => STARTUP.test(p)), "data");
      const tDownload = performance.now() - t0;
      importScripts("../teavm/js/bcu.js");
      await new Promise((ok) => { javaReady = ok; main([]); }); // wires the core and defines bcuLoad & co.
      const info = JSON.parse(bcuLoad(args.lang));
      post("loaded", { ...info, stats, onDemand: onDemandLog.slice(0, 5000), downloadMs: Math.round(tDownload), totalMs: Math.round(performance.now() - t0) });
    } else if (cmd === "lang") {
      if (self.bcuSetLang) { bcuSetLang(args.lang); post("stages", JSON.parse(bcuStages(""))); }
    } else if (cmd === "stages") {
      post("stages", JSON.parse(bcuStages("")));
    } else if (cmd === "battleStart") {
      post("battle", JSON.parse(bcuBattleStart(args.colc, args.map, args.stage, args.seed)));
    } else if (cmd === "battleStep") {
      post("frame", JSON.parse(bcuBattleStep(args.frames)));
    }
  } catch (e) {
    post("error", (e && e.message) || String(e));
    console.error(e);
  }
};
