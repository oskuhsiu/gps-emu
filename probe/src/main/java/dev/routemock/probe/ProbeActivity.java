package dev.routemock.probe;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.Task;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An intentionally independent receiver used to verify that another process observes mock
 * locations. It has no dependency on the main app or on the core route model.
 */
public final class ProbeActivity extends Activity {
    private static final String TAG = "RouteProbe";
    private static final int LOCATION_PERMISSION_REQUEST = 4101;
    private static final long UPDATE_INTERVAL_MS = 1_000L;
    private static final long MIN_UPDATE_INTERVAL_MS = 500L;

    private static final String CHANNEL_GPS = "GPS";
    private static final String CHANNEL_NETWORK = "NETWORK";
    private static final String CHANNEL_FUSED = "FUSED";

    private final Map<String, ChannelState> channelStates = new LinkedHashMap<>();
    private final List<String> errors = new ArrayList<>();

    private LocationManager locationManager;
    private FusedLocationProviderClient fusedClient;
    private TextView statusView;
    private LinearLayout samplesContainer;
    private final Map<String, TextView> sampleViews = new LinkedHashMap<>();

    private boolean receiveRequested;
    private boolean receiverCycleActive;
    private boolean platformRegistered;
    private boolean fusedRegistered;

    private final LocationListener platformListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            String provider = safeProvider(location.getProvider());
            String channel;
            if (LocationManager.GPS_PROVIDER.equals(provider)) {
                channel = CHANNEL_GPS;
            } else if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
                channel = CHANNEL_NETWORK;
            } else {
                channel = "ANDROID_" + provider.toUpperCase(Locale.US);
            }
            recordLocation(channel, location);
        }

        @Override
        public void onProviderDisabled(String provider) {
            reportError(providerName(provider) + " provider became unavailable");
        }

        @Override
        public void onProviderEnabled(String provider) {
            reportInfo(providerName(provider) + " provider is available");
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
            // Required by LocationListener before API 30; samples remain the source of truth.
        }
    };

    private final LocationCallback fusedCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult result) {
            if (result == null) {
                reportError("Fused location returned an empty result");
                return;
            }
            for (Location location : result.getLocations()) {
                if (location != null) {
                    recordLocation(CHANNEL_FUSED, location);
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(245, 247, 250));
        getWindow().setNavigationBarColor(Color.rgb(245, 247, 250));

        locationManager = getSystemService(LocationManager.class);
        fusedClient = LocationServices.getFusedLocationProviderClient(getApplicationContext());

        channelStates.put(CHANNEL_GPS, new ChannelState(CHANNEL_GPS));
        channelStates.put(CHANNEL_NETWORK, new ChannelState(CHANNEL_NETWORK));
        channelStates.put(CHANNEL_FUSED, new ChannelState(CHANNEL_FUSED));
        buildUi();
        requestStart();
    }

    @Override
    protected void onStart() {
        super.onStart();
        startIfRequested();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // onStart and onResume can both be reached after a permission dialog. Registration is
        // guarded by receiverCycleActive so this remains one callback set per visible Activity.
        startIfRequested();
    }

    @Override
    protected void onStop() {
        stopReceivers();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopReceivers();
        super.onDestroy();
    }

    private void buildUi() {
        final ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(245, 247, 250));

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        scrollView.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                top = systemBars.top;
                bottom = systemBars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(20), dp(18) + top, dp(20), dp(24) + bottom);
            return insets;
        });

        TextView title = textView("Route Probe", 26, Color.rgb(24, 36, 52));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setContentDescription("Route Probe independent location receiver");
        root.addView(title, marginParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0, 0, 0));

        TextView subtitle = textView(
                "獨立接收端：讀取這支裝置目前收到的 GPS、網路與 Google Fused 定位。\n"
                        + "它不依賴主 App、不注入定位，只用來確認其他 App 是否看見同一筆位置。",
                15, Color.rgb(72, 84, 98));
        subtitle.setContentDescription("這是獨立的接收端，不依賴主 App");
        root.addView(subtitle, marginParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12), 0, 0, 0));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        root.addView(actions, marginParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(18), 0, 0, 0));

        Button startButton = new Button(this);
        startButton.setText("開始接收");
        startButton.setAllCaps(false);
        startButton.setContentDescription("開始接收定位更新");
        startButton.setTag("start_receiving");
        startButton.setOnClickListener(view -> requestStart());
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(52), 1f));

        Button clearButton = new Button(this);
        clearButton.setText("清除紀錄");
        clearButton.setAllCaps(false);
        clearButton.setContentDescription("清除所有定位紀錄");
        clearButton.setTag("clear_records");
        clearButton.setOnClickListener(view -> clearRecords());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
        clearParams.leftMargin = dp(10);
        actions.addView(clearButton, clearParams);

        statusView = textView("尚未開始。請按「開始接收」並授予定位權限。", 14,
                Color.rgb(48, 62, 78));
        statusView.setTag("receiver_status");
        statusView.setContentDescription("定位接收狀態");
        statusView.setBackgroundColor(Color.WHITE);
        root.addView(statusView, marginParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(16), 0, 0, 0));

        TextView heading = textView("最後收到的樣本", 19, Color.rgb(24, 36, 52));
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading, marginParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22), 0, 0, 0));

        samplesContainer = new LinearLayout(this);
        samplesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(samplesContainer, marginParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 0, 0, 0));
        for (String channel : channelStates.keySet()) {
            TextView sampleView = textView(channelStates.get(channel).render(), 14,
                    Color.rgb(44, 57, 72));
            sampleView.setTag("sample_" + channel.toLowerCase(Locale.US));
            sampleView.setContentDescription(channel + " 最後定位樣本");
            sampleView.setBackgroundColor(Color.WHITE);
            sampleViews.put(channel, sampleView);
            samplesContainer.addView(sampleView,
                    marginParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8), 0, 0, 0));
        }

        setContentView(scrollView);
        scrollView.requestApplyInsets();
    }

    private void requestStart() {
        receiveRequested = true;
        if (!hasLocationPermission()) {
            statusView.setText("正在請求精確與大略定位權限；授予後才會開始接收。\n"
                    + "若系統只允許大略位置，仍會顯示實際收到的樣本。\n\n" + errorsText());
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            }, LOCATION_PERMISSION_REQUEST);
            return;
        }
        startReceivers();
    }

    private void startIfRequested() {
        if (receiveRequested && hasLocationPermission()) {
            startReceivers();
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startReceivers() {
        if (!hasLocationPermission()) {
            reportError("定位權限尚未授予，沒有開始接收");
            return;
        }
        if (receiverCycleActive) {
            return;
        }
        receiverCycleActive = true;
        registerPlatformReceivers();
        registerFusedReceiver();
        refreshStatus();
    }

    private void registerPlatformReceivers() {
        if (locationManager == null) {
            reportError("LocationManager unavailable on this device");
            return;
        }
        int registered = 0;
        registered += requestPlatformProvider(LocationManager.GPS_PROVIDER, "GPS");
        registered += requestPlatformProvider(LocationManager.NETWORK_PROVIDER, "Network");
        platformRegistered = registered > 0;
        if (registered == 0) {
            reportError("No GPS or network provider is currently available");
        }
    }

    private int requestPlatformProvider(String provider, String label) {
        try {
            if (!locationManager.isProviderEnabled(provider)) {
                reportError(label + " provider is disabled or unavailable");
                return 0;
            }
            locationManager.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, 0f,
                    platformListener, Looper.getMainLooper());
            return 1;
        } catch (SecurityException e) {
            reportError(label + " provider permission denied", e);
        } catch (IllegalArgumentException e) {
            reportError(label + " provider is not supported", e);
        } catch (RuntimeException e) {
            reportError(label + " provider could not be registered", e);
        }
        return 0;
    }

    private void registerFusedReceiver() {
        int availability = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this);
        if (availability != ConnectionResult.SUCCESS) {
            reportError("Google Play services unavailable (code " + availability + ")");
            return;
        }

        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
                .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
                .setWaitForAccurateLocation(false)
                .build();
        fusedRegistered = true;
        try {
            Task<Void> registration = fusedClient.requestLocationUpdates(
                    request, fusedCallback, Looper.getMainLooper());
            registration.addOnFailureListener(error -> {
                fusedRegistered = false;
                reportError("Google Fused receiver could not be registered", error);
            });
        } catch (SecurityException e) {
            fusedRegistered = false;
            reportError("Google Fused receiver permission denied", e);
        } catch (RuntimeException e) {
            fusedRegistered = false;
            reportError("Google Fused receiver could not be registered", e);
        }
    }

    private void stopReceivers() {
        receiverCycleActive = false;
        if (platformRegistered && locationManager != null) {
            try {
                locationManager.removeUpdates(platformListener);
            } catch (RuntimeException e) {
                reportError("Could not remove Android location updates", e);
            }
        }
        platformRegistered = false;

        if (fusedRegistered && fusedClient != null) {
            fusedRegistered = false;
            try {
                fusedClient.removeLocationUpdates(fusedCallback)
                        .addOnFailureListener(error ->
                                reportError("Could not remove Google Fused updates", error));
            } catch (RuntimeException e) {
                reportError("Could not remove Google Fused updates", e);
            }
        }
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) {
            return;
        }
        if (hasLocationPermission()) {
            reportInfo("定位權限已授予，開始接收");
            receiveRequested = true;
            startReceivers();
        } else {
            receiveRequested = false;
            reportError("定位權限被拒絕，沒有開始接收");
        }
    }

    private void recordLocation(String channel, Location location) {
        ChannelState state = channelStates.get(channel);
        if (state == null) {
            state = new ChannelState(channel);
            channelStates.put(channel, state);
        }
        state.count++;
        state.lastLocation = new Location(location);
        TextView view = sampleViews.get(channel);
        if (view == null && samplesContainer != null) {
            view = textView(state.render(), 14, Color.rgb(44, 57, 72));
            view.setTag("sample_" + channel.toLowerCase(Locale.US));
            view.setContentDescription(channel + " 最後定位樣本");
            view.setBackgroundColor(Color.WHITE);
            sampleViews.put(channel, view);
            samplesContainer.addView(view,
                    marginParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8), 0, 0, 0));
        }
        if (view != null) {
            view.setText(state.render());
        }
        logLocation(channel, location);
        refreshStatus();
    }

    private void logLocation(String channel, Location location) {
        JSONObject record = new JSONObject();
        try {
            record.put("channel", channel);
            record.put("provider", safeProvider(location.getProvider()));
            record.put("latitude", location.getLatitude());
            record.put("longitude", location.getLongitude());
            record.put("speedMps", location.hasSpeed() ? location.getSpeed() : 0.0);
            record.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : -1.0);
            record.put("isMock", isMock(location));
            record.put("elapsedRealtimeNanos", location.getElapsedRealtimeNanos());
            record.put("wallTimeMs", location.getTime());
            Log.i(TAG, record.toString());
        } catch (JSONException e) {
            Log.e(TAG, "Could not encode location sample", e);
        }
    }

    private void clearRecords() {
        for (ChannelState state : channelStates.values()) {
            state.count = 0;
            state.lastLocation = null;
        }
        errors.clear();
        for (Map.Entry<String, TextView> entry : sampleViews.entrySet()) {
            entry.getValue().setText(channelStates.get(entry.getKey()).render());
        }
        statusView.setText("紀錄已清除；等待新的定位樣本。\n" + (receiverCycleActive
                ? "目前仍在接收。" : "請按「開始接收」啟動。"));
    }

    private void reportInfo(String message) {
        if (statusView == null) {
            return;
        }
        refreshStatus(message, false);
    }

    private void reportError(String message) {
        reportError(message, null);
    }

    private void reportError(String message, Throwable error) {
        String detail = message;
        if (error != null && error.getMessage() != null && !error.getMessage().isEmpty()) {
            detail += ": " + error.getMessage();
        }
        errors.add(detail);
        if (errors.size() > 8) {
            errors.remove(0);
        }
        Log.w(TAG, detail, error);
        refreshStatus();
    }

    private void refreshStatus() {
        refreshStatus(null, false);
    }

    private void refreshStatus(String transientMessage, boolean ignored) {
        if (statusView == null) {
            return;
        }
        StringBuilder status = new StringBuilder();
        if (transientMessage != null && !transientMessage.isEmpty()) {
            status.append(transientMessage).append('\n');
        }
        if (receiverCycleActive) {
            status.append("接收中：畫面停止時會移除所有更新，返回後會恢復。\n");
        } else if (receiveRequested) {
            status.append("已要求接收；等待可用的定位來源。\n");
        } else {
            status.append("尚未開始接收。\n");
        }
        if (!errors.isEmpty()) {
            status.append('\n').append(errorsText());
        }
        statusView.setText(status.toString().trim());
    }

    private String errorsText() {
        if (errors.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder("最近的錯誤：");
        for (String error : errors) {
            result.append('\n').append("• ").append(error);
        }
        return result.toString();
    }

    private static boolean isMock(Location location) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return location.isMock();
        }
        return location.isFromMockProvider();
    }

    private static String safeProvider(String provider) {
        return provider == null || provider.isEmpty() ? "unknown" : provider;
    }

    private static String providerName(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            return "GPS";
        }
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) {
            return "Network";
        }
        return safeProvider(provider);
    }

    private TextView textView(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        return view;
    }

    private LinearLayout.LayoutParams marginParams(int width, int top, int right, int bottom,
                                                   int left) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        params.rightMargin = right;
        params.bottomMargin = bottom;
        params.leftMargin = left;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class ChannelState {
        private final String channel;
        private long count;
        private Location lastLocation;

        private ChannelState(String channel) {
            this.channel = channel;
        }

        private String render() {
            if (lastLocation == null) {
                return channel + "\n尚無樣本\ncount：0（樣本數）";
            }
            String provider = safeProvider(lastLocation.getProvider());
            String speed = lastLocation.hasSpeed()
                    ? String.format(Locale.US, "%.2f m/s", lastLocation.getSpeed()) : "未知";
            String accuracy = lastLocation.hasAccuracy()
                    ? String.format(Locale.US, "%.2f m", lastLocation.getAccuracy()) : "未知";
            return String.format(Locale.US,
                    "%s\ncount：%d（樣本數）\nprovider：%s\nlatitude/longitude：%.7f, %.7f\n"
                            + "speedMps：%s\naccuracy：%s\nisMock：%s\nelapsedRealtimeNanos：%d",
                    channel, count, provider, lastLocation.getLatitude(), lastLocation.getLongitude(),
                    speed, accuracy, isMock(lastLocation), lastLocation.getElapsedRealtimeNanos());
        }
    }
}
