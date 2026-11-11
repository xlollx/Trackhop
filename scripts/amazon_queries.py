#!/usr/bin/env python3
"""Prints an Amazon Music player GraphQL operation as the player sends it, and its persisted-query
hash, from the player's public JavaScript (no account, no cookies).

The player (the "dragonfly" bundle) uses Apollo's automatic persisted queries: the hash is the
SHA-256 of graphql-js print() of the operation's document after Apollo added __typename to every
selection set but the operation's, and after the player stripped the fields marked @localOnly.
(Checked against deletePlaylist, whose hash the connector already had.) Documents with @client,
@connection or other client directives are not handled: the script stops on them.

    python3 scripts/amazon_queries.py searchTracksResult            # downloads the current bundle
    python3 scripts/amazon_queries.py searchTracksResult dragonfly.js
    python3 scripts/amazon_queries.py --list [dragonfly.js]          # operation names in the bundle

The text goes in AmazonQueries.kt and the hash in AmazonClient.HASHES (see
docs/guide/amazon-music-capture.md).
"""
import gzip, hashlib, json, re, sys, urllib.request

SITE = "https://music.amazon.com/"
CDN = "https://d5fx445wy2wpk.cloudfront.net/release/web-platform/"


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        data = r.read()
    return (gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data).decode("utf-8")


def bundle():
    """main.js names its chunks; one of them names dragonfly.<hash>.js."""
    main = re.search(r'release/web-platform/(main\.[0-9a-f]+\.js)', fetch(SITE)).group(1)
    js = fetch(CDN + main)
    chunks = re.search(r'\{(\d+:"[0-9a-f]+"(?:,\d+:"[0-9a-f]+")*)\}\[e\]\+"\.js"', js).group(1)
    for n, h in re.findall(r'(\d+):"([0-9a-f]+)"', chunks):
        m = re.search(r'"(dragonfly\.[0-9a-f]+\.js)"', fetch(f"{CDN}{n}.{h}.js"))
        if m:
            return fetch(CDN + m.group(1))
    sys.exit("no dragonfly bundle named in the player's chunks")


def document(src, op):
    m = re.search(r'operation:"(?:query|mutation)",name:\{kind:"Name",value:"%s"\}' % re.escape(op), src)
    if not m:
        sys.exit(f"{op}: not in the bundle")
    start = max(src.rfind('{__meta__', 0, m.start()), src.rfind('{kind:"Document"', 0, m.start()))
    depth, i = 0, start
    while True:
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        if depth == 0:
            break
        i += 1
    js = re.sub(r'([{,])([A-Za-z_$][A-Za-z0-9_$]*):', r'\1"\2":', src[start:i + 1])
    doc = json.loads(js.replace(":!0", ":true").replace(":!1", ":false"))
    doc.pop("__meta__", None)
    return doc


# ---- graphql-js 16 print()

def wrap(a, s, b=""): return a + s + b if s else ""
def join(xs, sep=""): return sep.join(x for x in xs if x)
def indent(s): return wrap("  ", s.replace("\n", "\n  "))
def block(xs): return "{\n" + indent(join(xs, "\n")) + "\n}" if xs else ""


def value(v):
    k = v["kind"]
    if k == "Variable": return "$" + v["name"]["value"]
    if k == "StringValue":
        if v.get("block"): sys.exit("block strings are not handled")
        return json.dumps(v["value"], ensure_ascii=False)
    if k in ("IntValue", "FloatValue", "EnumValue"): return v["value"]
    if k == "BooleanValue": return "true" if v["value"] else "false"
    if k == "NullValue": return "null"
    if k == "ListValue": return "[" + join([value(x) for x in v["values"]], ", ") + "]"
    if k == "ObjectValue": return "{" + join([f["name"]["value"] + ": " + value(f["value"]) for f in v["fields"]], ", ") + "}"
    sys.exit("value " + k)


