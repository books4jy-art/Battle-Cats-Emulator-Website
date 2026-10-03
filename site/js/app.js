// Page logic: talks to the worker (js/worker.js) that runs BCU's core and draws the battle on the canvas.
"use strict";

const $ = (id) => document.getElementById(id);
const worker = new Worker("js/worker.js" + location.search); // passes ?assets=local on (see tools/dev_server.py)
const PARAMS = new URLSearchParams(location.search);
const SEED = PARAMS.has("seed") ? +PARAMS.get("seed") : null; // ?seed=1 repeats a battle exactly (testing)

let stages = [];
let units = null;        // {uniCut, units: [{id, r, max, maxp, lv, plus, f: [names], i: [icon files]}]}
let lineup = loadLineup(); // 10 slots: null or {id, form, lv?, plus?}
let speed = 0;           // BCU's speed setting: 0 = 1×, 1 = 2×, 2 = 4× (2^speed battle frames per screen frame)
let running = false;     // a battle is on (not finished)
let paused = false;
let lastFrame = null;
let row = 0;             // lineup row at the front (from the worker)

function showError(msg) {
  $("error").textContent = msg;
  $("error").hidden = false;
}

// ---------------------------------------------------------------- canvas → worker
// The battle is drawn by the worker (BCU's own painter) on this canvas, handed over as an OffscreenCanvas.
const field = $("field");
const canvasPx = { w: 960, h: 480 };
if (field.transferControlToOffscreen) {
  const off = field.transferControlToOffscreen();
  worker.postMessage({ cmd: "canvas", args: { canvas: off } }, [off]);
  // keep the drawing sharp: canvas pixels = its size on screen × device pixel ratio
  const fit = () => {
    const r = field.getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio || 1, 3);
    if (r.width < 1 || r.height < 1) return;
    canvasPx.w = Math.round(r.width * dpr); canvasPx.h = Math.round(r.height * dpr);
    worker.postMessage({ cmd: "resize", args: canvasPx });
  };
  new ResizeObserver(fit).observe(field);
  fit();
}

// ---------------------------------------------------------------- messages from the worker
worker.onmessage = (ev) => {
  const { type, data } = ev.data;
  if (type === "progress") {
    $("loadStatus").textContent = data === "index" ? t("stIndex") : data;
  } else if (type === "download") {
    $("progress").hidden = false;
    $("bar").style.width = (100 * data.done / data.total).toFixed(1) + "%";
    $("loadStatus").textContent = data.done >= data.total ? t("stCore") : t("stData", (data.done / 1e6).toFixed(1), (data.total / 1e6).toFixed(1));
  } else if (type === "loaded") {
    $("bar").style.width = "100%";
    $("loadStatus").textContent = t("stDone", {
      units: data.units, enemies: data.enemies, mb: (data.stats.bytes / 1e6).toFixed(1),
      cached: data.stats.cached, sec: (data.totalMs / 1000).toFixed(1),
    });
    console.log("loaded", data);
    $("load").querySelector("span").textContent = t("loaded");
    try { localStorage.setItem("autoload", "1"); } catch (e) { /* storage blocked */ }
    worker.postMessage({ cmd: "stages" });
    worker.postMessage({ cmd: "units" });
  } else if (type === "stages") {
    onStages(data);
  } else if (type === "units") {
    units = data;
    unitById.clear();
    for (const u of units.units) unitById.set(u.id, u);
    lineup = lineup.map((s) => (s && unitById.has(s.id) && s.form < unitById.get(s.id).f.length ? s : null));
    if (!lineup.some(Boolean)) lineup = defaultLineup();
    $("lineupBox").hidden = false;
    renderLineup();
    if ($("picker").open) renderUnits();
  } else if (type === "storage") {
    storage = data;
    showStorage();
  } else if (type === "saveAll") {
    $("saveProgress").hidden = false;
    $("saveBar").style.width = (data.total ? 100 * data.done / data.total : 100).toFixed(1) + "%";
    $("saveAll").querySelector("span").textContent = t("saving", (data.done / 1e6).toFixed(0), (data.total / 1e6).toFixed(0));
  } else if (type === "cleared") {
    try { localStorage.removeItem("autoload"); } catch (e) { /* storage blocked */ }
    location.reload();
  } else if (type === "icon") {
    gotIcon(data.path, data.blob);
  } else if (type === "battle") {
    if (data.error) {
      $("run").disabled = false;
      return showError(data.error === "empty lineup" ? t("emptyLineup") : data.error);
    }
    running = true; paused = false;
    $("run").disabled = false;
    $("run").textContent = t("restart");
    $("pause").disabled = false;
    $("pause").querySelector("span").textContent = t("pause");
    $("overlay").hidden = true;
    $("result").textContent = "";
    $("result").className = "result";
    loop.start();
  } else if (type === "frame") {
    lastFrame = data;
    row = data.row;
    loop.waiting = false;
    showStats(data);
    if (data.result !== 0) {
      running = false;
      loop.stop();
      $("pause").disabled = true;
      $("result").textContent = data.result > 0 ? t("win") : t("lose");
      $("result").className = "result " + (data.result > 0 ? "win" : "lose");
    }
  } else if (type === "error") {
    $("load").disabled = $("load").querySelector("span").textContent === t("loaded");
    $("run").disabled = false;
    $("saveAll").disabled = false;
    showError(/offline/.test(data) || !navigator.onLine ? t("needOnline") : data);
    console.error(data);
  } else if (type === "log") {
    console.log(data);
  }
};

