// Web Worker that runs BCU's core (compiled by TeaVM into ../teavm/js/bcu.js).
//
// Game files come from BCU's own asset packs on GitHub: data/index.json says which pack holds each
// file, where, and with which key. Files are fetched with HTTP range requests, decrypted here, and
// handed to the Java side when it asks (bcuReadFile). Start-up data is loaded up front in bulk; any
// other file is loaded the moment the game needs it (synchronously, allowed inside workers).
// Everything downloaded is saved in the browser (Cache Storage) and read from there on later visits,
// also offline; "Download everything" saves the whole game.
"use strict";

importScripts("aes.js");

const IV = Uint8Array.from("5af764bb8e1a80e202fe24a3d56add1d".match(/../g), (h) => parseInt(h, 16)); // md5("battlecatsultimate")
// Small files BCU's apps bundle themselves (ability and animation text), from BCU_Android at a fixed commit.
const EXTRA_URL = "https://raw.githubusercontent.com/battlecatsultimate/BCU_Android/86400116299340f24aea5e80fc38951a8bb08d93/app/src/main/res/raw/";
const EXTRA_FILES = ["proc.json", "proc_kr.json", "proc_jp.json", "proc_es.json", "proc_zh.json", "animation_type.json"];
// Stage, cat and enemy names (English, Korean, and Japanese as the fallback BCU uses), from bcu-assets/lang.
const NAMES_URL = "https://raw.githubusercontent.com/battlecatsultimate/bcu-assets/master/lang/";
const NAME_FILES = ["en", "kr", "jp"].flatMap((l) => ["StageName", "UnitName", "EnemyName"].map((f) => `${l}-${f}.txt`));
const CACHE = "bcu-assets-v1";       // saved pack ranges
const EXTRA_CACHE = "bcu-extra-v1";  // saved small files (ability texts, name lists)
const SNAP_CACHE = "bcu-startup-v1"; // start-up data, decrypted, in one piece (faster return visits)
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

const UTF8 = new TextDecoder("utf-8", { ignoreBOM: true }); // keeps a byte-order mark, as Java's decoder does
self.bcuReadText = (path) => {
  const b = files.get(path) || fetchNow(path);
  return b ? UTF8.decode(b) : null;
};

// ---------------------------------------------------------------- downloading and saved downloads
// Every downloaded byte range of a pack is kept in Cache Storage (key "<pack url>?bytes=a-b"). When the
// worker starts it gets a Blob for each saved range. The browser keeps those on disk, not in memory, and
// any file inside one can be read straight away: asynchronously, or synchronously with FileReaderSync
// when the game needs a file this instant. So a saved file never needs the network again (offline too).
const saved = new Map(); // zip index -> [{start, end, blob}] sorted by start (end exclusive)
let cacheP = null;
const openCache = () => (cacheP ??= self.caches ? caches.open(CACHE).catch(() => null) : Promise.resolve(null));

function assetUrl(zip) {
  return index.asset_url.replace("{id}", index.zips[zip][0]);
}

/** Where a file's encrypted bytes are: [zip, start, end) in the pack file. */
function fileRange(path) {
  const [zip, off, size] = index.files[path];
  const start = index.zips[zip][2] + off;
  return [zip, start, start + regulate(size)];
}

// Per pack, ranges are kept sorted by start; each also remembers, of itself and all ranges before it, the
// one reaching furthest (`best`). The range holding [start, end) exists exactly when, for the last range
// starting at or before `start`, that furthest-reaching one ends at or after `end` (it then holds it).
function addSaved(zip, start, end, blob) {
  if (!saved.has(zip)) saved.set(zip, []);
  const list = saved.get(zip);
  let i = list.length;
  while (i > 0 && list[i - 1].start > start) i--;
  list.splice(i, 0, { start, end, blob, best: null });
  for (let j = i; j < list.length; j++) {
    const prev = j > 0 ? list[j - 1].best : null;
    list[j].best = prev && prev.end >= list[j].end ? prev : list[j];
  }
}

