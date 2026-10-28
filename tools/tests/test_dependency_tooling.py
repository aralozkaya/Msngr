import base64
import hashlib
import io
import json
import sys
import tarfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import update_ruffle
import dependency_inventory


class RuffleArchiveTest(unittest.TestCase):
    commit = "a" * 40

    def archive(self, *, commit=None, version="1.2.3", duplicate=False, symlink=False):
        files = {
            "package.json": json.dumps({"name": "@ruffle-rs/ruffle", "version": version}).encode(),
            "ruffle.js": f'commitHash:"{commit or self.commit}",versionNumber:"{version}"'.encode(),
            "core.ruffle.fixture.js": b"loader", "fixture.wasm": b"wasm",
            "LICENSE_MIT": b"license", "LICENSE_APACHE": b"license",
            "../escape.js": b"must never be extracted", "ruffle.js.map": b"omitted",
        }
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w:gz") as bundle:
            for name, data in files.items():
                info = tarfile.TarInfo("package/" + name)
                info.size = len(data)
                if symlink and name == "ruffle.js":
                    info.type = tarfile.SYMTYPE
                    info.linkname = "../../outside"
                bundle.addfile(info, io.BytesIO(data))
            if duplicate:
                info = tarfile.TarInfo("package/ruffle.js")
                info.size = 1
                bundle.addfile(info, io.BytesIO(b"x"))
        data = output.getvalue()
        return data, "sha512-" + base64.b64encode(hashlib.sha512(data).digest()).decode()

    def test_complete_runtime_ignores_maps_and_unsafe_paths(self):
        data, integrity = self.archive()
        files = update_ruffle.runtime_files(data, integrity, "1.2.3", self.commit)
        self.assertEqual(set(files), {"ruffle.js", "core.ruffle.fixture.js", "fixture.wasm", "LICENSE_MIT", "LICENSE_APACHE"})

    def test_corrupted_download_is_rejected(self):
        data, integrity = self.archive()
        with self.assertRaisesRegex(ValueError, "integrity"):
            update_ruffle.runtime_files(data + b"corruption", integrity, "1.2.3", self.commit)

    def test_runtime_from_different_source_commit_is_rejected(self):
        data, integrity = self.archive(commit="b" * 40)
        with self.assertRaisesRegex(ValueError, "source commit"):
            update_ruffle.runtime_files(data, integrity, "1.2.3", self.commit)

    def test_duplicate_and_symlinked_assets_are_rejected(self):
        for options in ({"duplicate": True}, {"symlink": True}):
            with self.subTest(options=options):
                data, integrity = self.archive(**options)
                with self.assertRaisesRegex(ValueError, "Invalid"):
                    update_ruffle.runtime_files(data, integrity, "1.2.3", self.commit)

    def test_checked_out_runtime_and_inventory_agree(self):
        metadata = update_ruffle.verify()
        packages = dependency_inventory.inventory()["results"][0]["packages"]
        self.assertEqual(packages[1]["package"]["commit"], metadata["sourceCommit"])
        self.assertEqual(packages[2]["package"]["version"], metadata["version"])


if __name__ == "__main__":
    unittest.main()
