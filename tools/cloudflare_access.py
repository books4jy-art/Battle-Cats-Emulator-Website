"""Make sure a Cloudflare Access lock covers the Pages site, so only the allowed email can open it.

Run by .github/workflows/deploy.yml BEFORE anything is uploaded, so the site is never public.
Creates (or updates) one self-hosted Access application covering <project>.pages.dev and its preview
addresses (*.<project>.pages.dev), allowing only ALLOWED_EMAIL (sign-in by a one-time code sent to it).

Environment: CLOUDFLARE_API_TOKEN (needs "Access: Apps and Policies: Edit"), CLOUDFLARE_ACCOUNT_ID,
ALLOWED_EMAIL. Usage: python tools/cloudflare_access.py <pages-project-name>
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request

API = "https://api.cloudflare.com/client/v4"


def call(method: str, path: str, body: dict | None = None) -> dict:
    req = urllib.request.Request(
        API + path, method=method, data=None if body is None else json.dumps(body).encode(),
        headers={"Authorization": "Bearer " + os.environ["CLOUDFLARE_API_TOKEN"], "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            out = json.loads(r.read())
    except urllib.error.HTTPError as e:
        out = json.loads(e.read() or b"{}")
    if not out.get("success"):
        msgs = "; ".join(f"{e.get('code')}: {e.get('message')}" for e in out.get("errors", [])) or str(out)
        hint = ""
        if "access" in path and any(str(e.get("code")) in ("9999", "12006", "10000") for e in out.get("errors", [])):
            hint = ("\nIf this says Zero Trust isn't set up: open the Cloudflare dashboard > Zero Trust once, "
                    "pick a team name and the Free plan, then run this workflow again.")
        sys.exit(f"Cloudflare API {method} {path} failed: {msgs}{hint}")
    return out


def main() -> None:
    project = sys.argv[1]
    account = os.environ["CLOUDFLARE_ACCOUNT_ID"]
    email = os.environ.get("ALLOWED_EMAIL", "").strip()
    if not email or "@" not in email:
        sys.exit("ALLOWED_EMAIL secret is missing: add the email that may open the site.")
    host = f"{project}.pages.dev"
    app = {
        "type": "self_hosted",
        "name": f"BCU web ({project})",
        "domain": host,
        "destinations": [{"type": "public", "uri": host}, {"type": "public", "uri": "*." + host}],
        "session_duration": "720h",
        "app_launcher_visible": False,
        "policies": [{"name": "Only the owner", "decision": "allow", "include": [{"email": {"email": email}}]}],
    }
    apps = call("GET", f"/accounts/{account}/access/apps?per_page=100")["result"] or []
    existing = next((a for a in apps if a.get("domain") == host), None)
    if existing:
        res = call("PUT", f"/accounts/{account}/access/apps/{existing['id']}", app)["result"]
        print(f"Access lock updated for {host} (+ previews): only the allowed email can sign in.")
    else:
        res = call("POST", f"/accounts/{account}/access/apps", app)["result"]
        print(f"Access lock created for {host} (+ previews): only the allowed email can sign in.")
    if not res.get("policies"):
        sys.exit("The Access application has no policy attached; refusing to continue (the site would be blocked or open).")


if __name__ == "__main__":
    main()
