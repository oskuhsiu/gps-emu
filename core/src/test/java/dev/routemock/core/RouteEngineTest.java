package dev.routemock.core;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class RouteEngineTest {
    private static final GeoPoint ORIGIN = new GeoPoint(0, 0);
    private static final GeoPoint EAST = new GeoPoint(0, 1);
    private static long seconds(double value) { return Math.round(value * 1_000_000_000d); }
    private static RouteEngine straight(double speed) { return new RouteEngine(List.of(ORIGIN, EAST), speed, 0); }

    @Test public void knownEarthDistancesAndEastwardMotion() {
        assertEquals(111_195.08, GeoPoint.distanceMeters(ORIGIN, EAST), 0.1);
        assertEquals(10_007_557.22, RouteEngine.distanceMeters(ORIGIN, new GeoPoint(90, 0)), 0.1);
        var sample = straight(3.6).sample(seconds(100));
        assertEquals(100, sample.traveledMeters(), 1e-9);
        assertEquals(100, GeoPoint.distanceMeters(ORIGIN, sample.point()), 0.001);
        assertEquals(0, sample.point().latitude(), 1e-10);
        assertEquals(90, sample.bearingDegrees(), 0.001);
        assertEquals(1, sample.speedMps(), 1e-10);
        assertEquals(RouteEngine.Phase.MOVING, sample.phase());
    }

    @Test public void speedChangeSettlesOldSpeedBeforeApplyingNewSpeed() {
        var engine = straight(3.6);
        engine.setSpeedKmh(7.2, seconds(10));
        assertEquals(30, engine.sample(seconds(20)).traveledMeters(), 1e-9);
    }

    @Test public void pauseResumeDoesNotAccumulatePausedTimeAndAllowsSpeedChanges() {
        var engine = straight(3.6);
        engine.pause(seconds(10));
        var paused = engine.sample(seconds(100));
        assertEquals(RouteEngine.Phase.PAUSED, paused.phase());
        assertEquals(10, paused.traveledMeters(), 1e-9);
        assertEquals(0, paused.speedMps(), 0);
        engine.setSpeedKmh(7.2, seconds(150));
        engine.pause(seconds(175));
        engine.resume(seconds(200));
        engine.resume(seconds(205));
        assertEquals(30, engine.sample(seconds(210)).traveledMeters(), 1e-9);
    }

    @Test public void stopFreezesLastSampleAcrossDelayedTicks() {
        var engine = straight(3.6);
        var last = engine.sample(seconds(10));
        engine.stop();
        var stopped = engine.sample(seconds(100));
        assertStoppedAt(last, stopped);
        assertEquals(stopped, engine.sample(seconds(1000)));
    }

    @Test public void stopAfterPauseCannotBeRestartedByCommands() {
        var engine = straight(3.6);
        engine.pause(seconds(10));
        var last = engine.sample(seconds(100));
        engine.stop();
        var stopped = engine.sample(seconds(200));
        assertStoppedAt(last, stopped);
        engine.stop();
        engine.setSpeedKmh(30, seconds(300));
        engine.pause(seconds(400));
        engine.resume(seconds(500));
        assertEquals(stopped, engine.sample(seconds(1000)));
    }

    @Test public void stopAtArrivalPreservesExactEndpoint() {
        var engine = new RouteEngine(List.of(ORIGIN, new GeoPoint(0, 0.001)), 3.6, 0);
        var arrived = engine.sample(seconds(1000));
        assertEquals(RouteEngine.Phase.ARRIVED, arrived.phase());
        engine.stop();
        assertStoppedAt(arrived, engine.sample(seconds(2000)));
    }

    @Test public void stopSingleAndRepeatedCoordinatesPreservesHoldingPoint() {
        for (var route : List.of(List.of(ORIGIN), List.of(ORIGIN, ORIGIN, ORIGIN),
                List.of(new GeoPoint(0, 180), new GeoPoint(0, -180)))) {
            var engine = new RouteEngine(route, 5, 0);
            var holding = engine.sample(seconds(10));
            engine.stop();
            var stopped = engine.sample(seconds(100));
            assertStoppedAt(holding, stopped);
            engine.stop();
            engine.resume(seconds(200));
            engine.setSpeedKmh(30, seconds(300));
            assertEquals(stopped, engine.sample(seconds(1000)));
        }
    }

    private static void assertStoppedAt(RouteEngine.Sample previous, RouteEngine.Sample stopped) {
        assertEquals(RouteEngine.Phase.STOPPED, stopped.phase());
        assertEquals(previous.point(), stopped.point());
        assertEquals(previous.traveledMeters(), stopped.traveledMeters(), 0);
        assertEquals(previous.totalMeters(), stopped.totalMeters(), 0);
        assertEquals(0, stopped.speedMps(), 0);
    }

    @Test public void delayedTickCrossesCornersAndStopsExactlyAtEndpoint() {
        GeoPoint corner = new GeoPoint(0, 0.001), end = new GeoPoint(0.001, 0.001);
        var engine = new RouteEngine(List.of(ORIGIN, corner, end), 3.6, 0);
        var afterCorner = engine.sample(seconds(150));
        assertEquals(150, afterCorner.traveledMeters(), 1e-9);
        assertEquals(0.001, afterCorner.point().longitude(), 1e-10);
        assertTrue(afterCorner.point().latitude() > 0);
        assertEquals(38.805, GeoPoint.distanceMeters(corner, afterCorner.point()), 0.01);
        var arrived = engine.sample(seconds(1000));
        assertEquals(end, arrived.point());
        assertEquals(RouteEngine.Phase.ARRIVED, arrived.phase());
        assertEquals(arrived.totalMeters(), arrived.traveledMeters(), 0);
        assertEquals(0, arrived.speedMps(), 0);
        engine.pause(seconds(2000));
        engine.resume(seconds(3000));
        assertEquals(arrived, engine.sample(seconds(4000)));
    }

    @Test public void singleAndRepeatedCoordinatesHoldWithZeroSpeed() {
        for (var route : List.of(List.of(ORIGIN), List.of(ORIGIN, ORIGIN, ORIGIN),
                List.of(new GeoPoint(0, 180), new GeoPoint(0, -180)))) {
            var engine = new RouteEngine(route, 5, 0);
            engine.pause(seconds(5));
            engine.resume(seconds(10));
            var sample = engine.sample(seconds(100));
            assertEquals(RouteEngine.Phase.HOLDING, sample.phase());
            assertEquals(route.get(0), sample.point());
            assertEquals(0, sample.totalMeters(), 0);
            assertEquals(0, sample.speedMps(), 0);
        }
        var duplicateSegments = new RouteEngine(List.of(ORIGIN, ORIGIN, EAST, EAST), 3.6, 0);
        assertEquals(100, duplicateSegments.sample(seconds(100)).traveledMeters(), 1e-9);
    }

    @Test public void crossesAntimeridianOnShortArc() {
        var a = new GeoPoint(0, 179.999);
        var b = new GeoPoint(0, -179.999);
        assertEquals(222.390, RouteEngine.lengthMeters(List.of(a, b)), 0.01);
        var sample = new RouteEngine(List.of(a, b), 3.6, 0).sample(seconds(111.195));
        assertEquals(180, Math.abs(sample.point().longitude()), 0.000001);
        assertEquals(0, sample.point().latitude(), 1e-10);
        assertEquals(111.195, GeoPoint.distanceMeters(a, sample.point()), 0.001);
    }

    @Test public void greatCircleBetweenHighLatitudesBendsTowardPole() {
        var a = new GeoPoint(60, -45);
        var b = new GeoPoint(60, 45);
        double half = RouteEngine.distanceMeters(a, b) / 2;
        var sample = new RouteEngine(List.of(a, b), 3.6, 0).sample(seconds(half));
        assertEquals(0, sample.point().longitude(), 1e-8);
        assertEquals(67.79235, sample.point().latitude(), 0.00001);
        assertEquals(half, GeoPoint.distanceMeters(a, sample.point()), 0.001);
    }

    @Test public void antipodalRouteRemainsFiniteAndCoversExpectedDistance() {
        var a = new GeoPoint(0, 0);
        var b = new GeoPoint(0, 180);
        var sample = new RouteEngine(List.of(a, b), 3.6, 0).sample(seconds(1_000_000));
        assertEquals(1_000_000, GeoPoint.distanceMeters(a, sample.point()), 0.01);
        assertTrue(Float.isFinite(sample.bearingDegrees()));
        assertEquals(0, sample.bearingDegrees(), 0.001);
    }

    @Test public void backwardClocksDoNotRegressOrDoubleCountTime() {
        var engine = straight(3.6);
        assertEquals(10, engine.sample(seconds(10)).traveledMeters(), 0);
        assertEquals(10, engine.sample(seconds(5)).traveledMeters(), 0);
        engine.setSpeedKmh(7.2, seconds(8));
        assertEquals(20, engine.sample(seconds(15)).traveledMeters(), 0);
        engine.pause(seconds(12));
        engine.resume(seconds(14));
        assertEquals(30, engine.sample(seconds(20)).traveledMeters(), 0);
    }

    @Test public void invalidInputRejectedWithoutMutatingActiveProgress() {
        for (double speed : new double[]{Double.NaN, Double.POSITIVE_INFINITY, 0, -1, 0.49, 30.01}) {
            assertThrows(IllegalArgumentException.class, () -> RouteEngine.validateSpeed(speed));
        }
        RouteEngine.validateSpeed(0.5);
        RouteEngine.validateSpeed(30);
        for (double value : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, 91, -91}) {
            assertThrows(IllegalArgumentException.class, () -> new GeoPoint(value, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0, 181));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0, -181));
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(0, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new RouteEngine(List.of(), 5, 0));
        assertThrows(IllegalArgumentException.class, () -> RouteEngine.lengthMeters(Collections.nCopies(10_001, ORIGIN)));
        assertThrows(NullPointerException.class, () -> new RouteEngine(null, 5, 0));
        var engine = straight(3.6);
        assertThrows(IllegalArgumentException.class, () -> engine.setSpeedKmh(0, seconds(10)));
        assertEquals(5, engine.sample(seconds(5)).traveledMeters(), 0);
    }

    @Test public void routeSnapshotIsIndependentOfCallerMutations() {
        var input = new ArrayList<>(List.of(ORIGIN, EAST));
        var engine = new RouteEngine(input, 3.6, 0);
        input.clear();
        assertEquals(100, engine.sample(seconds(100)).traveledMeters(), 0);
    }

    @Test public void enormousClockGapArrivesWithoutOverflow() {
        var engine = new RouteEngine(List.of(ORIGIN, EAST), 3.6, Long.MIN_VALUE);
        assertEquals(RouteEngine.Phase.ARRIVED, engine.sample(Long.MAX_VALUE).phase());
    }
}
