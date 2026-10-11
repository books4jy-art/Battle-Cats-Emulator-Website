"""Make site/data/kr: the Korean game's versions of game files that differ from BCU's.

BCU's game data comes from the Japanese version of the game. The Korean version differs in places that
show (for example which background each Empire of Cats stage uses), so the site uses the Korean files
there. This compares a folder of files from the Korean game (e.g. its DataLocal, unpacked) with BCU's
packs, file by file, and copies the Korean files that differ into site/data/kr (same paths as in BCU's
packs, without "./org/"), listed in site/data/kr/files.json. tools/build_index.py adds that list to the
file index, and the browser then reads those files from the site instead of BCU's packs.

Usage: python tools/kr_overlay.py KOREAN_FILES_DIR [--prefix ./org/stage/] [--index site/data/index.json]
Only BCU files under --prefix are compared (default: stage data). Needs a built index.json.

       python tools/kr_overlay.py --castles DIR
DIR holds the Korean Empire of Cats enemy bases, ecNNN_ko.png, from the Korean game's server-downloaded
ImageServer pack (public copy: github.com/fieryhenry/BCData, kr_server/; server packs are AES-ECB with the
key md5("battlecats")[:8] in hex). They replace BCU's (Japanese) ./org/img/ec/ecNNN.png.
"""
from __future__ import annotations

import argparse
import concurrent.futures as cf
import json
import os
import re

import build_index as B

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "site", "data", "kr")


def bcu_file(index: dict, path: str) -> bytes:
    zi, off, size = index["files"][path]
    asset, key, base = index["zips"][zi]
    raw = B.fetch(index["asset_url"].format(id=asset), base + off, B.regulate(size))
    return B.decrypt(bytes.fromhex(key), raw)[:size]


def norm(b: bytes) -> bytes:
    return b.replace(b"\r", b"").strip()


def add_listing(entries: dict[str, bytes], prefix: str) -> None:
    """Writes the files and replaces the listing's entries under prefix with them."""
    listing_path = os.path.join(OUT, "files.json")
    listing = {}
    if os.path.exists(listing_path):
        with open(listing_path) as fh:
            listing = {p: n for p, n in json.load(fh).items() if not p.startswith(prefix)}
    for path, data in sorted(entries.items()):
        dest = os.path.join(OUT, path[len("./org/"):])
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        with open(dest, "wb") as fh:
            fh.write(data)
        listing[path] = len(data)
    os.makedirs(OUT, exist_ok=True)
    with open(listing_path, "w") as fh:
        json.dump(dict(sorted(listing.items())), fh, indent=0)


def castles(src: str, index: dict) -> None:
    entries = {}
    for name in sorted(os.listdir(src)):
        m = re.fullmatch(r"ec(\d{3})_ko\.png", name)
        path = f"./org/img/ec/ec{m.group(1)}.png" if m else None
        if path in index["files"]:  # only bases BCU has (the site has no use for others)
            with open(os.path.join(src, name), "rb") as fh:
                entries[path] = fh.read()
    add_listing(entries, "./org/img/ec/")
    print(f"{len(entries)} Korean enemy bases -> {OUT}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("kr_dir", nargs="?")
    ap.add_argument("--castles", metavar="DIR")
    ap.add_argument("--prefix", default="./org/stage/")
    ap.add_argument("--index", default=os.path.join(ROOT, "site", "data", "index.json"))
    args = ap.parse_args()
    with open(args.index) as fh:
        index = json.load(fh)
    if args.castles:
        castles(args.castles, index)
        return
    kr = {name: os.path.join(args.kr_dir, name) for name in os.listdir(args.kr_dir)}
    paths = [p for p in index["files"] if p.startswith(args.prefix) and p.rsplit("/", 1)[1] in kr]

    def differs(path: str) -> bool:
        with open(kr[path.rsplit("/", 1)[1]], "rb") as fh:
            return norm(fh.read()) != norm(bcu_file(index, path))

    with cf.ThreadPoolExecutor(16) as ex:
        changed = [p for p, d in zip(paths, ex.map(differs, paths)) if d]

    entries = {}
    for path in changed:
        with open(kr[path.rsplit("/", 1)[1]], "rb") as fh:
            entries[path] = fh.read()
    add_listing(entries, args.prefix)
    print(f"{len(paths)} files compared, {len(changed)} differ -> {OUT}")


if __name__ == "__main__":
    main()
