"""Cloudflare helpers for the private deploy (.github/workflows/deploy.yml).

  subdomain           make sure the account has a workers.dev subdomain; prints it
  lock <worker-name>  attach a Cloudflare Access lock to the Worker itself (its workers.dev address and
                      preview links), allowing only ALLOWED_EMAIL (sign-in with a one-time code)

The workflow first uploads the Worker with its workers.dev address switched off, runs "lock", and only
then switches the address on, so the site is never public.

Environment: CLOUDFLARE_API_TOKEN ("Workers Scripts: Edit" + "Access: Apps and Policies: Edit"),
CLOUDFLARE_ACCOUNT_ID, ALLOWED_EMAIL.
"""
from __future__ import annotations

import json
import os
import secrets
import string
import sys
import urllib.error
import urllib.request

API = "https://api.cloudflare.com/client/v4"


def call(method: str, path: str, body: dict | None = None, ok404: bool = False) -> dict | None:
    req = urllib.request.Request(
        API + path, method=method, data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + os.environ["CLOUDFLARE_API_TOKEN"], "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            out = json.loads(r.read())
    except urllib.error.HTTPError as e:
        if ok404 and e.code == 404:
            return None
        out = json.loads(e.read() or b"{}")
    if not out.get("success"):
        msgs = "; ".join(f"{e.get('code')}: {e.get('message')}" for e in out.get("errors", [])) or str(out)
        hint = ""
        if "/access/" in path:
            hint = ("\nIf Zero Trust isn't set up yet: Cloudflare dashboard > Zero Trust, pick a team name and the "
                    "Free plan, then run the workflow again.")
        elif "/workers/" in path:
            hint = "\nThe API token may be missing the \"Workers Scripts: Edit\" permission."
        sys.exit(f"Cloudflare API {method} {path} failed: {msgs}{hint}")
    return out


def subdomain(account: str) -> None:
    got = call("GET", f"/accounts/{account}/workers/subdomain", ok404=True)
    name = (got or {}).get("result", {}) or {}
    name = name.get("subdomain")
    if not name:
        name = "bcu-" + "".join(secrets.choice(string.ascii_lowercase + string.digits) for _ in range(6))
        call("PUT", f"/accounts/{account}/workers/subdomain", {"subdomain": name})
    print(name)


def lock(account: str, worker: str) -> None:
    email = os.environ.get("ALLOWED_EMAIL", "").strip()
    if "@" not in email:
        sys.exit("ALLOWED_EMAIL secret is missing: add the email that may open the site.")
    scripts = call("GET", f"/accounts/{account}/workers/scripts")["result"] or []
    script = next((s for s in scripts if s.get("id") == worker), None)
    if not script or not script.get("tag"):
        sys.exit(f"Worker {worker} not found; upload it first.")
    app = {
        "type": "self_hosted",
        "name": f"BCU web ({worker})",
        "destinations": [{"type": "worker", "worker_id": script["tag"]}],
        "session_duration": "720h",
        "app_launcher_visible": False,
        "policies": [{"name": "Only the owner", "decision": "allow", "include": [{"email": {"email": email}}]}],
    }
    apps = call("GET", f"/accounts/{account}/access/apps?per_page=100")["result"] or []
    existing = next((a for a in apps if any(d.get("type") == "worker" and d.get("worker_id") == script["tag"]
                                            for d in a.get("destinations") or [])), None)
    if existing:
        res = call("PUT", f"/accounts/{account}/access/apps/{existing['id']}", app)["result"]
        print(f"Access lock updated on Worker {worker}: only the allowed email can sign in.")
    else:
        res = call("POST", f"/accounts/{account}/access/apps", app)["result"]
        print(f"Access lock created on Worker {worker}: only the allowed email can sign in.")
    if not res.get("policies"):
        sys.exit("The Access application has no policy attached; refusing to continue.")


def main() -> None:
    account = os.environ["CLOUDFLARE_ACCOUNT_ID"]
    if sys.argv[1:2] == ["subdomain"]:
        subdomain(account)
    elif sys.argv[1:2] == ["lock"] and len(sys.argv) == 3:
        lock(account, sys.argv[2])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
