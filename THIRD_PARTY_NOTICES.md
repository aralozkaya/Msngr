# Third-party library licenses

Reviewed October 28, 2026. This audit covers **code libraries**, including the code
bundled inside the Ruffle JS/WASM runtime. Artwork, emoticons, scenes, pictures, winks,
sounds, and other media rights are outside this review. The project's [0BSD license](COPYING)
applies to its original code and documentation, not to third-party material or trademarks.

The review checked the resolved **release and debug** dependency graphs, published POMs, JAR/AAR
notices, pinned source headers and licenses, the selected Ruffle Cargo graph and actual
JavaScript imports. Test/compiler dependencies are not described as APK dependencies.
Ruffle's inventory conservatively includes its build and procedural-macro dependencies.

## Findings and corrections

No incompatible code-library license was identified in the reviewed inputs. The earlier
distribution had incomplete notice/source coverage: Ruffle's two top-level license files
did not cover all dependency notices, the Unicode notice was only in source, and libsiren
had no packaged corresponding-source/relinking path. These are addressed for builds made
from this revision. This is a technical license-compliance review, not a guarantee about
all legal rights or a patent clearance opinion.

| Component | License and handling |
| --- | --- |
| 104 distinct Android library versions (99 release; 102 debug) | Apache-2.0; MaterialKolor also contains MIT-licensed wrapper code. Published licenses and embedded notices are retained. Includes AndroidX/Compose, Kotlin, coroutines, Tink, Guava compatibility classes, Gson, MaterialKolor, Markdown Renderer, JetBrains Markdown, Poko and JSpecify. Debug-only Compose tooling and its separately resolved NavigationEvent versions are covered too. |
| libsiren, `c7724c03d4a6daf4d6dff9e917c14ce5071dce66` | Upstream headers allow LGPL 2 or later and `COPYING` supplies LGPL 2.1. Msngr distributes under LGPL-2.1-or-later. Original authors/notices and LGPL text are retained; modified files carry dated change notices. |
| Dorkbox CabParser, `e81fc32929ab7ce3bb995f8c7279101365137d29` | Apache-2.0. Three adapted LZX Java files retain upstream headers and now identify the package/interface changes. The license is shipped in the APK. |
| Ruffle 0.7.1, `89a7049965f3090e706265670df309a5128233ac` | MIT OR Apache-2.0; the MIT option permits the local XML/dependency security changes. Both original licenses, the upstream web attribution collection, and exact dependency notices are retained. |
| Ruffle Cargo graph, 313 packages | MIT, Apache-2.0, BSD, ISC, Zlib, Unicode-3.0 and compatible alternatives, plus the MPL packages below. The three NihAV crates inherit their Git repository's MIT `COPYING`, despite missing Cargo license fields. `self_cell` uses its Apache-2.0 option, not its alternative GPL option. Compound AND expressions retain both notices. |
| Symphonia 0.5.5 | MPL-2.0 for `symphonia`, `symphonia-core`, `symphonia-metadata`, `symphonia-bundle-mp3`, and `symphonia-codec-aac`. Their unmodified complete crate source copies and MPL text are shipped inside every APK. |
| Ruffle JavaScript dependencies | `@fluent/bundle` 0.19.1, `@fluent/langneg` 0.7.0 and `wasm-feature-detect` 1.9.0: Apache-2.0. `tsx-dom` 3.1.0 and the Webpack 5.111.1 runtime: MIT. Fluent/tsx-dom are included even where upstream calls them development dependencies, because their code is imported into the distributed bundle. |
| Rust standard library 1.99.1 | The matching toolchain's standard-library copyright/license collection is retained, including its dependency-specific notices. This collection covers more platforms than the shipped WASM build. |
| Unicode emoji property ranges, 18.0.0 | Unicode-3.0. The complete existing Unicode permission/copyright notice is now included in the binary's offline notices too. |

Exact identities, artifact hashes, notice references and packaged-file hashes are in
[`third_party/library-audit.json`](third_party/library-audit.json). Full notice texts are
in [`app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt`](app/src/main/assets/licenses/THIRD_PARTY_NOTICES.txt).
They are readable offline under **Settings → About → Open-source licenses**.

