"""Make the scanner's artifact readable in the GitHub job summary."""
import json
import os
from pathlib import Path

report = Path("app/build/reports/dependencies/vulnerabilities.json")
if not report.is_file():
    raise SystemExit("OSV-Scanner did not produce a report; the scan is incomplete")
data = json.loads(report.read_text(encoding="utf-8"))
lines = ["## Dependency vulnerability scan", ""]
found = 0
for result in data.get("results", []):
    for entry in result.get("packages", []):
        advisories = entry.get("vulnerabilities", [])
        if not advisories:
            continue
        found += 1
        package = entry["package"]
        name = f"{package.get('name', '?')} {package.get('version', package.get('commit', ''))}"
        # Package identities are data, never workflow commands or raw HTML.
        name = name.replace("`", "").replace("\n", " ").replace("\r", " ").replace("<", "&lt;")
        lines.append(f"- **{name}**")
        for advisory in advisories:
            identifier = advisory["id"]
            if all(c.isalnum() or c in "-_" for c in identifier):
                lines.append(f"  - [{identifier}](https://osv.dev/vulnerability/{identifier})")
lines += ["", f"{found} package(s) have reported advisories.", "",
          "The Ruffle inventory covers the selected WASM target and its build dependencies."]
inventory = json.loads(Path("third_party/ruffle-build/inventory.json").read_text())
if any(p["package"].get("name") == "ttf-parser" for group in inventory["results"] for p in group["packages"]):
    lines += ["", "Maintenance warning: [RUSTSEC-2026-0192](https://rustsec.org/advisories/RUSTSEC-2026-0192.html) marks ttf-parser as unmaintained. This informational notice does not block releases; other advisories still do."]
summary = "\n".join(lines) + "\n"
if destination := os.environ.get("GITHUB_STEP_SUMMARY"):
    with Path(destination).open("a", encoding="utf-8") as stream:
        stream.write(summary)
else:
    print(summary)