// ---------------------------------------------------------------- saved data (see js/worker.js and sw.js)
let storage = null; // {saved, have, total} bytes

function showStorage() {
  if (!storage) return;
  const full = storage.have >= storage.total;
  $("storage").hidden = false;
  $("storageInfo").textContent = (navigator.onLine ? "" : t("offline") + " ") + t("stSaved", {
    full, mb: (storage.saved / 1e6).toFixed(0), pct: Math.floor(100 * storage.have / storage.total),
    rest: Math.max(1, (storage.total - storage.have) / 1e6).toFixed(0),
  });
  $("saveAll").hidden = full;
  $("saveAll").disabled = false;
  $("saveAll").querySelector("span").textContent = t("saveAll");
  $("saveProgress").hidden = true;
}

$("saveAll").onclick = async () => {
  $("saveAll").disabled = true;
  try { if (navigator.storage && navigator.storage.persist) await navigator.storage.persist(); } catch (e) { /* not allowed: still saved, the browser may clear it when space runs low */ }
  worker.postMessage({ cmd: "saveAll" });
};
$("clearData").onclick = () => {
  if (confirm(t("clearAsk"))) worker.postMessage({ cmd: "clear" });
};
addEventListener("online", showStorage);
addEventListener("offline", showStorage);
if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => { /* no offline page; everything else works */ });

// ---------------------------------------------------------------- game loop (30 battle frames per second × speed)
const loop = {
  timer: 0, waiting: false, next: 0,
  start() {
    this.stop();
    this.waiting = false;
    this.next = performance.now();
    const tick = (now) => {
      this.timer = requestAnimationFrame(tick);
      if (paused || this.waiting || now < this.next) return;
      this.next = Math.max(this.next + 1000 / 30, now - 100);
      this.waiting = true; // one step at a time: never queue up more than the worker can do
      worker.postMessage({ cmd: "battleStep", args: { frames: 1 << speed, speed } });
    };
    this.timer = requestAnimationFrame(tick);
  },
  stop() { cancelAnimationFrame(this.timer); this.timer = 0; },
};

function setPaused(p) {
  if (!running) return;
  paused = p;
  $("pause").querySelector("span").textContent = t(p ? "resume" : "pause");
  $("overlay").textContent = t("paused");
  $("overlay").hidden = !p;
}

function showStats(f) {
  $("sTime").textContent = (f.time / 30).toFixed(1) + "s";
  $("sMoney").textContent = `${f.money} / ${f.maxMoney}`;
  $("sEbase").textContent = `${Math.max(0, f.ebase[0])} / ${f.ebase[1]}`;
  $("sUbase").textContent = `${Math.max(0, f.ubase[0])} / ${f.ubase[1]}`;
}

