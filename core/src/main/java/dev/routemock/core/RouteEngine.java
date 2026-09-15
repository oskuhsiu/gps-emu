package dev.routemock.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Single-thread-confined route playback using monotonic nanoseconds and a spherical Earth.
 * Backward timestamps are clamped to the latest observed time, including command timestamps,
 * so elapsed time is never counted twice. Commands still take effect at that latest time.
 */
public final class RouteEngine {
    public static final int MAX_POINTS = 10_000;
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    public enum Phase { MOVING, PAUSED, HOLDING, ARRIVED, STOPPED }
    /** routePositionMeters is distance along the base geometry; it decreases on return and wraps in a loop. */
    public record Sample(GeoPoint point, double speedMps, float bearingDegrees,
                         double routePositionMeters, double totalMeters, Phase phase) {}

    private final List<GeoPoint> points;
    private final double[] lengths;
    private final double totalMeters;
    private double speedMps;
    // Bounded distance within one playback cycle (base-route distance for ONCE).
    private double cyclePositionMeters;
    private final PlaybackMode mode;
    private long lastNanos;
    private Phase phase;

    public RouteEngine(List<GeoPoint> points, double speedKmh, long nowNanos) {
        this(points, speedKmh, PlaybackMode.ONCE, nowNanos);
    }

    public RouteEngine(List<GeoPoint> points, double speedKmh, PlaybackMode mode, long nowNanos) {
        validateSpeed(speedKmh);
        validatePoints(points);
        this.mode = Objects.requireNonNull(mode, "mode");
        var unique = new ArrayList<GeoPoint>();
        for (GeoPoint point : points) {
            if (unique.isEmpty() || distanceMeters(unique.get(unique.size() - 1), point) > 1e-8) {
                unique.add(point);
            }
        }
        this.points = List.copyOf(unique);
        lengths = new double[unique.size() - 1];
        double total = 0;
        for (int i = 0; i < lengths.length; i++) {
            lengths[i] = distanceMeters(unique.get(i), unique.get(i + 1));
            total += lengths[i];
        }
        totalMeters = total;
        if (total > 0 && mode == PlaybackMode.LOOP && !points.get(0).equals(points.get(points.size() - 1))) {
            throw new IllegalArgumentException("Loop route must end at its exact starting point");
        }
        speedMps = speedKmh / 3.6;
        lastNanos = nowNanos;
        phase = total == 0 ? Phase.HOLDING : Phase.MOVING;
    }

    public Sample sample(long nowNanos) {
        advance(nowNanos);
        if (totalMeters == 0) return new Sample(points.get(0), 0, 0, 0, 0, phase);
        boolean reverse = mode == PlaybackMode.PING_PONG && cyclePositionMeters >= totalMeters;
        double routePositionMeters = reverse ? 2 * totalMeters - cyclePositionMeters : cyclePositionMeters;
        if (mode == PlaybackMode.ONCE && routePositionMeters >= totalMeters) {
            return new Sample(points.get(points.size() - 1), 0, 0, totalMeters, totalMeters, phase);
        }
        double remaining = routePositionMeters;
        int segment = 0;
        // At a vertex, use the segment we are about to traverse in the current direction.
        while (segment < lengths.length - 1) {
            // Reflection/subtraction can put an exact vertex a few ulps on the wrong leg.
            if (mode != PlaybackMode.ONCE && Math.abs(remaining - lengths[segment]) <= 4 * Math.ulp(totalMeters)) {
                remaining = lengths[segment];
            }
            if (reverse ? remaining <= lengths[segment] : remaining < lengths[segment]) break;
            remaining -= lengths[segment++];
        }
        GeoPoint start = points.get(segment), end = points.get(segment + 1);
        GeoPoint position = interpolate(start, end, remaining / lengths[segment]);
        double aheadMeters = reverse ? Math.max(0, remaining - 1) : Math.min(lengths[segment], remaining + 1);
        // Decrease the original arc fraction on return, including deterministic antipodal arcs.
        GeoPoint ahead = interpolate(start, end, aheadMeters / lengths[segment]);
        return new Sample(position, phase == Phase.MOVING ? speedMps : 0,
                bearing(position, ahead), routePositionMeters, totalMeters, phase);
    }

    public void setSpeedKmh(double speedKmh, long nowNanos) {
        validateSpeed(speedKmh);
        advance(nowNanos);
        speedMps = speedKmh / 3.6;
    }

    public void pause(long nowNanos) {
        advance(nowNanos);
        if (phase == Phase.MOVING) phase = Phase.PAUSED;
    }

    public void resume(long nowNanos) {
        advance(nowNanos);
        if (phase == Phase.PAUSED) phase = Phase.MOVING;
    }

