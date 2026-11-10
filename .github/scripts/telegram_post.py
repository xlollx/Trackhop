#!/usr/bin/env python3
"""
A post on Trackhop's Telegram channel (.github/workflows/telegram.yml), in English:
- for an app release (tag vX.Y.Z): its What's new from fastlane (en-US, the versionCode of the tag);
- for a Desktop/Docker release (tag server-vX.Y.Z): its section of server/CHANGELOG.md;
- or any text given, with up to 10 pictures as an album (the Instagram carousel).
Secret TELEGRAM_BOT_TOKEN (a bot made with @BotFather, admin of the channel with "Post messages"
only) and variable TELEGRAM_CHANNEL (@name of the channel). Without them nothing is sent.
"""
import html, json, os, re, sys, urllib.error, urllib.request

sys.stdout.reconfigure(line_buffering=True)
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SITE = "https://trackhop.org"
RELEASES = "https://github.com/xlollx/Trackhop/releases/tag/"


def bullets(notes: str) -> str:
    """One line per sentence, issue numbers dropped: a release note read on a phone."""
    notes = re.sub(r"\s*\(#\d+[^)]*\)", "", notes).strip()
    parts = re.split(r"(?<=[.!?])\s+(?=[A-Z\"“])", notes)
    return "\n".join("• " + html.escape(p.strip().rstrip("."), quote=False) for p in parts if p.strip())


def md_bullets(md: str) -> str:
    """The server changelog's Markdown bullets to Telegram HTML: bold kept, links as their text."""
    out = []
    for line in md.strip().splitlines():
        line = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line.strip())
        if not line: continue
        line = re.sub(r"\s*\(#\d+[^)]*\)", "", re.sub(r"^[-*]\s+", "", line))
        # Short on a phone: the bold title and its first clause; the full text is on the release page.
        line = re.split(r";\s", line, maxsplit=1)[0].rstrip(".")
        line = html.escape(line.replace("`", ""), quote=False)
        line = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", line)
        line = re.sub(r"(?<![*\w])\*([^*]+)\*(?!\w)", r"<i>\1</i>", line)
        out.append("• " + line)
    return "\n".join(out)


def app_text(tag: str) -> str:
    gradle = (ROOT / "android/app/build.gradle").read_text()
    code = re.search(r"versionCode\s+(\d+)", gradle).group(1)
    notes = ROOT / f"fastlane/metadata/android/en-US/changelogs/{code}.txt"
    body = notes.read_text().strip() if notes.exists() else ""
    v = tag.removeprefix("v")
    return (f"🎉 <b>Trackhop {v} for Android</b>\n\n{bullets(body)}\n\n"
            f"⬇️ <a href=\"{RELEASES}{tag}\">Download</a>  ·  🌐 <a href=\"{SITE}\">All the ways to get it</a>")


def server_text(tag: str) -> str:
    v = tag.removeprefix("server-v")
    section, keep = [], False
    for line in (ROOT / "server/CHANGELOG.md").read_text().splitlines():
        if line.startswith("## "):
            keep = line.split()[1] == v
            continue
        if keep:
            section.append(line)
    return (f"🖥️ <b>Trackhop {v} for Desktop and Docker</b>\n<i>Windows, macOS, Linux</i>\n\n{md_bullets(chr(10).join(section))}\n\n"
            f"⬇️ <a href=\"{RELEASES}{tag}\">Download</a>  ·  🐳 <code>docker pull ghcr.io/xlollx/trackhop:{v}</code>\n"
            f"📖 <a href=\"{SITE}/guide/desktop\">Desktop guide</a>  ·  <a href=\"{SITE}/guide/docker\">Docker guide</a>")


def call(token: str, method: str, data: bytes, ctype: str) -> None:
    req = urllib.request.Request(f"https://api.telegram.org/bot{token}/{method}", data=data, headers={"Content-Type": ctype})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            ok = json.load(r).get("ok")
    except urllib.error.HTTPError as e:
        # Telegram's own reason ("chat not found", "need administrator rights in the channel chat"...), never the address (it holds the token).
        reason = json.loads(e.read() or b"{}").get("description", "")
        sys.exit(f"Telegram refused {method}: HTTP {e.code} {reason}")
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
    tag, text, images, edit = (os.environ.get(k, "").strip() for k in ("TAG", "TEXT", "IMAGES", "EDIT"))
    html_mode = not text  # a release post is written in Telegram's HTML; a given text goes as it is
    if not text:
        if tag.startswith("server-v"): text = server_text(tag)
        elif tag.startswith("v"): text = app_text(tag)
        else: sys.exit("Nothing to post: give a tag (vX.Y.Z or server-vX.Y.Z) or a text.")
    pics = [ROOT / p.strip() for p in images.split(",") if p.strip()][:10]
    for p in pics:
        if not p.is_file(): sys.exit(f"No picture at {p}")
    print(text)
    token, chat = os.environ.get("TELEGRAM_BOT_TOKEN", "").strip(), os.environ.get("TELEGRAM_CHANNEL", "").strip()
    if not token or not chat:
        print("No TELEGRAM_BOT_TOKEN / TELEGRAM_CHANNEL: nothing sent.")
        return
    # "https://t.me/trackhop_app", "t.me/trackhop_app" and "trackhop_app" all mean "@trackhop_app"; a numeric id stays.
    chat = re.sub(r"^(https?://)?t\.me/", "", chat)
    if not chat.startswith("@") and not chat.lstrip("-").isdigit():
        chat = "@" + chat
    try:
        with urllib.request.urlopen(f"https://api.telegram.org/bot{token}/getMe", timeout=30) as r:
            me = json.load(r).get("result", {}).get("username", "?")
    except urllib.error.HTTPError as e:
        sys.exit(f"Telegram refused the bot token: HTTP {e.code} (is TELEGRAM_BOT_TOKEN the one @BotFather gave?)")
    print(f"Bot @{me} posting to {chat}")
    if not pics:
        msg = {"chat_id": chat, "text": text[:4096], "link_preview_options": {"is_disabled": True}}
        if html_mode: msg["parse_mode"] = "HTML"
        if edit:
            # Rewrites a post the bot made: its number, or its link (t.me/trackhop_app/12).
            msg["message_id"] = int(re.sub(r".*/", "", edit))
            call(token, "editMessageText", json.dumps(msg).encode(), "application/json")
        else:
            call(token, "sendMessage", json.dumps(msg).encode(), "application/json")
    else:
        # An album: the text as the first picture's caption (Telegram takes up to 1024 characters there).
        first = {"caption": text[:1024]} | ({"parse_mode": "HTML"} if html_mode else {})
        media = [{"type": "photo", "media": f"attach://p{i}"} | (first if i == 0 else {}) for i in range(len(pics))]
        data, ctype = multipart({"chat_id": chat, "media": json.dumps(media)}, [(f"p{i}", p) for i, p in enumerate(pics)])
        call(token, "sendMediaGroup", data, ctype)
        if len(text) > 1024:
            call(token, "sendMessage", json.dumps({"chat_id": chat, "text": text[:4096]}).encode(), "application/json")
    print("Posted.")


if __name__ == "__main__":
    main()