// ---------------------------------------------------------------- input on the battle
const input = (cmd) => worker.postMessage({ cmd: "input", args: { cmd } });
const pointers = new Map(); // pointerId -> {x, y} (canvas pixels)
let gesture = null;         // {x0, y0, mode: null | "pan" | "swipe" | "pinch", lastX, dist, long, longTimer}

function canvasXY(e) {
  const r = field.getBoundingClientRect();
  return { x: (e.clientX - r.left) * canvasPx.w / r.width, y: (e.clientY - r.top) * canvasPx.h / r.height };
}

field.addEventListener("pointerdown", (e) => {
  field.setPointerCapture(e.pointerId);
  const p = canvasXY(e);
  pointers.set(e.pointerId, p);
  if (pointers.size === 1) {
    gesture = { x0: p.x, y0: p.y, mode: null, lastX: p.x, long: false };
    // long press on a slot = lock it (as in BCU)
    gesture.longTimer = setTimeout(() => {
      if (gesture && !gesture.mode && running && !paused) { gesture.long = true; input(`tap ${gesture.x0} ${gesture.y0} 1`); }
    }, 550);
  } else if (pointers.size === 2 && gesture) {
    clearTimeout(gesture.longTimer);
    const [a, b] = [...pointers.values()];
    gesture.mode = "pinch";
    gesture.dist = Math.hypot(a.x - b.x, a.y - b.y);
  }
});

field.addEventListener("pointermove", (e) => {
  if (!pointers.has(e.pointerId) || !gesture) return;
  const p = canvasXY(e);
  pointers.set(e.pointerId, p);
  if (gesture.mode === "pinch") {
    if (pointers.size < 2) return;
    const [a, b] = [...pointers.values()];
    const d = Math.hypot(a.x - b.x, a.y - b.y);
    if (gesture.dist > 0 && d > 0) input(`zoom ${d / gesture.dist} ${(a.x + b.x) / 2}`);
    gesture.dist = d;
    return;
  }
  const dx = p.x - gesture.x0, dy = p.y - gesture.y0, slop = 8 * (window.devicePixelRatio || 1);
  if (!gesture.mode) {
    if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) gesture.mode = "pan";
    else if (Math.abs(dy) > canvasPx.h * 0.12 && Math.abs(dy) > Math.abs(dx)) {
      gesture.mode = "swipe";
      if (running && !paused) input(`act ${dy < 0 ? -4 : -5}`); // swipe up / down: switch lineup row
    }
    if (gesture.mode) clearTimeout(gesture.longTimer);
  }
  if (gesture.mode === "pan") {
    input(`pan ${p.x - gesture.lastX}`);
    gesture.lastX = p.x;
  }
});

function endPointer(e) {
  if (!pointers.has(e.pointerId)) return;
  pointers.delete(e.pointerId);
  if (!gesture) return;
  clearTimeout(gesture.longTimer);
  if (pointers.size === 0) {
    if (!gesture.mode && !gesture.long && e.type === "pointerup" && running && !paused) input(`tap ${gesture.x0} ${gesture.y0} 0`);
    gesture = null;
  }
}
field.addEventListener("pointerup", endPointer);
field.addEventListener("pointercancel", endPointer);
field.addEventListener("wheel", (e) => {
  e.preventDefault();
  input(`zoom ${Math.pow(1.0015, -e.deltaY)} ${canvasXY(e).x}`);
}, { passive: false });
field.addEventListener("contextmenu", (e) => e.preventDefault());

document.addEventListener("keydown", (e) => {
  if (!running || e.target.closest("input, select, textarea, dialog") || e.ctrlKey || e.metaKey || e.altKey) return;
  const k = e.key.toLowerCase();
  if (k >= "1" && k <= "5" && k.length === 1) { if (!paused) input(`act ${row * 5 + (+k - 1)}`); }
  else if (k === "q") { if (!paused) input("act -1"); }
  else if (k === "e") { if (!paused) input("act -2"); }
  else if (k === "r") { if (!paused) input("act -4"); }
  else if (k === "p" || (k === "escape" && paused)) setPaused(!paused);
  else return;
  e.preventDefault();
});

