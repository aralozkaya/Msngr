import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from ruffle_build import packages_from_metadata


class WebInventoryTest(unittest.TestCase):
    def graph(self):
        packages = [{"id": "web", "name": "ruffle_web", "version": "0.7.1", "source": None}]
        nodes = [{"id": "web", "deps": []}]
        locked = []
        for i in range(101):
            package = {"id": str(i), "name": "dependency-" + str(i), "version": "1.0.0",
                       "source": "registry+https://github.com/rust-lang/crates.io-index"}
            packages.append(package)
            nodes.append({"id": str(i), "deps": []})
            nodes[0]["deps"].append({"pkg": str(i), "dep_kinds": [{"kind": "build" if i == 100 else None}]})
            locked.append({k: package[k] for k in ("name", "version", "source")})
        return {"packages": packages, "resolve": {"nodes": nodes}}, {"package": locked}

    def test_build_dependencies_are_scanned_but_unshipped_test_and_desktop_packages_are_not(self):
        graph, lock = self.graph()
        for name in ("desktop-only", "test-only"):
            graph["packages"].append({"id": name, "name": name, "version": "0.1.0", "source": "registry+unused"})
            graph["resolve"]["nodes"].append({"id": name, "deps": []})
        graph["resolve"]["nodes"][0]["deps"].append({"pkg": "test-only", "dep_kinds": [{"kind": "dev"}]})
        names = {p["package"]["name"] for p in packages_from_metadata(graph, lock)["results"][0]["packages"]}
        self.assertIn("dependency-100", names)
        self.assertNotIn("desktop-only", names)
        self.assertNotIn("test-only", names)

    def test_resolution_cannot_silently_upgrade_the_locked_build(self):
        graph, lock = self.graph()
        graph["packages"][1]["version"] = "1.1.0"
        with self.assertRaisesRegex(ValueError, "outside the pinned"):
            packages_from_metadata(graph, lock)

    def test_git_dependencies_require_an_exact_commit(self):
        graph, lock = self.graph()
        for collection in (graph["packages"], lock["package"]):
            package = next(p for p in collection if p["name"] == "dependency-0")
            package["source"] = "git+https://github.com/example/library#main"
        with self.assertRaisesRegex(ValueError, "Unpinned"):
            packages_from_metadata(graph, lock)


if __name__ == "__main__":
    unittest.main()
