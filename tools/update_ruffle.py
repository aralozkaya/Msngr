"""Verify the bundled runtime, or update it and its source submodule to a stable release.

Uses only Python's standard library. No upstream install scripts are executed.
"""
import argparse
import base64
import hashlib
import io
import json
import re
import subprocess
import tarfile
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "third_party/ruffle"
ASSETS = ROOT / "app/src/main/assets/ruffle"
LOCK = ROOT / "third_party/ruffle-runtime.json"
UPSTREAM = "https://github.com/ruffle-rs/ruffle.git"
VERSION = re.compile(r"[0-9]+\.[0-9]+\.[0-9]+")
ASSET = re.compile(r"(?:[a-zA-Z0-9_.-]+\.(?:js|wasm)|LICENSE_(?:MIT|APACHE))")


def git(*args):
    return subprocess.check_output(["git", "-C", str(SOURCE), *args], text=True).strip()


def download(url, limit):
    request = Request(url, headers={"User-Agent": "Msngr-dependency-updater"})
    with urlopen(request, timeout=60) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("Upstream response exceeds the size limit")
    return data


def runtime_files(archive, integrity, version, commit):
    if not integrity.startswith("sha512-"):
        raise ValueError("Expected an npm SHA-512 integrity value")
    expected = base64.b64decode(integrity.removeprefix("sha512-"), validate=True)
    if hashlib.sha512(archive).digest() != expected:
        raise ValueError("Ruffle package integrity check failed")
    files = {}
    total = 0
    expanded = 0
    count = 0
    package = None
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as bundle:
        for member in bundle:
            expanded += member.size
            count += 1
            if expanded > 120_000_000 or count > 128:
                raise ValueError("Oversized Ruffle archive")
            if member.name == "package/package.json":
                if not member.isfile() or member.size > 100_000 or package is not None:
                    raise ValueError("Invalid package metadata")
                package = json.load(bundle.extractfile(member))
            elif member.name.startswith("package/") and ASSET.fullmatch(member.name[8:]):
                name = member.name[8:]
                total += member.size
                if not member.isfile() or name in files or total > 80_000_000:
                    raise ValueError("Invalid or oversized runtime asset")
                files[name] = bundle.extractfile(member).read()
    if package is None or package.get("name") != "@ruffle-rs/ruffle" or package.get("version") != version:
        raise ValueError("Unexpected Ruffle package identity")
    required = {"ruffle.js", "LICENSE_MIT", "LICENSE_APACHE"}
    if not required.issubset(files) or not any(n.endswith(".wasm") for n in files) or not any(n.startswith("core.ruffle.") for n in files):
        raise ValueError("Incomplete Ruffle runtime")
    loader = files["ruffle.js"].decode("utf-8")
    if f'commitHash:"{commit}"' not in loader or f'versionNumber:"{version}"' not in loader:
        raise ValueError("Published runtime does not match the source commit/version")
    return files


def verify(root=ROOT):
    lock = json.loads((root / "third_party/ruffle-runtime.json").read_text())
    source = root / "third_party/ruffle"
    head = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if head != lock["sourceCommit"]:
        raise ValueError("Ruffle source/runtime mismatch")
    version = json.loads((source / "web/packages/selfhosted/package.json").read_text())["version"]
    if version != lock["version"]:
        raise ValueError("Ruffle source/package version mismatch")
    assets = root / "app/src/main/assets/ruffle"
    if {p.name for p in assets.iterdir() if p.name != "README.md"} != set(lock["files"]):
        raise ValueError("Ruffle runtime file set differs from the lock")
    for name, digest in lock["files"].items():
        if not ASSET.fullmatch(name) or hashlib.sha256((assets / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"Ruffle asset integrity check failed: {name}")
    loader = (assets / "ruffle.js").read_text(encoding="utf-8")
    if f'commitHash:"{head}"' not in loader or f'versionNumber:"{version}"' not in loader:
        raise ValueError("Ruffle loader does not match the source")
    return lock


def update():
    release = json.loads(download("https://api.github.com/repos/ruffle-rs/ruffle/releases/latest", 2_000_000))
    version = release["tag_name"].removeprefix("v")
    if release["draft"] or release["prerelease"] or not VERSION.fullmatch(version):
        raise ValueError("Expected an official stable Ruffle release")
    git("fetch", "--depth=1", UPSTREAM, f"refs/tags/v{version}")
    commit = git("rev-parse", "FETCH_HEAD^{commit}")
    package = json.loads(download(f"https://registry.npmjs.org/@ruffle-rs/ruffle/{version}", 1_000_000))
    integrity = package["dist"]["integrity"]
    url = f"https://registry.npmjs.org/@ruffle-rs/ruffle/-/ruffle-{version}.tgz"
    archive = download(url, 40_000_000)
    files = runtime_files(archive, integrity, version, commit)
    lock = {"version": version, "sourceCommit": commit, "package": url, "integrity": integrity,
            "files": {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}}
    # Network/identity checks finish before changing the checkout or bundled files.
    git("checkout", "--detach", commit)
    old = json.loads(LOCK.read_text())["files"] if LOCK.exists() else {}
    for name in old:
        if not ASSET.fullmatch(name):
            raise ValueError("Invalid old asset name")
        if name not in files:
            (ASSETS / name).unlink()
    for name, data in files.items():
        (ASSETS / name).write_bytes(data)
    LOCK.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8", newline="\n")
    verify()
    print(f"Verified Ruffle {version} at {commit}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--update", action="store_true", help="Update source and assets to the latest stable release")
    arguments = parser.parse_args()
    if arguments.update:
        update()
    else:
        metadata = verify()
        print(f"Verified Ruffle {metadata['version']} at {metadata['sourceCommit']}")