// ---------------------------------------------------------------- full screen
const arena = $("arena");
function setFull(on) {
  arena.classList.toggle("full", on);
  $("full").querySelector("span").textContent = t(on ? "exitFull" : "full");
  document.body.style.overflow = on ? "hidden" : "";
}
$("full").onclick = async () => {
  const on = !arena.classList.contains("full");
  if (on) {
    setFull(true);
    try {
      if (arena.requestFullscreen) await arena.requestFullscreen({ navigationUI: "hide" });
      if (screen.orientation && screen.orientation.lock) await screen.orientation.lock("landscape").catch(() => {});
    } catch (e) { /* no real full screen (e.g. iPhone): the page-filling view still works */ }
  } else {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    setFull(false);
  }
};
document.addEventListener("fullscreenchange", () => { if (!document.fullscreenElement && arena.classList.contains("full")) setFull(false); });

// ---------------------------------------------------------------- stages
function onStages(data) {
  const keep = stages.length ? [$("colc").value, $("map").value, $("stage").value] : null;
  // main story (collection 000003) first, the rest in BCU's order
  stages = [...data.filter((c) => c.id === "000003"), ...data.filter((c) => c.id !== "000003")];
  fillSelect($("colc"), stages.map((c, i) => [i, c.name || c.id]));
  if (keep) { // language switch: same choices, new names
    $("colc").value = keep[0]; onColc(); $("map").value = keep[1]; onMap(); $("stage").value = keep[2];
    return;
  }
  // start on the main story, map "EoC 1" (index 9), first stage (Korea)
  const main = stages.findIndex((c) => c.id === "000003");
  if (main >= 0) $("colc").value = main;
  onColc();
  if (main >= 0 && stages[main].maps.length > 9) { $("map").value = 9; onMap(); }
  $("battleBox").hidden = false;
}

function fillSelect(sel, items) {
  sel.innerHTML = "";
  for (const [v, label] of items) {
    const o = document.createElement("option");
    o.value = v; o.textContent = label;
    sel.appendChild(o);
  }
}

function onColc() {
  const c = stages[$("colc").value];
  fillSelect($("map"), c.maps.map((m, i) => [i, `${i + 1}. ${m.name}`]));
  onMap();
}

function onMap() {
  const m = stages[$("colc").value].maps[$("map").value];
  fillSelect($("stage"), m.stages.map((s, i) => [i, `${i + 1}. ${s}`]));
}

// ---------------------------------------------------------------- lineup
const unitById = new Map();

function defaultLineup() { // the basic cats, like a new save
  const l = Array(10).fill(null);
  for (let i = 0; i < 9; i++) if (unitById.has(i)) l[i] = { id: i, form: 0 };
  return l;
}

function loadLineup() {
  try {
    const l = JSON.parse(localStorage.getItem("lineup"));
    if (Array.isArray(l) && l.length === 10) return l;
  } catch (e) { /* none saved or storage blocked */ }
  return Array(10).fill(null);
}

function saveLineup() {
  try { localStorage.setItem("lineup", JSON.stringify(lineup)); } catch (e) { /* storage blocked */ }
}

/** Level and plus level of a slot (BCU's defaults for that cat unless changed). */
const levelOf = (s) => {
  const u = unitById.get(s.id);
  return { lv: s.lv ?? u.lv, plus: s.plus ?? u.plus };
};

const textEl = (cls, text) => Object.assign(document.createElement("span"), { className: cls, textContent: text });

