"""Prepare Ruffle production sources, inventory the WASM build, or rebuild its runtime.

Normal Android builds use the committed bundle and need neither Rust nor Node.
Source checkouts exclude upstream SWF fixtures before any checkout occurs.
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM = "https://github.com/ruffle-rs/ruffle.git"
TARGET = "wasm32-unknown-unknown"
INVENTORY = "third_party/ruffle-build/inventory.json"
BUILD_INFO = "third_party/ruffle-build/manifest.json"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run(*command, cwd, capture=False):
    return subprocess.run(command, cwd=cwd, check=True, text=True,
                          stdout=subprocess.PIPE if capture else None).stdout


def read_runtime():
    return json.loads((ROOT / "third_party/ruffle-runtime.json").read_text())


def prepare(runtime):
    parent = ROOT / "app/build"
    parent.mkdir(parents=True, exist_ok=True)
    source = Path(tempfile.mkdtemp(prefix="ruffle-source-", dir=parent))
    run("git", "clone", "--filter=blob:none", "--no-checkout", "--depth=1",
        "--branch", "v" + runtime["version"], UPSTREAM, str(source), cwd=ROOT)
    subprocess.run(["git", "-C", str(source), "sparse-checkout", "set", "--no-cone", "--stdin"],
                   input="/*\n!/tests/\n!*.swf\n!*.swz\n", text=True, check=True)
    run("git", "checkout", "--detach", runtime["sourceCommit"], cwd=source)
    if runtime.get("sourceBuild"):
        info = json.loads((ROOT / BUILD_INFO).read_text())
        if info["sourceCommit"] != runtime["sourceCommit"] or info["version"] != runtime["version"]:
            raise ValueError("Source build configuration does not match the runtime")
        for name, expected in info["inputs"].items():
            if digest(ROOT / name) != expected:
                raise ValueError("Source build input hash mismatch: " + name)
        run("git", "apply", str(ROOT / "third_party/patches/ruffle-security.patch"), cwd=source)
        shutil.copyfile(ROOT / "third_party/ruffle-build/Cargo.lock", source / "Cargo.lock")
        shutil.copyfile(ROOT / "third_party/ruffle-build/version-seal.json", source / "web/version_seal.json")
    else:
        # Inventory only the web workspace. This changes build selection, not dependencies.
        manifest = source / "Cargo.toml"
        text = re.sub(r"members = \[.*?\]", 'members = ["web"]', manifest.read_text(), count=1, flags=re.S)
        text = text.replace('default-members = ["desktop"]', 'default-members = ["web"]')
        manifest.write_text(text, encoding="utf-8", newline="\n")
    return source


def dependency_inventory(source, *, patched):
    original_lock = tomllib.loads((source / "Cargo.lock").read_text())
    command = ["cargo", "metadata", "--format-version", "1", "--filter-platform", TARGET,
               "--features", "ruffle_web/jpegxr"]
    if patched:
        command.append("--locked")
    metadata = json.loads(run(*command, cwd=source, capture=True))
    return packages_from_metadata(metadata, original_lock)


def packages_from_metadata(metadata, original_lock):
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    # Include normal AND build/proc-macro dependencies; omit tests and desktop-only code.
    selected = set()

    def visit(identifier):
        if identifier in selected:
            return
        selected.add(identifier)
        for dependency in nodes[identifier]["deps"]:
            if any(k["kind"] in (None, "build") for k in dependency["dep_kinds"]):
                visit(dependency["pkg"])

    roots = [p["id"] for p in packages.values() if p["name"] == "ruffle_web"]
    if len(roots) != 1:
        raise ValueError("Expected exactly one Ruffle web package")
    visit(roots[0])
    locked = {(p["name"], p["version"], p.get("source")) for p in original_lock["package"]}
    result = {}
    for identifier in selected:
        package = packages[identifier]
        source_id = package["source"]
        if not source_id:
            continue
        if (package["name"], package["version"], source_id) not in locked:
            raise ValueError("Metadata resolved a dependency outside the pinned Cargo.lock: " + identifier)
        if source_id.startswith("registry+"):
            identity = {"name": package["name"], "version": package["version"], "ecosystem": "crates.io"}
        elif source_id.startswith("git+"):
            repository, commit = source_id[4:].rsplit("#", 1)
            if not re.fullmatch(r"[0-9a-f]{40}", commit):
                raise ValueError("Unpinned Git dependency")
            identity = {"name": repository.split("?", 1)[0].removeprefix("https://"), "commit": commit}
        else:
            raise ValueError("Unsupported dependency source: " + source_id)
        result[json.dumps(identity, sort_keys=True)] = {"package": identity}
    if len(result) < 100:
        raise ValueError("Unexpectedly small Ruffle dependency inventory")
    return {"results": [{"packages": [result[k] for k in sorted(result)]}]}


def inventory_bytes(runtime, source=None):
    source = source or prepare(runtime)
    data = dependency_inventory(source, patched=bool(runtime.get("sourceBuild")))
    return (json.dumps(data, indent=2) + "\n").encode("utf-8")


def verify_inventory(runtime):
    source = prepare(runtime)
    actual = inventory_bytes(runtime, source)
    if actual != (ROOT / INVENTORY).read_bytes():
        raise ValueError("Ruffle dependency inventory differs from the selected source/build inputs")
    print("Verified the pinned WASM and build dependency inventory")
    if runtime.get("sourceBuild"):
        run("cargo", "test", "--locked", "--package", "ruffle_common", "--lib",
            "security_update_tests", cwd=source)


def rebuild(runtime, source):
    info = json.loads((ROOT / BUILD_INFO).read_text())
    tools = info["tools"]
    for command, expected in ((["rustc", "--version"], tools["rust"]),
                              (["node", "--version"], tools["node"]),
                              (["wasm-bindgen", "--version"], tools["wasm-bindgen"]),
                              (["wasm-opt", "--version"], tools["binaryen"])):
        if expected not in run(*command, cwd=source, capture=True):
            raise ValueError("Unexpected build tool version: " + command[0])
    env = os.environ.copy()
    env.update(BUILD_WASM_MVP="true", ENABLE_VERSION_SEAL="true", CFG_RELEASE_CHANNEL="msngr-patched",
               CARGO_FEATURES="jpegxr", WASM_SOURCE="cargo_and_store")
    npm = shutil.which("npm")
    if not npm:
        raise ValueError("npm is required for a source rebuild")
    web = source / "web"
    subprocess.run([npm, "ci", "--ignore-scripts", "--no-audit", "--no-fund"], cwd=web, env=env, check=True)
    for workspace in ("ruffle-core", "ruffle-selfhosted"):
        subprocess.run([npm, "run", "build", "--workspace=" + workspace], cwd=web, env=env, check=True)
    print("Built runtime:", web / "packages/selfhosted/dist")


def install_built(source):
    from update_ruffle import ASSET, verify
    info = json.loads((ROOT / BUILD_INFO).read_text())
    commit = run("git", "rev-parse", "HEAD", cwd=source, capture=True).strip()
    if commit != info["sourceCommit"]:
        raise ValueError("Built source does not match the selected upstream commit")
    patch = ROOT / "third_party/patches/ruffle-security.patch"
    paths = re.findall(r"^\+\+\+ b/(.+)$", patch.read_text(), flags=re.M)
    actual_patch = subprocess.check_output(["git", "diff", "--", *paths], cwd=source)
    if actual_patch != patch.read_bytes():
        raise ValueError("Built source differs from the recorded source patch")
    modified = set(run("git", "diff", "--name-only", cwd=source, capture=True).splitlines())
    if modified - set(paths) - {"Cargo.lock"}:
        raise ValueError("Built source contains additional tracked changes")
    for name, expected in info["inputs"].items():
        if digest(ROOT / name) != expected:
            raise ValueError("Source build input changed: " + name)
    if digest(source / "Cargo.lock") != digest(ROOT / "third_party/ruffle-build/Cargo.lock"):
        raise ValueError("Built source used a different Cargo.lock")
    if digest(source / "web/version_seal.json") != digest(ROOT / "third_party/ruffle-build/version-seal.json"):
        raise ValueError("Built runtime used a different version seal")
    bundle = source / "web/packages/selfhosted/dist"
    files = {p.name: p.read_bytes() for p in bundle.iterdir() if ASSET.fullmatch(p.name)}
    if len([name for name in files if name.endswith(".wasm")]) != 2 or "ruffle.js" not in files:
        raise ValueError("Expected both WASM variants in the built runtime")
    loader = files["ruffle.js"].decode("utf-8")
    if (f'commitHash:"{commit}"' not in loader or f'versionNumber:"{info["version"]}"' not in loader
            or 'versionChannel:"msngr-patched"' not in loader):
        raise ValueError("Built loader has unexpected source/version/channel metadata")
    runtime = {"version": info["version"], "sourceCommit": commit,
               "sourceBuild": digest(ROOT / BUILD_INFO)}
    inventory = inventory_bytes(runtime, source)
    (ROOT / INVENTORY).write_bytes(inventory)
    runtime["dependencyInventory"] = hashlib.sha256(inventory).hexdigest()
    runtime["files"] = {name: hashlib.sha256(data).hexdigest() for name, data in sorted(files.items())}
    assets = ROOT / "app/src/main/assets/ruffle"
    for old in assets.iterdir():
        if ASSET.fullmatch(old.name) and old.name not in files:
            old.unlink()
    for name, data in files.items():
        (assets / name).write_bytes(data)
    (ROOT / "third_party/ruffle-runtime.json").write_text(json.dumps(runtime, indent=2) + "\n", encoding="utf-8", newline="\n")
    verify()
    print("Installed and verified the patched runtime and its dependency inventory")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-inventory", action="store_true")
    parser.add_argument("--rebuild", action="store_true")
    parser.add_argument("--install-built", type=Path, metavar="SOURCE_DIR")
    options = parser.parse_args()
    runtime = read_runtime()
    if options.install_built:
        install_built(options.install_built.resolve())
    elif options.verify_inventory:
        verify_inventory(runtime)
    elif options.rebuild:
        if not runtime.get("sourceBuild"):
            raise SystemExit("This runtime uses the official package; no local source patch is selected")
        rebuild(runtime, prepare(runtime))
    else:
        parser.error("Select --verify-inventory, --rebuild, or --install-built")
