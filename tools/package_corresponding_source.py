"""Package complete application/native source alongside distributed APKs.

Only Git-tracked paths are admitted. Exact submodule source/metadata is expanded;
credentials, private diagnostics and build directories can never enter this ZIP.
The APK's separate library-sources.zip already carries unmodified MPL sources.
"""
import argparse
import subprocess
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from library_licenses import zip_write
from setup_dependencies import verify_checkout

ROOT = Path(__file__).resolve().parent.parent


def safe_source_path(name):
    path = PurePosixPath(name)
    return bool(name) and "\\" not in name and ":" not in name and not path.is_absolute() and ".." not in path.parts and not any(
        part.lower().startswith(".env") or part.lower() in (".git", ".codex", "captures", "build", "local.properties", "keystore.properties", "signing.properties", "agents.md")
        or part.lower().endswith((".jks", ".keystore", ".credentials", ".creds", ".pem", ".p12", ".pfx", ".key")) for part in path.parts)


def source_bytes(root, name):
    path = root / name
    if not safe_source_path(name) or path.resolve() != root.resolve() / name:
        raise ValueError("Private, linked or invalid source path; refusing source distribution")
    return path.read_bytes()


def tracked(root, *paths):
    records = subprocess.check_output(["git", "-C", str(root), "ls-files", "--stage", "-z", "--", *paths])
    entries = {}
    for record in records.decode().split("\0")[:-1]:
        metadata, name = record.split("\t", 1)
        mode, revision, stage = metadata.split()
        if stage != "0" or mode not in {"100644", "100755", "160000"} or not safe_source_path(name):
            raise ValueError(f"Unmerged, linked or invalid source path: {name}")
        entries[name] = (mode, revision)
    return entries


def write_sources(archive, root, entries):
    for name, (mode, revision) in entries.items():
        destination = "Msngr/" + name
        if mode != "160000":
            zip_write(archive, destination, source_bytes(root, name), mode=int(mode, 8))
            continue
        directory = root / name
        verify_checkout(root, name, revision)
        # APK builds use the committed Ruffle runtime and only its sparse source metadata.
        selected = ("Cargo.toml", "Cargo.lock", "LICENSE.md", "web/packages/selfhosted/package.json") if name == "third_party/ruffle" else ()
        files = tracked(directory, *selected)
        if selected and set(selected) != set(files):
            raise ValueError(f"Missing tracked build metadata in {name}")
        for path, (file_mode, _) in files.items():
            zip_write(archive, f"{destination}/{path}", source_bytes(directory, path), mode=int(file_mode, 8))
        zip_write(archive, f"{destination}/UPSTREAM_COMMIT", (revision + "\n").encode())
    zip_write(archive, "Msngr/SOURCE_BUILD.txt", b"This source copy accompanies the APKs from the same release. Install the Android SDK/NDK, CMake and Java 21 documented in README.md. Run ./gradlew :app:assembleDebug (gradlew.bat on Windows); dependency setup is already expanded. Pass -PreleaseTag=vMAJOR.MINOR.PATCH for the release's version. libsiren-hardening.patch is applied automatically. Modify third_party/libsiren or the patch to rebuild/relink the shared library with the included JNI bridge and application. You may sign a modified APK with your own key. The APK and app/src/main/assets/licenses/library-sources.zip also include the complete unmodified Symphonia MPL-2.0 sources. Each component retains its license.\n")


def package(output, root=ROOT):
    root = root.resolve()
    entries = tracked(root)
    if output.resolve().is_relative_to(root):
        relative = output.resolve().relative_to(root).as_posix()
        if relative in entries or any(directory.as_posix() in entries for directory in PurePosixPath(relative).parents):
            raise ValueError("Output would overwrite tracked source")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as staging:
        temporary = Path(staging.name)
    try:
        with zipfile.ZipFile(temporary, "w") as archive:
            write_sources(archive, root, entries)
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    print("Packaged corresponding application/native source:", output.name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    package(parser.parse_args().output)
