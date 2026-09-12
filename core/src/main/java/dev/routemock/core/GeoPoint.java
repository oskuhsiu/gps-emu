package dev.routemock.core;

/** A validated WGS84 coordinate in degrees. */
public record GeoPoint(double latitude, double longitude) {
    public GeoPoint {
        if (!Double.isFinite(latitude) || latitude < -90 || latitude > 90
                || !Double.isFinite(longitude) || longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Invalid latitude or longitude");
        }
    }

    /** Great-circle distance on the mean-radius Earth, in meters. */
    public static double distanceMeters(GeoPoint a, GeoPoint b) {
        return RouteEngine.distanceMeters(a, b);
    }
}
