"""Synthetic, isolated local Git tests. No network or actual credentials."""
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("review", ROOT / "scripts/security-review.py")
REVIEW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REVIEW)


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="mad2026-review-test-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.repo = self.base / "repo"
        self.repo.mkdir()
        self.remote = self.base / "remote.git"
        self.git("init", "-q", "-b", "review")
        self.git("config", "user.name", "Synthetic Reviewer")
        self.git("config", "user.email", "review@example.invalid")
        self.git("config", "core.autocrlf", "false")
        for name in REVIEW.POLICY_FILES:
            dest = self.repo / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / name, dest)
        (self.repo / ".gitignore").write_text("__pycache__/\n")
        self.fixture = self.repo / "fixture.txt"
        self.fixture.write_text("safe\n")
        self.commit()
        self.git("update-index", "--chmod=+x", ".githooks/pre-push")
        self.commit()
        self.git("init", "--bare", "-q", str(self.remote))
        self.git("remote", "add", "origin", str(self.remote))
        self.git("config", "core.hooksPath", ".githooks")

    def run_cmd(self, *args):
        return subprocess.run(args, cwd=self.repo, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    def git(self, *args):
        proc = self.run_cmd("git", *args)
        self.assertEqual(proc.returncode, 0, f"Git operation failed: {args[0]}")
        return proc.stdout

    def commit(self):
        self.git("add", ".")
        self.git("commit", "--allow-empty", "-qm", "synthetic test")

    def cli(self, *args):
        return self.run_cmd(sys.executable, "scripts/security-review.py", *args)

    def pending(self):
        return self.repo / ".git/security-review/pending.json"

    def prepare(self):
        result = self.cli("--prepare", "origin", "refs/heads/review")
        self.assertEqual(result.returncode, 1)
        return json.loads(self.pending().read_text())

    def approve(self, decision="synthetic"):
        report = json.loads(self.pending().read_text())
        report["reviewer"] = "isolated-test-agent"
        report["reason"] = "Generated fixture reviewed for this integration test only."
        for value in report["decisions"].values():
            value.update(decision=decision, reason="Known synthetic test content; no real credential.")
        self.pending().write_text(json.dumps(report))
        return self.cli("--approve")

    def push(self, target="review"):
        return self.run_cmd("git", "push", "origin", "refs/heads/review:refs/heads/" + target)

    def secret(self):
        return "ghp_" + "A1b2" * 9

    def test_clean_push_requires_review_then_succeeds(self):
        self.assertNotEqual(self.push().returncode, 0)
        self.assertEqual(self.approve().returncode, 0)
        self.assertEqual(self.push().returncode, 0)

    def test_head_secret_not_hidden_by_clean_index(self):
        self.fixture.write_text(self.secret())
        self.commit()
        self.fixture.write_text("safe\n")
        self.git("add", "fixture.txt")
        report = self.prepare()
        self.assertTrue(any(c["source"].startswith("commit:") and c["rule"] == "github-token" for c in report["scope"]["candidates"]))
        self.assertNotIn(self.secret(), self.pending().read_text())
        self.assertNotEqual(self.push().returncode, 0)

    def test_secret_deleted_in_later_commit_still_detected(self):
        self.fixture.write_text(self.secret())
        self.commit()
        self.fixture.write_text("safe\n")
        self.commit()
        report = self.prepare()
        self.assertTrue(any(c["rule"] == "github-token" for c in report["scope"]["candidates"]))

    def test_synthetic_candidate_approved_without_hook_bypass(self):
        self.fixture.write_text(self.secret())
        self.commit()
        self.prepare()
        self.assertEqual(self.approve().returncode, 0)
        result = self.push()
        self.assertEqual(result.returncode, 0)
        self.assertNotIn(self.secret().encode(), result.stdout + result.stderr)

    def test_secret_and_uncertain_cannot_be_approved(self):
        self.fixture.write_text(self.secret())
        self.commit()
        self.prepare()
        for decision in ("secret", "uncertain"):
            self.assertEqual(self.approve(decision).returncode, 2)
            self.assertNotEqual(self.push().returncode, 0)

    def test_missing_decision_rejected(self):
        self.fixture.write_text(self.secret())
        self.commit()
        self.prepare()
        self.approve()
        report = json.loads(self.pending().read_text())
        report["decisions"].pop(next(iter(report["decisions"])))
        self.pending().write_text(json.dumps(report))
        self.assertEqual(self.cli("--approve").returncode, 2)

    def test_worktree_change_invalidates_approval(self):
        self.prepare(); self.approve()
        self.fixture.write_text("different but benign\n")
        self.assertNotEqual(self.push().returncode, 0)

    def test_new_commit_invalidates_approval(self):
        self.prepare(); self.approve()
        self.commit()
        self.assertNotEqual(self.push().returncode, 0)

    def test_index_only_change_invalidates_approval(self):
        self.prepare(); self.approve()
        self.fixture.write_text("staged change\n")
        self.git("add", "fixture.txt")
        self.fixture.write_text("safe\n")
        self.assertNotEqual(self.push().returncode, 0)

    def test_different_destination_invalidates_approval(self):
        self.prepare(); self.approve()
        other = self.base / "other.git"
        self.git("init", "--bare", "-q", str(other))
        self.git("remote", "set-url", "origin", str(other))
        self.assertNotEqual(self.push().returncode, 0)

    def test_multiple_refs_and_non_checked_out_branch_scanned(self):
        self.prepare(); self.approve()
        self.assertEqual(self.push().returncode, 0)
        self.git("checkout", "-qb", "other")
        self.fixture.write_text(self.secret()); self.commit()
        self.git("checkout", "-q", "review")
        self.fixture.write_text("ordinary update\n"); self.commit()
        result = self.run_cmd("git", "push", "origin", "refs/heads/review:refs/heads/review", "refs/heads/other:refs/heads/other")
        self.assertNotEqual(result.returncode, 0)
        report = json.loads(self.pending().read_text())
        self.assertTrue(any(c["rule"] == "github-token" for c in report["scope"]["candidates"]))
        self.assertEqual(self.approve().returncode, 0)
        self.assertEqual(self.run_cmd("git", "push", "origin", "refs/heads/review:refs/heads/review", "refs/heads/other:refs/heads/other").returncode, 0)

    def test_other_target_ref_invalidates_approval(self):
        self.prepare(); self.approve()
        self.assertNotEqual(self.push("other").returncode, 0)

    def test_remote_advance_invalidates_approval(self):
        self.prepare(); self.approve()
        self.assertEqual(self.push().returncode, 0)
        self.fixture.write_text("new\n"); self.commit()
        self.prepare(); self.approve()
        older = self.git("rev-parse", "HEAD~2").decode().strip()
        self.git("--git-dir", str(self.remote), "update-ref", "refs/heads/review", older)
        self.assertNotEqual(self.push().returncode, 0)

    def test_corrupt_record_blocks_push(self):
        self.prepare(); self.approve()
        record = next((self.repo / ".git/security-review/approved").glob("*.json"))
        record.write_text("broken json")
        self.assertNotEqual(self.push().returncode, 0)

    def test_missing_scanner_blocks_push(self):
        self.prepare(); self.approve()
        (self.repo / "scripts/check-sensitive.py").unlink()
        self.assertNotEqual(self.push().returncode, 0)

    def test_quoted_password_special_characters_detected(self):
        for value in ("Q7m(9-AbC123456", "$Q7m9-AbC123456"):
            findings = set()
            REVIEW.SCANNER.inspect_content("fixture.txt", "test", ('password = "' + value + '"').encode(), findings)
            self.assertTrue(findings)

    def test_kotlin_false_positive_can_be_reviewed(self):
        self.fixture.write_text("val token = response.accessToken\nlateinit var password: EditText\n")
        self.commit()
        self.assertTrue(self.prepare()["scope"]["candidates"])
        self.assertEqual(self.approve("false_positive").returncode, 0)
        self.assertEqual(self.push().returncode, 0)


if __name__ == "__main__":
    unittest.main()
