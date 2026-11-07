#!/usr/bin/env python3
"""Keeps Trackhop's Wikidata item (Q142072916) up to date: the released version (P348, preferred,
with its date and "stable version"), and a few facts that should always be there. Idempotent: an
item that is already right is not edited. Logs in with a bot password (WIKIDATA_USER,
WIKIDATA_PASSWORD), never printed.

    scripts/wikidata_update.py VERSION [DATE]      # DATE: YYYY-MM-DD, today (UTC) by default
"""
import datetime, json, os, sys, urllib.parse, urllib.request, http.cookiejar

API = "https://www.wikidata.org/w/api.php"
ITEM = "Q142072916"
UA = "TrackhopRelease/1.0 (https://github.com/xlollx/Trackhop)"
STABLE, DOCKER = "Q2804309", "Q15206305"

# Facts the item should always carry; each one is added only when missing.
ITEM_FACTS = [
    ("P400", DOCKER), ("P400", "Q94"),        # platforms: Docker, Android
    ("P750", "Q1386210"), ("P750", "Q135288"),  # distributed by F-Droid, Microsoft Store
    ("P479", "Q165970"),                      # input device: touchscreen
    ("P437", "Q54820071"),                    # distribution format: digital download
    ("P6216", "Q50423863"),                   # copyright status: copyrighted (AGPL is a copyright licence)
] + [("P407", q) for q in (  # the app's languages
    "Q1860", "Q188", "Q1321", "Q150", "Q652", "Q5287", "Q9176", "Q809", "Q750553", "Q7737", "Q256", "Q13414913")]
STRING_FACTS = [
    ("P2003", "trackhop.app"),                                # Instagram
    ("P2078", "https://trackhop.org/guide/"),                 # user manual
    ("P1401", "https://github.com/xlollx/Trackhop/issues"),   # issue tracker
    ("P5885", "9pfx74q2xn9p"),                                # Microsoft Store
    ("P4435", "trackhop"),                                    # Snap Store
    ("P10766", "trackhop"),                                   # Chocolatey
    ("P8778", "xlollx/trackhop"),                             # Docker Hub
    ("P9618", "songport"),                                    # AlternativeTo (page still under the old name)
]
COMMONS = "https://commons.wikimedia.org/w/api.php"
LOGO_FILE = "Trackhop logo.svg"
LOGO_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "store", "logo.svg")
LOGO_PAGE = """=={{int:filedesc}}==
{{Information
|description={{en|1=Logo of Trackhop, a free and open-source app that keeps music playlists in sync across streaming services.}}{{it|1=Logo di Trackhop, app libera e open source che tiene sincronizzate le playlist tra servizi musicali.}}
|date=2026
|source={{own}}: the app icon of https://github.com/xlollx/Trackhop (docs/store/logo.svg)
|author=xlollx
|other fields={{Information field|name=Wikidata|value=[[d:Q142072916|Q142072916]]}}
}}
=={{int:license-header}}==
{{self|cc-by-sa-4.0}}
{{Trademarked}}

[[Category:Logos of free software]]
[[Category:SVG logos]]"""

