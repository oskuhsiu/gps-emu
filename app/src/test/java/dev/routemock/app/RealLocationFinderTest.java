package dev.routemock.app;

import dev.routemock.core.GeoPoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class RealLocationFinderTest {
    private static final long REQUEST = 20_000_000_000L;
    private static final long NOW = 25_000_000_000L;

    @Test public void acceptsFreshAccurateSampleIncludingCoordinateBoundaries() {
        assertTrue(usable(false, 25, 121, true, 5, NOW));
        assertTrue(usable(false, -90, -180, true, 50, REQUEST));
        assertTrue(usable(false, 90, 180, true, .1f, NOW));
    }

    @Test public void rejectsMockCachedFutureAndExpiredSamples() {
        assertFalse(usable(true, 25, 121, true, 5, NOW));
        assertFalse(usable(false, 25, 121, true, 5, REQUEST - 1));
        assertFalse(usable(false, 25, 121, true, 5, NOW + 1));
        assertTrue(RealLocationFinder.isUsableSample(false, 25, 121, true, 5,
                REQUEST, REQUEST, REQUEST + 10_000_000_000L));
        assertFalse(RealLocationFinder.isUsableSample(false, 25, 121, true, 5,
                REQUEST, REQUEST, REQUEST + 10_000_000_001L));
        assertFalse(RealLocationFinder.isUsableSample(false, 25, 121, true, 5, -1, -1, NOW));
    }

    @Test public void rejectsInvalidCoordinatesAndMissingOrPoorAccuracy() {
        for (double latitude : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -90.001, 90.001})
            assertFalse(usable(false, latitude, 121, true, 5, NOW));
        for (double longitude : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, -180.001, 180.001})
            assertFalse(usable(false, 25, longitude, true, 5, NOW));
        assertFalse(usable(false, 25, 121, false, 5, NOW));
        for (float accuracy : new float[]{0, -1, 50.001f, Float.NaN, Float.POSITIVE_INFINITY})
            assertFalse(usable(false, 25, 121, true, accuracy, NOW));
    }

    @Test public void originToleranceHasTenMeterFloorAndUsesValidAccuracy() {
        GeoPoint origin = new GeoPoint(0, 0);
        GeoPoint fiveMeters = new GeoPoint(.000045, 0);
        GeoPoint twentyMeters = new GeoPoint(.00018, 0);
        GeoPoint far = new GeoPoint(.001, 0);
        assertTrue(RealLocationFinder.nearOrigin(origin, origin, 1));
        assertTrue(RealLocationFinder.nearOrigin(origin, fiveMeters, 1));
        assertFalse(RealLocationFinder.nearOrigin(origin, twentyMeters, 1));
        assertTrue(RealLocationFinder.nearOrigin(origin, twentyMeters, 25));
        float boundary = (float) GeoPoint.distanceMeters(origin, twentyMeters);
        assertTrue(RealLocationFinder.nearOrigin(origin, twentyMeters, Math.nextUp(boundary)));
        assertFalse(RealLocationFinder.nearOrigin(origin, twentyMeters, Math.nextDown(boundary)));
        assertFalse(RealLocationFinder.nearOrigin(origin, far, 50));
        for (float accuracy : new float[]{0, -1, 51, Float.NaN, Float.POSITIVE_INFINITY})
            assertFalse(RealLocationFinder.nearOrigin(origin, origin, accuracy));
        assertFalse(RealLocationFinder.nearOrigin(null, origin, 10));
        assertFalse(RealLocationFinder.nearOrigin(origin, null, 10));
    }

    private static boolean usable(boolean mock, double latitude, double longitude,
            boolean hasAccuracy, float accuracy, long timestamp) {
        return RealLocationFinder.isUsableSample(mock, latitude, longitude, hasAccuracy,
                accuracy, timestamp, REQUEST, NOW);
    }
}
