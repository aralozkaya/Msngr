# Ruffle playback runtime

The selected runtime and its provenance are recorded in `third_party/ruffle-runtime.json`.
A `sourceBuild` entry selects official Ruffle source with the recorded security patch;
`package` and `integrity` entries select an official npm bundle. The source submodule
stays pinned to the matching official release. See `docs/DEPENDENCIES.md` for build
inputs, dependency inventory, update instructions, and verification.
Both WebAssembly variants and their loader chunks are required by Ruffle feature
detection. Source maps are omitted. Licenses are included alongside the runtime.
No runtime CDN or update request is used.

The authoritative version, source commit and hashes are in `third_party/ruffle-runtime.json`.
The upstream source is pinned by the `third_party/ruffle` Git submodule. The daily
Update Ruffle workflow opens a PR updating that source and the matching runtime together.
See `docs/DEPENDENCIES.md` for verification, updates, and vulnerability checks.

To update locally: run `python tools/update_ruffle.py --update`, then run wink
instrumentation with network access blocked by the host. Retain the runtime licenses.
