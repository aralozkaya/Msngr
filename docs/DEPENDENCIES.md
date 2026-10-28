# Third-party dependencies and security updates

Initialize the pinned dependencies after cloning or switching branches:

```sh
python tools/setup_dependencies.py
```

The helper uses a blobless, sparse Ruffle checkout containing only the lockfile,
manifest, version metadata, and licenses. Its upstream test SWFs are neither fetched
nor checked out, avoiding antivirus detections and long Windows paths. Ruffle's
`.gitmodules` update policy is `none` so ordinary recursive submodule updates skip it;
the helper handles its pinned revision explicitly. All CI workflows use the same helper.
Existing full checkouts are narrowed without restoring deleted SWF fixtures. Previously
downloaded Git objects may remain in those clones, but future filtered updates do not
fetch excluded fixtures. Do not force a full Ruffle checkout.

Use the committed gitlinks. `git submodule update --remote` changes the selected
versions and requires the same review and tests as a dependency update PR.
Submodules record commits; they do not check for updates or open PRs themselves.

## Libsiren

`third_party/libsiren` points directly to https://github.com/kakaroto/libsiren.
CMake copies its C sources and headers into each ABI's build directory and applies
`third_party/patches/libsiren-hardening.patch` before compiling `libmsngr_siren.so`.
The submodule remains pristine. Patch conflicts fail configuration, so an upstream
update cannot silently remove our allocation, bitstream, envelope, or noise-table
bounds checks. The JNI bridge serializes the codec's shared state and limits input
to 15 seconds of 16 kHz mono audio.

Dependabot checks the upstream branch daily and opens a PR when its selected commit
changes. Review and refresh the patch if needed, then run the real native codec tests.
Upstream's current commit dates from 2012; an absence of updates or reported
vulnerabilities does not establish safety. Continue maintaining these local fixes.
The upstream LGPL 2.1 license, authors, and complete source are retained in the
submodule; build with Android NDK 28.2.13676358 to rebuild or replace the shared library.

## Ruffle

`third_party/ruffle` pins the official source repository at a stable release.
Normal Android builds use the committed self-hosted JS/WASM runtime and need neither
Rust nor Node.js. The 0.7.1 runtime is rebuilt from that official source with
`third_party/patches/ruffle-security.patch`: quick-xml 0.41.0 fixes the two reported XML
denial-of-service issues, and zip 9.0.5 fixes the ZIP advisories. XML API adaptations
preserve Flash text/entity handling and retain the new namespace limit. The source
submodule remains pristine; the patch applies only to a separate production checkout.

`third_party/ruffle-runtime.json` records every bundled file's SHA-256, the source
commit, and hashes of the dependency inventory and source-build manifest. The latter
binds the patch, patched Cargo.lock, version seal, and tool versions. Official package
updates instead record npm SHA-512 integrity. Gradle verifies the selected provenance
and file set before building. Both WASM variants and loader chunks are kept.

The daily **Update Ruffle** workflow checks the latest stable release. It downloads
the package from the official npm registry, verifies its integrity and embedded
source commit/version, and updates the submodule, complete asset set, and manifest
together. It opens or refreshes `codex/update-ruffle`; unchanged releases produce no
new PR. While 0.7.1 remains the latest stable release, it preserves our patched bundle.
A newer official release replaces it in a PR and must pass the same tests and security
gate. The same operation is available locally with Python 3.11+ and Rust/Cargo 1.99.1:

```sh
python tools/update_ruffle.py --update
python tools/update_ruffle.py
```

To rebuild the patched runtime, install the versions in
`third_party/ruffle-build/manifest.json`, Java 21, npm, clang/libclang, and the Rust
`wasm32-unknown-unknown` target and `rust-src` component, then run:

```sh
python tools/ruffle_build.py --rebuild
```

The helper creates an ignored production checkout under `app/build`, excludes upstream
SWF fixtures before checkout, applies the pinned patch and lockfile, and builds both
WASM variants plus the self-hosted package. It prints the resulting directory and does
not replace the committed bundle. Install the result with
`python tools/ruffle_build.py --install-built PATH_TO_PRINTED_SOURCE_CHECKOUT`; it validates
the patch, lockfile, version seal, loader identity and complete bundle, then regenerates
the inventory and provenance hashes. Run the app's real playback tests before committing
a new source build. Build inputs are pinned; byte-for-byte reproduction across different
host toolchains has not been established.