/** The saved range holding [start, end) of a pack, if any. */
function findSaved(zip, start, end) {
  const list = saved.get(zip);
  if (!list) return null;
  let lo = 0, hi = list.length - 1, at = -1;
  while (lo <= hi) { // last range starting at or before `start`
    const mid = (lo + hi) >> 1;
    if (list[mid].start <= start) { at = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return at >= 0 && list[at].best.end >= end ? list[at].best : null;
}

/** Gets a Blob for every saved range (and drops ranges of packs the current file list no longer uses). */
async function loadSaved() {
  const cache = await openCache();
  if (!cache) return;
  const byUrl = new Map(index.zips.map((z, i) => [assetUrl(i), i]));
  const keys = await cache.keys();
  let next = 0;
  const work = async () => {
    while (next < keys.length) {
      const req = keys[next++];
      const m = /^(.*)\?bytes=(\d+)-(\d+)$/.exec(req.url);
      const zip = m ? byUrl.get(m[1]) : undefined;
      if (zip === undefined) { cache.delete(req).catch(() => {}); continue; }
      const res = await cache.match(req);
      if (res) addSaved(zip, +m[2], +m[3] + 1, await res.blob());
    }
  };
  await Promise.all(Array.from({ length: 32 }, work));
}

/** Saves a downloaded range and keeps a (disk-backed) Blob of it. */
async function saveRange(zip, start, end, buf) {
  const cache = await openCache();
  if (!cache) { addSaved(zip, start, end, new Blob([buf])); return; }
  const key = `${assetUrl(zip)}?bytes=${start}-${end - 1}`;
  try {
    await cache.put(key, new Response(buf));
    const res = await cache.match(key);
    addSaved(zip, start, end, res ? await res.blob() : new Blob([buf]));
  } catch (e) { // storage full or blocked: keep it for this visit only
    addSaved(zip, start, end, new Blob([buf]));
  }
}

function decryptFile(path, buf, bufStart) {
  const [zip, off, size] = index.files[path];
  const start = index.zips[zip][2] + off - bufStart;
  const plain = AES.decryptCBC(index.zips[zip][1], IV, buf.subarray(start, start + regulate(size)));
  files.set(path, plain.subarray(0, size));
}

/** The game needs a file right now (synchronous; only possible in a worker): from saved data, else the network. */
function fetchNow(path) {
  if (!index.files[path]) return null;
  const [zip, start, end] = fileRange(path);
  const r = findSaved(zip, start, end);
  if (r) {
    const buf = new Uint8Array(new FileReaderSync().readAsArrayBuffer(r.blob.slice(start - r.start, end - r.start)));
    stats.cached++;
    decryptFile(path, buf, start);
    return files.get(path);
  }
  const xhr = new XMLHttpRequest();
  xhr.open("GET", assetUrl(zip), false);
  xhr.responseType = "arraybuffer";
  xhr.setRequestHeader("Range", `bytes=${start}-${end - 1}`);
  try {
    xhr.send();
  } catch (e) {
    throw new Error(navigator.onLine ? `couldn't download ${path}` : "offline");
  }
  if (xhr.status !== 206) throw new Error(`couldn't download ${path} (HTTP ${xhr.status})`);
  stats.requests++; stats.onDemand++; stats.bytes += xhr.response.byteLength;
  onDemandLog.push(path);
  const buf = new Uint8Array(xhr.response);
  decryptFile(path, buf, start);
  saveRange(zip, start, end, buf); // (in the background)
  return files.get(path);
}

/** Groups byte ranges into as few requests as reasonable: same pack, sorted, small gaps merged, size capped. */
function planRanges(items, gap, cap) { // items: [{zip, start, end, path?}]
  const byZip = new Map();
  for (const it of items) {
    if (!byZip.has(it.zip)) byZip.set(it.zip, []);
    byZip.get(it.zip).push(it);
  }
  const ranges = [];
  for (const [zip, list] of byZip) {
    list.sort((a, b) => a.start - b.start);
    let cur = null;
    for (const it of list) {
      if (cur && it.start - cur.end <= gap && Math.max(cur.end, it.end) - cur.start <= cap) {
        cur.end = Math.max(cur.end, it.end); cur.items.push(it);
      } else {
        cur = { zip, start: it.start, end: it.end, items: [it] };
        ranges.push(cur);
      }
    }
  }
  return ranges;
}

async function download(r) {
  const url = assetUrl(r.zip);
  for (let attempt = 0; ; attempt++) {
    try {
      const res = await fetch(url, { headers: { Range: `bytes=${r.start}-${r.end - 1}` } });
      if (res.status !== 206) throw new Error(`HTTP ${res.status}`);
      const buf = new Uint8Array(await res.arrayBuffer());
      stats.requests++; stats.bytes += buf.length;
      return buf;
    } catch (e) {
      if (!navigator.onLine) throw new Error("offline");
      if (attempt >= 3) throw new Error(`couldn't download from ${url}: ${e.message}`);
      await new Promise((ok) => setTimeout(ok, 1000 * (attempt + 1)));
    }
  }
}

async function pool(list, n, fn) {
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(n, list.length) }, async () => {
    while (next < list.length) await fn(list[next++]);
  }));
}

