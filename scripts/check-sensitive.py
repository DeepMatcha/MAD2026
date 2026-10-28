#!/usr/bin/env python3
"""Scan Git index and tracked working files without printing matched values.

Exit codes: 0 = clean, 1 = findings, 2 = verification could not complete.
Only Python's standard library and the Git executable are required.
"""

import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


class ScanError(Exception):
    pass


PATTERNS = (
    ("private-key", re.compile(r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY-----")),
    ("aws-access-key", re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b")),
    ("github-token", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{22,})\b")),
    ("gitlab-token", re.compile(r"\bglpat-[A-Za-z0-9_-]{20,}\b")),
    ("google-api-key", re.compile(r"\bAIza[A-Za-z0-9_-]{35}\b")),
    ("slack-token", re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,}\b")),
    ("stripe-secret-key", re.compile(r"\b(?:sk|rk)_(?:live|test)_[A-Za-z0-9]{16,}\b")),
    ("openai-api-key", re.compile(r"\bsk-(?:(?:proj|svcacct)-[A-Za-z0-9_-]{20,}|[A-Za-z0-9]{48})\b")),
    ("jwt-token", re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")),
)

ASSIGNMENT = re.compile(
    r'''(?im)(?<![\w-])["']?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|'''
    r'''auth[_-]?token|token|password|passwd|client[_-]?secret|secret[_-]?key|'''
    r'''aws[_-]?secret[_-]?access[_-]?key|session[_-]?cookie|'''
    r'''_authToken|_auth)["']?\s*[:=]\s*'''
    r'''(?:"([^"\r\n]+)"|'([^'\r\n]+)'|([^\s\#,'";\}\)]+))'''
)
BEARER = re.compile(r"(?i)\b(?:authorization\s*[:=]\s*[\"']?|bearer\s+)(?:bearer\s+)?([A-Za-z0-9._~+/-]{12,}=*)")
CREDENTIAL_URL = re.compile(r"(?i)\b[a-z][a-z0-9+.-]*://[^\s/:@]+:([^\s/@]+)@")
EXAMPLE_ENV = {".env.example", ".env.sample", ".env.template", ".env.dist"}
PLACEHOLDERS = {
    "example", "sample", "placeholder", "changeme", "change-me", "replace-me",
    "redacted", "your-api-key", "your_api_key", "your-token", "your_token",
    "your-password", "your_password", "password", "not-a-secret", "none", "null",
    "true", "false",
}


def literal_secret(value):
    """Ignore short examples and common environment/config references."""
    value = value.strip()
    lowered = value.lower()
    if len(value) < 8 or lowered in PLACEHOLDERS:
        return False
    if re.fullmatch(r"[xX*._-]+", value) or re.fullmatch(r"<[^<>]+>", value):
        return False
    if value.startswith(("$", "{{", "%(")):
        return False
    if lowered.startswith(("process.env.", "os.environ", "os.getenv(", "getenv(", "system.getenv(", "env(", "config.", "settings.")):
        return False
    # Bare expressions such as get_token() are not literal credentials.
    if "(" in value or lowered in {"string", "str", "bytes", "optional[str]"}:
        return False
    return True


def risky_path(name):
    parts = [part.lower() for part in PurePosixPath(name).parts]
    base = parts[-1]
    if any(part == ".env" or (part.startswith(".env.") and part not in EXAMPLE_ENV) for part in parts):
        return True
    if base in {"id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", ".netrc", "_netrc", "google-services.json"}:
        return True
    if base.endswith((".pem", ".key", ".p12", ".pfx", ".jks", ".keystore")):
        return True
    if re.fullmatch(r"(?:credentials?|secrets?)(?:\.(?:json|ya?ml|toml|ini|cfg|txt))?", base):
        return True
    return bool(re.fullmatch(r"service[-_]account(?:[-_][^.]+)?\.json", base))


def git(root, *args):
    result = subprocess.run(["git", "-C", str(root), *args], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        # Git diagnostics may contain user-controlled paths or sensitive data.
        raise ScanError("Git could not read repository metadata or tracked content.")
    return result.stdout


def safe_path(name):
    # Do not expose even a path when its filename embeds a credential.
    if any(pattern.search(name) for _, pattern in PATTERNS):
        return "<redacted-path>"
    if any(literal_secret(next(v for v in match.groups() if v is not None)) for match in ASSIGNMENT.finditer(name)):
        return "<redacted-path>"
    if CREDENTIAL_URL.search(name) or BEARER.search(name):
        return "<redacted-path>"
    # Escape control characters so filenames cannot inject diagnostic lines.
    return ascii(name)


def inspect_content(name, source, data, findings):
    text = data.decode("utf-8", errors="replace")
    for rule, pattern in PATTERNS:
        for match in pattern.finditer(text):
            findings.add((name, source, text.count("\n", 0, match.start()) + 1, rule))
    for pattern, rule in ((ASSIGNMENT, "credential-assignment"), (BEARER, "authorization-token"), (CREDENTIAL_URL, "credential-url")):
        for match in pattern.finditer(text):
            value = next(group for group in match.groups() if group is not None)
            if literal_secret(value):
                findings.add((name, source, text.count("\n", 0, match.start()) + 1, rule))


def scan():
    root = Path(os.fsdecode(git(Path.cwd(), "rev-parse", "--show-toplevel")).strip())
    records = git(root, "ls-files", "--stage", "-z").split(b"\0")
    findings = set()
    blobs = {}
    count = 0
    for record in records:
        if not record:
            continue
        metadata, raw_name = record.split(b"\t", 1)
        mode, oid, stage = metadata.split()
        name = os.fsdecode(raw_name)
        if stage != b"0":
            raise ScanError("Resolve unmerged index entries before scanning.")
        if mode == b"160000":
            raise ScanError("Submodule content needs a separate security review; scanning could not complete.")
        if mode not in {b"100644", b"100755", b"120000"}:
            raise ScanError("Unsupported tracked entry; scanning could not complete.")
        if risky_path(name):
            findings.add((name, "tracked-path", 0, "sensitive-path"))
        if oid not in blobs:
            blobs[oid] = git(root, "cat-file", "blob", oid.decode("ascii"))
        inspect_content(name, "index", blobs[oid], findings)
        path = root / name
        # Never follow a tracked symlink to a private file outside the repository.
        # Reject symlinked parent directories for the same reason.
        if any(parent.is_symlink() for parent in path.parents if parent != root and root in parent.parents):
            raise ScanError("A tracked path has a symlinked parent directory.")
        if path.is_symlink():
            data = os.fsencode(os.readlink(path))
        elif path.exists():
            if not path.is_file():
                raise ScanError("A tracked working entry is not a regular file.")
            data = path.read_bytes()
        else:
            data = None
        if data is not None:
            inspect_content(name, "worktree", data, findings)
        count += 1
    if findings:
        for name, source, line, rule in sorted(findings):
            location = f":{line}" if line else ""
            print(f"BLOCK {safe_path(name)}{location} [{source}] {rule}")
        print(f"Security check failed: {len(findings)} finding(s). Matched values are not printed.")
        return 1
    print(f"Security check passed: {count} tracked file(s), index and working tree inspected.")
    return 0


def main():
    try:
        return scan()
    except (ScanError, OSError, ValueError) as error:
        # OSError messages may embed sensitive paths; emit only our safe diagnostics.
        message = str(error) if isinstance(error, ScanError) else "Could not read all tracked files or repository metadata."
        print(f"Security check could not complete: {message}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
