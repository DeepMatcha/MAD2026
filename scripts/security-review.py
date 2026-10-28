#!/usr/bin/env python3
"""Local agent review gate. No model calls, credentials, or review records uploaded."""

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("sensitive", Path(__file__).with_name("check-sensitive.py"))
SCANNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCANNER)
MAX_COMMITS = 256
POLICY_FILES = ("scripts/check-sensitive.py", "scripts/security-review.py", ".githooks/pre-push")
ALLOWED = {"false_positive", "synthetic"}


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=True, separators=(",", ":")).encode()


def digest(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def git(root, *args):
    return SCANNER.git(root, *args)


def root_path():
    return Path(os.fsdecode(git(Path.cwd(), "rev-parse", "--show-toplevel")).strip())


def state_dir(root):
    path = Path(os.fsdecode(git(root, "rev-parse", "--git-path", "security-review")).strip())
    return path if path.is_absolute() else root / path


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_bytes(canonical(value) + b"\n")
    os.replace(temporary, path)


def is_oid(value):
    return bool(re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", value))


def zero(value):
    return not value.strip("0")


def parse_updates(lines):
    updates = []
    for line in lines.splitlines():
        fields = line.split()
        if len(fields) != 4 or not is_oid(fields[1]) or not is_oid(fields[3]):
            raise SCANNER.ScanError("Invalid pre-push update record.")
        updates.append(fields)
    return sorted(updates)


def advertised_heads(root, remote):
    data = git(root, "ls-remote", "--heads", "--", remote).decode("utf-8")
    return sorted({line.split()[0] for line in data.splitlines()})


def build_report(root, remote, destination, updates):
    findings, _, local_digest = SCANNER.collect_local()
    commits = set()
    tips = set()
    for _, tip, _, old in updates:
        if zero(tip):
            continue
        # Commit and annotated commit tags are supported; other object types fail closed.
        tip_commit = git(root, "rev-parse", "--verify", tip + "^{commit}").decode().strip()
        tips.add(tip_commit)
        bases = advertised_heads(root, destination) if zero(old) else [old]
        for base in bases:
            git(root, "cat-file", "-e", base + "^{commit}")
        revisions = git(root, "rev-list", f"--max-count={MAX_COMMITS + 1}", tip_commit,
                        *["^" + base for base in bases]).decode().splitlines()
        commits.update(revisions)
        if len(commits) > MAX_COMMITS:
            raise SCANNER.ScanError("Push exceeds 256 new commits; split the push for bounded review.")
    # Inspect every outgoing snapshot: a secret added then deleted must still be reviewed.
    seen = set()
    for commit in sorted(commits | tips):
        for entry in git(root, "ls-tree", "-r", "-z", commit).split(b"\0"):
            if not entry:
                continue
            metadata, raw_name = entry.split(b"\t", 1)
            mode, kind, oid = metadata.split()
            if kind != b"blob":
                raise SCANNER.ScanError("Submodule or unsupported object requires separate review.")
            name = os.fsdecode(raw_name)
            key = (name, oid)
            if key in seen:
                continue
            seen.add(key)
            source = "commit:" + commit
            if SCANNER.risky_path(name):
                findings.add((name, source, 0, "sensitive-path"))
            SCANNER.inspect_content(name, source, git(root, "cat-file", "blob", oid.decode()), findings)
    policy = {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in POLICY_FILES}
    candidates = []
    for name, source, line, rule in sorted(findings):
        candidates.append({"id": digest([name, source, line, rule]), "path": SCANNER.safe_path(name),
                           "source": source, "line": line, "rule": rule,
                           "context": "Candidate value and source text omitted; inspect local syntax without exposing secrets."})
    scope = {"version": 1, "repository": digest(str(root.resolve())),
             "destination": digest([remote, destination]), "updates": digest(updates),
             "commits": sorted(commits | tips), "local_state": local_digest, "policy": policy,
             "candidates": candidates}
    return {"scope": scope, "scope_id": digest(scope), "reviewer": "", "reason": "",
            "decisions": {candidate["id"]: {"decision": "uncertain", "reason": ""} for candidate in candidates}}


def validate_review(review, report):
    if review.get("scope_id") != report["scope_id"] or review.get("scope") != report["scope"]:
        return False
    if not isinstance(review.get("reviewer"), str) or not review["reviewer"].strip():
        return False
    if not isinstance(review.get("reason"), str) or not review["reason"].strip():
        return False
    decisions = review.get("decisions")
    if not isinstance(decisions, dict) or set(decisions) != set(report["decisions"]):
        return False
    for item in decisions.values():
        if not isinstance(item, dict) or item.get("decision") not in ALLOWED:
            return False
        if not isinstance(item.get("reason"), str) or not item["reason"].strip():
            return False
    return True


def gate(root, remote, destination, updates):
    if not updates:
        print("No ref updates to review.")
        return 0
    report = build_report(root, remote, destination, updates)
    state = state_dir(root)
    approved = state / "approved" / (report["scope_id"] + ".json")
    if approved.exists():
        review = json.loads(approved.read_text(encoding="utf-8"))
        if validate_review(review, report):
            print("Security review approved for this exact push and tracked state.")
            return 0
    pending = state / "pending.json"
    # Preserve unfinished decisions only for the identical report.
    if pending.exists():
        try:
            previous = json.loads(pending.read_text(encoding="utf-8"))
            if previous.get("scope_id") == report["scope_id"] and previous.get("scope") == report["scope"]:
                report = previous
        except (ValueError, AttributeError):
            pass
    write_json(pending, report)
    print(f"Agent review required: {len(report['scope']['candidates'])} candidate(s). Push blocked.")
    print("Review $(git rev-parse --git-path security-review)/pending.json, then run:")
    print("python3 scripts/security-review.py --approve")
    return 1


def approve(root):
    state = state_dir(root)
    review = json.loads((state / "pending.json").read_text(encoding="utf-8"))
    # Approval records are attestations by the local agent, not cryptographic identities.
    scope = review.get("scope", {})
    expected = {"scope": scope, "scope_id": digest(scope),
                "decisions": {item["id"]: {} for item in scope.get("candidates", [])}}
    if not scope or not validate_review(review, expected):
        raise SCANNER.ScanError("Set reviewer, overall reason, and a reasoned false_positive/synthetic decision for every candidate. secret/uncertain cannot be approved.")
    write_json(state / "approved" / (review["scope_id"] + ".json"), review)
    print("Local review recorded. The hook will rescan and invalidate it if the push or tracked state changed.")
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--pre-push", nargs=2, metavar=("REMOTE", "DESTINATION"))
    modes.add_argument("--prepare", nargs=2, metavar=("REMOTE", "REF"))
    modes.add_argument("--approve", action="store_true")
    args = parser.parse_args()
    try:
        root = root_path()
        if args.approve:
            return approve(root)
        if args.pre_push:
            return gate(root, *args.pre_push, parse_updates(sys.stdin.read()))
        remote, ref = args.prepare
        if not ref.startswith("refs/heads/"):
            raise SCANNER.ScanError("Prepare requires an explicit refs/heads/ branch.")
        destination = os.fsdecode(git(root, "remote", "get-url", "--push", remote)).strip()
        tip = git(root, "rev-parse", "--verify", ref).decode().strip()
        result = git(root, "ls-remote", "--refs", "--", destination, ref).decode().splitlines()
        old = result[0].split()[0] if result else "0" * len(tip)
        return gate(root, remote, destination, [[ref, tip, ref, old]])
    except (SCANNER.ScanError, OSError, ValueError, KeyError, TypeError, AttributeError):
        # Never print arbitrary exception strings, paths, source lines, or review text.
        print("Security review could not complete. Check Git objects/fetch, report schema, all decisions, and the 256-commit limit. Push blocked.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