/** Loads files into memory: from saved data where possible, the rest downloaded in bulk (and saved). */
async function prefetch(paths, label, quiet) {
  const fromSaved = new Map(); // saved range -> [{path, start, end}]
  const missing = [];
  for (const path of paths) {
    if (files.has(path) || !index.files[path]) continue;
    const [zip, start, end] = fileRange(path);
    const r = findSaved(zip, start, end);
    if (r) {
      if (!fromSaved.has(r)) fromSaved.set(r, []);
      fromSaved.get(r).push({ path, start, end });
    } else missing.push({ zip, start, end, path });
  }
  const ranges = planRanges(missing, MERGE_GAP, 4 << 20);
  let total = ranges.reduce((n, r) => n + (r.end - r.start), 0), done = 0;
  for (const list of fromSaved.values()) total += list.reduce((n, f) => n + (f.end - f.start), 0);
  const progress = (n) => { done += n; if (!quiet) post("download", { label, done, total }); };
  // saved: one read per saved range, covering the files needed from it
  await pool([...fromSaved], 8, async ([r, list]) => {
    const lo = Math.min(...list.map((f) => f.start)), hi = Math.max(...list.map((f) => f.end));
    const buf = new Uint8Array(await r.blob.slice(lo - r.start, hi - r.start).arrayBuffer());
    stats.cached++;
    for (const f of list) decryptFile(f.path, buf, lo);
    progress(list.reduce((n, f) => n + (f.end - f.start), 0));
  });
  await pool(ranges, PARALLEL, async (r) => {
    const buf = await download(r);
    for (const it of r.items) decryptFile(it.path, buf, r.start);
    await saveRange(r.zip, r.start, r.end, buf);
    progress(r.end - r.start);
  });
}

/** How much of the game is saved in this browser: {saved: bytes of saved ranges, have/total: bytes of game files}. */
function storageInfo() {
  let savedBytes = 0, have = 0, total = 0;
  for (const list of saved.values()) for (const r of list) savedBytes += r.end - r.start;
  for (const path in index.files) {
    const [zip, start, end] = fileRange(path);
    total += end - start;
    if (findSaved(zip, start, end)) have += end - start;
  }
  return { saved: savedBytes, have, total };
}

let savingAll = false;
/** "Download everything": saves every game file not saved yet (for offline play). Not loaded into memory. */
async function saveAll() {
  if (savingAll) return;
  savingAll = true;
  try {
    const missing = [];
    for (const path in index.files) {
      const [zip, start, end] = fileRange(path);
      if (!findSaved(zip, start, end)) missing.push({ zip, start, end });
    }
    const ranges = planRanges(missing, 256 << 10, 8 << 20);
    const total = ranges.reduce((n, r) => n + (r.end - r.start), 0);
    let done = 0;
    post("saveAll", { done, total });
    await pool(ranges, 6, async (r) => {
      await saveRange(r.zip, r.start, r.end, await download(r));
      done += r.end - r.start;
      post("saveAll", { done, total });
    });
  } finally {
    savingAll = false;
    post("storage", storageInfo());
  }
}

// ---- start-up snapshot: all start-up files decrypted and joined into one saved file, read in one go next
// time (instead of thousands of saved pieces to read and decrypt). Made again when the game data changes.
const snapKey = () => `${self.location.origin}/bcu-startup.bin?built=${encodeURIComponent(index.built)}`;

async function loadSnapshot() {
  try {
    const cache = await caches.open(SNAP_CACHE);
    const res = await cache.match(snapKey());
    if (!res) return false;
    const buf = new Uint8Array(await res.arrayBuffer());
    const headLen = new DataView(buf.buffer).getUint32(0, true);
    const head = JSON.parse(new TextDecoder().decode(buf.subarray(4, 4 + headLen)));
    let off = 4 + headLen;
    for (const [path, size] of head) {
      if (index.files[path]) files.set(path, buf.subarray(off, off + size));
      off += size;
    }
    stats.snapshot = head.length;
    return true;
  } catch (e) {
    return false;
  }
}

