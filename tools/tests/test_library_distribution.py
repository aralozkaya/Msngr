import hashlib
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from library_licenses import ROOT, zip_write
from package_corresponding_source import package, safe_source_path, source_bytes


class LibraryDistributionTest(unittest.TestCase):
    def test_private_and_escape_paths_cannot_be_distributed(self):
        for name in ('.env', '.ENV', '.env.production', '.codex/probe.py', 'local.properties',
                     '../outside', '/outside', 'C:/outside', r'..\outside', 'signing.properties',
                     'app/build/private.txt', 'key.pem', 'key.pfx', 'account.credentials'):
            with self.subTest(name=name):
                self.assertFalse(safe_source_path(name))
        self.assertTrue(safe_source_path('third_party/ruffle-build/Cargo.lock'))
        self.assertTrue(safe_source_path('app/src/main/cpp/siren_jni.c'))

    def test_source_paths_cannot_follow_links_outside_the_checkout(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root/'file.c').write_text('source')
            self.assertEqual(source_bytes(root, 'file.c'), b'source')
            with self.assertRaises(ValueError):
                source_bytes(root, '../outside')
            try:
                (root/'link.c').symlink_to(root/'file.c')
            except OSError:
                return  # Windows can require extra privileges to create symlinks.
            with self.assertRaises(ValueError):
                source_bytes(root, 'link.c')

    def test_source_archive_has_stable_entries(self):
        results = []
        for _ in range(2):
            data = io.BytesIO()
            with zipfile.ZipFile(data, 'w') as archive:
                zip_write(archive, 'library/source.c', b'original source')
            results.append(data.getvalue())
        self.assertEqual(*results)

    def test_source_package_preserves_wrapper_mode_and_previous_archive_on_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            wrapper = root / "gradlew"
            wrapper.write_bytes(b"#!/bin/sh\n")
            subprocess.run(["git", "-C", str(root), "add", "--chmod=+x", "gradlew"], check=True)
            output = root / "source.zip"
            package(output, root)
            with zipfile.ZipFile(output) as archive:
                entry = archive.getinfo("Msngr/gradlew")
                self.assertEqual(entry.create_system, 3)
                self.assertEqual(entry.external_attr >> 16, 0o100755)
            with self.assertRaisesRegex(ValueError, "overwrite tracked source"):
                package(wrapper, root)
            self.assertEqual(wrapper.read_bytes(), b"#!/bin/sh\n")
            wrapper.unlink()
            previous = output.read_bytes()
            with self.assertRaises(FileNotFoundError):
                package(output, root)
            self.assertEqual(output.read_bytes(), previous)

    def test_missing_or_wrong_submodule_revision_cannot_be_distributed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            library = root / "library"
            library.mkdir()
            gitlink = "160000," + "a" * 40 + ",library"
            subprocess.run(["git", "-C", str(root), "update-index", "--add", "--cacheinfo", gitlink], check=True)
            output = root / "source.zip"
            with self.assertRaisesRegex(ValueError, "Missing or linked"):
                package(output, root)
            subprocess.run(["git", "init", "-q", str(library)], check=True)
            subprocess.run(["git", "-C", str(library), "-c", "user.name=Test", "-c",
                            "user.email=test@example.invalid", "commit", "-qm", "fixture", "--allow-empty"], check=True)
            with self.assertRaisesRegex(ValueError, "pinned revision"):
                package(output, root)
            self.assertFalse(output.exists())

    def test_committed_notices_and_copyleft_sources_match_the_audit(self):
        audit = json.loads((ROOT/'third_party/library-audit.json').read_text(encoding='utf-8'))
        directory = ROOT/'app/src/main/assets/licenses'
        for name, digest in audit['files'].items():
            self.assertEqual(hashlib.sha256((directory/name).read_bytes()).hexdigest(), digest, name)
        with zipfile.ZipFile(directory/'library-sources.zip') as archive:
            names = set(archive.namelist())
            self.assertIn('libsiren/COPYING', names)
            self.assertIn('libsiren/encoder.c', names)
            self.assertIn('msngr/third_party/patches/libsiren-hardening.patch', names)
            for package in ('symphonia', 'symphonia-core', 'symphonia-metadata', 'symphonia-bundle-mp3', 'symphonia-codec-aac'):
                self.assertIn(f'{package}-0.5.5/Cargo.toml', names)
                self.assertIn(f'{package}-0.5.5/LICENSE-MPL-2.0.txt', names)
            self.assertFalse(any('.env' in name or '.git/' in name for name in names))


if __name__ == '__main__':
    unittest.main()
