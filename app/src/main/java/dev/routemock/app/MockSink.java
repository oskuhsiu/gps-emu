package dev.routemock.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationManager;
import android.location.provider.ProviderProperties;
import android.os.SystemClock;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import dev.routemock.core.RouteEngine;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Worker-confined output. Persist ownership before each possibly successful mutation. */
final class MockSink {
    private static final String PREFS = "mock-cleanup";
    private final Context context;
    private final SharedPreferences ownership;
    private final LocationManager manager;
    private final FusedLocationProviderClient fused;
    private Task<Void> pending;
    private boolean platformOnly;

    MockSink(Context context) {
        this.context = context;
        ownership = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        manager = context.getSystemService(LocationManager.class);
        fused = LocationServices.getFusedLocationProviderClient(context);
    }

    static boolean hasPending(Context context) {
        return !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll().isEmpty();
    }

    boolean platformOnly() { return platformOnly; }
    boolean hasInFlight() { return pending != null && !pending.isComplete(); }

    private void mark(String key, boolean owned) throws IOException {
        SharedPreferences.Editor edit = ownership.edit();
        if (owned) edit.putBoolean(key, true); else edit.remove(key);
        if (!edit.commit()) throw new IOException("無法保存定位清理紀錄");
    }

    private void await(Task<Void> task) throws Exception {
        if (hasInFlight()) throw new IllegalStateException("上一個 Google 定位操作仍未完成");
        pending = task;
        try { Tasks.await(task, 10, TimeUnit.SECONDS); }
        finally { if (task.isComplete()) pending = null; }
    }

    private void setFusedMode(boolean enabled) throws Exception {
        // Permission can be revoked after the service setup check, including during cleanup.
        try { await(fused.setMockMode(enabled)); }
        catch (SecurityException e) { throw new IOException("Google 模擬定位權限已失效", e); }
    }

    void enable() throws Exception {
        int availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context);
        platformOnly = availability == ConnectionResult.SERVICE_MISSING;
        if (!platformOnly && availability != ConnectionResult.SUCCESS) {
            throw new IllegalStateException("Google Play 服務無法使用，請啟用或更新後重試："
                    + GoogleApiAvailability.getInstance().getErrorString(availability));
        }
        if (!platformOnly) {
            mark("fused", true);
            setFusedMode(true);
        }
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            mark(provider, true);
            ProviderProperties properties = new ProviderProperties.Builder()
                    .setHasAltitudeSupport(true).setHasSpeedSupport(true).setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE).build();
            manager.addTestProvider(provider, properties);
            manager.setTestProviderEnabled(provider, true);
        }
    }

    void publish(RouteEngine.Sample sample) throws Exception {
        long elapsed = SystemClock.elapsedRealtimeNanos();
        long wall = System.currentTimeMillis();
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            manager.setTestProviderLocation(provider, location(provider, sample, wall, elapsed));
        }
        if (!platformOnly) {
            try { await(fused.setMockLocation(location("fused", sample, wall, elapsed))); }
            catch (SecurityException e) { throw new IOException("Google 模擬定位權限已失效", e); }
        }
    }

    private static Location location(String provider, RouteEngine.Sample sample, long wall, long elapsed) {
        Location result = new Location(provider);
        result.setLatitude(sample.point().latitude());
        result.setLongitude(sample.point().longitude());
        result.setAccuracy(3f);
        result.setAltitude(0);
        result.setVerticalAccuracyMeters(5);
        result.setSpeed((float) sample.speedMps());
        result.setSpeedAccuracyMetersPerSecond(0.1f);
        result.setBearing(sample.bearingDegrees());
        result.setBearingAccuracyDegrees(1);
        result.setTime(wall);
        result.setElapsedRealtimeNanos(elapsed);
        return result;
    }

    void cleanup() throws Exception {
        if (!hasPending(context)) return;
        if (!MockLocationService.isSelectedMockApp(context))
            throw new SecurityException("請重新指定本 App 為模擬位置 App，再重試清理");
        Exception failure = null;
        // Never race a timed-out enable with disable. Retry only after its actual completion.
        if (hasInFlight()) failure = new IOException("等待 Google 定位操作完成後清理");
        else if (ownership.getBoolean("fused", false)) {
            try { setFusedMode(false); mark("fused", false); }
            catch (Exception e) { failure = e; }
        }
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            if (!ownership.getBoolean(provider, false)) continue;
            try { manager.removeTestProvider(provider); mark(provider, false); }
            catch (Exception e) { if (failure == null) failure = e; }
        }
        if (failure != null) throw failure;
    }
}
