"""Read original images used by the scene and display-picture catalogs."""
import re
import time
from urllib.parse import unquote, urljoin, urlsplit
from urllib.request import Request, urlopen

SOURCE = "https://windowswallpaper.miraheze.org/wiki/Windows_Live_Messenger"


def original_image(src):
    parts = urlsplit(urljoin(SOURCE, src))
    if parts.scheme != "https" or parts.hostname not in {
        "windowswallpaper.miraheze.org", "static.miraheze.org", "static.wikitide.net",
    }:
        raise ValueError(f"Unexpected wiki image host: {parts.netloc}")
    path = parts.path
    if "/thumb/" in path:
        path = path.replace("/thumb/", "/", 1).rsplit("/", 1)[0]
    url = parts._replace(path=path, fragment="").geturl()
    filename = unquote(path.rsplit("/", 1)[-1])
    stem, _, extension = filename.rpartition(".")
    identifier = re.sub(r"[^a-z0-9]+", "_", stem.lower()).strip("_")
    if not identifier or extension.lower() not in {"png", "apng", "gif", "jpg", "jpeg", "bmp"}:
        raise ValueError(f"Invalid wiki image filename: {filename}")
    return url, filename, identifier, f"{identifier}.{extension.lower()}"


def fetch(url):
    request = Request(url, headers={"User-Agent": "Mozilla/5.0 (Msngr artwork importer)"})
    for attempt in range(4):
        try:
            with urlopen(request, timeout=45) as response:
                return response.read()
        except OSError:
            if attempt == 3:
                raise
            time.sleep(attempt + 1)


def image_data(path, source):
    cached = path.is_file()
    data = path.read_bytes() if cached else fetch(source)
    if not data.startswith((b"\x89PNG\r\n\x1a\n", b"GIF87a", b"GIF89a", b"\xff\xd8\xff", b"BM")):
        raise ValueError(f"Unrecognized image data: {source}")
    if not cached:
        path.write_bytes(data)
    return data
