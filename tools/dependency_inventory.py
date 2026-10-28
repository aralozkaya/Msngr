"""Export exact third-party identities in OSV-Scanner's custom lockfile format."""
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def inventory(root=ROOT):
    packages = []
    for name in ("libsiren", "ruffle"):
        commit = subprocess.check_output(
            ["git", "-C", str(root / "third_party" / name), "rev-parse", "HEAD"], text=True).strip()
        packages.append({"package": {"name": f"github.com/{'kakaroto/libsiren' if name == 'libsiren' else 'ruffle-rs/ruffle'}", "commit": commit}})
    runtime = json.loads((root / "third_party/ruffle-runtime.json").read_text())
    if packages[1]["package"]["commit"] != runtime["sourceCommit"]:
        raise ValueError("Ruffle source/runtime mismatch; refusing to scan a misleading inventory")
    packages.append({"package": {"name": "@ruffle-rs/ruffle", "ecosystem": "npm", "version": runtime["version"]}})
    return {"results": [{"packages": packages}]}


if __name__ == "__main__":
    output = ROOT / "app/build/reports/dependencies/third-party.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(inventory(), indent=2) + "\n", encoding="utf-8")
    print("Wrote third-party commit and package identities for OSV-Scanner")
