import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from decisions import (  # noqa: E402
    freeze_blocker,
    presence_age_seconds,
    redis_endpoint,
    should_reclaim,
    tick_due,
)


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


class PresenceAgeTest(unittest.TestCase):
    """The idle timer is seeded from `updatedAt`; a bad parse postpones freezing."""

    NOW = datetime(2026, 11, 21, 12, 0, 0, tzinfo=timezone.utc)

    def test_nanosecond_timestamp_from_the_proxy(self):
        # velocity-presence writes Instant.toString(): 9 fractional digits + Z.
        # Sub-second precision survives; the rest is truncated, never ignored.
        self.assertAlmostEqual(
            119.876544,
            presence_age_seconds("2026-11-21T11:58:00.123456789Z", self.NOW),
            places=6,
        )

    def test_whole_seconds_with_z(self):
        self.assertEqual(60.0, presence_age_seconds("2026-11-21T11:59:00Z", self.NOW))

    def test_explicit_offset(self):
        self.assertEqual(60.0, presence_age_seconds("2026-11-21T08:59:00-03:00", self.NOW))

    def test_missing_timezone_is_utc(self):
        self.assertEqual(60.0, presence_age_seconds("2026-11-21T11:59:00", self.NOW))

    def test_future_timestamp_never_goes_negative(self):
        self.assertEqual(0.0, presence_age_seconds("2026-11-21T12:00:30Z", self.NOW))

    def test_garbage_is_zero_not_an_exception(self):
        for raw in ("", "not-a-date", "2026-13-40T99:99:99Z"):
            self.assertEqual(0.0, presence_age_seconds(raw, self.NOW))


class RedisEndpointTest(unittest.TestCase):
    """Credentials must never end up in argv (TimeoutExpired logs the command)."""

    def test_full_url(self):
        self.assertEqual(
            ("redis", 6379, "0", "s3cret"),
            redis_endpoint("redis://default:s3cret@redis:6379/0"),
        )

    def test_defaults_and_percent_encoding(self):
        self.assertEqual(
            ("cache.local", 6379, "2", "p@ss word/1"),
            redis_endpoint("redis://:p%40ss%20word%2F1@cache.local/2"),
        )

    def test_no_password(self):
        self.assertEqual(
            ("localhost", 6379, "0", None),
            redis_endpoint("redis://localhost"),
        )


if __name__ == "__main__":
    unittest.main()
