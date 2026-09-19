"""Synthetic regression fixtures for the action pin policy."""

import tempfile
import unittest
from pathlib import Path

from check_action_pins import check, immutable


class ActionPinsTests(unittest.TestCase):
    def test_reference_policy(self):
        for reference in (
            "owner/action@" + "a" * 40,
            "owner/repo/path@" + "b" * 40,
            "owner/repo/.github/workflows/build.yml@" + "c" * 40,
            "./.github/actions/local",
            "./.github/workflows/local.yml",
            "docker://alpine@sha256:" + "d" * 64,
        ):
            with self.subTest(reference=reference):
                self.assertTrue(immutable(reference))
        for reference in (
            "actions/checkout@v7",
            "owner/action@main",
            "owner/action@abcdef0",
            "owner/repo/.github/workflows/build.yml@v1",
            "docker://alpine:3",
            "docker://alpine@sha256:abc",
            "${{ inputs.action }}",
            None,
        ):
            with self.subTest(reference=reference):
                self.assertFalse(immutable(reference))

    def test_workflows_composites_and_containers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixtures = {
                ".github/workflows/build.yaml": "jobs:\n  build:\n    steps:\n      - {uses: 'owner/action@v1'}\n",
                ".github/workflows/reuse.yml": "jobs:\n  build:\n    uses: owner/repo/.github/workflows/build.yml@main\n",
                ".github/actions/nested/action.yaml": "runs:\n  using: composite\n  steps:\n    - uses: >-\n        owner/action@v2\n",
                "actions/container/action.yml": "runs:\n  using: docker\n  image: docker://alpine:3\n",
                "actions/local/action.yml": "runs:\n  using: docker\n  image: Dockerfile\n",
                ".github/codeql/config.yml": "queries:\n  - uses: security-and-quality\n",
                ".github/workflows/local.yml": "jobs:\n  build:\n    steps:\n      - uses: ./ci/my-action\n      - run: 'echo uses: owner/action@main'\n",
                "ci/my-action/action.yml": "runs:\n  using: composite\n  steps:\n    - uses: owner/action@main\n",
            }
            for name, contents in fixtures.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(contents, encoding="utf-8")
            failures = check(root)
            self.assertEqual(len(failures), 5, failures)
            for name in [*list(fixtures)[:4], "ci/my-action/action.yml"]:
                self.assertTrue(
                    any(str(root / name) in failure for failure in failures)
                )


if __name__ == "__main__":
    unittest.main()