function renderLineup() {
  const box = $("lineup");
  box.innerHTML = "";
  lineup.forEach((s, i) => {
    if (i === 5) box.appendChild(Object.assign(document.createElement("div"), { className: "gap" }));
    const b = document.createElement("button");
    b.type = "button";
    b.className = "slot" + (i >= 5 ? " row2" : "");
    const u = s && unitById.get(s.id);
    if (u) {
      const { lv, plus } = levelOf(s);
      b.append(iconEl(u.i[s.form]), textEl("nm", u.f[s.form] || `#${u.id}`), textEl("lv", `Lv ${lv}${plus ? " +" + plus : ""}`));
      b.title = u.f[s.form];
    } else {
      b.append(Object.assign(document.createElement("div"), { className: "noimg", textContent: "+" }), textEl("nm", t("empty")), textEl("lv", " "));
    }
    b.onclick = () => openPicker(i);
    box.appendChild(b);
  });
}

// ---- icons: fetched by the worker (and kept in the browser's cache) the first time they're on screen
const iconUrl = new Map();     // file -> blob URL
const iconWaiting = new Map(); // file -> [img elements]
const iconAsked = new Set();
let iconBatch = [];
const iconObserver = new IntersectionObserver((entries) => {
  for (const en of entries) {
    if (!en.isIntersecting) continue;
    iconObserver.unobserve(en.target);
    askIcon(en.target.dataset.icon);
  }
}, { rootMargin: "200px" });

function iconEl(path) {
  const img = document.createElement("img");
  img.alt = "";
  img.decoding = "async";
  if (!path) return img;
  if (iconUrl.has(path)) { img.src = iconUrl.get(path); return img; }
  img.dataset.icon = path;
  if (!iconWaiting.has(path)) iconWaiting.set(path, []);
  iconWaiting.get(path).push(img);
  iconObserver.observe(img);
  return img;
}

function askIcon(path) {
  if (!path || iconAsked.has(path)) return;
  iconAsked.add(path);
  iconBatch.push(path);
  if (iconBatch.length === 1) setTimeout(() => {
    worker.postMessage({ cmd: "icons", args: { paths: iconBatch, cut: units.uniCut } });
    iconBatch = [];
  }, 30);
}

function gotIcon(path, blob) {
  if (!blob) return;
  const url = URL.createObjectURL(blob);
  iconUrl.set(path, url);
  for (const img of iconWaiting.get(path) || []) img.src = url;
  iconWaiting.delete(path);
}

// ---- picker
let pickSlot = 0;

function openPicker(i) {
  if (!units) return;
  pickSlot = i;
  $("pickTitle").textContent = t("pickTitle", i + 1);
  $("search").placeholder = t("search");
  $("search").value = "";
  renderPicked();
  renderUnits();
  $("picker").showModal();
  if (matchMedia("(pointer: fine)").matches) $("search").focus();
}

function renderPicked() {
  const box = $("picked"), s = lineup[pickSlot];
  box.innerHTML = "";
  if (!s) return;
  const u = unitById.get(s.id), { lv, plus } = levelOf(s);
  const num = (label, value, max, key) => {
    const l = document.createElement("label");
    const inp = Object.assign(document.createElement("input"), { className: "input", type: "number", min: key === "lv" ? 1 : 0, max, value });
    inp.oninput = () => {
      if (inp.value === "") return;
      lineup[pickSlot][key] = Math.max(+inp.min, Math.min(max, Math.round(+inp.value)));
      saveLineup(); renderLineup();
    };
    inp.onchange = () => { inp.value = levelOf(lineup[pickSlot])[key]; };
    l.append(`${label} (≤ ${max})`, inp);
    return l;
  };
  box.append(num(t("level"), lv, u.max, "lv"));
  if (u.maxp > 0) box.append(num(t("plus"), plus, u.maxp, "plus"));
  const rm = Object.assign(document.createElement("button"), { type: "button", className: "tool", textContent: t("remove") });
  rm.onclick = () => { lineup[pickSlot] = null; saveLineup(); renderLineup(); $("picker").close(); };
  box.append(rm);
}

