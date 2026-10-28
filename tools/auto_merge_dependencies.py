"""Queue trusted dependency PRs for squash merge after their exact-head checks pass.

Runs from the default branch in a privileged workflow. Never checks out or executes
pull-request code. GitHub continues to enforce reviews, CodeQL, and branch rules.
"""
import argparse
import json
import os
import re
import subprocess
from urllib.parse import urlencode

REQUIRED_CHECKS = ("Build and test Android", "Check dependency vulnerabilities")
ACTIONS_APP = 15368
BOTS = {"dependabot[bot]", "github-actions[bot]"}
GRADLE_FILES = {
    "build.gradle.kts", "app/build.gradle.kts", "settings.gradle.kts",
    "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties",
    "gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat",
    "gradle.lockfile", "buildscript-gradle.lockfile", "app/gradle.lockfile",
    "app/buildscript-gradle.lockfile", "third_party/libsiren",
}
RUFFLE_FILES = {"third_party/ruffle", "third_party/ruffle-runtime.json", "third_party/ruffle-build/inventory.json"}


class GitHub:
    def __init__(self, repository):
        self.repository = repository

    def api(self, path):
        result = subprocess.run(["gh", "api", path, "--method", "GET"],
                                check=True, capture_output=True, text=True)
        return json.loads(result.stdout)

    def merge(self, number, commit):
        subprocess.run(["gh", "pr", "merge", str(number), "--repo", self.repository,
                        "--auto", "--squash", "--match-head-commit", commit],
                       check=True, capture_output=True, text=True)


def kind(pr, repository):
    if pr.get("state") != "open" or pr.get("draft"):
        return None
    head, base = pr["head"], pr["base"]
    if (head.get("repo") or {}).get("full_name") != repository or base["repo"]["full_name"] != repository:
        return None
    if base["ref"] != base["repo"]["default_branch"]:
        return None
    author, branch = pr["user"]["login"], head["ref"]
    if author == "dependabot[bot]" and branch.startswith("dependabot/"):
        return "dependabot"
    if author == "github-actions[bot]" and branch == "codex/update-ruffle":
        return "ruffle"
    return None


def allowed_file(path, update_kind):
    if update_kind == "dependabot":
        return path in GRADLE_FILES or bool(re.fullmatch(r"\.github/workflows/[^/]+\.ya?ml", path))
    return path in RUFFLE_FILES or bool(re.fullmatch(
        r"app/src/main/assets/ruffle/(?:[a-zA-Z0-9_.-]+\.(?:js|wasm)|LICENSE_(?:MIT|APACHE))", path))


def checks_pass(checks):
    latest = {}
    for check in checks:
        if (check.get("app") or {}).get("id") != ACTIONS_APP or check["name"] not in REQUIRED_CHECKS:
            continue
        if check["name"] not in latest or check["id"] > latest[check["name"]]["id"]:
            latest[check["name"]] = check
    return all(name in latest and latest[name]["status"] == "completed" and
               latest[name]["conclusion"] == "success" for name in REQUIRED_CHECKS)


def process_pr(client, number, *, expected_head=None, dry_run=False):
    prefix = f"repos/{client.repository}"
    pr = client.api(f"{prefix}/pulls/{number}")
    update_kind = kind(pr, client.repository)
    if not update_kind or (expected_head and pr["head"]["sha"] != expected_head):
        print(f"PR #{number}: outside automatic dependency merge scope")
        return False
    if not 0 < pr["changed_files"] <= 100:
        print(f"PR #{number}: dependency update is too large or empty")
        return False
    files = client.api(f"{prefix}/pulls/{number}/files?per_page=100")
    if len(files) != pr["changed_files"] or not all(
        allowed_file(item["filename"], update_kind) and
        (not item.get("previous_filename") or allowed_file(item["previous_filename"], update_kind))
        for item in files
    ):
        print(f"PR #{number}: changes need manual review")
        return False
    commit = pr["head"]["sha"]
    checks = []
    for page in range(1, 6):
        result = client.api(f"{prefix}/commits/{commit}/check-runs?per_page=100&filter=latest&page={page}")
        checks.extend(result["check_runs"])
        if len(result["check_runs"]) < 100:
            break
    else:
        raise RuntimeError("Too many check runs to establish a merge decision")
    if not checks_pass(checks):
        print(f"PR #{number}: waiting for successful build and vulnerability checks")
        return False
    # Recheck after API pagination; the CLI also enforces the expected head.
    latest = client.api(f"{prefix}/pulls/{number}")
    if latest["head"]["sha"] != commit or kind(latest, client.repository) != update_kind:
        print(f"PR #{number}: changed while evaluating checks")
        return False
    if not dry_run:
        client.merge(number, commit)
    print(f"PR #{number}: {'would enable' if dry_run else 'enabled'} conditional squash auto-merge at {commit[:12]}")
    return True


def candidates(client, event):
    if "pull_request" in event:
        if (event.get("sender", {}).get("login") not in BOTS or
                event["sender"]["login"] != event["pull_request"]["user"]["login"]):
            return []
        return [(event["pull_request"]["number"], event["pull_request"]["head"]["sha"])]
    run = event.get("workflow_run", {})
    if (run.get("conclusion") != "success" or run.get("actor", {}).get("login") not in BOTS or
            (run.get("head_repository") or {}).get("full_name") != client.repository):
        return []
    branch = run.get("head_branch", "")
    if branch != "codex/update-ruffle" and not branch.startswith("dependabot/"):
        return []
    query = urlencode({"state": "open", "head": client.repository.split("/")[0] + ":" + branch, "per_page": 100})
    prs = client.api(f"repos/{client.repository}/pulls?{query}")
    return [(pr["number"], run["head_sha"]) for pr in prs]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    client = GitHub(os.environ["GITHUB_REPOSITORY"])
    with open(os.environ["GITHUB_EVENT_PATH"], encoding="utf-8") as stream:
        event = json.load(stream)
    for number, expected in candidates(client, event):
        process_pr(client, number, expected_head=expected, dry_run=args.dry_run)
