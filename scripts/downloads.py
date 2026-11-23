#!/usr/bin/env python3
"""Counts Trackhop's downloads for the README badge and the site's counter.

    downloads.py OUT_DIR

Adds up every download that has a figure: GitHub release assets (APK, Desktop; winget, Scoop,
Homebrew and Chocolatey download these too), Docker Hub pulls, SourceForge downloads (public stats),
itch.io downloads (with the ITCH_API_KEY secret, or BUTLER_API_KEY: an itch.io API key), Google Play
installs and AppGallery downloads (from play.json and appgallery.json in the badges branch, written by
play-stats.yml and appgallery-stats.yml). A source that does not answer keeps its last published
figure, so the total never drops because of an outage. F-Droid and the Microsoft Store publish no
count, so the total only ever undercounts.
Also counts the music services in the README's "Music services at a glance" table for its badge.
Writes OUT_DIR/downloads.json and OUT_DIR/stars.json (shields.io endpoints) and OUT_DIR/downloads-detail.json.
"""
import json
import os
import re
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = "xlollx/Trackhop"
IMAGE = "xlollx/trackhop"
SF_PROJECT = "trackhop"
ITCH_PAGE = "xlollx.itch.io/trackhop"


def get(url):
    req = urllib.request.Request(url, headers={"User-Agent": "trackhop-downloads"})
    token = os.environ.get("GITHUB_TOKEN")
    if token and url.startswith("https://api.github.com/"):
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def github():
    total, page = 0, 1
    while True:
        releases = get(f"https://api.github.com/repos/{REPO}/releases?per_page=100&page={page}")
        if not releases:
            return total
        # The community-matches release holds data the app downloads every day, not installs.
        total += sum(a["download_count"] for r in releases if r["tag_name"] != "community-matches" for a in r["assets"])
        page += 1


def docker():
    return int(get(f"https://hub.docker.com/v2/repositories/{IMAGE}/").get("pull_count") or 0)


def sourceforge():
    """Every download since the project was made (23/11/2026), from SourceForge's public stats."""
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    d = get(f"https://sourceforge.net/projects/{SF_PROJECT}/files/stats/json?start_date=2026-11-01&end_date={today}")
    return int(d["total"])


def itch():
    """The downloads of Trackhop's itch.io page (all its files), from itch.io's server-side API."""
    key = os.environ.get("ITCH_API_KEY") or os.environ.get("BUTLER_API_KEY")
    if not key:
        return None
    req = urllib.request.Request("https://itch.io/api/1/key/my-games",
                                 headers={"User-Agent": "trackhop-downloads", "Authorization": f"Bearer {key}"})
    with urllib.request.urlopen(req, timeout=30) as r:
        games = json.load(r)["games"]
    return sum(int(g.get("downloads_count") or 0) for g in games if g.get("url", "").rstrip("/").endswith(ITCH_PAGE))


def badges_file(name):
    return get(f"https://raw.githubusercontent.com/{REPO}/badges/{name}")


def play():
    """Google Play: every user install in Play's reports (play-stats.yml)."""
    return int(badges_file("play.json")["totals"]["Daily User Installs"])


def appgallery():
    """Huawei AppGallery: every download in AppGallery Connect's report (appgallery-stats.yml)."""
    return int(badges_file("appgallery.json")["reports"]["appDownloadExport"]["totals"]["Total downloads"])


def services():
    """The services in the README's "Music services at a glance" table: one per bold name (two for
    "NetEase Cloud Music, QQ Music"), without Files and Charts, which are not services."""
    readme = (Path(__file__).resolve().parent.parent / "README.md").read_text(encoding="utf-8")
    start = readme.index("### Music services at a glance")
    table = readme[start:readme.index("\n#", start + 10)]
    names = [n.strip() for row in re.findall(r"^\| \*\*(.+?)\*\*", table, re.M) for n in row.split(",")]
    return len([n for n in names if n not in ("Files", "Charts")])


def previous():
    """The figures published last time (the badges branch), for a source that does not answer now."""
    try:
        return get(f"https://raw.githubusercontent.com/{REPO}/badges/downloads-detail.json")
    except Exception:
        return {}


def short(n):
    """The same figure the site shows: rounded down to the hundred, with a "+" (8,053 -> "8,000+")."""
    if n < 100:
        return str(n)
    return f"{n // 100 * 100:,}+"


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    parts = {"github": github(), "docker": docker()}
    last = previous()
    for name, count in (("sourceforge", sourceforge), ("itch", itch), ("play", play), ("appgallery", appgallery)):
        try:
            n = count()
        except Exception as e:
            print(f"{name}: {e}", file=sys.stderr)
            n = None
        parts[name] = n if n is not None else int(last.get(name) or 0)
    total = sum(parts.values())
    (out / "downloads.json").write_text(json.dumps(
        {"schemaVersion": 1, "label": "downloads", "message": short(total), "color": "brightgreen"}) + "\n")
    (out / "services.json").write_text(json.dumps(
        {"schemaVersion": 1, "label": "music services", "message": str(services()), "color": "blue"}) + "\n")
    (out / "docker.json").write_text(json.dumps(
        {"schemaVersion": 1, "label": "docker pulls", "message": short(parts["docker"]), "color": "1d63ed"}) + "\n")
    (out / "downloads-detail.json").write_text(json.dumps(
        {"total": total, **parts, "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ")}, indent=2) + "\n")
    # Stars too: shields.io's own GitHub badge often shows "invalid" when its GitHub quota runs out.
    stars = get(f"https://api.github.com/repos/{REPO}")["stargazers_count"]
    (out / "stars.json").write_text(json.dumps(
        {"schemaVersion": 1, "label": "stars", "message": str(stars), "color": "e3b341"}) + "\n")
    # The latest release's tag, for the README's release badge (drawn by scripts/badge_svg.py).
    latest = get(f"https://api.github.com/repos/{REPO}/releases/latest")
    tag = latest.get("tag_name", "")
    # When it last shipped, as "Nov 2026": a sign of life for the README's "updated" badge.
    shipped = datetime.strptime(latest["published_at"][:10], "%Y-%m-%d").strftime("%b %Y") if latest.get("published_at") else ""
    (out / "updated.json").write_text(json.dumps(
        {"schemaVersion": 1, "label": "updated", "message": shipped, "color": "brightgreen"}) + "\n")
    (out / "release.json").write_text(json.dumps(
        {"schemaVersion": 1, "label": "release", "message": tag, "color": "blue"}) + "\n")
    print(total, parts, "stars", stars, "release", tag)
