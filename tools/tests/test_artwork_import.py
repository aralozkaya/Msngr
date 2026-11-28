import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import download_scenes
from download_display_pictures import DisplayPictureParser
from messenger_artwork import original_image


class ArtworkImportTest(unittest.TestCase):
    def test_wiki_originals_keep_the_committed_filenames_and_ids(self):
        assets = download_scenes.DESTINATION.parent
        for folder, key in (("display_pictures", "pictures"), ("scenes", "scenes")):
            catalog = json.loads((assets / folder / "catalog.json").read_text(encoding="utf-8"))
            for item in catalog[key]:
                if "sourcePack" in item:
                    continue
                url, _, identifier, filename = original_image(item["sourceUrl"])
                self.assertEqual((url, identifier, filename), (item["sourceUrl"], item["id"], item["file"]))

    def test_thumbnail_links_preserve_the_original_extension_and_ignore_the_query_for_naming(self):
        src = "//static.wikitide.net/wiki/thumb/2/28/Photo%20One.GIF/100px-Photo_One.png?token=a"
        for parser, header, entries in ((DisplayPictureParser(), '<h2 id="Display_pictures">Pictures</h2>', "pictures"),
                                        (download_scenes.SceneParser(), "<h2>Scenes</h2>", "scenes")):
            parser.feed(f'{header}<h3>Group</h3><table><tr><td><img src="{src}"></td><td>Photo</td></tr></table>')
            row, = getattr(parser, entries)
            self.assertEqual(row["sourceUrl"], "https://static.wikitide.net/wiki/2/28/Photo%20One.GIF?token=a")
            self.assertEqual(row["file"], "photo_one.gif")

    def test_scene_pack_references_stay_in_the_extracted_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory).resolve()
            image = root / "image.png"
            image.write_bytes(b"image")
            self.assertEqual(download_scenes.pack_member(root, "image.png"), image)
            for name in ("../image.png", r"..\image.png", "C:/image.png", "/image.png", "image.png:extra"):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    download_scenes.pack_member(root, name)

    def test_generated_scene_names_are_literal_kotlin_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "java/org/aralozkaya/msngr/SceneCatalog.kt"
            output.parent.mkdir(parents=True)
            with patch.object(download_scenes, "DESTINATION", root / "assets/scenes"):
                download_scenes.write_kotlin_catalog([dict(id="one", name="Price $5", group="Unit",
                                                           file="one.png", seedColor="#ffffff", animated=False)])
            self.assertIn(r'Price \$5', output.read_text(encoding="utf-8"))
