#!/usr/bin/env python3
"""VirusTotal checks of a release's files (.github/workflows/virustotal.yml).

  virustotal.py scan --tag TAG --out DIR FILE...   look each file up by its SHA-256 (uploading the
                                                   ones VirusTotal has not seen), wait for the
                                                   results, write DIR/report.json, DIR/badge.json
                                                   (a shields.io endpoint) and DIR/notes.md
  virustotal.py notes BODY SECTION                 the release notes BODY with SECTION in place of
                                                   the previous one (or added at the end), to stdout

The free API allows 4 requests a minute: every request waits its turn. Only the standard library.
The API key comes from VT_API_KEY.
"""
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

API = "https://www.virustotal.com/api/v3"
GUI = "https://www.virustotal.com/gui/file/"
# Uploads bigger than this go through a one-time upload address (the API's rule).
DIRECT_LIMIT = 32 * 1024 * 1024
GAP = 16.0  # seconds between requests: 4 a minute
WAIT_LIMIT = 45 * 60  # longest wait for the analyses, in seconds
BEGIN, END = "<!-- virustotal -->", "<!-- /virustotal -->"

_last = 0.0


def _request(method, url, body=None, headers=None, key=None, tries=6):
    global _last
    for attempt in range(tries):
        wait = _last + GAP - time.time()
        if wait > 0:
            time.sleep(wait)
        _last = time.time()
        req = urllib.request.Request(url, data=body, method=method, headers=dict(headers or {}))
        req.add_header("x-apikey", key)
        req.add_header("accept", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return 404, {}
            if e.code in (429, 500, 502, 503, 504) and attempt < tries - 1:
                time.sleep(60)
                continue
            raise SystemExit(f"VirusTotal answered {e.code} to {method} {url}: {e.read()[:300]!r}")
        except (urllib.error.URLError, TimeoutError) as e:
            if attempt < tries - 1:
                time.sleep(30)
                continue
            raise SystemExit(f"VirusTotal unreachable: {e}")


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _upload(path, key):
    size = os.path.getsize(path)
    url = API + "/files"
    if size > DIRECT_LIMIT:
        _, answer = _request("GET", API + "/files/upload_url", key=key)
        url = answer["data"]
    boundary = uuid.uuid4().hex
    with open(path, "rb") as f:
        content = f.read()
    name = os.path.basename(path)
    body = (
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{name}\"\r\n"
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode() + content + f"\r\n--{boundary}--\r\n".encode()
    _, answer = _request("POST", url, body, {"Content-Type": f"multipart/form-data; boundary={boundary}"}, key)
    return answer["data"]["id"]


def _counts(stats):
    flagged = int(stats.get("malicious", 0))
    suspicious = int(stats.get("suspicious", 0))
    engines = flagged + suspicious + int(stats.get("undetected", 0)) + int(stats.get("harmless", 0))
    return {"malicious": flagged, "suspicious": suspicious, "engines": engines}


def _flagged_by(results):
    """Which engines flag a file, and as what: {engine: label}, for the log and report.json."""
    return {e: (r.get("result") or r.get("category")) for e, r in (results or {}).items()
            if r.get("category") in ("malicious", "suspicious")}


def scan(tag, out, files, key):
    product = "desktop" if tag.startswith("server-v") else "app"
    version = tag.removeprefix("server-v").removeprefix("v")
    results, pending, rechecked = {}, {}, set()
    for path in files:
        name, sha = os.path.basename(path), _sha256(path)
        status, known = _request("GET", f"{API}/files/{sha}", key=key)
        stats = known.get("data", {}).get("attributes", {}).get("last_analysis_stats") if status == 200 else None
        if stats and sum(stats.values()) > 0 and int(stats.get("malicious", 0)) > 0 and name not in rechecked:
            # Flagged when last seen: ask for a fresh analysis, the engines may have corrected
            # themselves since (a false positive reported to them), and wait for it.
            _, again = _request("POST", f"{API}/files/{sha}/analyse", key=key)
            analysis = again.get("data", {}).get("id")
            if analysis:
                rechecked.add(name)
                pending[name] = (sha, analysis)
                print(f"{name}: flagged when last seen, analysed again")
                continue
        if stats and sum(stats.values()) > 0:
            by = _flagged_by(known["data"]["attributes"].get("last_analysis_results"))
            results[name] = {"name": name, "sha256": sha, **_counts(stats), "flagged_by": by}
            print(f"{name}: known to VirusTotal" + (f", flagged by {by}" if by else ""))
        else:
            pending[name] = (sha, _upload(path, key))
            print(f"{name}: uploaded")
    started = time.time()
    while pending:
        if time.time() - started > WAIT_LIMIT:
            raise SystemExit("VirusTotal did not finish in time: " + ", ".join(pending))
        for name, (sha, analysis) in list(pending.items()):
            _, answer = _request("GET", f"{API}/analyses/{analysis}", key=key)
            attrs = answer.get("data", {}).get("attributes", {})
            if attrs.get("status") == "completed":
                by = _flagged_by(attrs.get("results"))
                results[name] = {"name": name, "sha256": sha, **_counts(attrs.get("stats", {})), "flagged_by": by}
                del pending[name]
                print(f"{name}: analysed" + (f", flagged by {by}" if by else ""))
        if pending:
            time.sleep(30)
    rows = [dict(results[os.path.basename(p)], link=GUI + results[os.path.basename(p)]["sha256"]) for p in files]
    flagged = [r for r in rows if r["malicious"] > 0]
    os.makedirs(out, exist_ok=True)
    report = {
        "product": product, "version": version, "tag": tag,
        "release": f"https://github.com/xlollx/Trackhop/releases/tag/{tag}",
        "checked": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "clean": not flagged, "files": rows,
    }
    with open(os.path.join(out, "report.json"), "w") as f:
        json.dump(report, f, indent=1)
    # No counts on the badge: "clean" when no engine flags any file, otherwise "checked" (the
    # report says which engine and why; a lone generic hit on an unsigned installer is common).
    badge = {"schemaVersion": 1, "label": "VirusTotal", "message": "clean" if not flagged else "checked",
             "color": "brightgreen" if not flagged else "blue", "namedLogo": "virustotal"}
    with open(os.path.join(out, "badge.json"), "w") as f:
        json.dump(badge, f)
    lines = [BEGIN, "", "**VirusTotal**: every file of this release, checked by the antivirus engines VirusTotal runs.", "",
             "| File | Result | SHA-256 |", "|---|---|---|"]
    for r in rows:
        result = "clean" if r["malicious"] == 0 and r["suspicious"] == 0 else (
            f"flagged by {r['malicious'] + r['suspicious']} of {r['engines']} engines" if r["engines"] else "checked")
        lines.append(f"| {r['name']} | [{result}]({r['link']}) | `{r['sha256']}` |")
    lines += ["", END]
    with open(os.path.join(out, "notes.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    with open(os.environ.get("GITHUB_OUTPUT", os.devnull), "a") as f:
        f.write(f"product={product}\nflagged={'true' if flagged else 'false'}\n")
    for r in flagged:
        print(f"::warning::{r['name']} is flagged by {r['malicious']} engines: {r['link']}")


def notes(body_path, section_path):
    body = open(body_path).read().rstrip()
    section = open(section_path).read().strip()
    if BEGIN in body and END in body:
        body = body[: body.index(BEGIN)].rstrip() + "\n\n" + section + body[body.index(END) + len(END):]
    else:
        body = body + "\n\n" + section
    sys.stdout.write(body.rstrip() + "\n")


def main(argv):
    if len(argv) >= 2 and argv[1] == "notes" and len(argv) == 4:
        return notes(argv[2], argv[3])
    if len(argv) >= 6 and argv[1] == "scan" and argv[2] == "--tag" and argv[4] == "--out":
        key = os.environ.get("VT_API_KEY", "").strip()
        if not key:
            raise SystemExit("VT_API_KEY is not set")
        return scan(argv[3], argv[5], argv[6:], key)
    raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv)
