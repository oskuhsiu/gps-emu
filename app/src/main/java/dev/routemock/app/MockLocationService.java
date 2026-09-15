package dev.routemock.app;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import dev.routemock.core.RouteEngine;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** All engine and sink transitions run on one worker, including Stop and asynchronous GMS waits. */
public final class MockLocationService extends Service {
    public static final String START = "dev.routemock.START", HOLD = "dev.routemock.HOLD",
            STOP = "dev.routemock.STOP", RELEASE = "dev.routemock.RELEASE", PAUSE = "dev.routemock.PAUSE",
            RESUME = "dev.routemock.RESUME", SPEED = "dev.routemock.SPEED",
            CLEANUP = "dev.routemock.CLEANUP";
    public record Status(String phase, String message, RouteEngine.Sample sample,
                         boolean active, boolean needsCleanup, boolean platformOnly) {}
    public static volatile Status status = new Status("STOPPED", "尚未開始", null, false, false, false);
    // Process-wide barrier: Activity recreation must not reuse a pre-cleanup fix.
    static volatile long realLocationNotBeforeNanos;
    private static final String CHANNEL = "mock-session";
    private static final int NOTIFICATION = 10;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> ticker;
    private MockSink sink;
    private RouteEngine engine;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean foreground;
    private boolean stopping;
    private boolean destroyed;
    private int latestStartId;
    private String stopMessage;
    private boolean stopError;

