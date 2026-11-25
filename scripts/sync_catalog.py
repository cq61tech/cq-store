import json
import os
import urllib.error
import urllib.request
from datetime import datetime, timezone

OWNER = "cq61tech"
STORE_REPO = "cq-store"
TOKEN = os.environ.get("GITHUB_TOKEN", "").strip()

ICON_CANDIDATES = [
    "logo.png",
    "icon.png",
    "screenshots/logo.png",
    "app/src/main/ic_launcher-playstore.png",
    "fastlane/metadata/android/en-US/images/icon.png",
    "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png",
    "CydiaIcon.png",
]


def api_get(path):
    url = f"https://api.github.com/{path}"
    req = urllib.request.Request(url)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("User-Agent", "cq-store-sync")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError:
        return None


def url_exists(url):
    req = urllib.request.Request(url, method="HEAD")
    req.add_header("User-Agent", "cq-store-sync")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status == 200
    except Exception:
        return False


def local_asset_url(rel_path):
    if os.path.exists(rel_path):
        return f"https://raw.githubusercontent.com/{OWNER}/{STORE_REPO}/main/{rel_path}"
    return ""


def find_icon(item_id, repo, branch):
    for ext in ("png", "webp", "jpg"):
        local = local_asset_url(f"assets/icons/{item_id}.{ext}")
        if local:
            return local
    for candidate in ICON_CANDIDATES:
        raw = f"https://raw.githubusercontent.com/{OWNER}/{repo}/{branch}/{candidate}"
        if url_exists(raw):
            return raw
    return ""


def find_banner(item_id):
    for ext in ("png", "webp", "jpg"):
        local = local_asset_url(f"assets/banners/{item_id}.{ext}")
        if local:
            return local
    return ""


def main():
    with open("apps.json", encoding="utf-8") as f:
        manifest = json.load(f)

    items = []
    for entry in manifest:
        repo = entry["repo"]
        meta = api_get(f"repos/{OWNER}/{repo}") or {}
        branch = meta.get("default_branch") or "main"

        raw_releases = api_get(f"repos/{OWNER}/{repo}/releases?per_page=10") or []
        releases = []
        for rel in raw_releases:
            if rel.get("draft"):
                continue
            assets = [
                {
                    "name": a["name"],
                    "url": a["browser_download_url"],
                    "size": a.get("size", 0),
                }
                for a in rel.get("assets", [])
            ]
            releases.append(
                {
                    "tag": rel.get("tag_name") or "",
                    "name": rel.get("name") or rel.get("tag_name") or "",
                    "publishedAt": rel.get("published_at") or "",
                    "notes": (rel.get("body") or "").strip(),
                    "assets": assets,
                }
            )

        spdx = (meta.get("license") or {}).get("spdx_id") or ""
        if spdx == "NOASSERTION":
            spdx = ""

        desc = entry.get("description") or (meta.get("description") or "").strip()
        short_desc = entry.get("shortDescription") or desc
        latest = releases[0] if releases else None

        items.append(
            {
                "id": entry["id"],
                "name": entry["name"],
                "repo": repo,
                "kind": entry["kind"],
                "packageId": entry.get("packageId"),
                "altPackageIds": entry.get("altPackageIds", []),
                "author": OWNER,
                "shortDescription": short_desc,
                "description": desc,
                "license": entry.get("license") or spdx,
                "iconUrl": entry.get("iconUrl") or find_icon(entry["id"], repo, branch),
                "bannerUrl": entry.get("bannerUrl") or find_banner(entry["id"]),
                "sourceUrl": entry.get("sourceUrl") or f"https://github.com/{OWNER}/{repo}",
                "telegramUrl": entry.get("telegramUrl", ""),
                "websiteUrl": entry.get("websiteUrl") or (meta.get("homepage") or ""),
                "repoUrl": entry.get("repoUrl", ""),
                "latestVersion": latest["tag"] if latest else "",
                "whatsNew": latest["notes"] if latest else "",
                "releases": releases,
            }
        )

    catalog = {
        "updatedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "items": items,
    }
    with open("catalog.json", "w", encoding="utf-8") as f:
        json.dump(catalog, f, ensure_ascii=False, indent=2)
    print(f"synced {len(items)} items")


if __name__ == "__main__":
    main()
