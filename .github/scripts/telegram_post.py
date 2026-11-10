#!/usr/bin/env python3
"""
A post on Trackhop's Telegram channel (.github/workflows/telegram.yml), in English:
- for an app release (tag vX.Y.Z): its What's new from fastlane (en-US, the versionCode of the tag);
- for a Desktop/Docker release (tag server-vX.Y.Z): its section of server/CHANGELOG.md;
- or any text given, with up to 10 pictures as an album (the Instagram carousel).
Secret TELEGRAM_BOT_TOKEN (a bot made with @BotFather, admin of the channel with "Post messages"
only) and variable TELEGRAM_CHANNEL (@name of the channel). Without them nothing is sent.
"""
import json, os, re, sys, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SITE = "https://trackhop.org"
RELEASES = "https://github.com/xlollx/Trackhop/releases/tag/"


def plain(md: str) -> str:
    """Markdown to the plain text Telegram shows: no bold, links as their text, short bullets."""
    t = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", md)
    t = re.sub(r"\*\*|__|`", "", t)
    t = re.sub(r"^\s*[-*]\s+", "• ", t, flags=re.M)
    return re.sub(r"\n{3,}", "\n\n", t).strip()


def app_text(tag: str) -> str:
    gradle = (ROOT / "android/app/build.gradle").read_text()
    code = re.search(r"versionCode\s+(\d+)", gradle).group(1)
    notes = ROOT / f"fastlane/metadata/android/en-US/changelogs/{code}.txt"
    body = notes.read_text().strip() if notes.exists() else ""
    v = tag.removeprefix("v")
    return f"Trackhop {v} for Android is out\n\n{body}\n\nDownload: {RELEASES}{tag}\nAll the ways to get it: {SITE}"


def server_text(tag: str) -> str:
    v = tag.removeprefix("server-v")
    section, keep = [], False
    for line in (ROOT / "server/CHANGELOG.md").read_text().splitlines():
        if line.startswith("## "):
            keep = line.split()[1] == v
            continue
        if keep:
            section.append(line)
    return (f"Trackhop {v} for Desktop (Windows, macOS, Linux) and Docker is out\n\n{plain(chr(10).join(section))}\n\n"
            f"Download: {RELEASES}{tag}\nDocker: docker pull ghcr.io/xlollx/trackhop:{v}\nGuides: {SITE}/guide/desktop · {SITE}/guide/docker")


def call(token: str, method: str, data: bytes, ctype: str) -> None:
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/{method}", data=data, headers={"Content-Type": ctype})
    with urllib.request.urlopen(req, timeout=60) as r:
        ok = json.load(r).get("ok")
    if not ok:
        sys.exit(f"Telegram refused {method}")


def multipart(fields: dict, files: list) -> tuple:
    b = "----trackhop" + os.urandom(8).hex()
    out = bytearray()
    for k, v in fields.items():
        out += f"--{b}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode()
    for name, path in files:
        out += f"--{b}\r\nContent-Disposition: form-data; name=\"{name}\"; filename=\"{path.name}\"\r\nContent-Type: image/png\r\n\r\n".encode()
        out += path.read_bytes() + b"\r\n"
    out += f"--{b}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={b}"


def main():
    tag, text, images = (os.environ.get(k, "").strip() for k in ("TAG", "TEXT", "IMAGES"))
    if not text:
        if tag.startswith("server-v"): text = server_text(tag)
        elif tag.startswith("v"): text = app_text(tag)
        else: sys.exit("Nothing to post: give a tag (vX.Y.Z or server-vX.Y.Z) or a text.")
    pics = [ROOT / p.strip() for p in images.split(",") if p.strip()][:10]
    for p in pics:
        if not p.is_file(): sys.exit(f"No picture at {p}")
    print(text)
    token, chat = os.environ.get("TELEGRAM_BOT_TOKEN", ""), os.environ.get("TELEGRAM_CHANNEL", "")
    if not token or not chat:
        print("No TELEGRAM_BOT_TOKEN / TELEGRAM_CHANNEL: nothing sent.")
        return
    if not pics:
        data = json.dumps({"chat_id": chat, "text": text[:4096], "disable_web_page_preview": False}).encode()
        call(token, "sendMessage", data, "application/json")
    else:
        # An album: the text as the first picture's caption (Telegram takes up to 1024 characters there).
        media = [{"type": "photo", "media": f"attach://p{i}"} | ({"caption": text[:1024]} if i == 0 else {}) for i in range(len(pics))]
        data, ctype = multipart({"chat_id": chat, "media": json.dumps(media)}, [(f"p{i}", p) for i, p in enumerate(pics)])
        call(token, "sendMediaGroup", data, ctype)
        if len(text) > 1024:
            call(token, "sendMessage", json.dumps({"chat_id": chat, "text": text[:4096]}).encode(), "application/json")
    print("Posted.")


if __name__ == "__main__":
    main()
