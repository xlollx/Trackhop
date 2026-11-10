#!/usr/bin/env python3
"""
A post on Trackhop's Instagram (@trackhop.app) through the official Instagram API (Instagram Login):
the pictures of a folder in the repository (JPEG, 1080×1350 for 4:5, in name order, at most 10)
as a carousel, or one picture alone, with the folder's caption.txt as the caption.
Run by .github/workflows/instagram.yml. Instagram fetches each picture from a public address: the
file as committed, on raw.githubusercontent.com at this very commit.
Secret INSTAGRAM_TOKEN and variable INSTAGRAM_USER_ID; without them nothing is sent.
With DRY_RUN=true the containers are made (Instagram checks every picture) but nothing is published.
"""
import json, os, sys, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path

sys.stdout.reconfigure(line_buffering=True)
ROOT = Path(__file__).resolve().parents[2]
API = "https://graph.instagram.com/v22.0"


def call(method: str, path: str, **params) -> dict:
    data = urllib.parse.urlencode(params).encode()
    url = f"{API}/{path}" + ("" if method == "POST" else "?" + data.decode())
    req = urllib.request.Request(url, data=data if method == "POST" else None, method=method)
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        # Instagram's own reason, never the address (it holds the token).
        err = json.loads(e.read() or b"{}").get("error", {})
        sys.exit(f"Instagram refused {path.split('/')[-1]}: HTTP {e.code} {err.get('message', '')}")


def ready(container: str, token: str) -> None:
    for _ in range(30):
        status = call("GET", container, fields="status_code", access_token=token).get("status_code")
        if status == "FINISHED":
            return
        if status in ("ERROR", "EXPIRED"):
            sys.exit(f"Instagram could not prepare {container}: {status}")
        time.sleep(5)
    sys.exit(f"Instagram still preparing {container} after a while")


def main():
    folder = ROOT / os.environ["FOLDER"].strip().strip("/")
    pics = sorted(p for p in folder.iterdir() if p.suffix.lower() in (".jpg", ".jpeg"))[:10]
    if not pics:
        sys.exit(f"No JPEG pictures in {folder} (Instagram's API takes JPEG only)")
    caption_file = folder / "caption.txt"
    caption = caption_file.read_text().strip() if caption_file.exists() else ""
    if len(caption) > 2200:
        sys.exit("The caption is longer than Instagram's 2200 characters")
    repo, sha = os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_SHA"]
    urls = [f"https://raw.githubusercontent.com/{repo}/{sha}/{p.relative_to(ROOT).as_posix()}" for p in pics]
    print(f"{len(urls)} pictures, caption of {len(caption)} characters")
    token, user = os.environ.get("INSTAGRAM_TOKEN", "").strip(), os.environ.get("INSTAGRAM_USER_ID", "").strip()
    if not token or not user:
        print("No INSTAGRAM_TOKEN / INSTAGRAM_USER_ID: nothing sent.")
        return
    if len(urls) == 1:
        post = call("POST", f"{user}/media", image_url=urls[0], caption=caption, access_token=token)["id"]
    else:
        children = []
        for u in urls:
            c = call("POST", f"{user}/media", image_url=u, is_carousel_item="true", access_token=token)["id"]
            ready(c, token)
            children.append(c)
        post = call("POST", f"{user}/media", media_type="CAROUSEL", children=",".join(children), caption=caption, access_token=token)["id"]
    ready(post, token)
    if os.environ.get("DRY_RUN") == "true":
        print("Dry run: Instagram took every picture and the caption; nothing published.")
        return
    media = call("POST", f"{user}/media_publish", creation_id=post, access_token=token)["id"]
    link = call("GET", media, fields="permalink", access_token=token).get("permalink", "")
    print(f"Published: {link}")


if __name__ == "__main__":
    main()
