"""Package complete application/native source alongside distributed APKs.

Only Git-tracked paths are admitted. Exact submodule source/metadata is expanded;
credentials, private diagnostics and build directories can never enter this ZIP.
The APK's separate library-sources.zip already carries unmodified MPL sources.
"""
import argparse
import subprocess
import zipfile
from pathlib import Path, PurePosixPath
from library_licenses import zip_write

ROOT = Path(__file__).resolve().parent.parent


def safe_source_path(name):
    path = PurePosixPath(name)
    return bool(name) and "\\" not in name and ":" not in name and not path.is_absolute() and ".." not in path.parts and not any(
        part.lower().startswith(".env") or part.lower() in (".git", ".codex", "captures", "build", "local.properties", "keystore.properties", "signing.properties", "agents.md")
        or part.lower().endswith((".jks", ".keystore", ".credentials", ".creds", ".pem", ".p12", ".pfx", ".key")) for part in path.parts)


def source_bytes(root, name):
    path = root / name
    resolved = path.resolve()
    if not safe_source_path(name) or path.is_symlink() or not resolved.is_relative_to(root.resolve()) or not safe_source_path(resolved.relative_to(root.resolve()).as_posix()):
        raise ValueError("Private, linked or invalid source path; refusing source distribution")
    return path.read_bytes()


def tracked(root):
    return subprocess.check_output(["git", "-C", str(root), "ls-files", "-z"]).decode().split("\0")[:-1]


def package(output, root=ROOT):
    names = tracked(root)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w") as archive:
        for name in sorted(names):
            if not safe_source_path(name):
                raise ValueError("Private or invalid tracked path; refusing source distribution")
            path = root / name
            if path.is_symlink():
                raise ValueError("Linked source path; refusing source distribution")
            if path.is_file():
                zip_write(archive, "Msngr/" + name, source_bytes(root, name))
        for library in ("libsiren", "ruffle"):
            directory = root / "third_party" / library
            revision = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "HEAD"], text=True).strip()
            # Ruffle's committed runtime is used by normal APK builds. Only the
            # sparse source metadata used by those builds is necessary here.
            selected = tracked(directory) if library == "libsiren" else [
                "Cargo.toml", "Cargo.lock", "LICENSE.md", "web/packages/selfhosted/package.json"]
            for name in selected:
                if not safe_source_path(name):
                    raise ValueError("Invalid submodule source path")
                zip_write(archive, f"Msngr/third_party/{library}/{name}", source_bytes(directory, name))
            zip_write(archive, f"Msngr/third_party/{library}/UPSTREAM_COMMIT", (revision+"\n").encode())
        zip_write(archive, "Msngr/SOURCE_BUILD.txt", b"This source copy accompanies the APKs from the same release. Install the Android SDK/NDK, CMake and Java 21 documented in README.md. Run gradlew :app:assembleDebug (gradlew.bat on Windows); dependency setup is already expanded. Pass -PreleaseTag=vMAJOR.MINOR.PATCH for the release's version. libsiren-hardening.patch is applied automatically. Modify third_party/libsiren or the patch to rebuild/relink the shared library with the included JNI bridge and application. You may sign a modified APK with your own key. The APK and app/src/main/assets/licenses/library-sources.zip also include the complete unmodified Symphonia MPL-2.0 sources. Each component retains its license.\n")
    print("Packaged corresponding application/native source:", output.name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    package(parser.parse_args().output)
