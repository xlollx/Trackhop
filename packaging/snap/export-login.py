#!/usr/bin/env python3
"""The Snap Store key for GitHub, without snapcraft (packaging/README.md, "Snap Store").

Does what `snapcraft export-login` does, for a machine where snap does not work (WSL): asks the
store for a token limited to the snap `trackhop` and the stable channel, has it signed by Ubuntu One
with your e-mail, password and two-factor code (typed here, sent only to login.ubuntu.com), and
writes the result to trackhop-store.txt, the value of the GitHub secret SNAPCRAFT_STORE_CREDENTIALS.

    python3 export-login.py

With --metrics, a second key that can only read the snap's figures (devices with it installed), for
store-stats.yml: written to trackhop-metrics.txt, the secret SNAPCRAFT_METRICS_CREDENTIALS.

    python3 export-login.py --metrics
"""
import base64
import datetime
import getpass
import json
import sys
import urllib.error
import urllib.request

STORE = "https://dashboard.snapcraft.io/dev/api/acl/"
SSO = "https://login.ubuntu.com/api/v2/tokens/discharge"
SSO_HOST = "login.ubuntu.com"


def post(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        sys.exit(f"{url}: HTTP {e.code} {e.read().decode(errors='replace')[:400]}")


def b64url(s):
    s = s.strip()
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def caveat_id(root):
    """The id of the root macaroon's caveat that Ubuntu One discharges (macaroon format 1)."""
    data = b64url(root)
    packets, i = [], 0
    while i < len(data):
        size = int(data[i:i + 4], 16)
        key, _, value = data[i + 4:i + size].rstrip(b"\n").partition(b" ")
        packets.append((key, value))
        i += size
    for n, (key, value) in enumerate(packets):
        if key != b"cid":
            continue
        for k, v in packets[n + 1:n + 3]:
            if k == b"cl" and v.decode() == SSO_HOST:
                return value.decode()
    sys.exit("No Ubuntu One caveat in the store's token: snapcraft's format has changed.")


def main():
    expires = (datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)
               + datetime.timedelta(days=365)).isoformat()
    metrics = "--metrics" in sys.argv
    ask = {
        "permissions": ["package_access", "package_metrics"] if metrics else ["package_access", "package_push", "package_update", "package_release"],
        "description": "trackhop-github-metrics" if metrics else "trackhop-github",
        "expires": expires,
        "packages": [{"series": "16", "name": "trackhop"}],
    }
    if not metrics:
        ask["channels"] = ["stable"]
    root = post(STORE, ask)["macaroon"]
    out, secret = ("trackhop-metrics.txt", "SNAPCRAFT_METRICS_CREDENTIALS") if metrics else ("trackhop-store.txt", "SNAPCRAFT_STORE_CREDENTIALS")
    cid = caveat_id(root)
    if "--check" in sys.argv:
        print("The store answered and its token has the Ubuntu One caveat: ready.")
        return
    email = input("Ubuntu One e-mail: ").strip()
    password = getpass.getpass("Password (not shown): ")
    otp = input("Two-factor code (empty if you have none): ").strip()
    body = {"email": email, "password": password, "caveat_id": cid}
    if otp:
        body["otp"] = otp
    discharge = post(SSO, body)["discharge_macaroon"]
    creds = json.dumps({"t": "u1-macaroon", "v": {"r": root, "d": discharge}})
    with open(out, "w") as f:
        f.write(base64.b64encode(creds.encode()).decode())
    print(f"Written to {out}: put its whole text in the GitHub secret {secret}, then delete the file.")


if __name__ == "__main__":
    main()
