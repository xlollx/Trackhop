#!/usr/bin/env python3
"""Counts Trackhop's downloads for the README badge and the site's counter.

    downloads.py OUT_DIR

Adds up every GitHub release asset (APK, Desktop) and the Docker Hub pulls. Stores that publish no
count (F-Droid, Snap, Microsoft Store) are not in it, so the total only ever undercounts.
Writes OUT_DIR/downloads.json and OUT_DIR/stars.json (shields.io endpoints) and OUT_DIR/downloads-detail.json.
"""
import json
import os
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

REPO = "xlollx/Trackhop"
IMAGE = "xlollx/trackhop"


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
        total += sum(a["download_count"] for r in releases for a in r["assets"])
        page += 1


def docker():
    return int(get(f"https://hub.docker.com/v2/repositories/{IMAGE}/").get("pull_count") or 0)


def short(n):
    """The same figure the site shows: rounded down to the hundred, with a "+" (8,053 -> "8,000+")."""
    if n < 100:
        return str(n)
    return f"{n // 100 * 100:,}+"


if __name__ == "__main__":
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    parts = {"github": github(), "docker": docker()}
    total = sum(parts.values())
    (out / "downloads.json").write_text(json.dumps(
        {"schemaVersion": 1, "label": "downloads", "message": short(total), "color": "brightgreen"}) + "\n")
    (out / "downloads-detail.json").write_text(json.dumps(
        {"total": total, **parts, "updated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%MZ")}, indent=2) + "\n")
    # Stars too: shields.io's own GitHub badge often shows "invalid" when its GitHub quota runs out.
    stars = get(f"https://api.github.com/repos/{REPO}")["stargazers_count"]
    (out / "stars.json").write_text(json.dumps(
        {"schemaVersion": 1, "label": "stars", "message": str(stars), "color": "e3b341"}) + "\n")
    print(total, parts, "stars", stars)