async function saveSnapshot(paths) {
  try {
    const cache = await caches.open(SNAP_CACHE);
    for (const req of await cache.keys()) if (req.url !== snapKey()) await cache.delete(req);
    if (await cache.match(snapKey())) return;
    const list = paths.filter((p) => files.has(p)).map((p) => [p, files.get(p).length]);
    const head = new TextEncoder().encode(JSON.stringify(list));
    const len = new Uint8Array(4);
    new DataView(len.buffer).setUint32(0, head.length, true);
    await cache.put(snapKey(), new Response(new Blob([len, head, ...list.map(([p]) => files.get(p))])));
  } catch (e) { /* not saved: next visit reads the pieces instead */ }
}

/** Small files outside the packs: saved too; the fixed-version ones are only downloaded once. */
async function loadExtras() {
  const cache = self.caches ? await caches.open(EXTRA_CACHE).catch(() => null) : null;
  const get = async (url, key, fixed) => {
    let res = fixed && cache ? await cache.match(url) : null;
    if (!res) {
      try {
        const net = await fetch(url);
        if (net.ok) {
          res = net;
          if (cache) await cache.put(url, net.clone()).catch(() => {});
        }
      } catch (e) { /* offline */ }
    }
    if (!res && cache) res = await cache.match(url);
    if (res) extra.set(key, new Uint8Array(await res.arrayBuffer()));
  };
  await Promise.all([
    ...EXTRA_FILES.map((name) => get((LOCAL ? "/bcu-extra/" : EXTRA_URL) + name, "lang/" + name, true)),
    ...NAME_FILES.map((name) => get((LOCAL ? "/bcu-lang/" : NAMES_URL) + name, "names/" + name, false)),
  ]);
}

// ---------------------------------------------------------------- drawing
// Canvas helpers the Java side calls (bcuweb.web.Canvas) that need local variables.
self.bcuCanvas = {
  gradRect(c, x, y, w, h, top, bottom) {
    const g = c.createLinearGradient(x, y, x, y + h);
    g.addColorStop(0, top); g.addColorStop(1, bottom);
    const s = c.fillStyle;
    c.fillStyle = g; c.fillRect(x, y, w, h); c.fillStyle = s;
  },
  getPixel(c, x, y) {
    const d = c.getImageData(x, y, 1, 1).data;
    return (d[3] << 24) | (d[0] << 16) | (d[1] << 8) | d[2];
  },
  setPixel(c, x, y, argb) {
    const d = new ImageData(1, 1);
    d.data[0] = (argb >> 16) & 255; d.data[1] = (argb >> 8) & 255; d.data[2] = argb & 255; d.data[3] = (argb >>> 24) & 255;
    c.putImageData(d, x, y);
  },
  /** Copy of a bitmap with colour channels swapped: output r/g/b take input channel r/g/b (0 red, 1 green, 2 blue). */
  swapChannels(img, r, g, b) {
    const cv = new OffscreenCanvas(Math.max(1, img.width), Math.max(1, img.height)), x = cv.getContext("2d");
    x.drawImage(img, 0, 0);
    const d = x.getImageData(0, 0, cv.width, cv.height), p = d.data;
    for (let i = 0; i < p.length; i += 4) {
      const v = [p[i], p[i + 1], p[i + 2]];
      p[i] = v[r]; p[i + 1] = v[g]; p[i + 2] = v[b];
    }
    x.putImageData(d, 0, 0);
    return cv;
  },
};
let canvas = null;   // OffscreenCanvas from the page
let redrawTimer = 0;
let speed = 0;       // BCU speed setting, for the speed icon

/** Draws the battle's current frame; if pictures are still decoding, draws it again once they're ready. */
function draw() {
  if (!canvas || !self.bcuDraw) return;
  clearTimeout(redrawTimer);
  const pending = +bcuDraw(speed);
  if (pending > 0) redrawTimer = setTimeout(draw, 30);
}

/** Lineup icons for the page: downloads the cats' deploy-icon files (cached) and cuts out the icon. */
async function icons(paths, cut) {
  await prefetch(paths.filter((p) => index.files[p]), "icons", true);
  const [x, y, w, h] = cut;
  for (const path of paths) {
    let blob = null;
    try {
      const bytes = files.get(path);
      if (bytes) {
        const bmp = await createImageBitmap(new Blob([bytes], { type: "image/png" }));
        const c = new OffscreenCanvas(w, h);
        c.getContext("2d").drawImage(bmp, x, y, w, h, 0, 0, w, h);
        bmp.close();
        blob = await c.convertToBlob({ type: "image/png" });
      }
    } catch (e) { /* broken image: no icon */ }
    post("icon", { path, blob });
  }
}

