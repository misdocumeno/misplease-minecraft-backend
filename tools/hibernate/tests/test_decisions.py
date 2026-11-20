import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from decisions import freeze_blocker, should_reclaim, tick_due  # noqa: E402


class FreezeBlockerTest(unittest.TestCase):
    def test_all_clear(self):
        self.assertIsNone(
            freeze_blocker(
                deploy_pending=False,
                maintenance_on=False,
                backup_running=False,
                wake_requested=False,
                has_rcon_password=True,
            )
        )

    def test_each_refusal(self):
        base = dict(
            deploy_pending=False,
            maintenance_on=False,
            backup_running=False,
            wake_requested=False,
            has_rcon_password=True,
        )
        for key, expected in [
            ("deploy_pending", "deploy pending"),
            ("maintenance_on", "maintenance on"),
            ("backup_running", "backup running"),
            ("wake_requested", "wake requested"),
        ]:
            with self.subTest(key=key):
                self.assertEqual(freeze_blocker(**{**base, key: True}), expected)

    def test_no_rcon_password(self):
        self.assertEqual(
            freeze_blocker(
                deploy_pending=False,
                maintenance_on=False,
                backup_running=False,
                wake_requested=False,
                has_rcon_password=False,
            ),
            "no RCON password",
        )

    def test_deploy_wins_over_everything(self):
        self.assertEqual(
            freeze_blocker(
                deploy_pending=True,
                maintenance_on=True,
                backup_running=True,
                wake_requested=True,
                has_rcon_password=False,
            ),
            "deploy pending",
        )


class ReclaimTest(unittest.TestCase):
    def test_above_floor_reclaims(self):
        self.assertTrue(
            should_reclaim(
                memory_bytes=2484 * 1024 * 1024,
                floor_bytes=192 * 1024 * 1024,
                deploy_pending=False,
                backup_running=False,
            )
        )

    def test_at_or_below_floor_is_done(self):
        for memory in (192, 121):
            with self.subTest(memory=memory):
                self.assertFalse(
                    should_reclaim(
                        memory_bytes=memory * 1024 * 1024,
                        floor_bytes=192 * 1024 * 1024,
                        deploy_pending=False,
                        backup_running=False,
                    )
                )

    def test_pending_deploy_or_backup_never_reclaims(self):
        for key in ("deploy_pending", "backup_running"):
            with self.subTest(key=key):
                self.assertFalse(
                    should_reclaim(
                        memory_bytes=2484 * 1024 * 1024,
                        floor_bytes=192 * 1024 * 1024,
                        deploy_pending=key == "deploy_pending",
                        backup_running=key == "backup_running",
                    )
                )


class TickDueTest(unittest.TestCase):
    """The 2026-11-19 bug: with everything asleep the loop never ticked."""

    def test_awake_always_ticks(self):
        self.assertTrue(tick_due(sleeping=False, now=1.0, last_tick=1.0, poll_seconds=15))

    def test_sleeping_waits_for_the_poll(self):
        self.assertFalse(tick_due(sleeping=True, now=10.0, last_tick=0.0, poll_seconds=15))
        self.assertFalse(tick_due(sleeping=True, now=14.9, last_tick=0.0, poll_seconds=15))

    def test_sleeping_ticks_after_the_poll(self):
        self.assertTrue(tick_due(sleeping=True, now=15.0, last_tick=0.0, poll_seconds=15))
        self.assertTrue(tick_due(sleeping=True, now=99.0, last_tick=0.0, poll_seconds=15))


if __name__ == "__main__":
    unittest.main()
