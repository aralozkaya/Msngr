"""Download the original Messenger display pictures (never wiki thumbnails).

Uses only Python's standard library. Run from any directory with Python 3.
The manifest records the source, version group, credits, and original SHA-256.
"""

import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
from urllib.parse import urljoin
from messenger_artwork import SOURCE, fetch, image_data, original_image


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
            self.image = attrs.get("src")

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
            if self.image and len(self.row) > 1:
                url, _, identifier, filename = original_image(self.image)
                self.pictures.append({
                    "id": identifier,
                    "name": self.row[1],
                    "group": self.group,
                    "file": filename,
                    "sourceUrl": url,
                    "filePage": self.file_page,
                    "sourceColumns": self.row[1:],
                })
            self.row = None


def main():
    parser = DisplayPictureParser()
    parser.feed(fetch(SOURCE).decode("utf-8"))
    if not parser.pictures or len({p["id"] for p in parser.pictures}) != len(parser.pictures):
        raise ValueError("Expected a nonempty display-picture catalog with unique IDs")
    DESTINATION.mkdir(parents=True, exist_ok=True)
    for picture in parser.pictures:
        path = DESTINATION / picture["file"]
        data = image_data(path, picture["sourceUrl"])
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
