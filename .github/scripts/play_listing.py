#!/usr/bin/env python3
"""
The Google Play store listing from the repository, through the Play Developer API (an "edit"):
texts from play/listing/<locale>/{title,short_description,full_description}.txt, phone screenshots
from docs/store/play/<locale>/<n>.jpg (the first 8, in number order), feature graphics from
docs/store/feature_<lang>.png. Run by .github/workflows/play-listing.yml.

Without --commit the edit is only validated by Google and then thrown away: nothing changes on Play.
The service account (secret PLAY_SERVICE_ACCOUNT_JSON) needs "Manage store presence".
"""
import argparse, json, os, re, sys
from pathlib import Path

import requests
from google.oauth2 import service_account
from google.auth.transport.requests import Request
from PIL import Image

PKG = "com.xlollx.songport"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PKG}/edits"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PKG}/edits"
ROOT = Path(__file__).resolve().parents[2]
LIMITS = {"title": 30, "short_description": 80, "full_description": 4000}
MAX_SHOTS = 8  # Play's limit for phone screenshots


def texts(locale):
    d = ROOT / "play/listing" / locale
    out = {k: (d / f"{k}.txt").read_text(encoding="utf-8").strip() for k in LIMITS}
    for k, n in LIMITS.items():
        if len(out[k]) > n:
            sys.exit(f"{locale}/{k}: {len(out[k])} characters, Play allows {n}")
    return out


def shots(locale):
    d = ROOT / "docs/store/play" / locale
    files = sorted(d.glob("*.jpg"), key=lambda p: int(re.sub(r"\D", "", p.stem) or 0))[:MAX_SHOTS]
    for f in files:
        w, h = Image.open(f).size
        if max(w, h) > 2 * min(w, h) or min(w, h) < 320 or max(w, h) > 3840:
            sys.exit(f"{f}: {w}x{h}, Play takes 320-3840 px with the long side at most twice the short one")
    return files


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--texts", action="store_true")
    ap.add_argument("--screenshots", action="store_true")
    ap.add_argument("--feature", action="store_true")
    ap.add_argument("--commit", action="store_true", help="publish the edit (without: validate only)")
    ap.add_argument("--locales", default="", help="comma-separated; default: every folder in play/listing")
    a = ap.parse_args()

    locales = [l for l in a.locales.split(",") if l] or sorted(p.name for p in (ROOT / "play/listing").iterdir() if p.is_dir())
    # Everything is read and checked before Google is asked anything.
    plan = {l: (texts(l) if a.texts else None, shots(l) if a.screenshots else []) for l in locales}

    key = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if not key:
        sys.exit("Texts and pictures are fine. No PLAY_SERVICE_ACCOUNT_JSON: nothing sent to Google.")
    creds = service_account.Credentials.from_service_account_info(
        json.loads(key), scopes=["https://www.googleapis.com/auth/androidpublisher"])
    creds.refresh(Request())
    s = requests.Session()
    s.headers["Authorization"] = f"Bearer {creds.token}"

    def call(method, url, **kw):
        r = s.request(method, url, timeout=120, **kw)
        if r.status_code >= 300:
            sys.exit(f"{method} {url.split('/edits')[-1]}: {r.status_code} {r.text[:500]}")
        return r.json() if r.content else {}

    edit = call("POST", API)["id"]
    try:
        for loc, (t, files) in plan.items():
            if t:
                call("PUT", f"{API}/{edit}/listings/{loc}", json={
                    "language": loc, "title": t["title"], "shortDescription": t["short_description"], "fullDescription": t["full_description"]})
                print(f"{loc}: texts")
            if files:
                call("DELETE", f"{API}/{edit}/listings/{loc}/phoneScreenshots")
                for f in files:
                    call("POST", f"{UPLOAD}/{edit}/listings/{loc}/phoneScreenshots?uploadType=media",
                         data=f.read_bytes(), headers={"Content-Type": "image/jpeg"})
                print(f"{loc}: {len(files)} screenshots")
            if a.feature:
                g = ROOT / f"docs/store/feature_{loc.split('-')[0]}.png"
                if g.exists():
                    call("DELETE", f"{API}/{edit}/listings/{loc}/featureGraphic")
                    call("POST", f"{UPLOAD}/{edit}/listings/{loc}/featureGraphic?uploadType=media",
                         data=g.read_bytes(), headers={"Content-Type": "image/png"})
                    print(f"{loc}: feature graphic")
        call("POST", f"{API}/{edit}:validate")
        print("Google validated the edit.")
        if a.commit:
            call("POST", f"{API}/{edit}:commit")
            print("Committed: the listing changes go to Google's review.")
            return
    finally:
        if not a.commit:
            s.delete(f"{API}/{edit}", timeout=60)
    print("Validate only: nothing was changed on Google Play.")


if __name__ == "__main__":
    main()
