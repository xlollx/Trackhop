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
ITEM_FACTS = [("P400", DOCKER)] + [("P407", q) for q in (  # the app's languages
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


def api(params, post=False):
    params = dict(params, format="json")
    data = urllib.parse.urlencode(params).encode()
    req = urllib.request.Request(API if post else API + "?" + data.decode(), data=data if post else None, headers={"User-Agent": UA})
    with opener.open(req, timeout=60) as r:
        out = json.load(r)
    if "error" in out:
        raise SystemExit("Wikidata: " + out["error"].get("code", "") + ": " + out["error"].get("info", ""))
    return out


def item_snak(prop, qid):
    return {"snaktype": "value", "property": prop, "datavalue": {"type": "wikibase-entityid", "value": {"entity-type": "item", "id": qid}}}


def string_snak(prop, value):
    return {"snaktype": "value", "property": prop, "datavalue": {"type": "string", "value": value}}


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
    token = api({"action": "query", "meta": "tokens", "type": "login"})["query"]["tokens"]["logintoken"]
    login = api({"action": "login", "lgname": user, "lgpassword": password, "lgtoken": token}, post=True)["login"]
    if login.get("result") != "Success":
        raise SystemExit("Wikidata login refused: " + login.get("reason", login.get("result", "")))
    rights = api({"action": "query", "meta": "userinfo", "uiprop": "rights"})["query"]["userinfo"].get("rights", [])
    if "edit" not in rights:
        raise SystemExit("Wikidata: signed in, but the bot password cannot edit. On Special:BotPasswords, "
                         "tick 'Edit existing pages' and 'Create, edit, and move pages', then save.")
    csrf = api({"action": "query", "meta": "tokens"})["query"]["tokens"]["csrftoken"]
    entity = api({"action": "wbgetentities", "ids": ITEM})["entities"][ITEM]
    claims = entity.get("claims", {})
    data, done = {"claims": []}, []

    # The version: one statement per release, the newest preferred, the others normal.
    versions = claims.get("P348", [])
    current = [c for c in versions if c["mainsnak"].get("datavalue", {}).get("value") == version]
    for c in versions:
        want = "preferred" if c in current else "normal"
        if c.get("rank") != want:
            c["rank"] = want
            data["claims"].append(c)
    if not current:
        data["claims"].append({
            "mainsnak": string_snak("P348", version), "type": "statement", "rank": "preferred",
            "qualifiers": {"P577": [time_snak("P577", day)], "P548": [item_snak("P548", STABLE)]},
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
