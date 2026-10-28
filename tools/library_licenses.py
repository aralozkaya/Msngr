"""Generate offline library notices and copyleft source copies from exact build inputs.

Run export_library_inventory.gradle first. Ruffle metadata/source must come from
tools/ruffle_build.py's selected source checkout, including its locked dependencies.
Compiler/test implementation code and artwork are excluded; notices for the
matching Rust standard library are retained. Machine-private files are excluded.
"""
import argparse
import hashlib
import html
import io
import json
import re
import subprocess
import tomllib
import xml.etree.ElementTree as ET
import zipfile
from html.parser import HTMLParser
from pathlib import Path
from ruffle_build import packages_from_metadata

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app/src/main/assets/licenses"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def text(path):
    return Path(path).read_text(encoding="utf-8-sig", errors="replace").replace("\r\n", "\n").strip() + "\n"


def notice_files(directory):
    return sorted(p for p in directory.iterdir() if p.is_file() and
                  re.match(r"^(licen[sc]e|copying|copyright|notice|unlicense|authors)([._-]|$)", p.name, re.I)
                  and p.suffix.lower() not in (".json", ".toml"))


def zip_write(archive, name, data):
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    archive.writestr(info, data)


def generate(android, metadata, ruffle_source, rust_notices, output=ASSETS):
    runtime = json.loads((ROOT / "third_party/ruffle-runtime.json").read_text())
    lock = tomllib.loads((ROOT / "third_party/ruffle-build/Cargo.lock").read_text())
    if packages_from_metadata(metadata, lock) != json.loads((ROOT / "third_party/ruffle-build/inventory.json").read_text()):
        raise ValueError("Ruffle metadata differs from the pinned inventory")
    if subprocess.check_output(["git", "-C", str(ruffle_source), "rev-parse", "HEAD"], text=True).strip() != runtime["sourceCommit"]:
        raise ValueError("Ruffle source revision mismatch")
    packages = {p["id"]: p for p in metadata["packages"]}
    nodes = {n["id"]: n for n in metadata["resolve"]["nodes"]}
    selected = set()
    def visit(identifier):
        if identifier in selected:
            return
        selected.add(identifier)
        for dependency in nodes[identifier]["deps"]:
            if any(k["kind"] in (None, "build") for k in dependency["dep_kinds"]):
                visit(dependency["pkg"])
    visit(next(k for k, p in packages.items() if p["name"] == "ruffle_web"))

    documents, components = {}, []
    def document(value):
        key = sha(value.encode("utf-8"))
        documents[key] = value
        return key
    apache = document(text(ASSETS / "CabParser-Apache-2.0.txt"))
    mpl_path = ROOT / "third_party/licenses/MPL-2.0.txt"
    mpl = document(text(mpl_path))
    def component(name, license, source, notices, **extra):
        if not license or not notices:
            raise ValueError("Missing license/notice for " + name)
        components.append(dict(name=name, license=license, source=source,
                               notices=sorted(set(notices)), **extra))

    component("Msngr", "0BSD", "https://github.com/aralozkaya/Msngr", [document(text(ROOT / "COPYING"))])
    seen = set()
    for item in android:
        name = ":".join(item[k] for k in ("group", "name", "version"))
        if name in seen:
            continue
        seen.add(name)
        pom = ET.parse(item["pom"])
        declared = [e.findtext("{*}name", "") for e in pom.findall(".//{*}licenses/{*}license")]
        # The empty listenablefuture compatibility jar inherits Guava's Apache license.
        if not declared and item["group"] == "com.google.guava" and item["name"] == "listenablefuture":
            declared = ["Apache-2.0 (Guava compatibility artifact)"]
        if not declared or any("Apache" not in value and "MIT" not in value for value in declared):
            raise ValueError("Review Android license: " + name + " " + repr(declared))
        notices = [apache]
        def read_archive(data):
            with zipfile.ZipFile(data) as archive:
                for entry in sorted(archive.namelist()):
                    if re.match(r"^(license|notice|copying|copyright)", Path(entry).name, re.I):
                        notices.append(document(archive.read(entry).decode("utf-8", errors="replace").replace("\r\n", "\n").strip()+"\n"))
                    elif entry == "classes.jar":
                        read_archive(io.BytesIO(archive.read(entry)))
        read_archive(item["file"])
        component(name, "MIT AND Apache-2.0" if item["group"] == "com.materialkolor" else "Apache-2.0",
                  "https://maven.google.com/" if item["group"].startswith("androidx.") else "https://repo.maven.apache.org/maven2/",
                  notices, ecosystem="Maven", variants=sorted(item['variants']), artifactSha256=sha(Path(item["file"]).read_bytes()))

    source_buffer = io.BytesIO()
    with zipfile.ZipFile(source_buffer, "w") as sources:
        for identifier in sorted(selected):
            package = packages[identifier]
            directory = Path(package["manifest_path"]).parent
            license = package.get("license")
            notices = []
            files = notice_files(directory)
            if not files and not (package.get("source") or "").startswith("registry+"):
                for parent in directory.parents:
                    files = notice_files(parent)
                    if files or (parent / ".git").exists():
                        break
            if not license and package["name"].startswith("nihav_"):
                # All three packages inherit the repository's actual MIT COPYING.
                license = "MIT"
            for file in files:
                notices.append(document(text(file)))
            if package['name'] == 'gc-arena-derive':
                notices.append(document(text(ROOT / 'third_party/licenses/gc-arena-MIT.txt')))
            if package['name'] == 'realfft':
                notices.append(document(text(ROOT / 'third_party/licenses/realfft-MIT.txt')))
            if license == "MPL-2.0":
                notices.append(mpl)
                for file in sorted(directory.rglob("*")):
                    if file.is_file():
                        zip_write(sources, f"{package['name']}-{package['version']}/{file.relative_to(directory).as_posix()}", file.read_bytes())
                zip_write(sources, f"{package['name']}-{package['version']}/LICENSE-MPL-2.0.txt", mpl_path.read_bytes())
            if not notices and "Apache-2.0" in (license or ""):
                notices = [apache]
            source = package.get("repository") or package.get("homepage") or "https://crates.io/crates/" + package["name"]
            component(f"{package['name']} {package['version']}", license, source, notices,
                      ecosystem="Cargo", authors=package.get("authors", []))

        upstream = ROOT / "third_party/libsiren"
        for name in subprocess.check_output(["git", "-C", str(upstream), "ls-files"], text=True).splitlines():
            zip_write(sources, "libsiren/" + name, (upstream / name).read_bytes())
        for name in ("third_party/patches/libsiren-hardening.patch", "app/src/main/cpp/siren_jni.c", "app/src/main/cpp/CMakeLists.txt"):
            zip_write(sources, "msngr/" + name, (ROOT / name).read_bytes())
        zip_write(sources, "README.txt", b"libsiren contains the complete upstream source. Apply msngr/third_party/patches/libsiren-hardening.patch using the included CMakeLists.txt. The full application and build instructions accompany releases as Msngr-corresponding-source.zip. Symphonia packages are unmodified MPL-2.0 source copies.\n")

    node_modules = ruffle_source / "web/node_modules"
    for name in ("@fluent/bundle", "@fluent/langneg", "wasm-feature-detect", "tsx-dom", "webpack"):
        directory = node_modules / name
        package = json.loads((directory / "package.json").read_text())
        notices = [document(text(p)) for p in notice_files(directory)]
        if name == "tsx-dom":
            notices.append(document(text(ROOT / "third_party/licenses/tsx-dom-MIT.txt")))
        if package["license"] == "Apache-2.0":
            notices.append(apache)
        component(name + " " + package["version"], package["license"], package.get("homepage") or str(package.get("repository", "https://www.npmjs.com/package/" + name)), notices, ecosystem="npm")

    component("Ruffle web attribution collection", "MIT OR Apache-2.0", "https://github.com/ruffle-rs/ruffle/tree/" + runtime["sourceCommit"],
              [document(text(ruffle_source / "LICENSE.md")), document(text(ruffle_source / "web/LICENSE.md"))])
    class PlainNotices(HTMLParser):
        def __init__(self):
            super().__init__()
            self.parts, self.skip = [], False
        def handle_starttag(self, tag, attrs):
            if tag in ("style", "script"): self.skip = True
            elif tag in ("p", "pre", "h1", "h2", "h3", "li"): self.parts.append("\n")
        def handle_endtag(self, tag):
            if tag in ("style", "script"): self.skip = False
        def handle_data(self, data):
            if not self.skip: self.parts.append(data)
    rust = PlainNotices()
    rust.feed(text(rust_notices))
    version = json.loads((ROOT / 'third_party/ruffle-build/manifest.json').read_text())['tools']['rust']
    component('Rust standard library ' + version, 'MIT OR Apache-2.0 (bundled notices retain their terms)',
              'https://github.com/rust-lang/rust/tree/' + version + '/library', [document(''.join(rust.parts))])
    component("Dorkbox CabParser e81fc32929ab7ce3bb995f8c7279101365137d29", "Apache-2.0", "https://github.com/dorkbox/CabParser", [apache, document("Copyright 2012 Dorkbox LLC.\nModified for Msngr: package relocation, local interface and exception shims.\n")])
    component("libsiren c7724c03d4a6daf4d6dff9e917c14ce5071dce66", "LGPL-2.1-or-later", "https://github.com/kakaroto/libsiren", [document(text(upstream / "COPYING")), document(text(upstream / "AUTHORS"))])
    unicode = (ROOT / "app/src/main/java/org/aralozkaya/msngr/UnicodeEmoji.kt").read_text(encoding="utf-8").split("/*\n", 1)[1].split("*/", 1)[0]
    component("Unicode emoji property data 18.0.0", "Unicode-3.0", "https://www.unicode.org/Public/18.0.0/ucd/emoji/emoji-data.txt", [document(unicode)])

    output.mkdir(parents=True, exist_ok=True)
    (output / "library-sources.zip").write_bytes(source_buffer.getvalue())
    heading = """Msngr open-source licenses

Msngr's original code is licensed under 0BSD. Third-party code retains its own licenses.
This build uses libsiren under LGPL-2.1-or-later, including local allocation and bounds fixes.
Modification and reverse engineering to debug modified LGPL components are permitted.
Complete libsiren source, its patch, JNI bridge and build configuration, and unmodified
MPL-2.0 Symphonia source are included in assets/licenses/library-sources.zip in this APK.
The corresponding application source and relinking/build instructions accompany releases
as Msngr-corresponding-source.zip: https://github.com/aralozkaya/Msngr/releases
See THIRD_PARTY_NOTICES.md in the source repository for redistribution instructions.
Ruffle's listed graph conservatively includes its build/proc-macro dependencies.
Alternative licenses are alternatives, not additional restrictions; self_cell uses Apache-2.0.

"""
    body = heading
    for item in sorted(components, key=lambda x: x["name"].lower()):
        body += f"{item['name']}\nLicense: {item['license']}\nSource: {item['source']}\n"
        if item.get("authors"):
            body += "Authors: " + "; ".join(item["authors"]) + "\n"
        body += "Notices: " + ", ".join(item["notices"]) + "\n\n"
    for key, value in sorted(documents.items()):
        body += f"\n{'='*72}\nNotice {key}\n{'='*72}\n{value}"
    (output / "THIRD_PARTY_NOTICES.txt").write_text(body, encoding="utf-8", newline="\n")
    page = '<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Open-source licenses</title><style>body{font:16px system-ui;padding:16px;line-height:1.5}pre{white-space:pre-wrap;overflow-wrap:anywhere}li{margin-bottom:1em}@media(prefers-color-scheme:dark){body{background:#181818;color:#eee}a{color:#9cf}}</style></head><body><h1>Open-source licenses</h1><p><a href="https://github.com/aralozkaya/Msngr/releases">Corresponding source and releases</a></p><pre>' + html.escape(heading) + '</pre><h2>Libraries</h2><ul>'
    numbers = {key:index for index,key in enumerate(sorted(documents), 1)}
    for item in sorted(components, key=lambda x: x['name'].lower()):
        links = ', '.join(f'<a href="#notice-{key}">Notice {numbers[key]}</a>' for key in item['notices'])
        page += f'<li><strong>{html.escape(item["name"])}</strong><br>{html.escape(item["license"])}<br>{links}</li>'
    page += '</ul>'
    for key,value in sorted(documents.items()):
        page += f'<section id="notice-{key}"><h2>Notice {numbers[key]}</h2><pre>{html.escape(value)}</pre></section>'
    page += '</body></html>\n'
    (output / "index.html").write_text(page, encoding="utf-8", newline="\n")
    audit = dict(ruffleSourceCommit=runtime["sourceCommit"],
                 ruffleInventorySha256=sha((ROOT / "third_party/ruffle-build/inventory.json").read_bytes()),
                 libsirenCommit=subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip(),
                 libsirenPatchSha256=sha((ROOT / "third_party/patches/libsiren-hardening.patch").read_bytes()),
                 inputs={name:sha((ROOT/name).read_bytes().replace(b'\r\n', b'\n')) for name in (
                     'COPYING', 'app/src/main/cpp/CMakeLists.txt', 'app/src/main/cpp/siren_jni.c',
                     'app/src/main/java/org/aralozkaya/msngr/UnicodeEmoji.kt')},
                 components=sorted(components, key=lambda x: x["name"]),
                 files={p.name:sha(p.read_bytes()) for p in sorted(output.iterdir()) if p.name in ("library-sources.zip", "THIRD_PARTY_NOTICES.txt", "index.html")})
    (ROOT / "third_party/library-audit.json").write_text(json.dumps(audit, indent=2, ensure_ascii=False)+"\n", encoding="utf-8")
    print(f"Generated notices for {len(components)} components ({len(seen)} Android, {len(selected)} Rust), {len(documents)} notice texts.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-inventory", type=Path, default=ROOT / "app/build/reports/licenses/android-runtime.json")
    parser.add_argument("--ruffle-metadata", type=Path, required=True)
    parser.add_argument("--ruffle-source", type=Path, required=True)
    parser.add_argument("--rust-library-notices", type=Path, required=True, help="Matching Rust toolchain's share/doc/rust/COPYRIGHT-library.html")
    args = parser.parse_args()
    generate(json.loads(args.android_inventory.read_text()), json.loads(args.ruffle_metadata.read_text()), args.ruffle_source.resolve(), args.rust_library_notices)
