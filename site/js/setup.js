// Battle setup: treasures and power-ups, cat base parts, battle items (the "Battle setup" section), talents and
// orbs of a lineup slot (in the cat picker), the lineup's combos, and saved lineups. The worker applies the setup
// to BCU's core (bcuweb.Setup), so battles and the info pages both use it. Loaded after app.js (uses its
// worker, lineup, saveLineup, renderLineup, unitById, $, t and textEl).
"use strict";

const Setup = (() => {
  let info = null; // from the worker: maximums, the orbs that exist, combo values
  let data = read("setup", {}); // {tech, trea, fruit, gods, alien, star, bslv, base, deco, nyc, worker, sniper}; missing = BCU's default (max)
  let saved = read("lineups", []); // [{name, slots}]
  let savedSel = -1; // saved lineup being edited (-1: none)
  try { savedSel = +(localStorage.getItem("lineupSel") ?? -1); } catch (e) { /* storage blocked */ }
  if (!(savedSel >= 0 && savedSel < saved.length)) savedSel = -1;

  function read(key, def) {
    try {
      const v = JSON.parse(localStorage.getItem(key));
      if (v && typeof v === "object") return v;
    } catch (e) { /* none saved or storage blocked */ }
    return def;
  }
  function write(key, v) {
    try { localStorage.setItem(key, JSON.stringify(v)); } catch (e) { /* storage blocked */ }
  }

  // ---------------------------------------------------------------- setup → worker
  let sendTimer = 0;
  function changed() {
    write("setup", data);
    clearTimeout(sendTimer);
    sendTimer = setTimeout(send, 250);
  }
  function send() {
    worker.postMessage({ cmd: "setup", args: { json: JSON.stringify(data) } });
  }

  /** Maximum of a setting: key and index as in Treasure (bslv, base, deco: per part). */
  function max(key, i) {
    const m = info[key];
    return Array.isArray(m) ? m[i] : m;
  }
  function get(key, i) {
    const v = Array.isArray(info[key]) || ["fruit", "gods"].includes(key) ? data[key]?.[i] : data[key];
    return v ?? max(key, i);
  }
  function set(key, i, v) {
    if (["alien", "star"].includes(key)) data[key] = v;
    else {
      const n = key === "fruit" ? 7 : key === "gods" ? 3 : info[key].length;
      data[key] = Array.from({ length: n }, (x, j) => (j === i ? v : get(key, j)));
    }
    changed();
  }

  // ---------------------------------------------------------------- the "Battle setup" section
  const el = (tag, props, ...kids) => {
    const e = Object.assign(document.createElement(tag), props || {});
    e.append(...kids.filter((k) => k != null));
    return e;
  };

  function numField(label, key, i) {
    const m = max(key, i);
    const inp = el("input", { className: "input", type: "number", min: 0, max: m, value: get(key, i), inputMode: "numeric" });
    inp.oninput = () => {
      if (inp.value === "") return;
      set(key, i, Math.max(0, Math.min(m, Math.round(+inp.value))));
    };
    inp.onchange = () => { inp.value = get(key, i); };
    return el("label", { className: "num" }, el("span", { textContent: label }), el("span", { className: "row" }, inp, el("small", { textContent: `/ ${m}` })));
  }

  /** A folding group of number fields with "All max" / "All 0" buttons. fields: [[label, key, index]]. */
  function group(title, fields, open) {
    const grid = el("div", { className: "setup-grid" });
    const fill = () => { grid.innerHTML = ""; for (const [label, key, i] of fields) grid.append(numField(label, key, i)); };
    const all = (v) => () => {
      for (const [, key, i] of fields) set(key, i, v === "max" ? max(key, i) : 0);
      fill();
    };
    fill();
    const btns = el("div", { className: "row-btns tight" },
      el("button", { type: "button", className: "tool", textContent: t("allMax"), onclick: all("max") }),
      el("button", { type: "button", className: "tool", textContent: t("allZero"), onclick: all(0) }));
    return el("details", { className: "setup-group", open: !!open }, el("summary", { textContent: title }), btns, grid);
  }

  function castleGroup() {
    const box = el("div", { className: "setup-grid" });
    const nyc = () => data.nyc || [0, 0, 0];
    const render = () => {
      box.innerHTML = "";
      box.append(numField(t("castleLv"), "bslv", 0));
      // nyc: [cannon, style, foundation]; their levels: bslv[set], deco[set - 1], base[set - 1]
      [["cannonPart", 0, "bslv", 0], ["stylePart", 1, "deco", -1], ["foundationPart", 2, "base", -1]].forEach(([name, k, lvKey, off]) => {
        const sel = el("select", { className: "input" });
        t("partNames").forEach((n, i) => sel.append(el("option", { value: i, textContent: n })));
        sel.value = nyc()[k];
        sel.onchange = () => {
          const p = nyc().slice();
          p[k] = +sel.value;
          data.nyc = p;
          changed();
          render();
        };
        box.append(el("label", { className: "num" }, el("span", { textContent: t(name) }), sel));
        const part = nyc()[k];
        if (part > 0) box.append(numField(`${t(name)} · ${t("partLv")}`, lvKey, part + off));
      });
    };
    render();
    return el("details", { className: "setup-group" }, el("summary", { textContent: t("castle") }), box);
  }

  function itemsGroup() {
    const check = (key, label) => {
      const c = el("input", { type: "checkbox", checked: !!data[key] });
      c.onchange = () => { data[key] = c.checked; changed(); };
      return el("label", { className: "check" }, c, el("span", { textContent: label }));
    };
    return el("details", { className: "setup-group" }, el("summary", { textContent: t("items") }),
      el("div", { className: "items" }, check("worker", t("itemWorker")), check("sniper", t("itemSniper"))));
  }

  function render() {
    const box = $("setup");
    if (!box || !info) return;
    const open = [...box.querySelectorAll("details")].map((d) => d.open);
    box.innerHTML = "";
    const traits = t("traitNames"), fruitTraits = TEXT.en.fruitTraits;
    box.append(
      group(t("upgrades"), t("techNames").map((n, i) => [n, "tech", i])),
      group(t("treasures"), t("treaNames").map((n, i) => [n, "trea", i])),
      group(t("traitTreasures"), fruitTraits.map((tr, i) => [traits[tr], "fruit", i])),
      group(t("alienTreasures"), [[t("alienName"), "alien", 0], [t("starName"), "star", 0], ...[0, 1, 2].map((i) => [t("godName", i), "gods", i])]),
      castleGroup(),
      itemsGroup(),
    );
    box.querySelectorAll("details").forEach((d, i) => { if (open[i]) d.open = true; });
  }

  function onInfo(i) {
    info = i;
    $("setupBox").hidden = false;
    render();
  }

  // ---------------------------------------------------------------- talents and orbs of a slot (cat picker)
  /** Talent levels of a slot (all at maximum unless changed). */
  function talentsOf(s, u) {
    return (u.t || []).map(([, m], i) => Math.max(0, Math.min(m, s.tal?.[i] ?? m)));
  }

  /** Slot text for the worker: "id:form:lv:plus:talents:orbs" (see BattleSession.start). */
  function slotText(s, lv, plus) {
    const u = unitById.get(s.id);
    let tal = "", orbs = "";
    if (u && s.form >= 2 && u.t) {
      // super talents unlock at level 60 (as in the game)
      tal = talentsOf(s, u).map((v, i) => (u.t[i][2] && lv + plus < 60 ? 0 : v)).join(".");
    }
    if (u && s.form >= 2 && u.o && s.orbs) orbs = Array.from({ length: u.o }, (x, i) => (s.orbs[i] ? s.orbs[i].join(".") : "")).join("/");
    return `${s.id}:${s.form}:${lv}:${plus}:${tal}:${orbs}`;
  }

  function traitLabel(mask) {
    if (mask === 1 << 12) return t("anyTrait");
    const names = t("traitNames");
    const out = [];
    for (let b = 0; b < 12; b++) if (mask & (1 << b)) out.push(names[b]);
    return out.join(", ");
  }

  /** Talent and orb editor for a lineup slot, or null if the cat has neither. */
  function slotEditor(s, onChange) {
    const u = unitById.get(s.id);
    if (!u || (!u.t && !u.o)) return null;
    const box = el("div", { className: "slot-extra" });
    if (s.form < 2) {
      box.append(el("p", { className: "hint", textContent: t("needTrue") }));
      return box;
    }
    if (u.t) {
      const grid = el("div", { className: "setup-grid" });
      const tal = talentsOf(s, u);
      u.t.forEach(([type, m, sup], i) => {
        const inp = el("input", { className: "input", type: "number", min: 0, max: m, value: tal[i], inputMode: "numeric" });
        inp.oninput = () => {
          if (inp.value === "") return;
          const now = talentsOf(s, u);
          now[i] = Math.max(0, Math.min(m, Math.round(+inp.value)));
          s.tal = now;
          onChange();
        };
        inp.onchange = () => { inp.value = talentsOf(s, u)[i]; };
        const name = t("talentNames")[type] || `#${type}`;
        grid.append(el("label", { className: "num" + (sup ? " super" : "") },
          el("span", { textContent: name, title: sup ? t("superTalent") : "" }, sup ? el("small", { textContent: ` ★ ${t("superTalent")}` }) : null),
          el("span", { className: "row" }, inp, el("small", { textContent: `/ ${m}` }))));
      });
      box.append(el("p", { className: "label", textContent: t("talents") }), grid);
    }
    if (u.o && info) {
      const list = el("div", { className: "orbs" });
      const renderOrbs = () => {
        list.innerHTML = "";
        for (let j = 0; j < u.o; j++) {
          const cur = s.orbs?.[j] || null;
          const type = el("select", { className: "input" }, el("option", { value: -1, textContent: t("noOrb") }));
          for (const [id] of info.orbs) type.append(el("option", { value: id, textContent: t("orbNames")[id] || `#${id}` }));
          type.value = cur ? cur[0] : -1;
          const row = el("div", { className: "orb" }, el("span", { className: "label", textContent: t("orbSlot", j + 1) }), type);
          const setOrb = (o) => {
            s.orbs = Array.from({ length: u.o }, (x, k) => (k === j ? o : s.orbs?.[k] || null));
            onChange();
            renderOrbs();
          };
          type.onchange = () => {
            const entry = info.orbs.find(([id]) => id === +type.value);
            setOrb(entry ? [entry[0], entry[1][0][0], entry[1][0][1].at(-1)] : null);
          };
          const entry = cur && info.orbs.find(([id]) => id === cur[0]);
          if (entry) {
            const trait = el("select", { className: "input" });
            for (const [mask] of entry[1]) trait.append(el("option", { value: mask, textContent: traitLabel(mask) }));
            trait.value = cur[1];
            const grades = (entry[1].find(([m]) => m === cur[1]) || entry[1][0])[1];
            const grade = el("select", { className: "input short" });
            for (const g of grades) grade.append(el("option", { value: g, textContent: t("grades")[g] ?? g }));
            grade.value = grades.includes(cur[2]) ? cur[2] : grades.at(-1);
            trait.onchange = () => {
              const gs = (entry[1].find(([m]) => m === +trait.value) || entry[1][0])[1];
              setOrb([cur[0], +trait.value, gs.includes(cur[2]) ? cur[2] : gs.at(-1)]);
            };
            grade.onchange = () => setOrb([cur[0], cur[1], +grade.value]);
            if (entry[1].length > 1 || entry[1][0][0] !== 1 << 12) row.append(trait);
            row.append(grade);
          }
          list.append(row);
        }
      };
      renderOrbs();
      box.append(el("p", { className: "label", textContent: t("orbs") }), list);
    }
    return box;
  }

  // ---------------------------------------------------------------- combos of the lineup
  let combos = [];
  function askCombos(text) {
    worker.postMessage({ cmd: "combos", args: { lineup: text } });
  }
  function onCombos(list) {
    combos = list;
    showCombos();
  }
  function showCombos() {
    const box = $("combos");
    if (!box) return;
    box.innerHTML = "";
    if (!combos.length) { box.append(el("p", { className: "hint", textContent: t("noCombos") })); return; }
    for (const [, name, type, lv] of combos) {
      const v = info?.combo?.[type]?.[lv];
      const effect = `${t("comboEffects")[type] || "#" + type} (${t("comboSizes")[lv] ?? lv})${v != null ? " " + t("comboValue", type, v) : ""}`;
      box.append(el("div", { className: "combo" }, el("b", { textContent: name || "" }), el("span", { textContent: effect })));
    }
  }

  // ---------------------------------------------------------------- saved lineups
  const copy = (v) => JSON.parse(JSON.stringify(v));

  function renderSaved() {
    const box = $("saved");
    if (!box) return;
    box.innerHTML = "";
    const sel = el("select", { className: "input" }, el("option", { value: -1, textContent: t("current") }));
    saved.forEach((l, i) => sel.append(el("option", { value: i, textContent: l.name })));
    sel.value = savedSel;
    sel.onchange = () => {
      savedSel = +sel.value;
      write("lineupSel", savedSel);
      if (savedSel >= 0) {
        lineup = copy(saved[savedSel].slots);
        saveLineup();
        renderLineup();
      }
      renderSaved();
    };
    const btn = (label, fn, disabled) => el("button", { type: "button", className: "tool", textContent: label, onclick: fn, disabled: !!disabled });
    box.append(el("label", { className: "field" }, el("span", { className: "label", textContent: t("savedLineups") }), sel),
      el("div", { className: "row-btns tight" },
        btn(t("saveOver"), () => { saved[savedSel].slots = copy(lineup); write("lineups", saved); }, savedSel < 0),
        btn(t("saveAs"), () => {
          const name = (prompt(t("lineupName"), savedSel >= 0 ? saved[savedSel].name : "") || "").trim();
          if (!name) return;
          saved.push({ name, slots: copy(lineup) });
          savedSel = saved.length - 1;
          write("lineups", saved); write("lineupSel", savedSel);
          renderSaved();
        }),
        btn(t("del"), () => {
          if (!confirm(t("delAsk", saved[savedSel].name))) return;
          saved.splice(savedSel, 1);
          savedSel = -1;
          write("lineups", saved); write("lineupSel", savedSel);
          renderSaved();
        }, savedSel < 0)));
  }

  return {
    send, onInfo, slotText, slotEditor, askCombos, onCombos, renderSaved,
    refresh() { render(); showCombos(); renderSaved(); },
  };
})();
