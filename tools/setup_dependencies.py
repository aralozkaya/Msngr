"""Initialize pinned dependencies without downloading Ruffle's upstream test fixtures."""
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUFFLE = "third_party/ruffle"
PATTERNS = "/Cargo.lock\n/Cargo.toml\n/LICENSE*\n/web/packages/selfhosted/package.json\n"


def git(directory, *arguments, data=None):
    return subprocess.check_output(["git", "-C", str(directory), *arguments],
                                   input=data, text=True).strip()


def configure_ruffle_checkout(source):
    # Set the sparse selection before any checkout. With a blobless clone, Git
    # fetches only these files; excluded SWF fixtures are never materialized.
    git(source, "sparse-checkout", "set", "--no-cone", "--stdin", data=PATTERNS)
    git(source, "config", "remote.origin.promisor", "true")
    git(source, "config", "remote.origin.partialclonefilter", "blob:none")


def pinned_commit(root, name):
    entry = git(root, "ls-files", "--stage", "--", name).split()
    if len(entry) != 4 or entry[0] != "160000" or entry[2] != "0":
        raise RuntimeError(f"Resolve the {name} submodule reference before setting up dependencies")
    return entry[1]


def verify_checkout(root, name, expected):
    directory = root.resolve() / name
    if directory.resolve() != directory or not (directory / ".git").exists():
        raise ValueError(f"Missing or linked submodule checkout: {name}")
    if git(directory, "rev-parse", "HEAD") != expected:
        raise ValueError(f"Submodule {name} differs from the pinned revision")


def prepare_ruffle(root=ROOT):
    root = Path(root).resolve()
    commit = pinned_commit(root, RUFFLE)
    git(root, "submodule", "init", "--", RUFFLE)
    # Existing clones also need this setting; submodule init does not overwrite it.
    git(root, "config", f"submodule.{RUFFLE}.update", "none")
    source = root / RUFFLE
    if not (source / ".git").exists():
        version = json.loads((root / "third_party/ruffle-runtime.json").read_text())["version"]
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
            raise RuntimeError("Expected a stable Ruffle version")
        url = git(root, "config", "-f", ".gitmodules", "--get", f"submodule.{RUFFLE}.url")
        git(root, "clone", "--filter=blob:none", "--no-checkout", "--depth=1", "--single-branch",
            "--branch", f"v{version}", "--", url, str(source))
    configure_ruffle_checkout(source)
    git(source, "fetch", "--filter=blob:none", "--depth=1", "origin", commit)
    git(source, "checkout", "--detach", commit)
    git(root, "submodule", "absorbgitdirs", "--", RUFFLE)
    return source


def setup(root=ROOT):
    git(root, "submodule", "sync", "--", "third_party/libsiren", RUFFLE)
    git(root, "submodule", "update", "--init", "--", "third_party/libsiren")
    prepare_ruffle(root)
    print("Initialized pinned libsiren and sparse Ruffle metadata; upstream SWF fixtures are excluded")


if __name__ == "__main__":
    setup()
