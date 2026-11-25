#!/usr/bin/env python3
"""
The site's data made from what the repository already holds, so the pages never drift from it:

  services  docs/_data/services.yml -> the README's "Music services at a glance" table (between the
            <!-- services:start --> and <!-- services:end --> lines). trackhop.org/services reads the
            same file, so the page and the README say the same thing.
  releases  the release notes -> docs/_data/releases.json, read by trackhop.org/whats-new and its
            RSS feeds: the app's from fastlane/metadata/android/<language>/changelogs/<versionCode>.txt
            (the F-Droid notes, in every language), version and date from the v<version> tags;
            Trackhop Desktop and Docker's from server/CHANGELOG.md (English), dated by their
            server-v<version> tags. A version without a tag yet is left out until it has one.

  python3 scripts/site_data.py [services|releases|all] [--check]

--check changes nothing and exits 1 when a file is not what the sources give (CI).
Needs PyYAML (pip install pyyaml) and, for releases, the tags (git fetch --tags).
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
README = ROOT / "README.md"
SERVICES = ROOT / "docs/_data/services.yml"
RELEASES = ROOT / "docs/_data/releases.json"
START, END = "<!-- services:start -->", "<!-- services:end -->"
# The site's languages (docs/_data/home/<code>.yml) and the fastlane folder of each.
LANGS = {"en": "en-US", "it": "it-IT", "es": "es-ES", "pt": "pt-BR", "fr": "fr-FR", "de": "de-DE",
         "pl": "pl-PL", "ru": "ru-RU", "tr": "tr-TR", "ja": "ja-JP", "ko": "ko-KR", "zh": "zh-CN"}


# ------------------------------------------------------------------------------------- services

def text(value, lang="en"):
    """A field that is either one text for every language or a map by language (English if one is missing)."""
    if isinstance(value, dict):
        return value.get(lang) or value["en"]
    return value or ""


def mark(value, data, lang="en"):
    if value == "yes":
        return "✓"
    if value == "no":
        return "—"
    return text(data["marks"][value], lang)


def services_table(data):
    """The README's table, in English, from services.yml."""
    lines = ["| Service | Read | Write | Remove | How you connect | Where |", "|---|:-:|:-:|:-:|---|---|"]
    for r in data["rows"]:
        name = text(r["name"])
        cell = f"**{name}**" if name else ""
        if r.get("extra"):
            cell += f" ({text(r['extra'])})"
        cells = [cell, mark(r["read"], data), mark(r["write"], data), mark(r["remove"], data),
                 text(r["how"]), text(data["where"][r["where"]])]
        lines.append(("| " + " | ".join(cells) + " |").replace("|  |", "| |", 1))
    return "\n".join(lines)


def services(check):
    import yaml
    data = yaml.safe_load(SERVICES.read_text(encoding="utf-8"))
    for r in data["rows"]:
        for key in ("read", "write", "remove"):
            if r[key] not in ("yes", "no") and r[key] not in data["marks"]:
                sys.exit(f"services.yml: {text(r['name']) or '(continued row)'}: unknown {key} {r[key]!r}")
        if r["where"] not in data["where"] or r["group"] not in data["groups"]:
            sys.exit(f"services.yml: {text(r['name']) or '(continued row)'}: unknown where or group")
    readme = README.read_text(encoding="utf-8")
    try:
        a, b = readme.index(START), readme.index(END)
    except ValueError:
        sys.exit(f"README.md: the {START} and {END} lines are missing")
    new = readme[:a] + START + "\n" + services_table(data) + "\n" + readme[b:]
    return write(README, readme, new, check)


# ------------------------------------------------------------------------------------- releases

def git(*args):
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True).stdout


def tags():
    """{tag: (date YYYY-MM-DD, versionCode or None)} for the v* and server-v* tags."""
    out = {}
    for line in git("for-each-ref", "--format=%(refname:short) %(creatordate:short)", "refs/tags/v*", "refs/tags/server-v*").splitlines():
        tag, date = line.split()
        if not re.fullmatch(r"(server-)?v\d+\.\d+(\.\d+)?", tag):
            continue
        code = None
        if tag.startswith("v"):
            try:
                gradle = git("show", f"{tag}:android/app/build.gradle")
                code = int(re.search(r"versionCode\s+(\d+)", gradle).group(1))
            except (subprocess.CalledProcessError, AttributeError):
                continue
        out[tag] = (date, code)
    return out


def version_key(v):
    return tuple(int(x) for x in v.split("."))


def server_sections():
    """{version: markdown} from server/CHANGELOG.md."""
    out, version, lines = {}, None, []
    for line in (ROOT / "server/CHANGELOG.md").read_text(encoding="utf-8").splitlines():
        if line.startswith("## "):
            if version:
                out[version] = "\n".join(lines).strip()
            version, lines = line[3:].split()[0], []
        elif version:
            lines.append(line)
    if version:
        out[version] = "\n".join(lines).strip()
    return out


def releases_data():
    found = tags()
    entries = []
    # The app: the first tag of each versionCode names that version.
    by_code = {}
    for tag, (date, code) in sorted(found.items(), key=lambda kv: (kv[1][0], kv[0])):
        if code is not None and code not in by_code:
            by_code[code] = (tag, date)
    for code, (tag, date) in by_code.items():
        notes = {}
        for lang, folder in LANGS.items():
            f = ROOT / f"fastlane/metadata/android/{folder}/changelogs/{code}.txt"
            if f.exists():
                notes[lang] = f.read_text(encoding="utf-8").strip()
        if "en" not in notes:
            continue
        version = tag[1:]
        entries.append({"id": tag, "kind": "app", "version": version, "date": date, "notes": notes})
    sections = server_sections()
    for tag, (date, _) in found.items():
        if tag.startswith("server-v") and tag[8:] in sections and sections[tag[8:]]:
            entries.append({"id": tag, "kind": "server", "version": tag[8:], "date": date, "notes": {"en": sections[tag[8:]]}})
    entries.sort(key=lambda e: (e["date"], e["kind"] == "app", version_key(e["version"])), reverse=True)
    return entries


def releases(check):
    new = json.dumps(releases_data(), ensure_ascii=False, indent=1) + "\n"
    old = RELEASES.read_text(encoding="utf-8") if RELEASES.exists() else ""
    return write(RELEASES, old, new, check)


# ------------------------------------------------------------------------------------- common

def write(path, old, new, check):
    if old == new:
        return True
    if check:
        print(f"{path.relative_to(ROOT)} is out of date: run python3 scripts/site_data.py", file=sys.stderr)
        return False
    path.write_text(new, encoding="utf-8")
    print(f"{path.relative_to(ROOT)} updated")
    return True


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--check"]
    check = "--check" in sys.argv[1:]
    what = args[0] if args else "all"
    ok = True
    if what in ("services", "all"):
        ok = services(check) and ok
    if what in ("releases", "all"):
        ok = releases(check) and ok
    sys.exit(0 if ok else 1)
