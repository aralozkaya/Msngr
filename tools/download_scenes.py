"""Archive original Messenger scenes, preserving GIF/APNG bytes and provenance.

Run with Python 3 + Pillow + material-color-utilities. Empty wiki rows are recorded, never replaced by
thumbnails or guessed images. Only imports the Scenes section; conversation
backgrounds and dynamic backgrounds are excluded.
"""

import hashlib
import json
from pathlib import Path, PureWindowsPath
import re
import struct
import argparse
import subprocess
import tempfile
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from urllib.parse import quote, urljoin
from messenger_artwork import SOURCE, fetch, image_data, original_image

DESTINATION = Path(__file__).resolve().parents[1] / "app/src/main/assets/scenes"


def scene_display_name(name):
    """Prettify filename-style labels while preserving already readable scene names."""
    if "_" not in name:
        return name
    return " ".join(str(int(word)) if word.isdecimal() else word.capitalize()
                    for word in name.split("_") if word)


class SceneParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.section = self.group = ""
        self.heading = None
        self.row = self.cell = None
        self.images = []
        self.scenes = []
        self.unavailable = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag in ("h2", "h3"):
            self.heading = (tag, [])
        if self.section != "Scenes":
            return
        if tag == "tr":
            self.row, self.images = [], []
        elif tag == "td" and self.row is not None:
            self.cell = []
        elif tag == "img" and self.row is not None and attrs.get("src"):
            self.images.append(attrs["src"])

    def handle_data(self, data):
        if self.heading is not None:
            self.heading[1].append(data)
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if self.heading is not None and tag == self.heading[0]:
            value = " ".join("".join(self.heading[1]).split())
            if tag == "h2":
                self.section, self.group = value, ""
            else:
                self.group = value
            self.heading = None
        elif tag == "td" and self.cell is not None:
            self.row.append(" ".join("".join(self.cell).split()))
            self.cell = None
        elif tag == "tr" and self.row is not None:
            if len(self.row) > 1:
                if not self.images:
                    self.unavailable.append({"name": self.row[1], "group": self.group,
                                             "section": self.section, "sourceColumns": self.row[1:]})
                for src in self.images:
                    source, filename, identifier, local_name = original_image(src)
                    self.scenes.append({
                        "id": identifier, "name": self.row[1],
                        "group": self.group,
                        "section": self.section, "file": local_name,
                        "sourceUrl": source, "filePage": urljoin(SOURCE, "/wiki/File:" + quote(filename.replace(" ", "_"))),
                        "sourceColumns": self.row[1:],
                    })
            self.row = None


def png_frame_count(data):
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        return 1
    offset = 8
    while offset + 12 <= len(data):
        length = struct.unpack_from(">I", data, offset)[0]
        if data[offset + 4:offset + 8] == b"acTL":
            return struct.unpack_from(">I", data, offset + 8)[0]
        offset += 12 + length
    return 1


