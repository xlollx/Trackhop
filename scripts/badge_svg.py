#!/usr/bin/env python3
"""Draws the README's badges as plain SVG files, in shields.io's flat style, so the README no longer
depends on img.shields.io, which answers GitHub's image proxy with timeouts now and then.

    badge_svg.py DIR    every shields "endpoint" JSON in DIR (schemaVersion 1) gets an SVG next to it,
                        plus license.svg; raw.githubusercontent.com serves them as image/svg+xml.
"""
import json
import sys
from pathlib import Path
from xml.sax.saxutils import escape

# Labels the README used to set in the shields URL.
LABELS = {"virustotal-app": "app on VirusTotal", "virustotal-desktop": "Desktop on VirusTotal"}
COLORS = {"brightgreen": "#4c1", "green": "#97ca00", "yellow": "#dfb317", "orange": "#fe7d37",
          "red": "#e05d44", "blue": "#007ec6", "lightgrey": "#9f9f9f", "grey": "#555"}

# Advance widths of Verdana 11px (what shields measures with), in px; others count as 7.
_W = {**{c: 7.0 for c in "ABCDEFGHKNOPRSTUVXYZ"}, **{c: 7.6 for c in "MOQ"}, "W": 10.4, "I": 4.2, "J": 5.0,
      "L": 6.1, "a": 6.6, "b": 6.8, "c": 5.7, "d": 6.8, "e": 6.6, "f": 3.8, "g": 6.8, "h": 6.9,
      "i": 3.0, "j": 3.8, "k": 6.4, "l": 3.0, "m": 10.6, "n": 6.9, "o": 6.6, "p": 6.8, "q": 6.8,
      "r": 4.7, "s": 5.7, "t": 4.3, "u": 6.9, "v": 6.4, "w": 8.9, "x": 6.4, "y": 6.4, "z": 5.7,
      " ": 3.9, ".": 3.9, ",": 3.9, "-": 4.6, "+": 8.7, ":": 4.6, "(": 4.5, ")": 4.5, "/": 4.6,
      **{c: 7.0 for c in "0123456789"}}


def width(text):
    return sum(_W.get(c, 7.0) for c in text)


def color(value):
    value = (value or "lightgrey").strip()
    if value in COLORS:
        return COLORS[value]
    return value if value.startswith("#") else "#" + value


def svg(label, message, fill):
    lw, mw = round(width(label)) + 10, round(width(message)) + 10
    total = lw + mw
    label, message = escape(label), escape(message)
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{total}" height="20" role="img" aria-label="{label}: {message}">'
        f"<title>{label}: {message}</title>"
        '<linearGradient id="s" x2="0" y2="100%"><stop offset="0" stop-color="#bbb" stop-opacity=".1"/>'
        '<stop offset="1" stop-opacity=".1"/></linearGradient>'
        f'<clipPath id="r"><rect width="{total}" height="20" rx="3" fill="#fff"/></clipPath>'
        f'<g clip-path="url(#r)"><rect width="{lw}" height="20" fill="#555"/>'
        f'<rect x="{lw}" width="{mw}" height="20" fill="{fill}"/><rect width="{total}" height="20" fill="url(#s)"/></g>'
        '<g fill="#fff" text-anchor="middle" font-family="Verdana,Geneva,DejaVu Sans,sans-serif" font-size="11">'
        f'<text x="{lw / 2}" y="15" fill="#010101" fill-opacity=".3">{label}</text>'
        f'<text x="{lw / 2}" y="14">{label}</text>'
        f'<text x="{lw + mw / 2}" y="15" fill="#010101" fill-opacity=".3">{message}</text>'
        f'<text x="{lw + mw / 2}" y="14">{message}</text></g></svg>\n'
    )


def main(folder):
    folder = Path(folder)
    made = []
    for f in sorted(folder.glob("*.json")):
        try:
            data = json.loads(f.read_text())
        except ValueError:
            continue
        if not isinstance(data, dict) or data.get("schemaVersion") != 1:
            continue
        label = LABELS.get(f.stem, data.get("label", ""))
        f.with_suffix(".svg").write_text(svg(label, str(data.get("message", "")), color(data.get("color"))))
        made.append(f.stem)
    (folder / "license.svg").write_text(svg("license", "AGPL-3.0-only", COLORS["blue"]))
    print("badges:", ", ".join(made + ["license"]))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else ".")
