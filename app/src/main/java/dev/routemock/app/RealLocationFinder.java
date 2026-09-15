package dev.routemock.app;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.routemock.core.GeoPoint;
import java.util.Objects;
import java.util.function.Consumer;

/** One fresh foreground fix. The owner must call every method on the main thread. */
final class RealLocationFinder {
    static final long TIMEOUT_MILLIS = 25_000;
    static final long MAX_SAMPLE_AGE_NANOS = 10_000_000_000L;
    static final float MAX_ACCURACY_METERS = 50;
    private final LocationManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationListener listener;
    private Runnable timeout;
    private boolean running;
    private long generation;

    RealLocationFinder(Context context) {
        manager = context.getApplicationContext().getSystemService(LocationManager.class);
    }

    void start(Consumer<Location> success, Consumer<String> failure) {
        requireMainThread();
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        cancel();
        running = true;
        long token = generation;
        long requestedNanos = SystemClock.elapsedRealtimeNanos();
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (!running || generation != token) return;
                boolean mock = Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
                if (!isUsableSample(mock, location.getLatitude(), location.getLongitude(),
                        location.hasAccuracy(), location.getAccuracy(), location.getElapsedRealtimeNanos(),
                        requestedNanos, SystemClock.elapsedRealtimeNanos())) return;
                Location copy = new Location(location);
                cancel();
                success.accept(copy);
            }
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
        boolean registered = false;
        boolean permissionDenied = false;
        if (manager != null) {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (!manager.isProviderEnabled(provider)) continue;
                    manager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
                    registered = true;
                } catch (SecurityException e) {
                    permissionDenied = true;
                } catch (IllegalArgumentException | IllegalStateException e) {
                    // A missing or temporarily unavailable provider must not block the other one.
                }
            }
        }
        if (!registered) {
            cancel();
            failure.accept(permissionDenied ? "無法取得定位權限，請允許精確位置後重試"
                    : "沒有可用的定位來源，請開啟手機定位後重試");
            return;
        }
        timeout = () -> {
            if (!running || generation != token) return;
            cancel();
            failure.accept("尚未取得準確的真實位置，請到訊號良好的地方再試一次");
        };
        handler.postDelayed(timeout, TIMEOUT_MILLIS);
    }

    void cancel() {
        requireMainThread();
        running = false;
        generation++;
        if (timeout != null) handler.removeCallbacks(timeout);
        timeout = null;
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener);
            } catch (SecurityException | IllegalArgumentException e) {
                // Permission may have been revoked; generation also rejects queued callbacks.
            }
        }
        listener = null;
    }

    boolean isRunning() {
        requireMainThread();
        return running;
    }

    static boolean isUsableSample(boolean mock, double latitude, double longitude,
            boolean hasAccuracy, float accuracy, long sampleNanos, long requestedNanos, long nowNanos) {
        return !mock && Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
                && hasAccuracy && Float.isFinite(accuracy) && accuracy > 0 && accuracy <= MAX_ACCURACY_METERS
                && requestedNanos >= 0 && sampleNanos >= requestedNanos && sampleNanos <= nowNanos
                && nowNanos - sampleNanos <= MAX_SAMPLE_AGE_NANOS;
    }

    static boolean nearOrigin(GeoPoint real, GeoPoint routeStart, float accuracy) {
        return real != null && routeStart != null && Float.isFinite(accuracy)
                && accuracy > 0 && accuracy <= MAX_ACCURACY_METERS
                && GeoPoint.distanceMeters(real, routeStart) <= Math.max(10, accuracy);
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("RealLocationFinder must run on the main thread");
    }
}