def main():
    from PIL import Image
    from material_color_utilities import prominent_colors_from_image

    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument("--scenex-pack", type=Path, help="Original 2011/2012 scenex.mct")
    arguments.add_argument("--seven-zip", default="7z", help="7-Zip executable for the original CAB packs")
    options = arguments.parse_args()
    parser = SceneParser()
    # The MediaWiki API exposes the same page without skin/navigation markup.
    page = json.loads(fetch(SOURCE.split("/wiki/")[0] +
                            "/w/api.php?action=parse&page=Windows_Live_Messenger&prop=text%7Crevid&format=json"))
    revision = int(page["parse"]["revid"])
    parser.feed(page["parse"]["text"]["*"])
    if not parser.scenes:
        raise ValueError("No downloadable artwork found")
    DESTINATION.mkdir(parents=True, exist_ok=True)
    if options.scenex_pack:
        import_scenex_pack(options.scenex_pack, options.seven_zip, parser)
        (DESTINATION / "scenex-source.json").write_text(json.dumps(
            [scene for scene in parser.scenes if "sourcePack" in scene], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    elif (DESTINATION / "scenex-source.json").exists():
        supplemental = [scene for scene in json.loads((DESTINATION / "scenex-source.json").read_text(encoding="utf-8"))
                        if scene.get("section") == "Scenes"]
        parser.scenes.extend(supplemental)
        restored = {(scene["name"], scene["group"]) for scene in supplemental}
        parser.unavailable = [row for row in parser.unavailable if (row["name"], row["group"]) not in restored]
    if len({s["id"] for s in parser.scenes}) != len(parser.scenes):
        raise ValueError("Duplicate scene IDs")
    for scene in parser.scenes:
        scene["name"] = scene_display_name(scene["name"])
        path = DESTINATION / scene["file"]
        if "sourcePack" in scene and not path.is_file():
            raise FileNotFoundError(f"Restore {path.name} with --scenex-pack")
        data = image_data(path, scene["sourceUrl"])
        with Image.open(path) as image:
            scene["width"], scene["height"] = image.size
            scene["frames"] = max(getattr(image, "n_frames", 1), png_frame_count(data))
            # Small static previews avoid decoding animations throughout the picker.
            image.seek(0)
            preview = image.convert("RGB")
            scene["seedColor"] = prominent_colors_from_image(preview)[0]
            preview.thumbnail((320, 160))
            preview.save(DESTINATION / f'{scene["id"]}_preview.webp', quality=88)
        scene["animated"] = scene["frames"] > 1
        scene["sha256"] = hashlib.sha256(data).hexdigest()
        scene["bytes"] = len(data)
        print(f'{scene["group"]}: {scene["name"]} ({scene["frames"]} frames, {len(data):,} bytes)', flush=True)
    (DESTINATION / "catalog.json").write_text(json.dumps({
        "source": SOURCE, "revision": revision,
        "scenes": parser.scenes, "unavailable": parser.unavailable,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_kotlin_catalog(parser.scenes)
    print(f'Downloaded {len(parser.scenes)} originals; {len(parser.unavailable)} rows have no source image.')


def write_kotlin_catalog(scenes):
    """Precomputed Material HCT seeds keep palette extraction off the UI thread."""
    rows = []
    def kotlin_string(value):
        if value is None:
            return "null"
        escaped = value.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$")
        return '"' + re.sub(r"[\x00-\x1f]", lambda match: f"\\u{ord(match[0]):04x}", escaped) + '"'

    for scene in scenes:
        args = ", ".join(kotlin_string(scene[key]) for key in ("id", "name", "group", "file"))
        rows.append(f'    MessengerScene({args}, 0xFF{scene["seedColor"][1:].upper()}.toInt(), '
                    f'{str(scene["animated"]).lower()}, {kotlin_string(scene.get("wideFile"))}),')
    target = DESTINATION.parents[1] / "java/org/aralozkaya/msngr/SceneCatalog.kt"
    target.write_text("// Generated by tools/download_scenes.py. Do not edit by hand.\n"
                      "package org.aralozkaya.msngr\n\n"
                      "internal val MessengerScenes = listOf(\n" + "\n".join(rows) + "\n)\n", encoding="utf-8")


def pack_member(directory, name):
    path = PureWindowsPath(name)
    if not path.parts or path.anchor or ".." in path.parts or ":" in name:
        raise ValueError(f"Invalid scene pack member: {name}")
    member = directory.joinpath(*path.parts)
    if member.resolve() != member or not member.is_file():
        raise ValueError(f"Missing or linked scene pack member: {name}")
    return member


def import_scenex_pack(pack, seven_zip, parser):
    """Recover the empty wiki rows from an original Microsoft scenex.mct CAB.

    The pack is extracted as data. No installer or Messenger code is executed.
    Both original narrow and wide variants are retained byte-for-byte.
    """
    from PIL import Image

    pack_hash = hashlib.sha256(pack.read_bytes()).hexdigest()
    archive_source = "https://archive.org/download/Ultimate-Windows-Live-Archive/2011%20-%201rewlive4%20-%20wlsetup-all.exe"
    ns = {"m": "http://messenger.msn.com/messengercontent/1.0"}
    scene_ns = "{http://messenger.msn.com/scenex/1.0}"
    with tempfile.TemporaryDirectory(prefix="msngr-scene-import-") as temporary:
        root = Path(temporary)
        subprocess.run([str(seven_zip), "x", str(pack.resolve()), f"-o{root}", "-y"], check=True, capture_output=True)
        items = ET.parse(pack_member(root, "content.xml")).getroot().findall("m:item", ns)
        for item in items:
            cab = pack_member(root, item.attrib["file"])
            scene_dir = Path(tempfile.mkdtemp(dir=root))
            subprocess.run([str(seven_zip), "x", str(cab), f"-o{scene_dir}", "-y"],
                           check=True, capture_output=True)
            variants = ET.parse(pack_member(scene_dir, "content.xml")).getroot().findall("m:item", ns)
            if len(variants) not in {1, 2}:
                raise ValueError(f"Expected one or two scene images in {cab.name}")
            name = item.attrib[scene_ns + "displayname"]
            identifier = "wlm_2011_" + re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
            scene = {"id": identifier, "name": name, "group": "Windows Live Messenger 2011 and 2012",
                     "section": "Scenes", "sourceUrl": archive_source,
                     "sourcePack": "MessengerLang.msi/scenex.mct/" + item.attrib["file"],
                     "sourcePackSha256": pack_hash,
                     "filePage": SOURCE + "#Windows_Live_Messenger_2011_and_2012",
                     "sourceColumns": [name, "Microsoft"], "variants": []}
            for index, variant in enumerate(variants):
                original = pack_member(scene_dir, variant.attrib["file"])
                filename = identifier + ("_wide" if index else "") + original.suffix.lower()
                data = original.read_bytes()
                with Image.open(original) as image:
                    width, height = image.size
                (DESTINATION / filename).write_bytes(data)
                scene["variants"].append({"file": filename, "originalFile": original.name,
                                          "width": width, "height": height, "bytes": len(data),
                                          "sha256": hashlib.sha256(data).hexdigest()})
                scene["wideFile" if index else "file"] = filename
            parser.scenes.append(scene)
            parser.unavailable = [row for row in parser.unavailable if not
                                  (row["name"] == name and row["group"] == scene["group"])]


if __name__ == "__main__":
    main()
