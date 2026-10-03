// Service worker: keeps the site's own files (page, scripts, game engine, file list) in the browser so the
// site opens offline. Always asks the server first (a cheap "has it changed?" check), so a new version
// shows up straight away when online; falls back to the saved copy when offline. Game data from BCU's
// packs isn't handled here: the game's worker saves that itself (js/worker.js).
"use strict";

const SHELL = "bcu-shell-v1";
const FILES = ["./", "css/style.css", "js/i18n.js", "js/audio.js", "js/app.js", "js/worker.js", "js/aes.js", "teavm/js/bcu.js", "data/index.json"];
const DEV_RELAYS = /^\/bcu-(assets|extra|lang|music)\//; // tools/dev_server.py (the worker saves those)

self.addEventListener("install", (ev) => {
  ev.waitUntil((async () => {
    const cache = await caches.open(SHELL);
    // one by one: a file that can't be fetched now (e.g. signed out) just isn't saved yet
    await Promise.all(FILES.map((f) => fetch(f, { cache: "no-cache" })
      .then((res) => (res.ok && res.type === "basic" ? cache.put(keyOf(new URL(f, self.registration.scope)), res) : null))
      .catch(() => {})));
    await self.skipWaiting();
  })());
});

self.addEventListener("activate", (ev) => {
  ev.waitUntil((async () => {
    for (const name of await caches.keys()) if (name.startsWith("bcu-shell-") && name !== SHELL) await caches.delete(name);
    await self.clients.claim();
  })());
});

/** Saved copies ignore the query string (?assets=local, ?seed=1) and "/" is the page. */
function keyOf(url) {
  const path = url.pathname.endsWith("/") ? url.pathname + "index.html" : url.pathname;
  return url.origin + path;
}

self.addEventListener("fetch", (ev) => {
  const req = ev.request;
  const url = new URL(req.url);
  if (req.method !== "GET" || url.origin !== self.location.origin || DEV_RELAYS.test(url.pathname) || req.headers.has("range")) return;
  ev.respondWith((async () => {
    const key = keyOf(url);
    try {
      // navigations go as they are (so a sign-in redirect still reaches the browser)
      const res = req.mode === "navigate" ? await fetch(req) : await fetch(req, { cache: "no-cache" });
      if (res.status === 200 && res.type === "basic") {
        const copy = res.clone();
        caches.open(SHELL).then((c) => c.put(key, copy)).catch(() => {});
      }
      return res;
    } catch (e) { // offline (or the sign-in page can't be reached): the saved copy
      const cache = await caches.open(SHELL);
      return (await cache.match(key)) || (req.mode === "navigate" && (await cache.match(keyOf(new URL("./", self.registration.scope))))) || Response.error();
    }
  })());
});
