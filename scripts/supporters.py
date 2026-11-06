#!/usr/bin/env python3
"""Adds supporters' names to docs/_data/supporters.yml (the site's "Thank you") and the README's list.

    supporters.py "Name" ...            names given (a Ko-fi supporter, by the Supporters workflow)
    supporters.py --sponsors FILE       public GitHub Sponsors from a GraphQL answer (FILE, or - for stdin)

Names only, as the person shows them: never amounts. A name already listed (any case) is left as it is.
Prints the names it added, one per line.
"""
import html
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FILE = ROOT / "docs" / "_data" / "supporters.yml"
README = ROOT / "README.md"
MAX = 40

# A supporter who gave no name (Ko-fi shows "Somebody" or "Someone") still gets a thank-you, under a
# nickname of their own: the next one of these not yet used, then the list again with a number.
ANONYMOUS = ("someone", "somebody", "anonymous", "supporter")
NICKNAMES = [
    "Anonymous Mixtape", "Anonymous Encore", "Anonymous B-side", "Anonymous Bassline",
    "Anonymous Night Owl", "Anonymous Vinyl", "Anonymous Chorus", "Anonymous Remix",
    "Anonymous Groove", "Anonymous Headphones", "Anonymous Bridge", "Anonymous Hidden Track",
]


def nickname(have):
    for n in range(1, 1000):
        for base in NICKNAMES:
            name = base if n == 1 else f"{base} {n}"
            if name.casefold() not in have:
                return name
    return None


def clean(name):
    name = re.sub(r"[\x00-\x1f\x7f]", "", str(name or "")).strip()
    return name[:MAX].strip()


def listed(text):
    return {m.group(1).casefold() for m in re.finditer(r'^- name: "((?:[^"\\]|\\.)*)"', text, re.M)}


def add(names):
    text = FILE.read_text(encoding="utf-8")
    have = {json.loads('"' + n + '"').casefold() for n in listed(text)}
    added = []
    for raw in names:
        name = clean(raw)
        if name.casefold() in ANONYMOUS:
            name = nickname(have)
        if not name or name.casefold() in have:
            continue
        have.add(name.casefold())
        added.append(name)
    if added:
        if not text.endswith("\n"):
            text += "\n"
        text += "".join(f"- name: {json.dumps(n, ensure_ascii=False)}\n" for n in added)
        FILE.write_text(text, encoding="utf-8")
    readme(text)
    return added


def readme(text):
    """The README's thank-you line, between its supporters markers."""
    names = [json.loads('"' + n + '"') for n in re.findall(r'^- name: "((?:[^"\\]|\\.)*)"', text, re.M)]
    page = README.read_text(encoding="utf-8")
    line = " · ".join(html.escape(n, quote=False) for n in names)
    new = re.sub(r"(<!-- supporters:start -->\n).*?(\n<!-- supporters:end -->)",
                 lambda m: m.group(1) + line + m.group(2), page, count=1, flags=re.S)
    if new != page:
        README.write_text(new, encoding="utf-8")


def sponsors(answer):
    data = answer.get("data") or {}
    nodes = ((data.get("user") or data.get("viewer") or {}).get("sponsorshipsAsMaintainer") or {}).get("nodes") or []
    for n in nodes:
        if n.get("privacyLevel") != "PUBLIC":
            continue
        who = n.get("sponsorEntity") or {}
        yield who.get("name") or who.get("login")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args[:1] == ["--sponsors"]:
        src = sys.stdin if len(args) < 2 or args[1] == "-" else open(args[1], encoding="utf-8")
        names = list(sponsors(json.load(src)))
    else:
        names = args
    for n in add(names):
        print(n)
