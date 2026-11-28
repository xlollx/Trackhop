#!/usr/bin/env python3
"""Helper: simple project work done by a free model, so the maintainer's assistant spends fewer tokens.

Modes (env MODE):
  translate  Translates the given string keys of android/app/src/main/res/values/strings.xml into the
             other 11 app languages and writes them into each values-xx/strings.xml (added at the end,
             or replacing the existing entry). Placeholders and escaping are checked by code: a
             translation that loses a placeholder is retried once, then left out and reported.
  ask        Sends TASK (plus the files listed in FILES) and writes the answer to OUT_PATH.

Models: Google's Gemini (free key, secret GEMINI_API_KEY), then OpenCode Zen's free DeepSeek V4.1 Flash
(OPENCODE_API_KEY), then OpenRouter's free models (OPENROUTER_API_KEY). GitHub Models was retired on 30/07/2026. Nothing here reads user data or secrets other than the model keys.
"""
import json, os, re, sys, time, urllib.request, urllib.error, pathlib

ROOT = pathlib.Path(os.environ.get("GITHUB_WORKSPACE", "."))
RES = ROOT / "android/app/src/main/res"
LANGS = {
    "it": "Italian", "es": "Spanish", "pt-rBR": "Brazilian Portuguese", "fr": "French (informal: tu)",
    "de": "German (informal: du)", "pl": "Polish", "ru": "Russian", "tr": "Turkish (polite: siz)",
    "ja": "Japanese", "ko": "Korean", "zh": "Simplified Chinese",
}
SUMMARY = []


def log(msg):
    print(msg, flush=True)
    SUMMARY.append(msg)


