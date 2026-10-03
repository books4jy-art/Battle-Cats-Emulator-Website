// Info pages: cats, enemies and the chosen stage, with numbers from BCU's core (bcuweb.info.Info in the
// worker). Uses app.js's helpers (worker, $, t, units/unitById, iconEl, textEl, stages).
"use strict";

const Info = (() => {
  let tab = "cats";
  let cat = null;      // {id, form, lv, plus}
  let enemy = null;    // {id, magHp, magAtk}
  let enemyList = null; // [[id, name, icon]]
  const pending = new Map(); // request -> callback
  const ask = (req, cb) => { pending.set(req, cb); worker.postMessage({ cmd: "info", args: { req } }); };

  function onData(req, data) {
    const cb = pending.get(req);
    pending.delete(req);
    if (cb) cb(data);
  }

  // ---------------------------------------------------------------- tabs
  function showTab(name) {
    tab = name;
    document.querySelectorAll("[data-tab]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.tab === name)));
    for (const n of ["cats", "enemies", "stage"]) $("tab-" + n).hidden = n !== name;
    if (name === "enemies" && !enemyList) ask("enemies", (d) => { enemyList = d; renderEnemyList(); });
    if (name === "stage") renderStage();
  }
  document.querySelectorAll("[data-tab]").forEach((b) => b.onclick = () => showTab(b.dataset.tab));
  const reveal = () => $("infoBox").scrollIntoView({ behavior: "smooth", block: "start" });

  // ---------------------------------------------------------------- helpers
  const el = (tag, cls, text) => Object.assign(document.createElement(tag), cls ? { className: cls } : {}, text != null ? { textContent: text } : {});
  const sec = (f) => `${f}f (${(f / 30).toFixed(f % 30 ? 2 : 0)}s)`;
  const num = (n) => Number(n).toLocaleString(LANG === "kr" ? "ko-KR" : "en-US");

  function statGrid(rows) {
    const g = el("div", "kv");
    for (const [k, v] of rows) {
      if (v == null) continue;
      g.append(el("span", "k", t(k)), el("span", "v", String(v)));
    }
    return g;
  }

  /** A short list on one line (traits, targets). */
  function chips(titleKey, items) {
    if (!items.length) return null;
    const box = el("div", "info-list");
    box.append(el("p", "label", t(titleKey)), el("p", "chips", items.join(" · ")));
    return box;
  }

  function list(titleKey, items) {
    if (!items.length) return null;
    const box = el("div", "info-list");
    box.append(el("p", "label", t(titleKey)));
    const ul = el("ul");
    for (const it of items) ul.append(el("li", null, it));
    box.append(ul);
    return box;
  }

  const traitNames = (ids) => ids.map((x) => (typeof x === "number" ? (t("traitNames")[x] || `#${x}`) : x));
  const abilityNames = (ids) => ids.map((x) => t("abilityNames")[x] || `#${x}`);
  const attackType = (d) => [d.area ? t("areaAtk") : t("singleAtk"), d.ld ? t("ldAtk") : null, d.omni ? t("omniAtk") : null].filter(Boolean).join(" · ");
  const hits = (d) => (d.hits.length > 1 ? d.hits.map((h, i) => `${i + 1}: ${num(h[0])} (${h[1]}f)`).join(", ") : null);

  function search(input, rows, render) {
    const q = input.value.trim().toLowerCase();
    return rows.filter((r) => !q || render(r).some((s) => String(s).toLowerCase().includes(q)));
  }

  // ---------------------------------------------------------------- cats
  function renderCatList() {
    if (!units) return;
    const q = $("catSearch").value.trim().toLowerCase();
    const found = units.units.filter((u) => !q || String(u.id) === q || String(u.id).padStart(3, "0") === q || u.f.some((n) => n.toLowerCase().includes(q)));
    fillLazily($("catList"), found, (u) => {
      const b = el("button", "pick");
      b.type = "button";
      b.append(iconEl(u.i[u.f.length - 1]), el("span", "no", String(u.id).padStart(3, "0")), el("span", "nm", u.f[u.f.length - 1] || `#${u.id}`));
      b.onclick = () => showCat(u.id, u.f.length - 1, -1, -1, false);
      return b;
    });
  }
  $("catSearch").oninput = renderCatList;

  function showCat(id, form, lv, plus, jump = true) {
    cat = { id, form, lv, plus };
    showTab("cats");
    loadCat();
    if (jump) reveal();
  }

  function loadCat() {
    if (!cat) return;
    const req = `unit ${cat.id} ${cat.form} ${cat.lv} ${cat.plus}`;
    ask(req, renderCat);
  }

  function renderCat(d) {
    const card = $("catCard");
    card.hidden = false;
    card.innerHTML = "";
    if (d.error) { card.append(el("p", "hint", d.error)); return; }
    cat.lv = d.lv; cat.plus = d.plus;
    const u = unitById.get(d.id);
    const head = el("div", "info-head");
    head.append(iconEl(u && u.i[d.form]));
    const title = el("div");
    title.append(el("h3", null, d.name || `#${d.id}`), el("p", "hint", `#${String(d.id).padStart(3, "0")} · ${t("rarityNames")[d.rarity] || ""}`));
    head.append(title);
    card.append(head);
    // forms
    const forms = el("div", "seg3 forms-seg");
    d.forms.forEach((n, i) => {
      const b = el("button", null, t("form", i));
      b.type = "button";
      b.title = n;
      b.setAttribute("aria-pressed", String(i === d.form));
      b.onclick = () => { cat.form = i; loadCat(); };
      forms.append(b);
    });
    card.append(forms);
    // level
    const lvRow = el("div", "row-btns lv-row");
    const inp = (label, value, max, key) => {
      const l = el("label", "mini");
      const i = Object.assign(el("input", "input"), { type: "number", min: key === "lv" ? 1 : 0, max, value });
      i.onchange = () => { cat[key] = Math.max(+i.min, Math.min(max, Math.round(+i.value || 0))); loadCat(); };
      l.append(`${t(label)} (≤ ${max})`, i);
      return l;
    };
    lvRow.append(inp("level", d.lv, d.max, "lv"));
    if (d.maxp > 0) lvRow.append(inp("plus", d.plus, d.maxp, "plus"));
    card.append(lvRow);
    card.append(statGrid([
      ["sHp", num(d.hp)], ["sKb", d.kb], ["sAtk", num(d.atk)], ["sDps", num(d.dps)],
      ["sRange", d.range], ["sSpeed", d.speed], ["sCost", num(d.cost)], ["sCd", sec(d.cooldown)],
      ["sItv", sec(d.itv)], ["sPre", d.hits.length ? sec(d.hits[0][1]) : null], ["sTba", sec(d.tba)], ["sType", attackType(d)],
      ["sHits", hits(d)],
    ]));
    for (const box of [chips("targets", traitNames(d.traits)), list("abilities", [...abilityNames(d.abilities), ...d.procs])]) if (box) card.append(box);
    if (d.talents) card.append(el("p", "hint", t("talentsNote")));
  }

  // ---------------------------------------------------------------- enemies
  function renderEnemyList() {
    if (!enemyList) return;
    const found = search($("enemySearch"), enemyList, (r) => [r[0], String(r[0]).padStart(3, "0"), r[1]]);
    fillLazily($("enemyList"), found, ([id, name, icon]) => {
      const b = el("button", "pick");
      b.type = "button";
      b.append(enemyIcon(icon), el("span", "no", String(id).padStart(3, "0")), el("span", "nm", name || `#${id}`));
      b.onclick = () => showEnemy(id, 100, 100, false);
      return b;
    });
  }
  $("enemySearch").oninput = renderEnemyList;
  const enemyIcon = (path) => { const img = iconEl(path, true); img.classList.add("eicon"); return img; };

  function showEnemy(id, magHp, magAtk, jump = true) {
    enemy = { id, magHp, magAtk };
    showTab("enemies");
    ask(`enemy ${id} ${magHp} ${magAtk}`, renderEnemy);
    if (jump) reveal();
  }

  function renderEnemy(d) {
    const card = $("enemyCard");
    card.hidden = false;
    card.innerHTML = "";
    if (d.error) { card.append(el("p", "hint", d.error)); return; }
    const head = el("div", "info-head");
    const icon = enemyList && enemyList.find((r) => r[0] === d.id);
    head.append(enemyIcon(icon ? icon[2] : `./org/enemy/${String(d.id).padStart(3, "0")}/edi_${String(d.id).padStart(3, "0")}.png`));
    const title = el("div");
    title.append(el("h3", null, d.name || `#${d.id}`), el("p", "hint", `#${String(d.id).padStart(3, "0")}`));
    head.append(title);
    card.append(head);
    const row = el("div", "row-btns lv-row");
    const inp = (label, value, key) => {
      const l = el("label", "mini");
      const i = Object.assign(el("input", "input"), { type: "number", min: 1, max: 1000000, value });
      i.onchange = () => { enemy[key] = Math.max(1, Math.round(+i.value || 100)); showEnemy(enemy.id, enemy.magHp, enemy.magAtk, false); };
      l.append(t(label), i);
      return l;
    };
    row.append(inp("magHp", d.magHp, "magHp"), inp("magAtk", d.magAtk, "magAtk"));
    card.append(row);
    card.append(statGrid([
      ["sHp", num(d.hp)], ["sKb", d.kb], ["sAtk", num(d.atk)], ["sDps", num(d.dps)],
      ["sRange", d.range], ["sSpeed", d.speed], ["sMoney", num(d.money)], ["sItv", sec(d.itv)],
      ["sPre", d.hits.length ? sec(d.hits[0][1]) : null], ["sTba", sec(d.tba)], ["sType", attackType(d)], ["sHits", hits(d)],
    ]));
    for (const box of [chips("traits", traitNames(d.traits)), list("abilities", [...abilityNames(d.abilities), ...d.procs])]) if (box) card.append(box);
  }

  // ---------------------------------------------------------------- stage
  function stageReq() {
    if (!stages.length) return null;
    return `stage ${stages[$("colc").value].id} ${$("map").value} ${$("stage").value}`;
  }

  function renderStage() {
    const req = stageReq();
    if (req) ask(req, drawStage);
  }

  function drawStage(d) {
    const card = $("stageCard");
    card.innerHTML = "";
    if (d.error) { card.append(el("p", "hint", d.error)); return; }
    const star = d.stars[0] || 100;
    card.append(el("h3", null, d.name), el("p", "hint", d.map));
    card.append(statGrid([
      ["stLen", num(d.len)], ["stHealth", num(d.health)], ["stMax", d.max],
      ["stTime", d.timeLimit ? `${d.timeLimit} min` : null], ["stCont", t(d.continue ? "yes" : "no")],
      ["stStars", d.stars.length > 1 ? d.stars.map((s) => s + "%").join(" / ") : null],
    ]));
    const wrap = el("div", "table-wrap");
    const table = el("table", "stage-table");
    const th = el("tr");
    for (const k of ["enemy", "stCount", "stMag", "stBase", "stStart", "stRespawn", "stLayer"]) th.append(el("th", null, t(k)));
    table.append(th);
    const range = (a, b) => (a === b || !b ? String(a) : `${a}~${b}`);
    const frames = (a, b) => { // spawn timers count battle frames (30 per second)
      const s = (f) => (f / 30).toFixed(f % 30 ? 1 : 0);
      return a === b || !b ? `${a}f (${s(a)}s)` : `${a}~${b}f (${s(a)}~${s(b)}s)`;
    };
    for (const r of d.enemies) {
      const tr = el("tr");
      const name = el("td", "en");
      if (r.id >= 0) name.append(enemyIcon(`./org/enemy/${String(r.id).padStart(3, "0")}/edi_${String(r.id).padStart(3, "0")}.png`));
      name.append(el("span", null, r.name + (r.boss ? ` (${t("boss")})` : "")));
      const magHp = Math.round(r.mag * star / 100), magAtk = Math.round(r.magAtk * star / 100);
      tr.append(name,
        el("td", null, r.number === 0 ? "∞" : String(r.number)),
        el("td", null, magHp === magAtk ? `${magHp}%` : `${magHp}% / ${magAtk}%`),
        el("td", null, (r.castle[0] >= r.castle[1] ? r.castle[0] : `${r.castle[0]}~${r.castle[1]}`) + "%"),
        el("td", null, frames(r.start[0], Math.abs(r.start[0]) >= Math.abs(r.start[1]) ? r.start[0] : r.start[1])),
        el("td", null, frames(r.respawn[0], r.respawn[1])),
        el("td", null, range(r.layer[0], r.layer[1])));
      if (r.id >= 0) { tr.classList.add("link"); tr.onclick = () => showEnemy(r.id, magHp, magAtk); }
      table.append(tr);
    }
    wrap.append(table);
    card.append(wrap);
    card.append(el("p", "hint", t("stageNote")));
  }

  // ---------------------------------------------------------------- language / data changes
  function refresh() {
    $("catSearch").placeholder = t("search");
    $("enemySearch").placeholder = t("searchEnemy");
  }

  function unitsChanged() { // names (and language) may have changed
    refresh();
    renderCatList();
    if (cat) loadCat();
    if (enemyList) ask("enemies", (d) => { enemyList = d; renderEnemyList(); });
    if (enemy) ask(`enemy ${enemy.id} ${enemy.magHp} ${enemy.magAtk}`, renderEnemy);
    if (tab === "stage") renderStage();
  }

  refresh();
  return {
    onData, unitsChanged, refresh, showCat, showEnemy,
    showStage() { showTab("stage"); reveal(); },
    stageChanged() { if (tab === "stage") renderStage(); },
  };
})();
