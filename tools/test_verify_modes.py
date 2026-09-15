"""Hand-authored vertex/time observations, independent of the fitter."""
import json
import tempfile
import unittest
from pathlib import Path

from verify_modes import Route, verify

# Approximately ten metres per side near the equator; coordinates are lon, lat.
A = [0, 0]
B = [.000089932, 0]
C = [.000089932, .000089932]
D = [0, .000089932]


class ModeVerificationTest(unittest.TestCase):
    def check_capture(self, vertices, mode, closed=False, mutate=None, stopped_after=None):
        rows = []
        for channel in ('GPS', 'NETWORK', 'FUSED'):
            for i, point in enumerate(vertices):
                row = dict(channel=channel, longitude=point[0], latitude=point[1],
                           elapsedRealtimeNanos=(100+i*5)*1_000_000_000,
                           speedMps=0 if stopped_after is not None and i >= stopped_after else 2,
                           accuracy=3, isMock=True)
                rows.append(row)
        if mutate:
            mutate(rows)
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory)/'receiver.jsonl'
            log.write_text('\n'.join(json.dumps(row) for row in rows))
            return verify(log, Route([A, B, C, D, A] if closed else [A, B, C]), mode, 7.2)

    def test_once_arrival(self):
        result = self.check_capture([A, B, C, C, C, C], 'once', stopped_after=2)
        self.assertEqual(result['GPS']['samples'], 5)

    def test_ping_pong_reversal(self):
        result = self.check_capture([A, B, C, B, A, B, C], 'ping_pong')
        self.assertGreaterEqual(result['FUSED']['turns'], 2)

    def test_ping_pong_capture_starts_returning(self):
        result = self.check_capture([C, B, A, B, C, B, A], 'ping_pong')
        self.assertGreaterEqual(result['GPS']['turns'], 2)

    def test_wrong_moving_mode(self):
        with self.assertRaisesRegex(ValueError, 'mode/position fit'):
            self.check_capture([A, B, C, D, A, B, C], 'ping_pong', closed=True)

    def test_loop_wrap(self):
        result = self.check_capture([A, B, C, D, A, B, C], 'loop', closed=True)
        self.assertEqual(result['NETWORK']['wraps'], 1)

    def test_wrong_mode(self):
        with self.assertRaises(ValueError):
            self.check_capture([A, B, C, C, C, C], 'ping_pong', stopped_after=2)

    def test_perturbed_point(self):
        def perturb(rows):
            rows[3]['latitude'] += .0001
        with self.assertRaisesRegex(ValueError, 'off route'):
            self.check_capture([A, B, C, B, A, B, C], 'ping_pong', mutate=perturb)

    def test_stale(self):
        def stale(rows):
            rows[3]['elapsedRealtimeNanos'] = rows[2]['elapsedRealtimeNanos']
        with self.assertRaisesRegex(ValueError, 'stale timestamps'):
            self.check_capture([A, B, C, B, A, B, C], 'ping_pong', mutate=stale)

    def test_no_movement(self):
        with self.assertRaisesRegex(ValueError, 'no observable movement'):
            self.check_capture([C]*6, 'once', stopped_after=0)

    def test_missing_wrap(self):
        with self.assertRaisesRegex(ValueError, 'no observed mode boundary'):
            self.check_capture([A, B, C, D], 'loop', closed=True)

    def test_once_missing_arrived_tail(self):
        with self.assertRaises(ValueError):
            self.check_capture([A, B, C, B, A, B], 'once')

    def test_open_loop_rejected(self):
        with self.assertRaisesRegex(ValueError, 'exactly closed'):
            self.check_capture([A, B, C, B, A, B], 'loop')


if __name__ == '__main__':
    unittest.main()
