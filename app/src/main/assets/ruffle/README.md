# Ruffle playback runtime

This self-hosted runtime is built from the official Ruffle 0.7.1 source with the
reviewed XML/ZIP security patch recorded in `third_party/ruffle-build/manifest.json`.
The source submodule remains pinned to the official release. See `docs/DEPENDENCIES.md`
for the patch, locked dependencies, build tools, and verification procedure.
Both WebAssembly variants and their loader chunks are required by Ruffle feature
detection. Source maps are omitted. Licenses are included alongside the runtime.
No runtime CDN or update request is used.

The authoritative version, source commit and hashes are in `third_party/ruffle-runtime.json`.
The upstream source is pinned by the `third_party/ruffle` Git submodule. The daily
Update Ruffle workflow opens a PR updating that source and the matching runtime together.
See `docs/DEPENDENCIES.md` for verification, updates, and vulnerability checks.

To update locally: run `python tools/update_ruffle.py --update`, then run wink
instrumentation with network access blocked by the host. Retain the runtime licenses.
