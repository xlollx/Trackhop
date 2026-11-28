#!/usr/bin/env python3
"""
Points every direct Trackhop Desktop download link at the newest Desktop release that is out.

The app's APK, the Bridge and the release badges link to releases/latest and need nothing. Desktop
can't: its releases are prereleases (server-v<version>, --latest=false), so the README, the site's
home page and the Desktop guide name the version in each link. This keeps them on the newest one.

  python3 scripts/download_links.py [--version X.Y.Z] [--check]

The version is the newest server-v release on GitHub that is not a draft and already has its files
(GITHUB_TOKEN or GH_TOKEN, if set, is sent along). Before anything is written every link is fetched:
if one does not answer, nothing changes and it exits 1. --check writes nothing and exits 1 when a
file is behind. Run by the Site data workflow after every Build and Desktop run on main.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = "xlollx/Trackhop"
FILES = ["README.md", "docs/guide/desktop.md", "docs/_includes/home.html"]
V = r"\d+\.\d+\.\d+"
# Only link-shaped and "current version" text: the version history (CHANGELOG, releases.json) and
# examples such as the file name in security.md stay as they are.
RULES = [
    (re.compile(rf"(releases/(?:download|tag)/server-v){V}"), r"\g<1>{v}"),
    (re.compile(rf"(Trackhop-Desktop-v){V}(-)"), r"\g<1>{v}\g<2>"),
    (re.compile(rf"(Version ){V}( \(\[what's in it\])"), r"\g<1>{v}\g<2>"),
    (re.compile(rf"(The current version is \*\*){V}(\*\*)"), r"\g<1>{v}\g<2>"),
    (re.compile(rf'(assign dv = "){V}(")'), r"\g<1>{v}\g<2>"),
]
LINK = re.compile(rf"https://github\.com/{REPO}/releases/(?:download/server-v{V}/[^\s\"')`]+|tag/server-v{V})")
DOWNLOAD = f"https://github.com/{REPO}/releases/download/server-v{{v}}/Trackhop-Desktop-v{{v}}-"


def request(url, method="GET"):
    req = urllib.request.Request(url, method=method, headers={"User-Agent": "trackhop-download-links"})
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        req.add_header("Authorization", f"Bearer {token}")
    return urllib.request.urlopen(req, timeout=30)


def newest_desktop():
    with request(f"https://api.github.com/repos/{REPO}/releases?per_page=50") as r:
        releases = json.load(r)
    for rel in releases:
        tag = rel["tag_name"]
        if rel["draft"] or not re.fullmatch(rf"server-v{V}", tag):
            continue
        v = tag[len("server-v"):]
        # A release whose files are still uploading is skipped until the installer is there.
        if any(a["name"] == f"Trackhop-Desktop-v{v}-windows.msi" for a in rel["assets"]):
            return v
    sys.exit("No Desktop release with its files found.")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def answers(url):
    """GitHub answers a file that exists with a 302 to a signed address that expires: that 302 is enough."""
    opener = urllib.request.build_opener(_NoRedirect)
    req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "trackhop-download-links"})
    try:
        with opener.open(req, timeout=30) as r:
            return r.status < 400
    except urllib.error.HTTPError as e:
        return e.code in (301, 302, 303, 307, 308)
    except OSError:
        return False


def main():
    args = sys.argv[1:]
    check = "--check" in args
    v = args[args.index("--version") + 1] if "--version" in args else newest_desktop()
    changed, links = {}, set()
    for name in FILES:
        path = ROOT / name
        old = path.read_text(encoding="utf-8")
        new = old
        for rule, repl in RULES:
            new = rule.sub(repl.replace("{v}", v), new)
        links.update(m.group(0) for m in LINK.finditer(new))
        if new != old:
            changed[name] = (path, new)
    # The home page builds its links from dv: check the files it names, as they will be.
    links.update(DOWNLOAD.format(v=v) + s for s in ("windows.msi", "macos-arm64.dmg", "linux.deb"))
    if check:
        for name in changed:
            print(f"{name}: Desktop links are not on {v}. Run python3 scripts/download_links.py")
        sys.exit(1 if changed else 0)
    broken = sorted(u for u in links if not answers(u))
    if broken:
        print("Not changed, these links don't answer:", *broken, sep="\n  ")
        sys.exit(1)
    for path, new in changed.values():
        path.write_text(new, encoding="utf-8")
    print(f"Desktop {v}: {len(links)} links answer; " + (f"updated {', '.join(changed)}." if changed else "already up to date."))


if __name__ == "__main__":
    main()
