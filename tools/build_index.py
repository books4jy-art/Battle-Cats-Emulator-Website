"""Build site/data/index.json: where every game file sits inside BCU's asset packs.

BCU's game data lives in encrypted .asset.bcuzip packs in github.com/battlecatsultimate/bcu-assets.
This script reads only the packs' headers and file tables (a few KB each, using HTTP range requests)
and writes an index. The browser then downloads just the files it needs, straight from BCU's repo,
and decrypts them itself. No game data is copied into this repository.

Pack layout (from bcu-core/io/PackLoader.java, readPack / FileLoader):
  pack : HEAD(16) | key(16) | len(4, little-endian) | encrypted ZipDesc JSON (len rounded up to 16) | files...
  file : starts at base + fd.offset, AES-128-CBC with the entry's key,
               IV = md5("battlecatsultimate"), no padding, size rounded up to 16
When two packs contain the same path, the pack with the later id wins (AssetLoader merges in id order).

Usage: python tools/build_index.py [--out site/data/index.json]
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import hashlib
import json
import os
import re
import struct
import sys
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

UPDATE_URL = "https://raw.githubusercontent.com/battlecatsultimate/bcu-page/master/api/updateInfo.json"
ASSET_URL = "https://raw.githubusercontent.com/battlecatsultimate/bcu-assets/master/assets/{id}.asset.bcuzip"
# Music and sound effects: plain .ogg files numbered 000, 001, ... (BCU's UpdateCheck downloads them the same way)
MUSIC_URL = "https://raw.githubusercontent.com/battlecatsultimate/bcu-assets/master/music/{id}.ogg"
CORE_VER = "0.7.19.1"  # AssetLoader.CORE_VER in the pinned bcu-core; packs for newer BCU versions are skipped
HEAD = hashlib.md5(b"battlecatsultimate").digest()
IV = HEAD


def get_ver(v: str) -> int:
    """Data.getVer: every run of digits, folded base 100 ("0.7.19.1" -> 71901, "050000" -> 50000)."""
    ans = 0
    for n in re.findall(r"\d+", v):
        ans = ans * 100 + int(n)
    return ans


def fetch(url: str, start: int | None = None, length: int | None = None, tries: int = 5) -> bytes:
    headers = {"User-Agent": "bcu-web-index"}
    if start is not None:
        headers["Range"] = f"bytes={start}-{start + length - 1}"
    for attempt in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as r:
                data = r.read()
            if start is not None and len(data) != length:
                raise IOError(f"short read {len(data)} != {length}")
            return data
        except Exception as e:  # noqa: BLE001
            if attempt == tries - 1:
                raise
            print(f"  retry {url} ({e})", file=sys.stderr)
            time.sleep(2 * (attempt + 1))
    raise AssertionError


def regulate(n: int) -> int:
    return n if n % 16 == 0 else (n | 0xF) + 1


def decrypt(key: bytes, data: bytes) -> bytes:
    d = Cipher(algorithms.AES(key), modes.CBC(IV)).decryptor()
    return d.update(data) + d.finalize()


def read_pack(asset_id: str) -> dict:
    """One downloaded .asset.bcuzip is a single entry (PackLoader.readPack): its key, base offset and file table."""
    url = ASSET_URL.format(id=asset_id)
    zh = fetch(url, 0, 36)
    if zh[:16] != HEAD:
        raise ValueError(f"{asset_id}: bad header")
    key = zh[16:32]
    (dn,) = struct.unpack("<i", zh[32:36])
    desc = json.loads(decrypt(key, fetch(url, 36, regulate(dn)))[:dn].decode("utf-8"))
    return {
        "id": desc["desc"]["id"],
        "ver": desc["desc"].get("BCU_VERSION", "0.0.0"),
        "asset": asset_id,
        "key": key.hex(),
        "base": 36 + regulate(dn),
        "files": desc["files"],
    }


def music_size(i: int) -> int:
    """Size of music file i (0 if there is none)."""
    url = MUSIC_URL.replace("{id}", f"{i:03d}")
    for attempt in range(5):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, method="HEAD"), timeout=30) as r:
                return int(r.headers.get("Content-Length", 0))
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 0
            time.sleep(2 ** attempt)
        except OSError:
            time.sleep(2 ** attempt)
    raise RuntimeError(f"couldn't check {url}")


def music_list() -> list:
    """[[id, size]] of the music files, probing in blocks until a whole block is missing."""
    found, start = [], 0
    with cf.ThreadPoolExecutor(16) as ex:
        while True:
            sizes = list(ex.map(music_size, range(start, start + 64)))
            found += [[start + i, sz] for i, sz in enumerate(sizes) if sz]
            if not any(sizes):
                return found
            start += 64


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="site/data/index.json")
    args = ap.parse_args()

    info = json.loads(fetch(UPDATE_URL))
    assets = [a["id"] for a in info["assets"] if a["type"] == "core" and get_ver(a["ver"]) <= get_ver(CORE_VER)]
    print(f"{len(assets)} core asset packs")

    with cf.ThreadPoolExecutor(8) as ex:
        packs = dict(zip(assets, ex.map(read_pack, assets)))

    # AssetLoader: entries keyed by id (a later pack with the same id replaces it), then merged in id order.
    zips: dict[str, dict] = {}
    for asset_id in sorted(packs):
        z = packs[asset_id]
        if get_ver(z["ver"]) <= get_ver(CORE_VER):
            zips[z["id"]] = z
    files: dict[str, list] = {}
    zip_list = []
    for zid in sorted(zips):
        z = zips[zid]
        zi = len(zip_list)
        zip_list.append([z["asset"], z["key"], z["base"]])
        for fd in z["files"]:
            files[fd["path"]] = [zi, fd["offset"], fd["size"]]

    out = {
        "core_ver": CORE_VER,
        "built": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "asset_url": ASSET_URL,
        "zips": zip_list,  # [asset id, key hex, base offset]
        "files": files,  # path -> [zip index, offset from base, size]
        "music_url": MUSIC_URL,
        "music": music_list(),  # [id, size]
    }
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w") as fh:
        json.dump(out, fh, separators=(",", ":"), sort_keys=True)
    total = sum(f[2] for f in files.values())
    print(f"{len(zip_list)} entries, {len(files)} files, {total / 1e6:.0f} MB of game data indexed -> {args.out}")
    print(f"{len(out['music'])} music files, {sum(m[1] for m in out['music']) / 1e6:.0f} MB")


if __name__ == "__main__":
    main()
