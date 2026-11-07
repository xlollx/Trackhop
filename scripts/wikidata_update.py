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
    if not any(c["mainsnak"].get("datavalue", {}).get("value", {}).get("id") == DOCKER for c in claims.get("P400", [])):
        data["claims"].append({"mainsnak": item_snak("P400", DOCKER), "type": "statement", "rank": "normal"})
        if "Docker moved from operating system to platform" not in done:
            done.append("platform Docker")
    if not claims.get("P2003"):
        data["claims"].append({"mainsnak": string_snak("P2003", "trackhop.app"), "type": "statement", "rank": "normal"})
        done.append("Instagram username")
    if "it" not in entity.get("labels", {}):
        data["labels"] = {"it": {"language": "it", "value": "Trackhop"}}
        done.append("Italian label")
    if "it" not in entity.get("descriptions", {}):
        data["descriptions"] = {"it": {"language": "it", "value": "app libera e open source che tiene sincronizzate le playlist tra servizi musicali"}}
        done.append("Italian description")
    if not any(a["value"] == "Songport" for a in entity.get("aliases", {}).get("it", [])):
        data["aliases"] = {"it": [{"language": "it", "value": "Songport", "add": ""}]}
        done.append("Italian alias")

    if not data["claims"]:
        del data["claims"]
    if len(data) == 0:
        print(f"{ITEM} is already up to date ({version}).")
        return
    summary = "Trackhop " + version + ": " + (", ".join(done) if done else "newest version preferred")
    api({"action": "wbeditentity", "id": ITEM, "data": json.dumps(data), "token": csrf, "summary": summary,
         "baserevid": entity["lastrevid"]}, post=True)
    print(f"{ITEM} updated: {summary}")


if __name__ == "__main__":
    main()
