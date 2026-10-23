# Messenger scenes and appearance

In **Settings → Appearance → Color scheme**, select **Scenes** and choose a scene.
The scene picker fills the window behind the system bars, with its toolbar and list
inset for the status bar, navigation bar, and search keyboard.
The selected color option expands into a connected settings surface using Compose's
default `AnimatedVisibility` transitions, without custom animation specifications.
Scene artwork appears on the contact list's elevated profile card. **Show scene in chats**
controls whether it also appears on the conversation's top app bar; switching this off
keeps the scene's color palette throughout the app. **Dark mode tint** dims scene
images on both surfaces only in dark mode, with light text and matching status bar icons.
The conversation image and dark mode tint are enabled by default. The expanded controls
use plain switch rows on the option's shared surface; switching them does not recolor the rows.
Saved choices to disable either option are preserved.
Every scene generates a full Material 3 palette in light and dark modes,
including accents, containers, surfaces, outlines, errors, inverse and fixed colors.
The rest of the app and shortcut avatars use the same theme.

**Main color** offers the original client's range of color families plus a color wheel,
brightness slider, and `#RRGGBB` entry. Choosing a color in Scenes keeps the artwork and
replaces its palette seed. **Use scene colors** restores its own palette. Default mode
also offers these controls; Dynamic uses Android's wallpaper colors on Android 12+.
Preferences follow the existing account/service storage boundary.

Text placed directly on artwork has a fine white glyph outline, white glow and a small
shadow. Messenger Plus! spans, original emoticons, line wrapping and ellipsis remain intact.
Controls and menus keep their Material surfaces.

## Bundled collection

The [Windows Wallpaper Wiki](https://windowswallpaper.miraheze.org/wiki/Windows_Live_Messenger)
is the collection index. Assets are bundled for offline use:

| Collection | Selectable scenes | Original files |
| --- | ---: | ---: |
| Windows Live Messenger 2009 scenes | 22 | 22 |
| Windows Live Messenger 2011/2012 scenes | 29 | 58 (narrow + wide) |
| Total | 51 | 80 |

Only scenes are bundled. Conversation backgrounds, including dynamic backgrounds and
their still images, are excluded from both the picker and the importer's source selection.
Previously saved conversation-background selections fall back to Daisy Hill.

The wiki's 2011/2012 rows have no image links. These 29 scenes were recovered from the
original Microsoft `MessengerLang.msi → scenex.mct`, inside the archived 2011 installer:

- [Archived installer](https://archive.org/download/Ultimate-Windows-Live-Archive/2011%20-%201rewlive4%20-%20wlsetup-all.exe)
- Installer SHA-1: `e580ba931db8833f4c63f910ce8720739b95ccdc`
- Installer SHA-256: `4d27327d43251ba58d18cd8d91c4e24b46389c5c33b5dfee4aeda83735a19ee7`

Both original 400×110 and 1200×110 variants are preserved without conversion. Wide
headers choose the wider image at the original 553 dp threshold. The wiki's unnamed
2009 Default row has no image; Default color mode supplies the app's plain appearance.

The source's scene images are static. GIF and APNG decoding/playback is supported by the
scene renderer, with lifecycle-aware pausing; it is verified using animated test fixtures.

## Provenance and regeneration

`app/src/main/assets/scenes/catalog.json` records source URLs, version groups, original
filenames, dimensions, byte counts, SHA-256 hashes, animation detection and palette seeds.
`scenex-source.json` retains the supplemental archive provenance. Artwork remains the
property of its original owners; the wiki's text license does not relicense the images.
The source columns preserve credited artists and licensors.

`tools/download_scenes.py` uses Python 3, Pillow and `material-color-utilities`. It downloads
originals rather than wiki thumbnails, writes static WebP picker previews, extracts Material
HCT color seeds, and generates `SceneCatalog.kt`. Rerunning it preserves the supplemental
2011/2012 collection. To import an original pack again:

```text
python tools/download_scenes.py --scenex-pack path/to/scenex.mct --seven-zip path/to/7z.exe
```

The installer was unpacked as data with 7-Zip; no installation or Messenger execution was
needed. In the 2011 installer, the `MessengerLang.7z` cabinet resource contains
`MessengerLang.cab → MessengerLang.msi → scenexmct` (the MSI's internal name for `scenex.mct`).

## Verification

- JVM tests cover all scene seeds, Messenger swatches, black, white, gray and RGB seeds:
  paired text colors meet 4.5:1 contrast in both themes; fixed roles and surface hierarchy
  are also checked.
- Existing emoticon codec tests retain their 19 px padding behavior. Scenes decode at
  their original dimensions.
- Device tests exercise scene selection, custom color, reset and preference restoration;
  option expansion and collapse; both placement/tint switches and legacy selection fallback;
  render profile/chat headers in light and dark themes; and check GIF/APNG frame changes.
  They also verify that hiding chat artwork preserves the palette and profile card image,
  tint affects actual image pixels only in dark mode, and status bar icons stay readable.