// ---------------------------------------------------------------- messages from the page
self.onmessage = async (ev) => {
  const { cmd, args } = ev.data;
  try {
    if (cmd === "load") {
      const t0 = performance.now();
      post("progress", "index");
      index = await (await fetch("../data/index.json")).json();
      if (LOCAL) index.asset_url = self.location.origin + "/bcu-assets/{id}.asset.bcuzip";
      const tSaved = performance.now();
      const startup = Object.keys(index.files).filter((p) => STARTUP.test(p));
      const timed = (name, pr) => pr.then((v) => { stats[name + "Ms"] = Math.round(performance.now() - tSaved); return v; });
      const [, , snap] = await Promise.all([timed("extras", loadExtras()), timed("ranges", loadSaved()), timed("snap", self.caches ? loadSnapshot() : Promise.resolve(false))]);
      stats.savedMs = Math.round(performance.now() - tSaved);
      await prefetch(startup, "data");
      const tDownload = performance.now() - t0;
      importScripts("../teavm/js/bcu.js");
      await new Promise((ok) => { javaReady = ok; main([]); }); // wires the core and defines bcuLoad & co.
      const info = JSON.parse(bcuLoad(args.lang));
      if (canvas) bcuAttachCanvas(canvas);
      post("loaded", { ...info, stats, onDemand: onDemandLog.slice(0, 5000), downloadMs: Math.round(tDownload), totalMs: Math.round(performance.now() - t0) });
      post("storage", storageInfo());
      if (!snap && self.caches) saveSnapshot(startup); // (in the background)
    } else if (cmd === "lang") {
      if (self.bcuSetLang) {
        bcuSetLang(args.lang);
        post("stages", JSON.parse(bcuStages("")));
        post("units", JSON.parse(bcuUnits("")));
      }
    } else if (cmd === "storage") {
      if (index) post("storage", storageInfo());
    } else if (cmd === "saveAll") {
      await saveAll();
    } else if (cmd === "clear") {
      if (self.caches) await Promise.all([caches.delete(CACHE), caches.delete(EXTRA_CACHE), caches.delete(SNAP_CACHE)]);
      saved.clear();
      post("cleared", "");
    } else if (cmd === "stages") {
      post("stages", JSON.parse(bcuStages("")));
    } else if (cmd === "canvas") {
      canvas = args.canvas;
      if (self.bcuAttachCanvas) bcuAttachCanvas(canvas);
    } else if (cmd === "resize") {
      if (canvas && (canvas.width !== args.w || canvas.height !== args.h)) {
        canvas.width = args.w; canvas.height = args.h;
        draw();
      }
    } else if (cmd === "battleStart") {
      // download the battle's animations in bulk first (much faster than one file at a time once it runs)
      const dirs = bcuBattleFiles(args.colc, args.map, args.stage, 0, args.lineup, false).split("\n").filter(Boolean);
      await prefetch(Object.keys(index.files).filter((p) => dirs.some((d) => p.startsWith(d))), "battle", true);
      const res = JSON.parse(bcuBattleStart(args.colc, args.map, args.stage, args.seed, args.lineup, !!args.auto));
      if (res.error) return post("battle", res);
      // let the first pictures (background, castles, lineup, enemies) decode before the battle runs
      for (const t0 = performance.now(); canvas && +bcuDraw(speed) > 0 && performance.now() - t0 < 5000;) {
        await new Promise((ok) => setTimeout(ok, 20));
      }
      post("battle", res);
    } else if (cmd === "battleStep") {
      speed = args.speed || 0;
      const res = JSON.parse(bcuBattleStep(args.frames));
      draw();
      post("frame", res);
    } else if (cmd === "input") {
      if (self.bcuInput) { bcuInput(args.cmd); draw(); }
    } else if (cmd === "units") {
      post("units", JSON.parse(bcuUnits("")));
    } else if (cmd === "icons") {
      await icons(args.paths, args.cut);
    }
  } catch (e) {
    post("error", (e && e.message) || String(e));
    console.error(e);
  }
};
