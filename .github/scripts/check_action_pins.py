"""Reject mutable action and reusable workflow references, without network access."""

import re
import sys
from pathlib import Path

import yaml


def immutable(reference):
    if not isinstance(reference, str):
        return False
    if reference.startswith("./"):
        return True
    if reference.startswith("docker://"):
        return (
            re.fullmatch(r"docker://[^\s@]+@sha256:[0-9a-f]{64}", reference) is not None
        )
    return re.fullmatch(r"[^\s@]+/[^\s@]+@[0-9a-f]{40}", reference) is not None


def references(document):
    # Inspect executable uses fields only; CodeQL query suites also use this key.
    for job in document.get("jobs", {}).values():
        if "uses" in job:
            yield job["uses"]
        for step in job.get("steps", []):
            if "uses" in step:
                yield step["uses"]
    runs = document.get("runs", {})
    for step in runs.get("steps", []):
        if "uses" in step:
            yield step["uses"]
    if runs.get("using") == "docker":
        image = runs.get("image", "")
        if image != "Dockerfile":
            yield image


def check(root):
    errors = []
    for path in sorted(root.rglob("*")):
        if path.suffix not in {".yml", ".yaml"}:
            continue
        if (
            path.name not in {"action.yml", "action.yaml"}
            and path.parent != root / ".github" / "workflows"
        ):
            continue
        try:
            document = yaml.safe_load(path.read_text(encoding="utf-8"))
            for reference in references(document):
                if not immutable(reference):
                    errors.append(f"{path}: mutable reference: {reference!r}")
        except (yaml.YAMLError, AttributeError, TypeError) as error:
            errors.append(f"{path}: invalid workflow/action: {error}")
    return errors


if __name__ == "__main__":
    failures = check(Path(sys.argv[1]) if len(sys.argv) > 1 else Path("."))
    print("\n".join(failures) if failures else "All action references are immutable.")
    sys.exit(bool(failures))
