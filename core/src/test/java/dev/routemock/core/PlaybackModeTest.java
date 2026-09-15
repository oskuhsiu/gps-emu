package dev.routemock.core;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackModeTest {
    private static final GeoPoint A = new GeoPoint(0, 0);
    private static final GeoPoint B = new GeoPoint(0, 0.001);
    private static final GeoPoint C = new GeoPoint(0.002, 0.001);
    private static final List<GeoPoint> OPEN = List.of(A, B, C);
    private static final double AB = RouteEngine.distanceMeters(A, B);
    private static final double L = RouteEngine.lengthMeters(OPEN);
    private static long nanos(double seconds) { return Math.round(seconds * 1e9); }
    private static RouteEngine engine(PlaybackMode mode) {
        return new RouteEngine(mode == PlaybackMode.LOOP ? List.of(A, B, C, A) : OPEN, 3.6, mode, 0);
    }
    private static void position(RouteEngine.Sample s, double meters, float bearing) {
        assertEquals(meters, s.routePositionMeters(), 1e-6);
        assertEquals(bearing, s.bearingDegrees(), 0.01);
        assertEquals(RouteEngine.Phase.MOVING, s.phase());
        assertEquals(1, s.speedMps(), 0);
    }

    @Test public void unequalLegsReflectPositionAndBearing() {
        var e = engine(PlaybackMode.PING_PONG);
        position(e.sample(nanos(AB / 2)), AB / 2, 90);
        position(e.sample(nanos(AB + 50)), AB + 50, 0);
        position(e.sample(nanos(L + 50)), L - 50, 180);
        position(e.sample(nanos(2 * L - AB / 2)), AB / 2, 270);
        position(e.sample(nanos(2 * L + 50)), 50, 90);
    }

    @Test public void exactEndpointsAndCornersChooseDirectionOfNextMovement() {
        // Tune speed so endpoint time is an exact integer number of nanoseconds.
        double speed = L / 100;
        var e = new RouteEngine(OPEN, speed * 3.6, PlaybackMode.PING_PONG, 0);
        var end = e.sample(nanos(100));
        assertEquals(C, end.point());
        assertEquals(180, end.bearingDegrees(), 0.01);
        assertEquals(speed, end.speedMps(), 0);
        assertEquals(A, e.sample(nanos(200)).point());
        assertEquals(90, e.sample(nanos(200)).bearingDegrees(), 0.01);
        var corner = new RouteEngine(List.of(A, B, new GeoPoint(0.001, 0.001)),
                AB * 3.6 / 128, PlaybackMode.PING_PONG, 0);
        assertEquals(B, corner.sample(nanos(128)).point());
        assertEquals(0, corner.sample(nanos(128)).bearingDegrees(), 0.01);
        assertEquals(0, RouteEngine.distanceMeters(B, corner.sample(nanos(384)).point()), 1e-8);
        assertEquals(270, corner.sample(nanos(384)).bearingDegrees(), 0.01);
    }

    @Test public void loopWrapsExistingGeometryAndRejectsOpenRoutes() {
        assertThrows(IllegalArgumentException.class,
                () -> new RouteEngine(OPEN, 3.6, PlaybackMode.LOOP, 0));
        assertThrows(IllegalArgumentException.class, () -> new RouteEngine(
                List.of(new GeoPoint(0, 180), A, new GeoPoint(0, -180)), 3.6, PlaybackMode.LOOP, 0));
        var e = engine(PlaybackMode.LOOP);
        double length = e.sample(0).totalMeters();
        position(e.sample(nanos(length + 20)), 20, 90);
        var exact = new RouteEngine(List.of(A, B, C, A), length * 3.6 / 100,
                PlaybackMode.LOOP, 0);
        assertEquals(A, exact.sample(nanos(100)).point());
        assertEquals(90, exact.sample(nanos(100)).bearingDegrees(), 0.01);
    }

    @Test public void manyCyclesAndOverflowStayBounded() {
        for (var mode : List.of(PlaybackMode.PING_PONG, PlaybackMode.LOOP)) {
            var route = mode == PlaybackMode.LOOP ? List.of(A, B, C, A) : OPEN;
            double length = RouteEngine.lengthMeters(route);
            double cycle = mode == PlaybackMode.LOOP ? length : 2 * length;
            var e = new RouteEngine(route, 3.6, mode, 0);
            double elapsed = cycle * 1_000_000 + 20;
            position(e.sample(nanos(elapsed)), 20, 90);
            var huge = new RouteEngine(route, 30, mode, Long.MIN_VALUE).sample(Long.MAX_VALUE);
            assertEquals(RouteEngine.Phase.MOVING, huge.phase());
            assertTrue(huge.routePositionMeters() >= 0 && huge.routePositionMeters() <= length);
        }
    }

    @Test public void commandsSettleAcrossTurnsAndStopCannotResume() {
        for (var mode : PlaybackMode.values()) {
            var e = engine(mode);
            var last = e.sample(nanos(50));
            e.stop();
            e.resume(nanos(10000));
            e.setSpeedKmh(30, nanos(20000));
            e.pause(nanos(30000));
            var stopped = e.sample(nanos(40000));
            assertEquals(last.point(), stopped.point());
            assertEquals(last.routePositionMeters(), stopped.routePositionMeters(), 0);
            assertEquals(RouteEngine.Phase.STOPPED, stopped.phase());
            assertEquals(0, stopped.speedMps(), 0);
        }
        var e = engine(PlaybackMode.PING_PONG);
        e.setSpeedKmh(7.2, nanos(L + 20));
        e.pause(nanos(L + 30));
        assertEquals(L - 40, e.sample(nanos(L + 100)).routePositionMeters(), 1e-6);
        e.resume(nanos(L + 200));
        assertEquals(L - 60, e.sample(nanos(L + 210)).routePositionMeters(), 1e-6);
        assertEquals(L - 60, e.sample(nanos(L + 205)).routePositionMeters(), 1e-6);
    }

    @Test public void loopSpeedAndPauseSettleThroughWrap() {
        var e = engine(PlaybackMode.LOOP);
        double length = e.sample(0).totalMeters();
        e.setSpeedKmh(7.2, nanos(length - 10));
        e.pause(nanos(length + 10));
        assertEquals(30, e.sample(nanos(length + 100)).routePositionMeters(), 1e-6);
        e.resume(nanos(length + 200));
        assertEquals(50, e.sample(nanos(length + 210)).routePositionMeters(), 1e-6);
    }

    @Test public void zeroLengthAndDuplicatesWorkAcrossModes() {
        for (var mode : PlaybackMode.values()) {
            for (var route : List.of(List.of(A), List.of(A, A),
                    List.of(new GeoPoint(0, 180), new GeoPoint(0, -180)))) {
                var s = new RouteEngine(route, 3.6, mode, 0).sample(Long.MAX_VALUE);
                assertEquals(RouteEngine.Phase.HOLDING, s.phase());
                assertEquals(0, s.totalMeters(), 0);
            }
        }
        var s = new RouteEngine(List.of(A, A, B, B, A, A), 3.6, PlaybackMode.LOOP, 0)
                .sample(nanos(2 * AB + 20));
        position(s, 20, 90);
    }

    @Test public void returningGeographyRetracesSameArc() {
        for (var route : List.of(List.of(new GeoPoint(0, 179.999), new GeoPoint(0, -179.999)),
                List.of(new GeoPoint(60, -45), new GeoPoint(60, 45)),
                List.of(A, new GeoPoint(0, 180)))) {
            double length = RouteEngine.lengthMeters(route);
            var e = new RouteEngine(route, 3.6, PlaybackMode.PING_PONG, 0);
            var outbound = e.sample(nanos(length / 4));
            var inbound = e.sample(nanos(2 * length - length / 4));
            assertEquals(0, RouteEngine.distanceMeters(outbound.point(), inbound.point()), 1e-6);
            assertEquals(180, (inbound.bearingDegrees() - outbound.bearingDegrees() + 360) % 360, 0.01);
        }
    }
}