    /** Freeze current progress without advancing through time since the last sample or command. */
    public void stop() {
        phase = Phase.STOPPED;
    }

    private void advance(long nowNanos) {
        if (nowNanos <= lastNanos) return;
        // Preserve nanosecond precision for ordinary gaps; avoid signed overflow for huge gaps.
        long elapsed = nowNanos - lastNanos;
        double seconds = (elapsed >= 0 ? elapsed : (double) nowNanos - lastNanos) / 1_000_000_000d;
        lastNanos = nowNanos;
        if (phase != Phase.MOVING) return;
        double distance = seconds * speedMps;
        if (mode == PlaybackMode.ONCE) {
            cyclePositionMeters = Math.min(totalMeters, cyclePositionMeters + distance);
            if (cyclePositionMeters >= totalMeters) phase = Phase.ARRIVED;
        } else {
            double cycleMeters = mode == PlaybackMode.PING_PONG ? 2 * totalMeters : totalMeters;
            cyclePositionMeters = (cyclePositionMeters + distance % cycleMeters) % cycleMeters;
        }
    }

    public static void validateSpeed(double speedKmh) {
        if (!Double.isFinite(speedKmh) || speedKmh < 0.5 || speedKmh > 30) {
            throw new IllegalArgumentException("Speed must be between 0.5 and 30 km/h");
        }
    }

    public static double lengthMeters(List<GeoPoint> points) {
        validatePoints(points);
        double total = 0;
        for (int i = 1; i < points.size(); i++) total += distanceMeters(points.get(i - 1), points.get(i));
        return total;
    }

    private static void validatePoints(List<GeoPoint> points) {
        Objects.requireNonNull(points, "points");
        if (points.isEmpty() || points.size() > MAX_POINTS) {
            throw new IllegalArgumentException("Route must contain 1 to 10000 points");
        }
        for (GeoPoint point : points) Objects.requireNonNull(point, "point");
    }

    public static double distanceMeters(GeoPoint a, GeoPoint b) {
        double[] av = vector(a), bv = vector(b);
        double crossX = av[1] * bv[2] - av[2] * bv[1];
        double crossY = av[2] * bv[0] - av[0] * bv[2];
        double crossZ = av[0] * bv[1] - av[1] * bv[0];
        return EARTH_RADIUS_METERS * Math.atan2(Math.sqrt(crossX * crossX
                + crossY * crossY + crossZ * crossZ), dot(av, bv));
    }

    private static GeoPoint interpolate(GeoPoint a, GeoPoint b, double fraction) {
        if (fraction <= 0) return a;
        if (fraction >= 1) return b;
        double[] av = vector(a), bv = vector(b);
        double cosine = dot(av, bv);
        double[] tangent = {bv[0] - cosine * av[0], bv[1] - cosine * av[1], bv[2] - cosine * av[2]};
        double norm = Math.sqrt(dot(tangent, tangent));
        if (norm < 1e-14 && cosine < 0) {
            // Antipodal endpoints have no unique shortest arc. Choose a stable perpendicular.
            double[] axis = Math.abs(av[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{1, 0, 0};
            double projection = dot(axis, av);
            for (int i = 0; i < 3; i++) tangent[i] = axis[i] - projection * av[i];
            norm = Math.sqrt(dot(tangent, tangent));
        }
        if (norm == 0) return a;
        double angle = distanceMeters(a, b) / EARTH_RADIUS_METERS * fraction;
        double[] result = new double[3];
        for (int i = 0; i < 3; i++) result[i] = av[i] * Math.cos(angle) + tangent[i] / norm * Math.sin(angle);
        return new GeoPoint(Math.toDegrees(Math.atan2(result[2], Math.hypot(result[0], result[1]))),
                Math.toDegrees(Math.atan2(result[1], result[0])));
    }

    private static float bearing(GeoPoint a, GeoPoint b) {
        double latA = Math.toRadians(a.latitude()), latB = Math.toRadians(b.latitude());
        double delta = Math.toRadians(b.longitude() - a.longitude());
        double degrees = Math.toDegrees(Math.atan2(Math.sin(delta) * Math.cos(latB),
                Math.cos(latA) * Math.sin(latB) - Math.sin(latA) * Math.cos(latB) * Math.cos(delta)));
        return (float) ((degrees + 360) % 360);
    }

    private static double[] vector(GeoPoint point) {
        double lat = Math.toRadians(point.latitude()), lon = Math.toRadians(point.longitude());
        return new double[]{Math.cos(lat) * Math.cos(lon), Math.cos(lat) * Math.sin(lon), Math.sin(lat)};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