def typ(t):
    if t["kind"] == "NamedType": return t["name"]["value"]
    if t["kind"] == "ListType": return "[" + typ(t["type"]) + "]"
    return typ(t["type"]) + "!"


def arg(a): return a["name"]["value"] + ": " + value(a["value"])


def directives(n):
    out = []
    for d in n.get("directives") or []:
        if d["name"]["value"] not in ("include", "skip"):
            sys.exit("directive @" + d["name"]["value"] + " is not handled")
        out.append("@" + d["name"]["value"] + wrap("(", join([arg(a) for a in d.get("arguments") or []], ", "), ")"))
    return join(out, " ")


def selections(ss): return block([selection(x) for x in ss["selections"]]) if ss else ""


def selection(s):
    k = s["kind"]
    if k == "Field":
        prefix = wrap("", s["alias"]["value"] if s.get("alias") else "", ": ") + s["name"]["value"]
        args = [arg(a) for a in s.get("arguments") or []]
        line = prefix + wrap("(", join(args, ", "), ")")
        if len(line) > 80:
            line = prefix + wrap("(\n", indent(join(args, "\n")), "\n)")
        return join([line, directives(s), selections(s.get("selectionSet"))], " ")
    if k == "FragmentSpread": return "..." + s["name"]["value"] + wrap(" ", directives(s))
    on = wrap("on ", s["typeCondition"]["name"]["value"] if s.get("typeCondition") else "")
    return join(["...", on, directives(s), selections(s["selectionSet"])], " ")


def variable(v):
    default = wrap(" = ", value(v["defaultValue"]) if v.get("defaultValue") else "")
    return "$" + v["variable"]["name"]["value"] + ": " + typ(v["type"]) + default + wrap(" ", directives(v))


def definition(d):
    variables = wrap("(", join([variable(v) for v in d.get("variableDefinitions") or []], ", "), ")")
    if d["kind"] == "OperationDefinition":
        prefix = join([d["operation"], join([d["name"]["value"], variables]), directives(d)], " ")
        return ("" if prefix == "query" else prefix + " ") + selections(d["selectionSet"])
    return ("fragment " + d["name"]["value"] + variables + " on " + d["typeCondition"]["name"]["value"] + " "
            + wrap("", directives(d), " ") + selections(d["selectionSet"]))


# ---- the player's transforms

def local_only(node):
    """Fields marked @localOnly are left out (the player's stripLocalOnlyFieldsTransformer)."""
    if isinstance(node, list):
        return [local_only(x) for x in node if not (isinstance(x, dict) and x.get("kind") == "Field"
                and any(d["name"]["value"] == "localOnly" for d in x.get("directives") or []))]
    return {k: local_only(v) for k, v in node.items()} if isinstance(node, dict) else node


def typename(node, parent=None):
    """Apollo's addTypenameToDocument."""
    if isinstance(node, list): return [typename(x, parent) for x in node]
    if not isinstance(node, dict): return node
    out = {k: typename(v, node) for k, v in node.items()}
    if node.get("kind") == "SelectionSet" and (parent or {}).get("kind") != "OperationDefinition":
        if not any(x["kind"] == "Field" and x["name"]["value"].startswith("__") for x in out["selections"]):
            out["selections"] = out["selections"] + [{"kind": "Field", "name": {"kind": "Name", "value": "__typename"}}]
    return out


def main():
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    if args[0] == "--list":
        src = open(args[1]).read() if len(args) > 1 else bundle()
        names = re.findall(r'OperationDefinition",operation:"(query|mutation)",name:\{kind:"Name",value:"(\w+)"', src)
        print("\n".join(sorted({f"{o} {n}" for o, n in names})))
        return
    src = open(args[1]).read() if len(args) > 1 else bundle()
    text = join([definition(d) for d in typename(local_only(document(src, args[0])))["definitions"]], "\n\n")
    print(text)
    print("\n# sha256:", hashlib.sha256(text.encode()).hexdigest())


if __name__ == "__main__":
    main()
