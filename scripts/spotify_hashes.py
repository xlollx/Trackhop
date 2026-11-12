#!/usr/bin/env python3
"""Reads Spotify's web player the way Trackhop's Spotify (web) connector does, and writes the
persisted-query hashes it needs to spotify-hashes.json (published on the "badges" branch, read by
the app before anything else). Exit code 1 when one is missing: the hourly run turns red, a warning
before users notice.

    python3 scripts/spotify_hashes.py <out dir>
"""
import json, re, sys, time, urllib.request

NEEDED = ["libraryV3", "fetchPlaylist", "fetchLibraryTracks", "searchTracks", "searchDesktop", "getTrack",
          "profileAttributes", "addToPlaylist", "removeFromPlaylist", "addToLibrary", "removeFromLibrary", "userTopContent",
          "ArtistConcerts", "concert"]
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
HASH = re.compile(r'"([A-Za-z][A-Za-z0-9_]*)"\s*,\s*"(query|mutation)"\s*,\s*"([0-9a-f]{64})"')
PACK = re.compile(r'https://open\.spotifycdn\.com/cdn/build/web-player/web-player\.[^"\']+\.js')
CHUNK_MAP = re.compile(r'\{\d+:"[^"]+"(?:,\d+:"[^"]+")*\}')
PAIR = re.compile(r'(\d+):"([^"]+)"')
HEX = re.compile(r'[0-9a-f]{8,}')


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def main(out_dir):
    page = get("https://open.spotify.com/")
    pack = PACK.search(page)
    if not pack:
        sys.exit("No web-player bundle on the page")
    found = {}

    def scan(text):
        for m in HASH.finditer(text):
            found.setdefault(m.group(1), m.group(3))

    main_js = get(pack.group(0))
    scan(main_js)
    maps = [dict((int(a), b) for a, b in PAIR.findall(m.group(0))) for m in CHUNK_MAP.finditer(main_js)]
    maps = [m for m in maps if len(m) > 3]
    hashes = [m for m in maps if all(HEX.fullmatch(v) for v in m.values())]
    names = [m for m in maps if m not in hashes]
    chunks = []
    for h in hashes:
        for n in names:
            common = sorted(set(h) & set(n))
            if len(common) >= 3:
                chunks += [f"https://open.spotifycdn.com/cdn/build/web-player/{n[i]}.{h[i]}.js" for i in common]
    chunks = sorted(dict.fromkeys(chunks), key=lambda u: 0 if re.search("xpui|routes|playlist|library|search|collection", u) else 1)
    read = 0
    for u in chunks:
        if all(n in found for n in NEEDED) or read > 80_000_000:
            break
        try:
            t = get(u)
        except Exception:
            continue
        read += len(t)
        scan(t)
    ops = {n: {"hash": found[n]} for n in NEEDED if n in found}
    out = {"schema_version": 1, "bundle": pack.group(0).rsplit("/", 1)[-1],
           "last_updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "operations": ops}
    with open(f"{out_dir}/spotify-hashes.json", "w") as f:
        json.dump(out, f, indent=1, sort_keys=True)
    missing = [n for n in NEEDED if n not in found]
    print(f"{len(ops)}/{len(NEEDED)} hashes from {out['bundle']}, {read // 1_000_000} MB of chunks read")
    if missing:
        sys.exit("Missing: " + ", ".join(missing))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
