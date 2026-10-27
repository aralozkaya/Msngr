import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import setup_dependencies


class SparseDependencyTest(unittest.TestCase):
    def setUp(self):
        base = setup_dependencies.ROOT / "app/build/dependency-setup-tests"
        base.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix="setup-test-", dir=base)
        self.folder = Path(self.temporary.name).resolve()
        assert self.folder.is_relative_to(base.resolve())
        self.addCleanup(self.temporary.cleanup)
        self.upstream = self.folder / "upstream"
        self.upstream.mkdir()
        self.git(self.upstream, "init", "-q")
        self.git(self.upstream, "config", "uploadpack.allowFilter", "true")
        self.git(self.upstream, "config", "uploadpack.allowReachableSHA1InWant", "true")
        self.root = self.folder / "parent"
        self.root.mkdir()
        self.git(self.root, "init", "-q")
        (self.root / "third_party").mkdir()
        (self.root / ".gitmodules").write_text(
            '[submodule "third_party/ruffle"]\n'
            '\tpath = third_party/ruffle\n'
            f'\turl = {self.upstream.as_uri()}\n'
            '\tupdate = none\n', encoding="utf-8")

    @staticmethod
    def git(directory, *arguments):
        return subprocess.check_output(["git", "-C", str(directory), *arguments],
                                       text=True, stderr=subprocess.PIPE).strip()

    def release(self, version):
        package = self.upstream / "web/packages/selfhosted"
        package.mkdir(parents=True, exist_ok=True)
        (package / "package.json").write_text(json.dumps({"version": version}))
        (self.upstream / "Cargo.lock").write_text("version = 3\n")
        (self.upstream / "Cargo.toml").write_text('[workspace]\n')
        (self.upstream / "LICENSE_MIT").write_text("fixture license\n")
        excluded = self.upstream / "tests/excluded-fixture.txt"
        excluded.parent.mkdir(exist_ok=True)
        excluded.write_text(f"Harmless synthetic fixture for release {version}\n")
        self.git(self.upstream, "add", ".")
        self.git(self.upstream, "-c", "user.name=Dependency Test", "-c",
                 "user.email=dependency-test@example.invalid", "commit", "-qm", version)
        self.git(self.upstream, "tag", "v" + version)
        commit = self.git(self.upstream, "rev-parse", "HEAD")
        fixture = self.git(self.upstream, "hash-object", "tests/excluded-fixture.txt")
        (self.root / "third_party/ruffle-runtime.json").write_text(json.dumps({"version": version}))
        self.git(self.root, "add", ".gitmodules", "third_party/ruffle-runtime.json")
        self.git(self.root, "update-index", "--add", "--cacheinfo", f"160000,{commit},third_party/ruffle")
        return commit, fixture

    def assert_sparse(self, source, commit, excluded_blob):
        self.assertEqual(self.git(source, "rev-parse", "HEAD"), commit)
        self.assertTrue((source / "Cargo.lock").is_file())
        self.assertTrue((source / "web/packages/selfhosted/package.json").is_file())
        self.assertFalse((source / "tests").exists())
        # Enumerate only local objects: this must not cause a lazy network fetch.
        objects = self.git(source, "cat-file", "--batch-all-objects", "--batch-check=%(objectname)").splitlines()
        self.assertNotIn(excluded_blob, objects, "Excluded fixture blob was downloaded")

    def test_initialization_and_updates_never_fetch_excluded_fixtures(self):
        commit, fixture = self.release("1.2.3")
        source = setup_dependencies.prepare_ruffle(self.root)
        self.assert_sparse(source, commit, fixture)
        setup_dependencies.prepare_ruffle(self.root)
        self.assert_sparse(source, commit, fixture)
        commit, fixture = self.release("1.2.4")
        setup_dependencies.prepare_ruffle(self.root)
        self.assert_sparse(source, commit, fixture)

    def test_standard_recursive_update_skips_ruffle(self):
        self.release("1.2.3")
        self.git(self.root, "submodule", "update", "--init", "--recursive")
        self.assertFalse((self.root / "third_party/ruffle/.git").exists())


if __name__ == "__main__":
    unittest.main()
