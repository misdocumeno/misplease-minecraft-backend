"""Deploy-pin rules, tested against throwaway local git repos.

These are the rules that broke prod on 2026-11-19: a pin-only push (CI/agent/obs)
has no image tags on purpose, and `wanted` can jump over an image-affecting commit
whose build is still running. `image_fallback_sha` may only walk back when the
tail is pin-only, otherwise waiting is correct. No network, no docker.
"""

from __future__ import annotations

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
AGENT_PATH = HERE.parent / "agent.py"


def _git(*args: str, cwd: Path) -> str:
    return subprocess.run(
        ["git", *args],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()


def _init_repo(root: Path) -> tuple[Path, Path]:
    bare = root / "origin.git"
    work = root / "work"
    _git("init", "--bare", "-b", "main", str(bare), cwd=root)
    _git("init", "-b", "main", str(work), cwd=root)
    _git("remote", "add", "origin", str(bare), cwd=work)
    _git("config", "user.email", "test@example.invalid", cwd=work)
    _git("config", "user.name", "test", cwd=work)
    return bare, work


def _commit(work: Path, files: dict[str, str], message: str) -> str:
    for rel, text in files.items():
        path = work / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text + message, encoding="utf-8")
    _git("add", "-A", cwd=work)
    _git("commit", "-qm", message, cwd=work)
    _git("push", "-q", "origin", "main", cwd=work)
    return _git("rev-parse", "HEAD", cwd=work)


def _load_agent(root: Path):
    # Keep `git config --global` (safe.directory) out of the developer's config.
    os.environ["GIT_CONFIG_GLOBAL"] = str(root / "gitconfig")
    os.environ.setdefault("DEPLOY_STATE_FILE", str(root / "state.json"))
    spec = importlib.util.spec_from_file_location(f"deploy_agent_{id(root)}", AGENT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    module.ROOT = root
    return module


class ImageFallbackTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory(prefix="deploy-pin-")
        self.root = Path(self._tmp.name)
        _, self.work = _init_repo(self.root)
        self.agent = _load_agent(self.work)

    def tearDown(self):
        self._tmp.cleanup()

    def tag_only(self, tagged: set[str]) -> None:
        self.agent.missing_image_tags = (
            lambda sha: [] if sha in tagged else ["ghcr.io/example/velocity:missing"]
        )

    def test_pin_only_tail_applies_tagged_ancestor(self):
        image = _commit(
            self.work, {"infra/velocity/config/velocity.toml": "a"}, "image change"
        )
        _commit(self.work, {".github/workflows/deploy.yml": "b"}, "ci only")
        pin = _commit(self.work, {"tools/deploy/agent.py": "c"}, "agent only")
        self.tag_only({image})

        self.assertEqual(self.agent.image_fallback_sha(pin), image)

    def test_tagged_sha_has_no_fallback(self):
        image = _commit(
            self.work, {"infra/velocity/config/velocity.toml": "a"}, "image change"
        )
        self.tag_only({image})

        self.assertIsNone(self.agent.image_fallback_sha(image))

    def test_image_affecting_tail_waits(self):
        # The ancestor's images exist, but the tail contains a commit that *must*
        # produce its own image; its build failed or is still running.
        image = _commit(
            self.work, {"infra/velocity/config/velocity.toml": "a"}, "image change"
        )
        _commit(
            self.work, {"infra/minecraft/main/plugins.yml": "b"}, "image change, no tags"
        )
        pin = _commit(self.work, {"tools/deploy/agent.py": "c"}, "agent only")
        self.tag_only({image})

        self.assertIsNone(self.agent.image_fallback_sha(pin))

    def test_unknown_sha_is_not_guessed(self):
        # Never reachable from origin/main (e.g. a local, unpushed sha).
        _commit(self.work, {"infra/velocity/config/velocity.toml": "a"}, "image change")
        self.tag_only(set())

        self.assertIsNone(self.agent.image_fallback_sha("f" * 40))

    def test_deeper_pin_only_tail(self):
        image = _commit(
            self.work, {"infra/velocity/config/velocity.toml": "a"}, "image change"
        )
        for i, path in enumerate(
            [
                ".github/workflows/images.yml",
                "tools/hibernate/agent.py",
                "tools/obs/alloy/config.alloy",
                "*.md",
            ]
        ):
            _commit(self.work, {path.replace("*", "docs"): str(i)}, f"pin only {i}")
        pin = _commit(self.work, {"tools/deploy/agent.py": "z"}, "agent only")
        self.tag_only({image})

        self.assertEqual(self.agent.image_fallback_sha(pin), image)


if __name__ == "__main__":
    unittest.main()
