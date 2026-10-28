import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import auto_merge_dependencies as policy

REPOSITORY = "owner/Msngr"
COMMIT = "a" * 40


def pull(author="dependabot[bot]", branch="dependabot/gradle/library-2.0"):
    return {"number": 5, "state": "open", "draft": False, "changed_files": 1,
            "user": {"login": author}, "head": {"sha": COMMIT, "ref": branch,
                "repo": {"full_name": REPOSITORY}}, "base": {"ref": "main",
                "repo": {"full_name": REPOSITORY, "default_branch": "main"}}}


def checks():
    return [{"id": i, "name": name, "app": {"id": policy.ACTIONS_APP},
             "status": "completed", "conclusion": "success"}
            for i, name in enumerate(policy.REQUIRED_CHECKS, start=1)]


class FakeGitHub:
    repository = REPOSITORY

    def __init__(self, pr=None, filename="gradle/libs.versions.toml"):
        self.pr = pr or pull()
        self.files = [{"filename": filename}]
        self.checks = checks()
        self.merge_calls = []
        self.reads = 0
        self.change_head = False

    def api(self, path):
        if "/files?" in path:
            return self.files
        if "/check-runs?" in path:
            return {"check_runs": self.checks}
        self.reads += 1
        result = copy.deepcopy(self.pr)
        if self.change_head and self.reads > 1:
            result["head"]["sha"] = "b" * 40
        return result

    def merge(self, number, commit):
        self.merge_calls.append((number, commit))


class AutomaticDependencyMergeTest(unittest.TestCase):
    def test_merges_trusted_update_only_after_both_checks_pass(self):
        client = FakeGitHub()
        self.assertTrue(policy.process_pr(client, 5, expected_head=COMMIT))
        self.assertEqual(client.merge_calls, [(5, COMMIT)])

    def test_missing_failed_pending_or_spoofed_checks_block_merging(self):
        for mode in ("missing", "failure", "pending", "other-app"):
            with self.subTest(mode=mode):
                client = FakeGitHub()
                if mode == "missing":
                    client.checks.pop()
                elif mode == "failure":
                    client.checks[-1]["conclusion"] = "failure"
                elif mode == "pending":
                    client.checks[-1]["status"] = "in_progress"
                else:
                    client.checks[-1]["app"]["id"] = 999
                self.assertFalse(policy.process_pr(client, 5))
                self.assertFalse(client.merge_calls)

    def test_newer_failed_rerun_overrides_old_success(self):
        client = FakeGitHub()
        failure = dict(client.checks[-1], id=100, conclusion="failure")
        client.checks.insert(0, failure)
        self.assertFalse(policy.process_pr(client, 5))
        self.assertFalse(client.merge_calls)

    def test_drafts_human_authors_forks_and_nondefault_bases_are_excluded(self):
        for mode in ("draft", "human", "fork", "base"):
            with self.subTest(mode=mode):
                pr = pull()
                if mode == "draft":
                    pr["draft"] = True
                elif mode == "human":
                    pr["user"]["login"] = "contributor"
                elif mode == "fork":
                    pr["head"]["repo"]["full_name"] = "someone/fork"
                else:
                    pr["base"]["ref"] = "development"
                client = FakeGitHub(pr)
                self.assertFalse(policy.process_pr(client, 5))
                self.assertFalse(client.merge_calls)

    def test_app_code_and_submodule_url_changes_need_manual_review(self):
        for filename in ("app/src/main/java/App.kt", ".gitmodules", ".github/scripts/inject.py"):
            client = FakeGitHub(filename=filename)
            self.assertFalse(policy.process_pr(client, 5))
            self.assertFalse(client.merge_calls)

    def test_ruffle_scope_allows_only_runtime_and_source_updates(self):
        pr = pull("github-actions[bot]", "codex/update-ruffle")
        client = FakeGitHub(pr, filename="app/src/main/assets/ruffle/new.wasm")
        self.assertTrue(policy.process_pr(client, 5))
        client = FakeGitHub(pr, filename=".github/workflows/auto-merge-dependencies.yml")
        self.assertFalse(policy.process_pr(client, 5))
        self.assertFalse(client.merge_calls)

    def test_head_races_and_stale_workflow_runs_never_merge(self):
        client = FakeGitHub()
        client.change_head = True
        self.assertFalse(policy.process_pr(client, 5))
        self.assertFalse(client.merge_calls)
        client = FakeGitHub()
        self.assertFalse(policy.process_pr(client, 5, expected_head="b" * 40))
        self.assertFalse(client.merge_calls)

    def test_dry_run_never_merges(self):
        client = FakeGitHub()
        self.assertTrue(policy.process_pr(client, 5, dry_run=True))
        self.assertFalse(client.merge_calls)

    def test_untrusted_or_unsuccessful_workflow_runs_are_ignored(self):
        for actor, conclusion in (("contributor", "success"), ("dependabot[bot]", "failure")):
            event = {"workflow_run": {"actor": {"login": actor}, "conclusion": conclusion,
                "head_repository": {"full_name": REPOSITORY}, "head_branch": "dependabot/gradle/library"}}
            self.assertEqual(policy.candidates(FakeGitHub(), event), [])

    def test_human_changes_to_bot_prs_are_not_automatically_armed(self):
        event = {"sender": {"login": "contributor"}, "pull_request": pull()}
        self.assertEqual(policy.candidates(FakeGitHub(), event), [])

    def test_successful_bot_workflow_matches_pr_by_branch_and_commit(self):
        class CandidateGitHub(FakeGitHub):
            def api(self, path):
                self.asserted_path = path
                return [{"number": 5}]
        client = CandidateGitHub()
        event = {"workflow_run": {"actor": {"login": "github-actions[bot]"},
            "conclusion": "success", "head_repository": {"full_name": REPOSITORY},
            "head_branch": "codex/update-ruffle", "head_sha": COMMIT}}
        self.assertEqual(policy.candidates(client, event), [(5, COMMIT)])
        self.assertIn("codex%2Fupdate-ruffle", client.asserted_path)


if __name__ == "__main__":
    unittest.main()
