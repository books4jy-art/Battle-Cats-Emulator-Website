"""Local test server: serves site/ and relays BCU's game data, caching it on disk.

Normally the browser downloads game data straight from BCU's GitHub. Open the site as
http://localhost:8000/?assets=local to get it through this server instead (handy offline, on slow or
restricted networks, and for automated tests: every range is downloaded from GitHub once, then
served from tools/.cache/).

Usage: python tools/dev_server.py [port]
"""
from __future__ import annotations

import hashlib
import os
import re
import sys
import urllib.request
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SITE = os.path.join(ROOT, "site")
CACHE = os.path.join(ROOT, "tools", ".cache")
ASSETS = "https://raw.githubusercontent.com/battlecatsultimate/bcu-assets/master/assets/"
EXTRA = ("https://raw.githubusercontent.com/battlecatsultimate/BCU_Android/"
         "86400116299340f24aea5e80fc38951a8bb08d93/app/src/main/res/raw/")


def upstream(url: str, rng: str | None) -> bytes:
    key = hashlib.sha1(f"{url}|{rng}".encode()).hexdigest()
    path = os.path.join(CACHE, key)
    if os.path.exists(path):
        with open(path, "rb") as fh:
            return fh.read()
    headers = {"User-Agent": "bcu-web-dev"}
    if rng:
        headers["Range"] = rng
    for attempt in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=120) as r:
                data = r.read()
            break
        except Exception:  # noqa: BLE001
            if attempt == 3:
                raise
    os.makedirs(CACHE, exist_ok=True)
    with open(path + ".tmp", "wb") as fh:
        fh.write(data)
    os.replace(path + ".tmp", path)
    return data


class Handler(SimpleHTTPRequestHandler):
    def log_message(self, fmt, *args):  # quieter
        if "/bcu-" not in self.path:
            super().log_message(fmt, *args)

    def do_GET(self):  # noqa: N802
        m = re.fullmatch(r"/bcu-assets/(\d+)\.asset\.bcuzip", self.path)
        e = re.fullmatch(r"/bcu-extra/([\w.-]+\.json)", self.path)
        if not (m or e):
            return super().do_GET()
        rng = self.headers.get("Range")
        try:
            data = upstream(ASSETS + m.group(1) + ".asset.bcuzip" if m else EXTRA + e.group(1), rng if m else None)
        except Exception as ex:  # noqa: BLE001
            self.send_error(502, str(ex))
            return
        self.send_response(206 if (m and rng) else 200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        if m and rng:
            start = int(re.match(r"bytes=(\d+)-", rng).group(1))
            self.send_header("Content-Range", f"bytes {start}-{start + len(data) - 1}/*")
        self.end_headers()
        self.wfile.write(data)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    print(f"http://localhost:{port}/?assets=local  (game data cached in {CACHE})")
    ThreadingHTTPServer(("", port), partial(Handler, directory=SITE)).serve_forever()
