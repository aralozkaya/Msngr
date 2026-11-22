import base64
import hashlib
import io
import json
import os
import sys
import tarfile
import unittest
from email.message import Message
from pathlib import Path
from unittest.mock import patch
from urllib.error import HTTPError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import update_ruffle
import dependency_inventory


class RuffleArchiveTest(unittest.TestCase):
    commit = "a" * 40

    def archive(self, *, commit=None, version="1.2.3", duplicate=False, symlink=False, include_core_chunk=True, include_wasm=True):
        files = {
            "package.json": json.dumps({"name": "@ruffle-rs/ruffle", "version": version}).encode(),
            "ruffle.js": f'commitHash:"{commit or self.commit}",versionNumber:"{version}"'.encode(),
            "LICENSE_MIT": b"license", "LICENSE_APACHE": b"license",
            "../escape.js": b"must never be extracted", "ruffle.js.map": b"omitted",
        }
        if include_core_chunk:
            files["core.ruffle.fixture.js"] = b"loader"
        if include_wasm:
            files["fixture.wasm"] = b"wasm"
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

    def test_single_js_bundle_without_core_chunks_is_accepted(self):
        data, integrity = self.archive(include_core_chunk=False)
        files = update_ruffle.runtime_files(data, integrity, "1.2.3", self.commit)
        self.assertEqual(set(files), {"ruffle.js", "fixture.wasm", "LICENSE_MIT", "LICENSE_APACHE"})

    def test_runtime_without_wasm_is_rejected(self):
        data, integrity = self.archive(include_wasm=False)
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            update_ruffle.runtime_files(data, integrity, "1.2.3", self.commit)

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


class RuffleReleaseLookupTest(unittest.TestCase):
    def test_github_api_requests_attach_token_only_to_https_api_github(self):
        with patch.dict(os.environ, {"GH_TOKEN": "gh-secret", "GITHUB_TOKEN": "fallback"}, clear=True):
            api_req = update_ruffle.build_request(update_ruffle.LATEST_RELEASE_API)
            npm_req = update_ruffle.build_request("https://registry.npmjs.org/@ruffle-rs/ruffle/0.7.1")
            http_req = update_ruffle.build_request("http://api.github.com/repos/ruffle-rs/ruffle/releases/latest")
        self.assertEqual(api_req.get_header("Authorization"), "Bearer gh-secret")
        self.assertEqual(api_req.get_header("Accept"), "application/vnd.github+json")
        self.assertIsNone(npm_req.get_header("Authorization"))
        self.assertIsNone(http_req.get_header("Authorization"))

    def test_redirect_strips_authorization_when_leaving_github_api(self):
        handler = update_ruffle._StripAuthOnRedirectHandler()
        with patch.dict(os.environ, {"GITHUB_TOKEN": "token-value"}, clear=True):
            api_req = update_ruffle.build_request(update_ruffle.LATEST_RELEASE_API)
            external = handler.redirect_request(
                api_req, None, 302, "Found", Message(), "https://objects.githubusercontent.com/asset"
            )
            same_host = handler.redirect_request(
                api_req, None, 302, "Found", Message(), "https://api.github.com/repositories/1/releases/latest"
            )
        self.assertIsNone(external.get_header("Authorization"))
        self.assertEqual(same_host.get_header("Authorization"), "Bearer token-value")

    def test_latest_release_version_validates_api_response(self):
        valid = json.dumps({"tag_name": "v0.7.2", "draft": False, "prerelease": False}).encode()
        with patch.object(update_ruffle, "download", return_value=valid):
            self.assertEqual(update_ruffle.latest_release_version(), "0.7.2")
        for payload in (
            {"tag_name": "v0.7.2", "draft": True, "prerelease": False},
            {"tag_name": "v0.7.2", "draft": False, "prerelease": True},
            {"tag_name": "nightly-2026-11-16", "draft": False, "prerelease": False},
        ):
            with self.subTest(payload=payload):
                with patch.object(update_ruffle, "download", return_value=json.dumps(payload).encode()):
                    with self.assertRaisesRegex(ValueError, "official stable"):
                        update_ruffle.latest_release_version()

    def test_latest_release_version_falls_back_on_rate_limit(self):
        headers = Message()
        headers["X-RateLimit-Remaining"] = "0"
        rate_limited = HTTPError(update_ruffle.LATEST_RELEASE_API, 403, "rate limit exceeded", headers, None)

        class _Response:
            def __init__(self, url):
                self._url = url

            def geturl(self):
                return self._url

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        with patch.object(update_ruffle, "download", side_effect=rate_limited):
            with patch.object(update_ruffle._OPENER, "open", return_value=_Response(
                "https://github.com/ruffle-rs/ruffle/releases/tag/v0.7.2"
            )) as opener_mock:
                self.assertEqual(update_ruffle.latest_release_version(), "0.7.2")
            self.assertEqual(opener_mock.call_args.args[0].get_method(), "HEAD")
            with patch.object(update_ruffle._OPENER, "open", return_value=_Response(
                "https://evil.example/ruffle-rs/ruffle/releases/tag/v0.7.2"
            )):
                with self.assertRaisesRegex(ValueError, "official stable"):
                    update_ruffle.latest_release_version()

        forbidden = HTTPError(update_ruffle.LATEST_RELEASE_API, 403, "Forbidden", Message(), None)
        with patch.object(update_ruffle, "download", side_effect=forbidden):
            with self.assertRaises(HTTPError):
                update_ruffle.latest_release_version()


if __name__ == "__main__":
    unittest.main()
