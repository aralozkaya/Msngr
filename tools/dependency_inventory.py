"""Export exact third-party identities in OSV-Scanner's custom lockfile format."""
import json
from pathlib import Path
from setup_dependencies import pinned_commit, verify_checkout

ROOT = Path(__file__).resolve().parent.parent


def inventory(root=ROOT):
    packages = []
    runtime = json.loads((root / "third_party/ruffle-runtime.json").read_text())
    for name, repository in (("libsiren", "kakaroto/libsiren"), ("ruffle", "ruffle-rs/ruffle")):
        path = "third_party/" + name
        commit = pinned_commit(root, path)
        verify_checkout(root, path, commit)
        if name == "ruffle" and commit != runtime["sourceCommit"]:
            raise ValueError("Ruffle source/runtime mismatch; refusing to scan a misleading inventory")
        packages.append({"package": {"name": "github.com/" + repository, "commit": commit}})
    packages.append({"package": {"name": "@ruffle-rs/ruffle", "ecosystem": "npm", "version": runtime["version"]}})
    return {"results": [{"packages": packages}]}


if __name__ == "__main__":
    output = ROOT / "app/build/reports/dependencies/third-party.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(inventory(), indent=2) + "\n", encoding="utf-8")
    print("Wrote third-party commit and package identities for OSV-Scanner")
