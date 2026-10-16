"""Verify a same-signature APK and stage a public update. Does not publish by itself."""
from pathlib import Path
from datetime import datetime, timezone, timedelta
import argparse
import hashlib
import json
import re
import shutil
import subprocess

PACKAGE = "cn.qingji.app"


def run(executable, *args):
    result = subprocess.run([str(executable), *map(str, args)], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def signer(apksigner, apk):
    output = run(apksigner, "verify", "--verbose", "--print-certs", apk)
    values = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)", output)
    if not values:
        raise ValueError("APK signing certificate not found")
    return sorted(value.lower() for value in values)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--previous-apk", type=Path, required=True, help="A previously distributed, trusted APK")
    parser.add_argument("--site", type=Path, required=True)
    parser.add_argument("--github-repository", default="fyy-cn/qingji-releases")
    parser.add_argument("--aapt", type=Path, required=True)
    parser.add_argument("--apksigner", type=Path, required=True)
    parser.add_argument("--note", action="append", required=True)
    args = parser.parse_args()
    if signer(args.apksigner, args.apk) != signer(args.apksigner, args.previous_apk):
        raise ValueError("Signing identity changed. Existing users cannot upgrade in place.")
    badging = run(args.aapt, "dump", "badging", args.apk)
    metadata = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    if not metadata or metadata[1] != PACKAGE:
        raise ValueError("Unexpected APK package")
    version_code, version_name = int(metadata[2]), metadata[3]
    min_sdk = int(re.search(r"sdkVersion:'(\d+)'", badging)[1])
    if not re.fullmatch(r"[0-9]+(?:\.[0-9]+){1,3}", version_name):
        raise ValueError("Use a numeric release version name")
    if not 1 <= len(args.note) <= 20 or any(not 1 <= len(n) <= 500 for n in args.note):
        raise ValueError("Invalid release notes")
    apk_bytes = args.apk.read_bytes()
    sha = hashlib.sha256(apk_bytes).hexdigest()
    if len(apk_bytes) > 100 * 1024 * 1024:
        raise ValueError("APK exceeds the client's 100 MiB download limit")
    if not re.fullmatch(r"[A-Za-z0-9-]+/[A-Za-z0-9._-]+", args.github_repository):
        raise ValueError("Invalid GitHub repository")
    release_dir = args.site.resolve() / "docs" / "releases"
    release_dir.mkdir(parents=True, exist_ok=True)
    feed = release_dir / "latest.json"
    if feed.exists():
        old = json.loads(feed.read_text(encoding="utf-8"))
        if version_code < old["versionCode"] or version_code == old["versionCode"] and sha != old["sha256"]:
            raise ValueError("Version must increase; never replace an already published version")
    filename = f"QingJi-{version_name}-{version_code}.apk"
    asset_dir = args.site.resolve() / "release-assets"
    asset_dir.mkdir(parents=True, exist_ok=True)
    destination = asset_dir / filename
    if destination.exists() and hashlib.sha256(destination.read_bytes()).hexdigest() != sha:
        raise ValueError("Versioned APK already exists with a different hash")
    shutil.copyfile(args.apk, destination)
    release = dict(schema=1, packageName=PACKAGE, versionCode=version_code, versionName=version_name,
                   minSdk=min_sdk, apkUrl=f"https://github.com/{args.github_repository}/releases/download/v{version_name}/{filename}", sha256=sha,
                   sizeBytes=len(apk_bytes), publishedAt=datetime.now(timezone(timedelta(hours=8))).isoformat(timespec="seconds"), notes=args.note)
    temporary = feed.with_suffix(".tmp")
    temporary.write_text(json.dumps(release, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(feed)
    print(json.dumps({"feed": str(feed), "apk": str(destination), "sha256": sha, "versionCode": version_code, "sizeBytes": len(apk_bytes)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