# Qualifiers some statements should carry: (property, value of the statement, qualifier, item).
QUALIFIERS = [
    ("P1324", "https://github.com/xlollx/Trackhop", "P8423", "Q186055"),   # version control: Git
    ("P1324", "https://github.com/xlollx/Trackhop", "P10627", "Q364"),     # web interface: GitHub
    ("P5885", "9pfx74q2xn9p", "P400", "Q1406"),                           # Microsoft Store: Windows
    ("P856", "https://trackhop.org", "P407", "Q20923490"),                 # website: multiple languages
]
DESCRIPTIONS = {
    "en": "free and open-source app that keeps music playlists in sync across streaming services",
    "it": "app libera e open source che tiene sincronizzate le playlist tra servizi musicali",
    "de": "freie Open-Source-App, die Musik-Playlists zwischen Streamingdiensten synchron hält",
    "es": "aplicación libre y de código abierto que mantiene sincronizadas las listas de reproducción entre servicios de música en streaming",
    "fr": "application libre et open source qui garde les playlists musicales synchronisées entre services de streaming",
    "pt": "aplicação livre e de código aberto que mantém as listas de reprodução sincronizadas entre serviços de streaming de música",
    "pt-br": "aplicativo livre e de código aberto que mantém playlists de música sincronizadas entre serviços de streaming",
    "pl": "wolna aplikacja o otwartym kodzie źródłowym, która synchronizuje playlisty muzyczne między serwisami streamingowymi",
    "ru": "свободное приложение с открытым исходным кодом для синхронизации музыкальных плейлистов между стриминговыми сервисами",
    "tr": "müzik çalma listelerini akış hizmetleri arasında eşitleyen özgür ve açık kaynaklı uygulama",
    "ja": "音楽ストリーミングサービス間でプレイリストを同期する、自由でオープンソースのアプリ",
    "ko": "음악 스트리밍 서비스 간에 재생 목록을 동기화하는 자유 오픈 소스 앱",
    "zh": "在各音乐流媒体服务之间同步播放列表的自由开源应用",
    "zh-hans": "在各音乐流媒体服务之间同步播放列表的自由开源应用",
}

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def api(params, post=False, url=API, upload=None):
    params = dict(params, format="json")
    headers = {"User-Agent": UA}
    if upload:  # (name, bytes, type): multipart, as action=upload wants the file itself
        boundary = "trackhop" + os.urandom(12).hex()
        parts = [f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode() for k, v in params.items()]
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{upload[0]}"\r\n'
                     f"Content-Type: {upload[2]}\r\n\r\n".encode() + upload[1] + b"\r\n")
        body = b"".join(parts) + f"--{boundary}--\r\n".encode()
        headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        req = urllib.request.Request(url, data=body, headers=headers)
    else:
        data = urllib.parse.urlencode(params).encode()
        req = urllib.request.Request(url if post else url + "?" + data.decode(), data=data if post else None, headers=headers)
    with opener.open(req, timeout=60) as r:
        out = json.load(r)
    if "error" in out:
        raise SystemExit("Wikidata: " + out["error"].get("code", "") + ": " + out["error"].get("info", ""))
    return out


def login(user, password, url=API):
    token = api({"action": "query", "meta": "tokens", "type": "login"}, url=url)["query"]["tokens"]["logintoken"]
    result = api({"action": "login", "lgname": user, "lgpassword": password, "lgtoken": token}, post=True, url=url)["login"]
    if result.get("result") != "Success":
        raise SystemExit("Wikidata login refused: " + result.get("reason", result.get("result", "")))
    return api({"action": "query", "meta": "userinfo", "uiprop": "rights"}, url=url)["query"]["userinfo"].get("rights", [])


def ensure_logo(user, password):
    """True when the logo is on Commons, uploading it first if needed (CC BY-SA 4.0, as in the README)."""
    pages = api({"action": "query", "titles": "File:" + LOGO_FILE}, url=COMMONS)["query"]["pages"]
    if not any("missing" in p for p in pages.values()):
        return True
    try:
        if "upload" not in login(user, password, COMMONS):
            print("Commons: the bot password cannot upload (tick 'Upload new files' on Special:BotPasswords); logo skipped.")
            return False
        csrf = api({"action": "query", "meta": "tokens"}, url=COMMONS)["query"]["tokens"]["csrftoken"]
        with open(LOGO_PATH, "rb") as f:
            svg = f.read()
        out = api({"action": "upload", "filename": LOGO_FILE, "text": LOGO_PAGE, "comment": "Logo of Trackhop",
                   "token": csrf}, url=COMMONS, upload=(LOGO_FILE, svg, "image/svg+xml"))["upload"]
    except SystemExit as e:  # an upload problem never stops the Wikidata edit
        print("Commons: " + str(e))
        return False
    if out.get("result") != "Success":
        print("Commons: logo not uploaded: " + json.dumps(out.get("warnings", out)))
        return False
    print("Commons: uploaded File:" + LOGO_FILE)
    return True


def item_snak(prop, qid):
    return {"snaktype": "value", "property": prop, "datavalue": {"type": "wikibase-entityid", "value": {"entity-type": "item", "id": qid}}}


def string_snak(prop, value):
    return {"snaktype": "value", "property": prop, "datavalue": {"type": "string", "value": value}}


def release_reference(version):
    url = "https://github.com/xlollx/Trackhop/releases/tag/v" + version
    return {"snaks": {"P854": [string_snak("P854", url)]}}


def time_snak(prop, day):
    return {"snaktype": "value", "property": prop, "datavalue": {"type": "time", "value": {
        "time": "+" + day + "T00:00:00Z", "timezone": 0, "before": 0, "after": 0, "precision": 11,
        "calendarmodel": "http://www.wikidata.org/entity/Q1985727"}}}


