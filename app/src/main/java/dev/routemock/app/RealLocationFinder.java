package dev.routemock.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.CurrentLocationRequest;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.CancellationTokenSource;
import dev.routemock.core.GeoPoint;
import java.util.Objects;
import java.util.function.Consumer;

/** One fresh foreground fix. The owner must call every method on the main thread. */
final class RealLocationFinder {
    static final long TIMEOUT_MILLIS = 25_000;
    static final long MAX_SAMPLE_AGE_NANOS = 10_000_000_000L;
    static final long MAX_PLANNING_AGE_NANOS = 30_000_000_000L;
    static final float MAX_ACCURACY_METERS = 50;
    private final Context context;
    private final LocationManager manager;
    private CancellationTokenSource fusedCancellation;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationListener listener;
    private Runnable timeout;
    private boolean running;
    private long generation;

    RealLocationFinder(Context context) {
        this.context = context.getApplicationContext();
        manager = this.context.getSystemService(LocationManager.class);
    }

    void start(boolean freshOnly, long minimumSampleNanos,
            Consumer<Location> success, Consumer<String> failure) {
        requireMainThread();
        Objects.requireNonNull(success);
        Objects.requireNonNull(failure);
        cancel();
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            failure.accept("無法取得定位權限，請允許精確位置後重試");
            return;
        }
        if (manager == null || !manager.isLocationEnabled()) {
            failure.accept("沒有可用的定位來源，請開啟手機定位後重試");
            return;
        }
        running = true;
        long token = generation;
        long requestedNanos = SystemClock.elapsedRealtimeNanos();
        long floor = minimumSampleTime(freshOnly, minimumSampleNanos, requestedNanos);
        Consumer<Location> accept = location -> {
            if (!running || generation != token || location == null) return;
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED || !manager.isLocationEnabled()) return;
            if (!isUsableLocation(location, floor, SystemClock.elapsedRealtimeNanos(),
                    MAX_SAMPLE_AGE_NANOS)) return;
            Location copy = new Location(location);
            cancel();
            success.accept(copy);
        };
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER};
        if (!freshOnly) {
            Location best = null;
            for (String provider : providers) {
                try {
                    if (!manager.isProviderEnabled(provider)) continue;
                    Location cached = manager.getLastKnownLocation(provider);
                    if (cached != null && isUsableLocation(cached, floor,
                            SystemClock.elapsedRealtimeNanos(), MAX_SAMPLE_AGE_NANOS)
                            && (best == null || cached.getElapsedRealtimeNanos()
                                    > best.getElapsedRealtimeNanos())) best = cached;
                } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
                    // A provider can disappear or lose permission during lookup.
                }
            }
            if (best != null) {
                accept.accept(best);
                if (!running || generation != token) return;
            }
        }
        listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) { accept.accept(location); }
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
        };
        boolean registered = false;
        boolean permissionDenied = false;
        for (String provider : providers) {
            try {
                if (!manager.isProviderEnabled(provider)) continue;
                manager.requestLocationUpdates(provider, 0, 0, listener, Looper.getMainLooper());
                registered = true;
            } catch (SecurityException e) {
                permissionDenied = true;
            } catch (IllegalArgumentException | IllegalStateException e) {
                // A missing or temporarily unavailable provider must not block the other one.
            }
            if (!running || generation != token) return;
        }
        try {
            if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                    == ConnectionResult.SUCCESS) {
                fusedCancellation = new CancellationTokenSource();
                CurrentLocationRequest request = new CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setGranularity(Granularity.GRANULARITY_FINE)
                        .setDurationMillis(TIMEOUT_MILLIS)
                        .setMaxUpdateAgeMillis(maxUpdateAgeMillis(freshOnly, minimumSampleNanos, requestedNanos))
                        .build();
                LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(request, fusedCancellation.getToken())
                        .addOnSuccessListener(context.getMainExecutor(), accept::accept)
                        .addOnFailureListener(context.getMainExecutor(), ignored -> {
                            // Platform providers remain active until the shared deadline.
                        });
                registered = true;
            }
        } catch (RuntimeException e) {
            // Missing or unavailable GMS must not prevent platform location lookup.
        }
        if (!running || generation != token) return;
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
        long elapsedMillis = (SystemClock.elapsedRealtimeNanos() - requestedNanos) / 1_000_000;
        handler.postDelayed(timeout, Math.max(0, TIMEOUT_MILLIS - elapsedMillis));
    }

    void cancel() {
        requireMainThread();
        running = false;
        generation++;
        if (timeout != null) handler.removeCallbacks(timeout);
        timeout = null;
        if (fusedCancellation != null) fusedCancellation.cancel();
        fusedCancellation = null;
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
        return isUsableSample(mock, latitude, longitude, hasAccuracy, accuracy, sampleNanos,
                requestedNanos, nowNanos, MAX_SAMPLE_AGE_NANOS);
    }

    static long minimumSampleTime(boolean freshOnly, long barrier, long requestNanos) {
        return freshOnly ? Math.max(barrier, requestNanos) : barrier;
    }

    static long maxUpdateAgeMillis(boolean freshOnly, long barrier, long requestNanos) {
        if (freshOnly || barrier >= requestNanos) return 0;
        return Math.min(MAX_SAMPLE_AGE_NANOS, requestNanos - Math.max(0, barrier)) / 1_000_000;
    }

    static boolean isUsableLocation(Location location, long floor, long now, long maxAge) {
        if (location == null) return false;
        boolean mock = Build.VERSION.SDK_INT >= 31 ? location.isMock() : location.isFromMockProvider();
        return isUsableSample(mock, location.getLatitude(), location.getLongitude(),
                location.hasAccuracy(), location.getAccuracy(), location.getElapsedRealtimeNanos(),
                floor, now, maxAge);
    }

    static boolean isUsableSample(boolean mock, double latitude, double longitude,
            boolean hasAccuracy, float accuracy, long sampleNanos, long requestedNanos,
            long nowNanos, long maxAgeNanos) {
        return !mock && Double.isFinite(latitude) && latitude >= -90 && latitude <= 90
                && Double.isFinite(longitude) && longitude >= -180 && longitude <= 180
                && hasAccuracy && Float.isFinite(accuracy) && accuracy > 0 && accuracy <= MAX_ACCURACY_METERS
                && requestedNanos >= 0 && sampleNanos >= requestedNanos && sampleNanos <= nowNanos
                && maxAgeNanos >= 0 && nowNanos - sampleNanos <= maxAgeNanos;
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