def post(url, headers, body, timeout=180):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json", "User-Agent": "trackhop-helper/1.0 (+https://github.com/xlollx/Trackhop)", **headers})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def chat(system, user, want_json=False):
    """One answer from the first free model that replies."""
    model = os.environ.get("MODEL", "").strip()
    tries = []
    # Google's Gemini API (free key from AI Studio, secret GEMINI_API_KEY), OpenAI-compatible endpoint.
    gem = os.environ.get("GEMINI_API_KEY")
    if gem:
        for m in ([model.split("/", 1)[1]] if model.startswith("gemini/") else []) + ["gemini-3.8-flash", "gemini-flash-latest", "gemini-flash-lite-latest"]:
            tries.append(("gemini", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", {"Authorization": "Bearer " + gem}, m))
    oc = os.environ.get("OPENCODE_API_KEY")
    if oc:
        oc_models = ([model.split("/", 1)[1]] if model.startswith("opencode/") else []) + ["deepseek-v4.1-flash-free", "glm-5.3-flash-free"]
        for m in oc_models:
            tries.append(("opencode", "https://opencode.ai/zen/v1/chat/completions", {"Authorization": "Bearer " + oc}, m))
    orkey = os.environ.get("OPENROUTER_API_KEY")
    if orkey:
        for m in ([model[len("openrouter/"):]] if model.startswith("openrouter/") and model.count("/") > 1 else []) + ["openrouter/free", "qwen/qwen3-coder:free"]:
            tries.append(("openrouter", "https://openrouter.ai/api/v1/chat/completions", {"Authorization": "Bearer " + orkey, "X-Title": "Trackhop helper"}, m))
    last = None
    for name, url, headers, m in tries:
        body = {"model": m, "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}], "temperature": 0.2}
        for attempt in range(2):
            try:
                d = post(url, headers, body)
                text = d["choices"][0]["message"]["content"] or ""
                if text.strip():
                    return text, f"{name}:{m}"
                last = "empty answer"
                break
            except urllib.error.HTTPError as e:
                last = f"{name}:{m} HTTP {e.code} {e.read()[:200]!r}"
                if e.code in (429, 503) and attempt == 0:
                    time.sleep(20)
                    continue
                break
            except Exception as e:  # network, bad JSON
                last = f"{name}:{m} {e}"
                break
        print("  model failed:", last, flush=True)
    raise SystemExit("No free model answered: " + str(last))


def first_json(text):
    """The first JSON object in a model's answer (models like to add words around it)."""
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
    start = text.find("{")
    while start != -1:
        depth, i, in_str, esc = 0, start, False, False
        while i < len(text):
            c = text[i]
            if in_str:
                esc = (c == "\\" and not esc)
                if c == '"' and not esc:
                    in_str = False
            elif c == '"':
                in_str = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(text[start:i + 1])
                    except Exception:
                        break
            i += 1
        start = text.find("{", start + 1)
    return None


# ---- strings.xml ------------------------------------------------------------------------------

def read_entries(path):
    """name -> (kind, raw xml of the whole element, value or list of items, both raw-escaped)."""
    s = path.read_text(encoding="utf-8")
    out = {}
    for m in re.finditer(r'(?s)<string name="([^"]+)"([^>]*)>(.*?)</string>', s):
        out[m.group(1)] = ("string", m.group(0), m.group(3), m.group(2))
    for m in re.finditer(r'(?s)<string-array name="([^"]+)"[^>]*>(.*?)</string-array>', s):
        items = re.findall(r"(?s)<item>(.*?)</item>", m.group(2))
        out[m.group(1)] = ("array", m.group(0), items, "")
    return out


def unescape(v):
    return v.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")


def escape(v, keep_tags):
    v = v.replace("&", "&amp;")
    if not keep_tags:
        v = v.replace("<", "&lt;")
    else:
        v = re.sub(r"<(?!/?(?:b|i|u|a|br)\b)", "&lt;", v)
    return v.replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")


PH = re.compile(r"%(?:\d+\$)?[sdf]|%%|\{[a-z_]+\}")


def same_placeholders(a, b):
    return sorted(PH.findall(a)) == sorted(PH.findall(b))


def translate():
    keys = [k.strip() for k in re.split(r"[\s,]+", os.environ.get("KEYS", "")) if k.strip()]
    langs = [l.strip() for l in re.split(r"[\s,]+", os.environ.get("LANGS", "")) if l.strip()] or list(LANGS)
    en = read_entries(RES / "values/strings.xml")
    missing = [k for k in keys if k not in en]
    if missing:
        log("Keys not in values/strings.xml: " + ", ".join(missing))
    keys = [k for k in keys if k in en]
    if not keys:
        raise SystemExit("Nothing to translate")
    source = {}
    for k in keys:
        kind, _, val, _ = en[k]
        source[k] = [unescape(x) for x in val] if kind == "array" else unescape(val)
    glossary = os.environ.get("GLOSSARY", "")
    for lang in langs:
        name = LANGS.get(lang)
        if not name:
            log(f"Unknown language {lang}, skipped")
            continue
        path = RES / f"values-{lang}/strings.xml"
        existing = read_entries(path)
        # A few strings already translated, as the style to follow.
        samples = {k: (unescape(en[k][2]), unescape(existing[k][2])) for k in ["setup_save", "onboarding_skip", "setup_field_required", "nav_back", "setup_copied", "onboarding_intro"] if k in en and k in existing and en[k][0] == "string"}
        system = (
            f"You translate the user interface of Trackhop, an Android app that keeps music playlists in sync between services, from English into {name}. "
            "Answer with ONE JSON object only, no words around it: the same keys, each value translated (a string, or a list with the same number of items when the source is a list). "
            "Rules: keep every placeholder exactly (%1$s, %2$d, %d, %s, {name}); keep HTML tags like <b>; never translate product and service names "
            "(Trackhop, Trackhop Bridge, Spotify, YouTube Music, Apple Music, Amazon Music, TIDAL, Deezer, Qobuz, SoundCloud, Navidrome, Jellyfin, Plex, Emby, Google, Google Play, AppGallery, GitHub, F-Droid, Last.fm, ListenBrainz, MusicBrainz, Lidarr); "
            "keep quoted button names of Google's or other services' pages in the wording those pages use in that language when you know it, else in English; "
            "short, plain and friendly, like the examples; same meaning, nothing added." + ("\nGlossary: " + glossary if glossary else "")
        )
        user = "Examples (English → existing translation):\n" + json.dumps(samples, ensure_ascii=False, indent=1) + "\n\nTranslate:\n" + json.dumps(source, ensure_ascii=False, indent=1)
        got, used = None, ""
        for attempt in range(2):
            text, used = chat(system, user, want_json=True)
            got = first_json(text)
            if isinstance(got, dict) and not any(k in got for k in keys) and len(got) == 1 and isinstance(next(iter(got.values())), dict):
                got = next(iter(got.values()))
            if isinstance(got, dict) and any(k in got for k in keys):
                break
            print(f"  {lang}: unusable answer from {used}: {text[:300]!r}", flush=True)
        if not isinstance(got, dict) or not any(k in got for k in keys):
            log(f"{lang}: no usable JSON from the model, skipped")
            continue
        s = path.read_text(encoding="utf-8")
        done, bad = [], []
        for k in keys:
            kind, _, val, attrs = en[k]
            t = got.get(k)
            keep_tags = "<b>" in "".join(val if kind == "array" else [val])
            if kind == "array":
                if not isinstance(t, list) or len(t) != len(val) or not all(isinstance(x, str) and same_placeholders(unescape(a), x) for a, x in zip(val, t)):
                    bad.append(k); continue
                body = "".join(f"        <item>{escape(x, keep_tags)}</item>\n" for x in t)
                elem = f'<string-array name="{k}">\n{body}    </string-array>'
            else:
                if not isinstance(t, str) or not t.strip() or not same_placeholders(unescape(val), t):
                    print(f"  {lang} {k}: rejected {t!r:.200}", flush=True)
                    bad.append(k); continue
                elem = f'<string name="{k}"{attrs}>{escape(t.strip(), keep_tags)}</string>'
            if k in existing:
                s = s.replace(existing[k][1], elem, 1)
            else:
                i = s.rindex("</resources>")
                s = s[:i] + "    " + elem + "\n" + s[i:]
            done.append(k)
        path.write_text(s, encoding="utf-8")
        log(f"{lang}: {len(done)} translated with {used}" + (f"; NOT done (placeholders or format): {', '.join(bad)}" if bad else ""))


def ask():
    task = os.environ.get("TASK", "")
    files = [f.strip() for f in re.split(r"[\n,]+", os.environ.get("FILES", "")) if f.strip()]
    parts = [task]
    for f in files:
        p = ROOT / f
        if p.is_file():
            parts.append(f"\n--- {f} ---\n" + p.read_text(encoding="utf-8", errors="replace")[:60000])
    text, used = chat("You help maintain Trackhop, an open-source playlist sync app. Do exactly the task; answer with the result only.", "\n".join(parts))
    out = ROOT / (os.environ.get("OUT_PATH") or "helper-out.md")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    log(f"Answer from {used} written to {out.relative_to(ROOT)} ({len(text)} chars)")


if __name__ == "__main__":
    mode = os.environ.get("MODE", "translate")
    try:
        translate() if mode == "translate" else ask()
    finally:
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as f:
                f.write("\n".join(SUMMARY) + "\n")