def main():
    version = sys.argv[1].lstrip("v")
    day = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] else datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")
    user, password = os.environ.get("WIKIDATA_USER"), os.environ.get("WIKIDATA_PASSWORD")
    if not user or not password:
        print("No WIKIDATA_USER / WIKIDATA_PASSWORD: nothing to do.")
        return
    if "edit" not in login(user, password):
        raise SystemExit("Wikidata: signed in, but the bot password cannot edit. On Special:BotPasswords, "
                         "tick 'Edit existing pages' and 'Create, edit, and move pages', then save.")
    csrf = api({"action": "query", "meta": "tokens"})["query"]["tokens"]["csrftoken"]
    entity = api({"action": "wbgetentities", "ids": ITEM})["entities"][ITEM]
    claims = entity.get("claims", {})
    data, done = {"claims": []}, []

    # The version: one statement per release, the newest preferred, the others normal.
    versions = claims.get("P348", [])
    current = [c for c in versions if c["mainsnak"].get("datavalue", {}).get("value") == version]
    changed = {}  # statement id -> statement, so a statement touched twice is sent once
    for c in versions:
        want = "preferred" if c in current else "normal"
        if c.get("rank") != want:
            c["rank"] = want
            changed[c["id"]] = c
        if c in current and not c.get("references"):
            c["references"] = [release_reference(version)]
            changed[c["id"]] = c
    if not current:
        data["claims"].append({
            "mainsnak": string_snak("P348", version), "type": "statement", "rank": "preferred",
            "qualifiers": {"P577": [time_snak("P577", day)], "P548": [item_snak("P548", STABLE)]},
            "references": [release_reference(version)],
        })
        done.append(f"version {version} ({day})")

    # Facts that should be there; each added only when missing.
    os_claims = claims.get("P306", [])
    for c in os_claims:
        if c["mainsnak"].get("datavalue", {}).get("value", {}).get("id") == DOCKER:
            data["claims"].append({"id": c["id"], "remove": ""})
            done.append("Docker moved from operating system to platform")
    def has(prop, value):
        for c in claims.get(prop, []):
            v = c["mainsnak"].get("datavalue", {}).get("value")
            if v == value or (isinstance(v, dict) and v.get("id") == value):
                return True
        return False

    for prop, qid in ITEM_FACTS:
        if not has(prop, qid):
            data["claims"].append({"mainsnak": item_snak(prop, qid), "type": "statement", "rank": "normal"})
            done.append(prop)
    for prop, value in STRING_FACTS:
        if not has(prop, value):
            data["claims"].append({"mainsnak": string_snak(prop, value), "type": "statement", "rank": "normal"})
            done.append(prop)

    for prop, value, qprop, qid in QUALIFIERS:
        for c in claims.get(prop, []):
            v = c["mainsnak"].get("datavalue", {}).get("value")
            if v not in (value, value + "/"):
                continue
            if any(q.get("datavalue", {}).get("value", {}).get("id") == qid for q in c.get("qualifiers", {}).get(qprop, [])):
                continue
            c = changed.get(c["id"], c)
            c.setdefault("qualifiers", {}).setdefault(qprop, []).append(item_snak(qprop, qid))
            c.pop("qualifiers-order", None)
            changed[c["id"]] = c
            done.append(prop + " " + qprop)
    data["claims"].extend(changed.values())

    # The logo, from Commons.
    if not claims.get("P154") and ensure_logo(user, password):
        data["claims"].append({"mainsnak": {"snaktype": "value", "property": "P154", "datavalue": {"type": "string", "value": LOGO_FILE}},
                               "type": "statement", "rank": "normal"})
        done.append("logo")

    # The name is the same everywhere ("mul"); descriptions are added for missing languages only,
    # so a hand-made edit on the item is never overwritten.
    if "mul" not in entity.get("labels", {}):
        data["labels"] = {"mul": {"language": "mul", "value": "Trackhop"}}
        done.append("label")
    if not any(a["value"] == "Songport" for a in entity.get("aliases", {}).get("mul", [])):
        data["aliases"] = {"mul": [{"language": "mul", "value": "Songport", "add": ""}]}
        done.append("alias")
    missing = {lang: {"language": lang, "value": text} for lang, text in DESCRIPTIONS.items()
               if lang not in entity.get("descriptions", {})}
    if missing:
        data["descriptions"] = missing
        done.append("descriptions " + ", ".join(sorted(missing)))

    if not data["claims"]:
        del data["claims"]
    if len(data) == 0:
        print(f"{ITEM} is already up to date ({version}).")
        return
    summary = "Trackhop " + version + ": " + (", ".join(dict.fromkeys(done)) if done else "newest version preferred")
    api({"action": "wbeditentity", "id": ITEM, "data": json.dumps(data), "token": csrf, "summary": summary,
         "baserevid": entity["lastrevid"]}, post=True)
    print(f"{ITEM} updated: {summary}")


if __name__ == "__main__":
    main()
