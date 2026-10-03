// Page logic: talks to the worker (js/worker.js) that runs BCU's core, and draws the battle.
"use strict";

const $ = (id) => document.getElementById(id);
const worker = new Worker("js/worker.js" + location.search); // passes ?assets=local on (see tools/dev_server.py)
let stages = [];
let speed = 1;          // 1×, 4×, 0 = instant
let running = false;
let battleLen = 0;
let lastFrame = null;
const SEED = new URLSearchParams(location.search).has("seed") ? +new URLSearchParams(location.search).get("seed") : null; // ?seed=1 repeats a battle exactly (testing)

// The battle is drawn by the worker (BCU's own painter) on this canvas, handed over as an OffscreenCanvas.
const field = $("field");
if (field.transferControlToOffscreen) {
  const off = field.transferControlToOffscreen();
  worker.postMessage({ cmd: "canvas", args: { canvas: off } }, [off]);
  // keep the drawing sharp: canvas pixels = its size on screen × device pixel ratio
  const fit = () => {
    const r = field.getBoundingClientRect(), dpr = Math.min(window.devicePixelRatio || 1, 3);
    if (r.width > 0) worker.postMessage({ cmd: "resize", args: { w: Math.round(r.width * dpr), h: Math.round(r.height * dpr) } });
  };
  new ResizeObserver(fit).observe(field);
  fit();
}

function showError(msg) {
  $("error").textContent = msg;
  $("error").hidden = false;
}

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
    worker.postMessage({ cmd: "stages" });
  } else if (type === "stages") {
    const keep = stages.length ? [$("colc").value, $("map").value, $("stage").value] : null;
    // main story (collection 000003) first, the rest in BCU's order
    stages = [...data.filter((c) => c.id === "000003"), ...data.filter((c) => c.id !== "000003")];
    fillSelect($("colc"), stages.map((c, i) => [i, c.name || c.id]));
    if (keep) { // language switch: same choices, new names
      $("colc").value = keep[0]; onColc(); $("map").value = keep[1]; onMap(); $("stage").value = keep[2];
      return;
    }
    // start on the main story (collection 000003, listed first), map "EoC 1-3", first stage (Korea)
    const main = stages.findIndex((c) => c.id === "000003");
    if (main >= 0) $("colc").value = main;
    onColc();
    if (main >= 0 && stages[main].maps.length > 9) { $("map").value = 9; onMap(); }
    $("battleBox").hidden = false;
  } else if (type === "battle") {
    if (data.error) return showError(data.error);
    battleLen = data.len;
    running = true;
    $("result").textContent = t("running");
    $("result").className = "result";
    step();
  } else if (type === "frame") {
    lastFrame = data;
    showStats(data);
    if (data.result !== 0 || data.time >= 30 * 600) {
      running = false;
      $("run").disabled = false;
      $("result").textContent = data.result > 0 ? t("win") : data.result < 0 ? t("lose") : t("timeUp");
      $("result").className = "result " + (data.result > 0 ? "win" : data.result < 0 ? "lose" : "");
    } else if (running) {
      if (speed === 0) step();
      else setTimeout(step, 1000 / 30);
    }
  } else if (type === "error") {
    showError(data);
    console.error(data);
  } else if (type === "log") {
    console.log(data);
  }
};

function step() {
  // instant: big chunks; otherwise 1 or 4 battle frames per screen frame (BCU runs at 30 frames per second)
  worker.postMessage({ cmd: "battleStep", args: { frames: speed === 0 ? 300 : speed } });
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

// ---------------------------------------------------------------- numbers under the battle
function showStats(f) {
  $("sTime").textContent = (f.time / 30).toFixed(1) + "s";
  $("sMoney").textContent = `${f.money} / ${f.maxMoney}`;
  $("sEbase").textContent = `${Math.max(0, f.ebase[0])} / ${f.ebase[1]}`;
  $("sUbase").textContent = `${Math.max(0, f.ubase[0])} / ${f.ubase[1]}`;
}

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
  $("run").disabled = true;
  $("error").hidden = true;
  worker.postMessage({ cmd: "battleStart", args: {
    colc: stages[$("colc").value].id, map: +$("map").value, stage: +$("stage").value, seed: SEED ?? ((Math.random() * 2 ** 31) | 0),
  } });
};
document.querySelectorAll("[data-speed]").forEach((b) => b.onclick = () => {
  speed = +b.dataset.speed;
  document.querySelectorAll("[data-speed]").forEach((x) => x.setAttribute("aria-pressed", String(x === b)));
});
document.querySelectorAll("[data-lang]").forEach((b) => b.onclick = () => {
  LANG = b.dataset.lang;
  try { localStorage.setItem("lang", LANG); } catch (e) { /* storage blocked */ }
  applyLang();
  if (lastFrame) showStats(lastFrame);
  if (stages.length) worker.postMessage({ cmd: "lang", args: { lang: LANG } });
});
applyLang();
