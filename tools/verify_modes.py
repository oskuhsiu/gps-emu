#!/usr/bin/env python3
"""Independent receiver oracle for short (<2 km), nonpolar route fixtures.

Reads raw RouteProbe JSON lines. Requires observed mode boundaries, not merely
positions compatible with a mode. Uses no product code or private app state.
"""
import argparse
import json
import math
from pathlib import Path

from verify_probe import distance

TOLERANCE = 1.0


def require(condition, message):
    if not condition:
        raise ValueError(message)


class Route:
    def __init__(self, coordinates):
        require(len(coordinates) >= 2, 'route needs at least two vertices')
        self.points = [(float(p[1]), float(p[0])) for p in coordinates]
        require(all(math.isfinite(a) and math.isfinite(b) and abs(a) < 70
                    and abs(b) <= 180 for a, b in self.points),
                'fixture must have finite, nonpolar coordinates')
        self.cumulative = [0.0]
        for a, b in zip(self.points, self.points[1:]):
            self.cumulative.append(self.cumulative[-1] + distance(a, b))
        self.length = self.cumulative[-1]
        require(2 < self.length < 2000, 'fixture length must be >2 m and <2 km')
        require(max(p[1] for p in self.points) - min(p[1] for p in self.points) < 1,
                'fixture must not cross antimeridian')
        self.scale = 6371008.8 * math.pi / 180
        self.coslat = math.cos(math.radians(self.points[0][0]))

    def xy(self, p):
        return ((p[1] - self.points[0][1]) * self.scale * self.coslat,
                (p[0] - self.points[0][0]) * self.scale)

    def projections(self, point):
        px, py = self.xy(point)
        candidates = []
        for i, (a, b) in enumerate(zip(self.points, self.points[1:])):
            ax, ay = self.xy(a)
            bx, by = self.xy(b)
            dx, dy = bx - ax, by - ay
            square = dx * dx + dy * dy
            if square == 0:
                continue
            fraction = max(0, min(1, ((px-ax)*dx + (py-ay)*dy) / square))
            candidates.append((self.cumulative[i] + fraction *
                               (self.cumulative[i+1] - self.cumulative[i]),
                               math.hypot(px-ax-fraction*dx, py-ay-fraction*dy)))
        return candidates

    def at(self, position):
        for i in range(len(self.points)-1):
            start, end = self.cumulative[i:i+2]
            if end > start and position <= end:
                fraction = max(0, (position-start)/(end-start))
                a, b = self.points[i:i+2]
                return tuple(x + fraction*(y-x) for x, y in zip(a, b))
        return self.points[-1]


def route_position(phase, length, mode):
    if mode == 'once':
        return min(length, phase)
    if mode == 'loop':
        return phase % length
    remainder = phase % (2*length)
    return min(remainder, 2*length-remainder)


def verify(log, route, mode, speed_kmh, min_span=10):
    require(mode in ('once', 'ping_pong', 'loop'), 'unknown mode')
    require(math.isfinite(speed_kmh) and speed_kmh > 0, 'speed must be positive')
    require(math.isfinite(min_span) and min_span >= 0, 'invalid minimum span')
    if mode == 'loop':
        require(route.points[0] == route.points[-1], 'loop geometry must be exactly closed')
    rows = [json.loads(line) for line in Path(log).read_text().splitlines()
            if line.startswith('{')]
    speed = speed_kmh / 3.6
    result = {}
    for channel in ('GPS', 'NETWORK', 'FUSED'):
        samples = [r for r in rows if r.get('channel') == channel]
        require(bool(samples), channel + ': no samples')
        cutoff = samples[0]['elapsedRealtimeNanos'] + 2_000_000_000
        samples = [r for r in samples if r['elapsedRealtimeNanos'] >= cutoff]
        require(len(samples) >= 3, channel + ': too few fresh samples')
        times = [r['elapsedRealtimeNanos'] for r in samples]
        require(all(b > a for a, b in zip(times, times[1:])), channel + ': stale timestamps')
        elapsed = [(t-times[0])/1e9 for t in times]
        require(elapsed[-1] >= min_span, channel + ': observation too short')
        require(all(r['isMock'] is True and 0 <= r['accuracy'] <= 10
                    and all(math.isfinite(r[k]) for k in
                            ('latitude', 'longitude', 'speedMps', 'accuracy'))
                    for r in samples), channel + ': invalid mock/accuracy/numeric fields')
        points = [(r['latitude'], r['longitude']) for r in samples]
        require(all(min(error for _, error in route.projections(p)) <= TOLERANCE
                    for p in points), channel + ': off route')
        require(max(distance(points[0], p) for p in points) > TOLERANCE,
                channel + ': no observable movement')
        candidates = [d for d, _ in route.projections(points[0])]
        if mode == 'ping_pong':
            candidates += [2*route.length-d for d in candidates]
        def errors(phase):
            return [distance(p, route.at(route_position(phase+t*speed, route.length, mode)))
                    for p, t in zip(points, elapsed)]
        phase = min(candidates, key=lambda candidate: max(errors(candidate)))
        error = max(errors(phase))
        require(error <= TOLERANCE, channel + ': mode/position fit exceeds 1 m')
        phases = [phase+t*speed for t in elapsed]
        if mode == 'once':
            arrived = [r['speedMps'] == 0 for r in samples]
            require(any(not a for a in arrived) and all(arrived[-3:]),
                    channel + ': need movement and at least three arrived tail samples')
            for r, p, progress in zip(samples, points, phases):
                if r['speedMps'] == 0:
                    require(distance(p, route.points[-1]) <= TOLERANCE and
                            progress >= route.length-TOLERANCE,
                            channel + ': premature stop')
                else:
                    require(abs(r['speedMps']-speed) < .01 and
                            progress <= route.length+TOLERANCE,
                            channel + ': incorrect moving speed')
            boundaries = 1
        else:
            require(all(abs(r['speedMps']-speed) < .01 for r in samples),
                    channel + ': incorrect speed')
            # Oversparse captures cannot establish direction or a seam crossing.
            require(all((b-a)*speed <= route.length/2 + .001 for a, b in zip(elapsed, elapsed[1:])),
                    channel + ': samples too sparse to observe mode boundary')
            boundaries = math.floor(phases[-1]/route.length) - math.floor(phases[0]/route.length)
            require(boundaries >= 1, channel + ': no observed mode boundary')
            if mode == 'ping_pong':
                positions = [route_position(p, route.length, mode) for p in phases]
                changes = [b-a for a, b in zip(positions, positions[1:])]
                require(any(d > TOLERANCE for d in changes) and
                        any(d < -TOLERANCE for d in changes),
                        channel + ': both directions not observed')
        result[channel] = {'samples': len(samples), 'spanSeconds': round(elapsed[-1], 3),
                           'maxFitErrorMeters': round(error, 3),
                           'wraps' if mode == 'loop' else 'turns': boundaries if mode != 'once' else 0}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('log')
    parser.add_argument('--route', required=True)
    parser.add_argument('--mode', required=True, choices=('once', 'ping_pong', 'loop'))
    parser.add_argument('--speed', required=True, type=float, help='km/h')
    parser.add_argument('--min-span', type=float, default=10)
    args = parser.parse_args()
    try:
        route = Route(json.loads(Path(args.route).read_text())['routes'][0]['geometry']['coordinates'])
        print(json.dumps(verify(args.log, route, args.mode, args.speed, args.min_span), indent=2))
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.exit(1, 'FAIL: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
