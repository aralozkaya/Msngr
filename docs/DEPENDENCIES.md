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
The APK uses the matching official `@ruffle-rs/ruffle` prebuilt self-hosted runtime,
so normal Android builds do not need Rust, Node.js, or a runtime network download.
`third_party/ruffle-runtime.json` records the source commit, package SHA-512 integrity,
and SHA-256 of every bundled JS/WASM/license file. Gradle verifies the source commit,
file set, and hashes before building. Both WASM variants and loader chunks are kept.

The daily **Update Ruffle** workflow checks the latest stable release. It downloads
the package from the official npm registry, verifies its integrity and embedded
source commit/version, and updates the submodule, complete asset set, and manifest
together. It opens or refreshes `codex/update-ruffle`; unchanged releases produce no
new PR. The same operation is available locally with Python 3.11+:

```sh
python tools/update_ruffle.py --update
python tools/update_ruffle.py
```

Ruffle is deliberately excluded from Dependabot's generic submodule updater because
an arbitrary source commit can lack a matching published runtime. The stable-release
workflow handles that dependency. It explicitly dispatches Android CI and vulnerability
checks because PRs made with `GITHUB_TOKEN` do not trigger `pull_request` workflows.

## Vulnerability checks

**Dependency security** runs on PRs, merge queues, pushes to main, daily schedules,
and manual dispatch. It resolves debug/release/test Maven dependencies through Gradle,
records both submodule commits and Ruffle's npm version, and scans those exact identities
plus the pinned Ruffle `Cargo.lock` with OSV-Scanner. Scanner failures and findings fail
the check. The inventory and JSON report are retained as workflow artifacts. The release
workflow builds and packages APKs in parallel with the scan and requires both jobs to pass
before attaching files to a release. Manual release-validation runs keep the same security
check and retain packaged APKs as artifacts without publishing them.

The Rust lockfile includes upstream desktop/build dependencies as well as web dependencies.
Treat those findings as requiring investigation: determine whether a vulnerable package
is compiled into the bundled WASM before assessing app impact. Do not silently suppress
findings merely to make CI green. OSV reports known advisories; it does not audit native
code, discover unknown bugs, or prove the prebuilt WASM was produced from its claimed source.

The initial scan on October 27, 2026 reported advisory records in 11 crates in Ruffle
0.7.1's upstream lockfile, including `quick-xml` (used by Ruffle core) and several
desktop/TLS dependencies. The 160 resolved Android Maven dependencies and the direct
bundled-library identities had no reported findings in that scan. No advisory exclusions
have been added. The strict scan currently fails until these upstream findings are
remediated or their applicability is reviewed and any exceptions are narrowly documented.

Dependabot also opens Gradle update PRs daily and GitHub Actions update PRs weekly.
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