## Corresponding source and replacement libraries

Every APK contains `assets/licenses/library-sources.zip`, with complete upstream libsiren
source and authors/license, Msngr's native patch, JNI bridge and CMake configuration,
and the five complete unmodified MPL-2.0 Symphonia crate sources. The LGPL library and
Msngr changes to it retain LGPL-2.1-or-later terms. The bundled library can be modified,
rebuilt and relinked; modification and reverse engineering for debugging such changes
are permitted. There is no requirement to obtain Msngr's original signing key.

The release workflow also distributes **`Msngr-corresponding-source.zip` next to the
APKs**, in the same release and checksums file. It contains the complete tracked application
source, expanded libsiren source, patches and build scripts, the bundled runtime, and the
Ruffle metadata needed by normal APK builds. `SOURCE_BUILD.txt` explains rebuilding without
Git metadata. It does not require Ruffle test SWFs or a Rust toolchain for a normal APK build.
For a Ruffle runtime rebuild, use the pinned upstream source and instructions in
[dependency maintenance](docs/DEPENDENCIES.md).

Keep the source ZIP available alongside redistributed APKs. To create it from the exact
checked-out distribution revision, after adding the intended source files to Git:

```sh
python tools/package_corresponding_source.py --output app/build/Msngr-corresponding-source.zip
```

Source distribution uses only tracked paths and explicitly rejects credential files,
local configuration, private diagnostics, symlinks and paths outside the checkout.
Previously published APKs are not retroactively changed by this work. Any historical
release needing remediation must receive sources/notices produced from **its own exact
source and dependency revisions**, not this revision's archive.

## Updating the audit

The normal build's `verifyLibraryLicenses` task checks the actual release and debug artifact graphs,
artifact hashes, library revisions, native patch and Ruffle inventory against the reviewed
manifest, and checks notice/source bundle hashes. A changed dependency blocks an APK build
until its license and notices are reviewed. This applies to automated dependency updates too.

First export the exact resolved Android artifacts/POMs:

```sh
./gradlew -I tools/export_library_inventory.gradle :app:exportLibraryAudit
```

Use `tools/ruffle_build.py` to prepare the selected patched Ruffle source, then run
`cargo metadata --format-version 1 --filter-platform wasm32-unknown-unknown --features
ruffle_web/jpegxr --locked` there. Keep its JSON output and the matching Rust toolchain's
`share/doc/rust/COPYRIGHT-library.html`. With Ruffle's web `node_modules` installed from
its lockfile, regenerate:

```sh
python tools/library_licenses.py \
  --ruffle-metadata path/to/cargo-metadata.json \
  --ruffle-source path/to/selected-ruffle-source \
  --rust-library-notices path/to/COPYRIGHT-library.html
```

Review changed licenses, inherited notices and actual bundled JavaScript imports before
committing the result. The generator cannot establish authorship or grant missing rights.
`third_party/licenses/` preserves supplementary texts where published packages omit them:
MPL from Mozilla; tsx-dom's MIT notice from its upstream repository (Copyright 2018 Santo
Pfingsten); gc-arena's published MIT text; and the standard MIT text for realfft, whose
manifest declares MIT but whose 3.5.0 source omits a license file. No copyright owner was
invented for that missing notice; package authors are preserved from metadata.

## Authoritative terms

- [0BSD — Open Source Initiative](https://opensource.org/license/0bsd)
- [Apache License 2.0, redistribution section 4](https://www.apache.org/licenses/LICENSE-2.0)
- [libsiren's supplied LGPL 2.1 text](https://github.com/kakaroto/libsiren/blob/c7724c03d4a6daf4d6dff9e917c14ce5071dce66/COPYING), particularly sections 2, 4 and 6
- [MPL 2.0 terms](https://www.mozilla.org/en-US/MPL/2.0/), particularly section 3.2
- [Ruffle's pinned web license and notices](https://github.com/ruffle-rs/ruffle/blob/89a7049965f3090e706265670df309a5128233ac/web/LICENSE.md)
