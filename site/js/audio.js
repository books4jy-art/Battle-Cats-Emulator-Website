// Sound effects and music (Web Audio). BCU's music and sounds are plain .ogg files in bcu-assets/music,
// numbered like the game's (e.g. 20/21 hits, 27 deploy, 8/9 win/lose jingles). Files are saved in the
// browser the first time (Cache Storage "bcu-music-v1"), so they also play offline.
"use strict";

const Sound = (() => {
  const URL_BASE = new URLSearchParams(location.search).get("assets") === "local"
    ? "/bcu-music/" : "https://raw.githubusercontent.com/battlecatsultimate/bcu-assets/master/music/";
  const CACHE = "bcu-music-v1";
  const COMMON = [10, 15, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28]; // touch, can't deploy, hits, base hit, deaths, cannon, deploy...
  let ctx = null, master = null, seGain = null, musicGain = null;
  const buffers = new Map(); // id -> Promise<AudioBuffer|null>
  let music = null;          // {src, id}
  let musicTimer = 0;
  const prefs = { se: true, music: true, volume: 0.6 };
  try { Object.assign(prefs, JSON.parse(localStorage.getItem("audio") || "{}")); } catch (e) { /* defaults */ }
  const savePrefs = () => { try { localStorage.setItem("audio", JSON.stringify(prefs)); } catch (e) { /* storage blocked */ } };

  const url = (id) => URL_BASE + String(id).padStart(3, "0") + ".ogg";

  /** Browsers only allow sound after a tap or click: call this from one (e.g. "Start battle"). */
  function unlock() {
    if (!ctx) {
      const AC = self.AudioContext || self.webkitAudioContext;
      if (!AC) return;
      ctx = new AC();
      master = ctx.createGain(); master.connect(ctx.destination);
      seGain = ctx.createGain(); seGain.connect(master);
      musicGain = ctx.createGain(); musicGain.connect(master);
      applyPrefs();
    }
    if (ctx.state === "suspended") ctx.resume().catch(() => {});
  }

  function applyPrefs() {
    if (!ctx) return;
    master.gain.value = prefs.volume;
    seGain.gain.value = prefs.se ? 1 : 0;
    musicGain.gain.value = prefs.music ? 0.7 : 0; // music a bit under the effects, like the game
  }

  async function fetchSaved(id) {
    const cache = self.caches ? await caches.open(CACHE).catch(() => null) : null;
    let res = cache ? await cache.match(url(id)) : null;
    if (!res) {
      res = await fetch(url(id));
      if (!res.ok) throw new Error("HTTP " + res.status);
      if (cache) await cache.put(url(id), res.clone()).catch(() => {});
    }
    return res.arrayBuffer();
  }

  function load(id) {
    if (!ctx || id < 0) return Promise.resolve(null);
    if (!buffers.has(id)) {
      buffers.set(id, fetchSaved(id)
        .then((data) => new Promise((ok, fail) => ctx.decodeAudioData(data, ok, fail)))
        .catch(() => { buffers.delete(id); return null; })); // (try again next time, e.g. back online)
    }
    return buffers.get(id);
  }

  /** Sound effects from a battle step. One that isn't loaded yet is skipped (and loaded for next time). */
  function effects(ids) {
    if (!ctx || !prefs.se || !ids || !ids.length) return;
    for (const id of ids) {
      const p = buffers.get(id);
      if (!p) { load(id); continue; }
      p.then((buf) => {
        if (!buf || ctx.state !== "running") return;
        const src = ctx.createBufferSource();
        src.buffer = buf;
        src.connect(seGain);
        src.start();
      });
    }
  }

  function stopMusic() {
    clearTimeout(musicTimer);
    if (music) { try { music.src.stop(); } catch (e) { /* already stopped */ } music = null; }
  }

  /** Plays music `id`; loops from loopMs (0 = the whole track) unless once. delayMs: start later. */
  function playMusic(id, loopMs, once, delayMs) {
    stopMusic();
    if (!ctx || id < 0) return;
    const token = {};
    music = { src: null, id, token };
    const start = async () => {
      const buf = await load(id);
      if (!buf || !music || music.token !== token) return;
      const src = ctx.createBufferSource();
      src.buffer = buf;
      if (!once) {
        src.loop = true;
        src.loopStart = Math.min(loopMs / 1000, buf.duration - 0.01);
        src.loopEnd = buf.duration;
      }
      src.connect(musicGain);
      src.start();
      music.src = src;
    };
    if (delayMs) musicTimer = setTimeout(start, delayMs); else start();
  }

  return {
    unlock,
    /** Start of a battle: load the usual sounds and the stage's music. */
    prepare(musIds) { unlock(); for (const id of [...COMMON, ...musIds]) load(id); },
    effects,
    playMusic,
    stopMusic,
    pause() { if (ctx && ctx.state === "running") ctx.suspend().catch(() => {}); },
    resume() { if (ctx && ctx.state === "suspended") ctx.resume().catch(() => {}); },
    prefs,
    /** For checking: audio state, sounds loaded, music playing. */
    state() { return { ctx: ctx && ctx.state, loaded: buffers.size, music: music && music.src ? music.id : null }; },
    set(key, value) { prefs[key] = value; savePrefs(); applyPrefs(); },
  };
})();