function renderUnits() {
  const box = $("units"), q = $("search").value.trim().toLowerCase();
  box.innerHTML = "";
  const used = new Set(lineup.filter((s, i) => s && i !== pickSlot).map((s) => s.id));
  const cur = lineup[pickSlot];
  let shown = 0;
  for (const u of units.units) {
    if (q && !(String(u.id) === q || String(u.id).padStart(3, "0") === q || u.f.some((n) => n.toLowerCase().includes(q)))) continue;
    if (++shown > 400) break; // the list is for browsing; searching narrows it down
    const line = document.createElement("div");
    line.className = "unit";
    line.append(textEl("no", String(u.id).padStart(3, "0")));
    const forms = document.createElement("div");
    forms.className = "forms";
    u.f.forEach((name, fi) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "fbtn";
      b.title = `${name || "#" + u.id} · ${t("form", fi)}`;
      b.setAttribute("aria-pressed", String(!!cur && cur.id === u.id && cur.form === fi));
      b.disabled = used.has(u.id);
      if (b.disabled) b.title += ` · ${t("inLineup")}`;
      b.append(iconEl(u.i[fi]), textEl("", name || `#${u.id}`));
      b.onclick = () => {
        const old = lineup[pickSlot];
        lineup[pickSlot] = { id: u.id, form: fi, ...(old && old.id === u.id ? { lv: old.lv, plus: old.plus } : {}) };
        saveLineup(); renderLineup();
        $("picker").close();
      };
      forms.appendChild(b);
    });
    line.appendChild(forms);
    box.appendChild(line);
  }
}
$("search").oninput = renderUnits;
$("search").onkeydown = (e) => { if (e.key === "Enter") e.preventDefault(); };

// ---------------------------------------------------------------- controls
$("load").onclick = () => {
  if (!field.transferControlToOffscreen) return showError(t("noCanvas"));
  $("load").disabled = true;
  $("load").querySelector("span").textContent = t("loading");
  $("error").hidden = true;
  worker.postMessage({ cmd: "load", args: { lang: LANG } });
};
$("colc").onchange = onColc;
$("map").onchange = onMap;
$("run").onclick = () => {
  if (!lineup.some(Boolean)) return showError(t("emptyLineup"));
  $("run").disabled = true;
  $("error").hidden = true;
  loop.stop();
  running = false;
  const slots = lineup.map((s) => {
    if (!s) return "-";
    const { lv, plus } = levelOf(s);
    return `${s.id}:${s.form}:${lv}:${plus}`;
  });
  worker.postMessage({ cmd: "battleStart", args: {
    colc: stages[$("colc").value].id, map: +$("map").value, stage: +$("stage").value,
    seed: SEED ?? ((Math.random() * 2 ** 31) | 0), lineup: slots.join(","), auto: $("auto").checked,
  } });
};
$("pause").onclick = () => setPaused(!paused);
document.querySelectorAll("[data-speed]").forEach((b) => b.onclick = () => {
  speed = +b.dataset.speed;
  document.querySelectorAll("[data-speed]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
});
document.querySelectorAll("[data-lang]").forEach((b) => b.onclick = () => {
  LANG = b.dataset.lang;
  try { localStorage.setItem("lang", LANG); } catch (e) { /* storage blocked */ }
  applyLang();
  refreshTexts();
  if (stages.length) worker.postMessage({ cmd: "lang", args: { lang: LANG } }); // new stage and cat names
});
try { $("auto").checked = localStorage.getItem("auto") === "1"; } catch (e) { /* storage blocked */ }
$("auto").onchange = () => { try { localStorage.setItem("auto", $("auto").checked ? "1" : "0"); } catch (e) { /* storage blocked */ } };

/** Texts set from code (not data-t) after a language switch. */
function refreshTexts() {
  if (running || lastFrame) $("run").textContent = t("restart");
  $("pause").querySelector("span").textContent = t(paused ? "resume" : "pause");
  $("full").querySelector("span").textContent = t(arena.classList.contains("full") ? "exitFull" : "full");
  $("overlay").textContent = t("paused");
  if (lastFrame && !running) $("result").textContent = lastFrame.result > 0 ? t("win") : lastFrame.result < 0 ? t("lose") : "";
  showStorage();
}
applyLang();
// returning visitor: load straight away (the data is saved in the browser)
try { if (localStorage.getItem("autoload") === "1") $("load").click(); } catch (e) { /* storage blocked */ }