Ruffle is deliberately excluded from Dependabot's generic submodule updater because
an arbitrary source commit can lack a matching published runtime. The stable-release
workflow handles that dependency. It explicitly dispatches Android CI and vulnerability
checks because PRs made with `GITHUB_TOKEN` do not trigger `pull_request` workflows.

## Vulnerability checks

**Dependency security** runs on PRs, merge queues, pushes to main, daily schedules,
and manual dispatch. It resolves debug/release/test and Gradle plugin/buildscript Maven dependencies,
records both submodule commits and Ruffle's npm version, and scans those exact identities
plus Ruffle's selected WASM dependencies with OSV-Scanner. Cargo regenerates that inventory
from the pinned source, target, features and lockfile and compares it with the committed
inventory; normal and build/proc-macro dependencies are included, while tests and desktop-only
dependencies are excluded. Unexpected dependency resolution or a stale inventory fails.
Scanner failures and vulnerability findings fail
the check. The inventory and JSON report are retained as workflow artifacts. The release
workflow builds and packages APKs in parallel with the scan and requires both jobs to pass
before attaching files to a release. Manual release-validation runs keep the same security
check and retain packaged APKs as artifacts without publishing them.

The only nonblocking advisory is `RUSTSEC-2026-0192`, explicitly classified by RustSec
as an informational unmaintained notice for `ttf-parser`. It stays visible as a maintenance
warning in the job summary. `.github/osv-scanner.toml` accepts that exact record; it does
not ignore the package or other advisories. Actual vulnerabilities continue to block.

OSV reports known published advisories; it does not audit native code, discover unknown
bugs, or establish that absence of a finding means safety. The original full upstream
Cargo.lock contains desktop/TLS dependencies that are not part of this Android runtime;
scanning that entire workspace had also reported unrelated findings.

Dependabot also opens Gradle update PRs daily and GitHub Actions update PRs weekly.
Gradle updates are grouped so related Android plugin, Kotlin compiler, wrapper and
library versions can be checked together.
**Submit dependency graph** submits resolved Gradle dependencies on main so GitHub can
create vulnerability alerts and supported security update PRs. Review transitive alerts
even when Dependabot cannot automatically update their parent dependency.

Before merging a library update, run unit tests, debug/release builds, and
`WinkInstrumentedTest` and `VoiceClipInstrumentedTest` on a device/emulator. The voice
test includes real encoding/decoding and malformed frames; the wink test checks actual
WASM playback and blocked external requests. Keep network/file/script-access restrictions
in the player when updating Ruffle.

For an applicable vulnerability, upgrade or backport a fix, verify it, and ship an updated
APK. If a fix is unavailable, disable the affected feature until it can be patched.
Already installed apps need an app update to receive fixes to these bundled libraries.

## GitHub configuration

These workflows and Dependabot configuration take effect on the default branch.
Enable the repository dependency graph, Dependabot alerts, and Dependabot security updates.
Allow GitHub Actions to create pull requests so the Ruffle workflow can use `GITHUB_TOKEN`;
no personal access token is required. Require **Check dependency vulnerabilities** alongside
**Build and test Android** in the branch ruleset. The checked-in ruleset definition includes
both checks; changing that JSON alone does not change the live GitHub ruleset.

## Automatic merging

The **Auto-merge dependency updates** workflow enables squash auto-merge for same-repository
Dependabot PRs and Ruffle PRs created by `github-actions[bot]` on `codex/update-ruffle`.
It waits for successful **Build and test Android** and **Check dependency vulnerabilities**
results from GitHub Actions on the PR's current commit. Missing, pending, failed, or stale
results do not qualify. GitHub enforces the same two checks in the live branch ruleset,
alongside the existing CodeQL, review, conversation-resolution, and up-to-date-branch rules.

The automation runs trusted default-branch code and reads PR metadata through GitHub's API;
it never executes PR code with its merge token. File allowlists restrict it to dependency
declarations, Gradle wrapper/lockfiles, Actions versions, the libsiren gitlink, and Ruffle's
source/runtime files. Forks, drafts, contributor-authored PRs, application code changes,
and submodule URL changes require manual handling. The final merge request is bound to the
commit whose checks passed, so a concurrent push cannot silently change the decision.

Repository auto-merge must be enabled. The existing approving-review requirement is retained;
automatic merging waits for that approval as well as the required checks. Failed security
checks, unresolved review threads, CodeQL blockers, conflicts, and outdated branches keep
the PR open. This policy becomes active after its workflow is merged to the default branch.