    public static boolean hasPendingCleanup(Context context) { return MockSink.hasPending(context); }
    public static boolean isSelectedMockApp(Context context) {
        try {
            AppOpsManager ops = context.getSystemService(AppOpsManager.class);
            int mode = Build.VERSION.SDK_INT >= 29
                    ? ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.getPackageName())
                    : ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) { return false; }
    }

    @Override public void onCreate() {
        super.onCreate();
        sink = new MockSink(this);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "RouteMock:playback");
        wakeLock.setReferenceCounted(false);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "模擬位置行程", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? RELEASE : intent.getAction();
        boolean mustForeground = START.equals(action) || HOLD.equals(action) || CLEANUP.equals(action);
        if (mustForeground && !foreground) {
            try {
                startLocationForeground(CLEANUP.equals(action));
                foreground = true;
            } catch (RuntimeException e) {
                status = new Status("ERROR", "無法啟動前景定位：" + detail(e), null,
                        false, hasPendingCleanup(this), false);
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        }
        double speed = intent == null ? Double.NaN : intent.getDoubleExtra("speedKmh", Double.NaN);
        worker.execute(() -> {
            latestStartId = startId;
            command(action, speed);
        });
        return START_NOT_STICKY;
    }

    private void startLocationForeground(boolean cleanup) {
        Notification notification = notification(cleanup ? "正在清理模擬位置" : "正在準備模擬位置", false, !cleanup);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION, notification);
        }
    }

    private void command(String action, double speed) {
        if (destroyed) return;
        if (RELEASE.equals(action) || CLEANUP.equals(action)) { stopSession("模擬定位已關閉；接收 App 仍需等待新的真實定位", false); return; }
        if (stopping) return;
        try {
            if (START.equals(action) || HOLD.equals(action)) {
                if (engine != null) return;
                if (!foreground) {
                    startLocationForeground(false);
                    foreground = true;
                }
                if (hasPendingCleanup(this)) throw new IllegalStateException("上次模擬尚未清理，請先清理");
                requireSetup();
                DraftStore.Draft draft = DraftStore.load(this);
                var points = draft.route();
                if (HOLD.equals(action)) {
                    if (draft.waypoints().isEmpty()) throw new IllegalArgumentException("請先選擇定點位置");
                    points = List.of(draft.waypoints().get(draft.waypoints().size() - 1));
                } else if (points.size() < 2) throw new IllegalArgumentException("請先規劃步行路線");
                new RouteEngine(points, draft.speedKmh(), draft.mode(), SystemClock.elapsedRealtimeNanos()); // Validate before sink mutation.
                status = new Status("PREPARING", "正在啟用模擬位置", null, true, false, false);
                sink.enable();
                engine = new RouteEngine(points, draft.speedKmh(), draft.mode(), SystemClock.elapsedRealtimeNanos());
                tick();
                if (engine != null && !stopping)
                    ticker = worker.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
            } else if (engine != null) {
                long now = SystemClock.elapsedRealtimeNanos();
                if (STOP.equals(action)) engine.stop();
                else if (PAUSE.equals(action)) engine.pause(now);
                else if (RESUME.equals(action)) engine.resume(now);
                else if (SPEED.equals(action)) engine.setSpeedKmh(speed, now);
                tick();
            } else if (!foreground) stopSelf(latestStartId);
        } catch (Exception e) { stopSession(detail(e), true); }
    }

    private void requireSetup() {
        if (!isSelectedMockApp(this)) throw new SecurityException("請在開發人員選項指定本 App 為模擬位置 App");
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("需要精確位置權限");
        if (!getSystemService(LocationManager.class).isLocationEnabled())
            throw new IllegalStateException("請開啟系統定位服務");
    }

    private void tick() {
        if (engine == null || stopping || destroyed) return;
        try {
            requireSetup();
            wakeLock.acquire(30_000);
            RouteEngine.Sample sample = engine.sample(SystemClock.elapsedRealtimeNanos());
            sink.publish(sample);
            String message = switch (sample.phase()) {
                case MOVING -> "沿路線行走中";
                case PAUSED -> "已暫停，持續定點";
                case HOLDING -> "定點模擬中";
                case ARRIVED -> "已到達，持續定點";
                case STOPPED -> "已停止行走 · 維持定點";
            };
            if (sink.platformOnly()) message += "（僅 Android 定位；Google 服務不可用）";
            status = new Status(sample.phase().name(), message, sample, true, false, sink.platformOnly());
            getSystemService(NotificationManager.class).notify(NOTIFICATION, notification(message, sample.phase() == RouteEngine.Phase.MOVING
                    || sample.phase() == RouteEngine.Phase.PAUSED, true));
        } catch (Exception e) { stopSession(detail(e), true); }
    }

    private void stopSession(String message, boolean error) {
        if (stopping) return;
        stopMessage = message; stopError = error;
        stopping = true;
        if (ticker != null) { ticker.cancel(false); ticker = null; }
        engine = null;
        cleanup();
    }

    private void cleanup() {
        status = new Status("STOPPING", "正在清理模擬位置", status.sample(), true,
                hasPendingCleanup(this), sink.platformOnly());
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION,
                notification("正在清理模擬位置", false, false));
        try { sink.cleanup(); }
        catch (Exception e) {
            if (sink.hasInFlight()) {
                status = new Status("STOPPING", "等待 Google 定位操作完成後清理", status.sample(),
                        true, hasPendingCleanup(this), sink.platformOnly());
                // Keep the foreground service alive until the timed-out GMS operation settles.
                worker.schedule(this::cleanup, 1, TimeUnit.SECONDS);
                return;
            }
            status = new Status("ERROR", "清理未完成：" + detail(e), status.sample(),
                    false, hasPendingCleanup(this), sink.platformOnly());
            finishStop();
            return;
        }
        realLocationNotBeforeNanos = SystemClock.elapsedRealtimeNanos();
        status = new Status(stopError ? "ERROR" : "STOPPED", stopMessage, status.sample(),
                false, hasPendingCleanup(this), sink.platformOnly());
        finishStop();
    }

    private void finishStop() {
        if (wakeLock.isHeld()) wakeLock.release();
        stopping = false;
        if (foreground) { stopForeground(STOP_FOREGROUND_REMOVE); foreground = false; }
        stopSelf(latestStartId);
        if (destroyed) worker.shutdown();
    }

    private Notification notification(String text, boolean canStop, boolean canRelease) {
        PendingIntent launch = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_location)
                .setContentTitle("Route Mock").setContentText(text).setContentIntent(launch)
                .setOngoing(true).setOnlyAlertOnce(true);
        if (canStop) {
            PendingIntent stop = PendingIntent.getService(this, 1,
                    new Intent(this, MockLocationService.class).setAction(STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "停止行走", stop).build());
        }
        if (canRelease) {
            PendingIntent release = PendingIntent.getService(this, 2,
                    new Intent(this, MockLocationService.class).setAction(RELEASE),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null, "恢復真實定位", release).build());
        }
        return builder.build();
    }

    private static String detail(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    @Override public void onDestroy() {
        super.onDestroy();
        worker.execute(() -> {
            destroyed = true;
            if (stopping) return; // Existing serialized cleanup owns the outstanding Task.
            if (engine != null) stopSession("服務已結束", false);
            else {
                if (wakeLock.isHeld()) wakeLock.release();
                worker.shutdown();
            }
        });
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
