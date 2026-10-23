"""Download the original Messenger display pictures (never wiki thumbnails).

Uses only Python's standard library. Run from any directory with Python 3.
The manifest records the source, version group, credits, and original SHA-256.
"""

import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import time
from urllib.parse import unquote, urljoin
from urllib.request import Request, urlopen


SOURCE = "https://windowswallpaper.miraheze.org/wiki/Windows_Live_Messenger"
DESTINATION = Path(__file__).resolve().parents[1] / "app/src/main/assets/display_pictures"


class DisplayPictureParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.active = False
        self.heading = None
        self.group = ""
        self.row = None
        self.cell = None
        self.file_page = None
        self.image = None
        self.pictures = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "h2":
            self.active = attrs.get("id") == "Display_pictures"
        if not self.active:
            return
        if tag == "div" and "navbox-styles" in attrs.get("class", ""):
            self.active = False
        elif tag == "h3":
            self.heading = []
        elif tag == "tr":
            self.row, self.file_page, self.image = [], None, None
        elif tag == "td" and self.row is not None:
            self.cell = []
        elif tag == "a" and self.row is not None and attrs.get("href", "").startswith("/wiki/File:"):
            self.file_page = urljoin(SOURCE, attrs["href"])
        elif tag == "img" and self.row is not None:
            source = urljoin(SOURCE, attrs["src"])
            if "/thumb/" in source:
                source = source.replace("/thumb/", "/", 1).rsplit("/", 1)[0]
            self.image = source

    def handle_data(self, data):
        if not self.active:
            return
        if self.heading is not None:
            self.heading.append(data)
        if self.cell is not None:
            self.cell.append(data)

    def handle_endtag(self, tag):
        if not self.active:
            return
        if tag == "h3" and self.heading is not None:
            self.group = " ".join("".join(self.heading).split())
            self.heading = None
        elif tag == "td" and self.cell is not None:
            self.row.append(" ".join("".join(self.cell).split()))
            self.cell = None
        elif tag == "tr" and self.row is not None:
            if self.image:
                filename = unquote(self.image.rsplit("/", 1)[1])
                stem, extension = filename.rsplit(".", 1)
                identifier = re.sub(r"[^a-z0-9]+", "_", stem.lower()).strip("_")
                self.pictures.append({
                    "id": identifier,
                    "name": self.row[1],
                    "group": self.group,
                    "file": f"{identifier}.{extension.lower()}",
                    "sourceUrl": self.image,
                    "filePage": self.file_page,
                    "sourceColumns": self.row[1:],
                })
            self.row = None


def fetch(url):
    for attempt in range(4):
        try:
            with urlopen(Request(url, headers={"User-Agent": "Mozilla/5.0 (Msngr display picture importer)"}), timeout=45) as response:
                return response.read()
        except Exception:
            if attempt == 3:
                raise
            time.sleep(attempt + 1)


def main():
    parser = DisplayPictureParser()
    parser.feed(fetch(SOURCE).decode("utf-8"))
    assert parser.pictures, "No display pictures found; the wiki structure may have changed."
    assert len({p["id"] for p in parser.pictures}) == len(parser.pictures), "Duplicate IDs"
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for picture in parser.pictures:
        path = DESTINATION / picture["file"]
        data = path.read_bytes() if path.exists() else fetch(picture["sourceUrl"])
        assert data.startswith((b"\x89PNG\r\n\x1a\n", b"GIF87a", b"GIF89a", b"\xff\xd8\xff", b"BM")), picture["sourceUrl"]
        path.write_bytes(data)
        picture["sha256"] = hashlib.sha256(data).hexdigest()
        picture["bytes"] = len(data)
        # APNG uses a PNG signature, even if its filename ends in .png.
        picture["animated"] = data.startswith((b"GIF87a", b"GIF89a")) or b"acTL" in data
        print(f'{picture["group"]}: {picture["name"]} ({len(data):,} bytes' + (", animated)" if picture["animated"] else ")"), flush=True)
    (DESTINATION / "catalog.json").write_text(json.dumps({
        "source": SOURCE + "#Display_pictures",
        "pictures": parser.pictures,
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Downloaded {len(parser.pictures)} original display pictures.")


if __name__ == "__main__":
    main()
