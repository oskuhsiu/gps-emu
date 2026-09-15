package dev.routemock.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import dev.routemock.core.GeoPoint;
import dev.routemock.core.PlaybackMode;
import dev.routemock.core.RouteEngine;
import org.json.JSONException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private DraftStore.Draft draft = DraftStore.empty();
    private WebView map;
    private TextView status, summary, speedText, modeHint, details, message;
    private SeekBar speed;
    private RadioGroup modeSelector;
    private LinearLayout idleActions, playbackActions;
    private Button plan, start, pause, stop, restore, hold;
    private ImageButton locate, coordinate, undo, clear;
    private boolean mapReady, planning, startPending;
    private enum RealAction { LOCATE, PLAN, START }
    private RealLocationFinder realFinder;
    private Location realFix;
    private RealAction realAction;
    private boolean resumed, awaitingLocationPermission, awaitingRestore, releaseForLocation, autoLocateAttempted, locationFailed;
    private long restoreDeadline;
    private MockLocationService.Status restoreStartStatus;
    private MockLocationService.Status seenStatus = MockLocationService.status;
    private int routeRevision;
    private long lastPlanTime;
    private String localMessage = "點按地圖加入途經點，或用「座標」輸入。";
    private final Runnable commitSpeed = () -> {
        if (saveDraft() && MockLocationService.status.active()) sendCommand(MockLocationService.SPEED);
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() { advanceRealLocation(); render(); ui.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        realFinder = new RealLocationFinder(this);
        autoLocateAttempted = state != null && state.getBoolean("autoLocateAttempted");
        try { draft = DraftStore.load(this); } catch (IOException e) { localMessage = e.getMessage(); }
        buildScreen();
        speed.setProgress((int) Math.round(draft.speedKmh() * 2) - 1);
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!planning) {
            try {
                draft = DraftStore.load(this);
                speed.setProgress((int) Math.round(draft.speedKmh() * 2) - 1);
                showDraft(false);
            } catch (IOException e) { localMessage = e.getMessage(); }
        }
        map.onResume();
        boolean staleFix = !canReuseRealFix(RealLocationFinder.MAX_SAMPLE_AGE_NANOS);
        boolean canRefresh = !autoLocateAttempted || (staleFix
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED);
        if (canRefresh && !planning && !MockLocationService.status.active()
                && !MockLocationService.hasPendingCleanup(this)) {
            autoLocateAttempted = true;
            requestRealLocation(RealAction.LOCATE, false);
        }
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }
    @Override protected void onPause() {
        resumed = false;
        if (!awaitingLocationPermission) cancelRealLocation();
        ui.removeCallbacks(refresh);
        map.onPause();
        super.onPause();
    }
    @Override protected void onStop() {
        cancelRealLocation();
        super.onStop();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("autoLocateAttempted", autoLocateAttempted);
        super.onSaveInstanceState(state);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != 8) return;
        boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (realAction == null) {
            if (granted) {
                autoLocateAttempted = false;
                if (resumed && !planning && !MockLocationService.status.active() && !MockLocationService.hasPendingCleanup(this))
                    requestRealLocation(RealAction.LOCATE, false);
            }
            return;
        }
        awaitingLocationPermission = false;
        if (!granted)
            failRealLocation("請允許精確位置權限，再按「定位」重試。");
        else advanceRealLocation();
    }
    @Override protected void onDestroy() {
        realFinder.cancel();
        routeRevision++;
        ui.removeCallbacksAndMessages(null);
        network.shutdownNow();
        map.removeJavascriptInterface("RouteMock");
        map.destroy();
        super.onDestroy();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(int resource) { return getColor(resource); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("tnum");
        return view;
    }
    private TextView heading(String value) {
        TextView view = text(value, 16, color(R.color.ui_ink));
        view.setTypeface(null, Typeface.BOLD); view.setAccessibilityHeading(true);
        return view;
    }
    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this); column.setOrientation(LinearLayout.VERTICAL); return column;
    }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL); return row;
    }
    private Button button(String title, String locator, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setMinHeight(dp(48));
        b.setMinimumWidth(0); b.setMinWidth(0);
        b.setTextSize(14); b.setPadding(dp(12), dp(8), dp(12), dp(8)); b.setContentDescription(locator);
        b.setTypeface(null, Typeface.BOLD); b.setStateListAnimator(null);
        styleButton(b, R.drawable.ui_button_secondary, R.color.ui_control_text);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private ImageButton iconButton(int icon, String locator, Runnable action) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon); button.setImageTintList(getColorStateList(R.color.ui_control_text));
        button.setPadding(dp(12),dp(12),dp(12),dp(12));
        button.setBackgroundResource(R.drawable.ui_button_outline);
        button.setContentDescription(locator); button.setTooltipText(locator);
        button.setOnClickListener(v -> action.run()); return button;
    }
    private void styleButton(Button button, int background, int foreground) {
        button.setBackgroundResource(background);
        button.setTextColor(getColorStateList(foreground));
    }
    private void addButton(LinearLayout row, Button button) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        if (row.getChildCount() > 0) params.setMarginStart(dp(8));
        row.addView(button, params);
    }
    private void divider(LinearLayout parent) {
        View line = new View(this); line.setBackgroundColor(color(R.color.ui_border));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.setMargins(0, dp(8), 0, dp(8)); parent.addView(line, params);
    }
    private void addSpaced(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top); parent.addView(child, params);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildScreen() {
        LinearLayout root = column(); root.setBackgroundColor(color(R.color.ui_background));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        LinearLayout header = row(); header.setPadding(dp(16), dp(4), dp(12), dp(4));
        LinearLayout titles = column();
        TextView title = text("Route Mock", 20, color(R.color.ui_ink)); title.setTypeface(null, Typeface.BOLD); titles.addView(title);
        status = text("待命", 12, color(R.color.ui_accent)); status.setContentDescription("行程狀態"); titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0,-2,1));
        ImageButton settings = iconButton(R.drawable.ui_settings, "開啟設定引導", this::showSetup);
        settings.setBackgroundResource(R.drawable.ui_button_secondary);
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(dp(48), dp(48));
        settingsParams.setMarginStart(dp(8)); header.addView(settings, settingsParams); root.addView(header);
        LinearLayout body = column(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        FrameLayout mapPanel = new FrameLayout(this);
        map = new WebView(this); map.setContentDescription("步行路線地圖");
        map.getSettings().setJavaScriptEnabled(true);
        map.getSettings().setAllowFileAccess(false); map.getSettings().setAllowContentAccess(false);
        map.getSettings().setGeolocationEnabled(false); map.getSettings().setDomStorageEnabled(false);
        map.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        map.getSettings().setUserAgentString(map.getSettings().getUserAgentString() + " " + WalkingRouter.USER_AGENT);
        map.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
        map.addJavascriptInterface(new Object() {
            @JavascriptInterface public void addPoint(double lat, double lon) { ui.post(() -> appendPoint(lat,lon)); }
        }, "RouteMock");
        map.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equals(uri.getScheme()) && "routemock.local".equals(uri.getHost())) {
                    String path = uri.getPath();
                    if (path == null || path.contains("..")) return denied();
                    String asset = path.substring(1);
                    String mime = asset.endsWith(".js") ? "application/javascript" : asset.endsWith(".css") ? "text/css" : "text/html";
                    try { return new WebResourceResponse(mime,"UTF-8",getAssets().open(asset)); }
                    catch (IOException e) { return denied(); }
                }
                if ("https".equals(uri.getScheme()) && "tile.openstreetmap.org".equals(uri.getHost())) return null;
                return denied();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("www.openstreetmap.org".equals(uri.getHost()) && "https".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (RuntimeException ignored) { toast("沒有可開啟的瀏覽器"); }
                }
                return true;
            }
            @Override public void onPageFinished(WebView view, String url) {
                mapReady = true;
                showDraft(MockLocationService.status.active() || MockLocationService.hasPendingCleanup(MainActivity.this));
                showRealFix(true);
            }
        });
        mapPanel.addView(map, new FrameLayout.LayoutParams(-1, -1));
        GridLayout mapTools = new GridLayout(this); mapTools.setColumnCount(1); mapTools.setRowCount(4);
        locate = iconButton(R.drawable.ui_locate, "定位到真實位置", () -> requestRealLocation(RealAction.LOCATE, true));
        coordinate = iconButton(R.drawable.ui_add_point, "輸入座標", this::coordinateDialog);
        undo = iconButton(R.drawable.ui_undo, "撤回途經點", this::undo);
        clear = iconButton(R.drawable.ui_clear, "清除路線", () -> replacePoints(draft.waypoints().isEmpty()
                ? List.of() : List.of(draft.waypoints().get(0))));
        for (ImageButton tool : new ImageButton[]{locate,coordinate,undo,clear}) {
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(); params.width=dp(48); params.height=dp(48);
            params.setMargins(0,0,dp(6),dp(6)); tool.setElevation(dp(2)); mapTools.addView(tool,params);
        }
        FrameLayout.LayoutParams toolsParams = new FrameLayout.LayoutParams(-2,-2,Gravity.TOP | Gravity.END);
        toolsParams.setMargins(dp(12),dp(12),dp(6),dp(12)); mapPanel.addView(mapTools,toolsParams);
        mapPanel.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int columns = b-t >= dp(270) ? 1 : 2;
            if (mapTools.getColumnCount() != columns) {
                // Expand before reassigning specs, then shrink to the new bounds.
                mapTools.setColumnCount(2); mapTools.setRowCount(4);
                for (int i=0;i<mapTools.getChildCount();i++) {
                    GridLayout.LayoutParams params = (GridLayout.LayoutParams)mapTools.getChildAt(i).getLayoutParams();
                    params.rowSpec=GridLayout.spec(i/columns); params.columnSpec=GridLayout.spec(i%columns);
                    mapTools.getChildAt(i).setLayoutParams(params);
                }
                mapTools.setRowCount(columns==1?4:2); mapTools.setColumnCount(columns);
            }
        });
        body.addView(mapPanel, new LinearLayout.LayoutParams(-1, 0, 1));
        map.loadUrl("https://routemock.local/map.html");

        LinearLayout inspector = column(); inspector.setBackgroundColor(color(R.color.ui_surface));
        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(false); scroller.setContentDescription("路線設定");
        scroller.setClipToPadding(false);
        LinearLayout controls = column(); controls.setPadding(dp(16),dp(12),dp(16),dp(12));
        LinearLayout routeHeader = row(); LinearLayout routeTitles = column();
        routeTitles.addView(heading("路線"));
        summary = text("",13,color(R.color.ui_muted)); addSpaced(routeTitles, summary, 4);
        routeHeader.addView(routeTitles, new LinearLayout.LayoutParams(0, -2, 1));
        plan = button("規劃步行", "規劃步行路線", this::planRoute);
        styleButton(plan, R.drawable.ui_button_outline, R.color.ui_control_text);
        LinearLayout.LayoutParams planParams = new LinearLayout.LayoutParams(dp(108), -2);
        planParams.setMarginStart(dp(8)); routeHeader.addView(plan, planParams); controls.addView(routeHeader);
        divider(controls);
        controls.addView(heading("行走模式"));
        modeSelector = new RadioGroup(this); modeSelector.setOrientation(LinearLayout.HORIZONTAL);
        modeSelector.setContentDescription("行走模式");
        for (PlaybackMode mode : PlaybackMode.values()) {
            RadioButton option = new RadioButton(this); option.setId(View.generateViewId()); option.setTag(mode);
            option.setText(modeLabel(mode)); option.setTextSize(14); option.setMinHeight(dp(48));
            option.setButtonDrawable(null); option.setGravity(Gravity.CENTER);
            option.setPadding(dp(4), dp(8), dp(4), dp(8));
            option.setBackgroundResource(R.drawable.ui_segment);
            option.setTextColor(getColorStateList(R.color.ui_segment_text));
            option.setContentDescription("模式：" + modeLabel(mode));
            RadioGroup.LayoutParams params = new RadioGroup.LayoutParams(0, -2, 1);
            if (modeSelector.getChildCount() > 0) params.setMarginStart(dp(4));
            modeSelector.addView(option, params);
            if (mode == draft.mode()) modeSelector.check(option.getId());
        }
        modeSelector.setOnCheckedChangeListener((group, id) -> {
            RadioButton selected = group.findViewById(id);
            if (selected != null) changeMode((PlaybackMode) selected.getTag());
        });
        addSpaced(controls,modeSelector,6);
        modeHint = text("",12,color(R.color.ui_muted)); addSpaced(controls,modeHint,4);
        divider(controls);
        LinearLayout speedHeader = row(); speedHeader.addView(heading("速度"),new LinearLayout.LayoutParams(0,-2,1));
        speedText = text("",16,color(R.color.ui_accent)); speedText.setTypeface(null,Typeface.BOLD);
        speedHeader.addView(speedText); controls.addView(speedHeader);
        speed = new SeekBar(this); speed.setMax(59); speed.setContentDescription("移動速度");
        controls.addView(speed, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout speedRange = row();
        speedRange.addView(text("0.5",12,color(R.color.ui_muted)),new LinearLayout.LayoutParams(0,-2,1));
        speedRange.addView(text("30 km/h",12,color(R.color.ui_muted))); controls.addView(speedRange);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int p, boolean user) {
                double value=(p+1)/2.0; speedText.setText(String.format(Locale.TAIWAN,"%.1f km/h",value));
                if (user) {
                    draft = new DraftStore.Draft(draft.waypoints(),draft.route(),value,draft.mode());
                    ui.removeCallbacks(commitSpeed);
                    ui.postDelayed(commitSpeed, 250);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seek) {}
            @Override public void onStopTrackingTouch(SeekBar seek) {
                ui.removeCallbacks(commitSpeed);
                commitSpeed.run();
            }
        });
        message = text("",13,color(R.color.ui_muted)); message.setPadding(dp(12),dp(10),dp(12),dp(10));
        message.setBackgroundColor(color(R.color.ui_background));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); addSpaced(controls,message,14);
        details = text("",12,color(R.color.ui_muted)); details.setTextIsSelectable(true); addSpaced(controls,details,8);
        scroller.addView(controls); inspector.addView(scroller,new LinearLayout.LayoutParams(-1,dp(290)));

        // Session commands stay reachable while the route settings scroll.
        LinearLayout footer = column(); footer.setPadding(dp(16),dp(8),dp(16),dp(10));
        idleActions = row(); hold = button("定點", "開始定點", () -> begin(true)); start = button("開始行走", "開始行走", () -> begin(false));
        styleButton(start, R.drawable.ui_button_primary, R.color.ui_button_text);
        addButton(idleActions,hold); addButton(idleActions,start); footer.addView(idleActions);
        playbackActions = row(); pause = button("暫停", "暫停或繼續", () -> sendCommand("PAUSED".equals(MockLocationService.status.phase()) ? MockLocationService.RESUME : MockLocationService.PAUSE));
        styleButton(pause, R.drawable.ui_button_primary, R.color.ui_button_text);
        stop = button("停止行走", "停止行走並保持位置", () -> sendCommand(MockLocationService.STOP));
        addButton(playbackActions,pause); addButton(playbackActions,stop); footer.addView(playbackActions);
        restore = button("恢復真實定位", "恢復真實定位", this::restoreOrCleanup);
        styleButton(restore, R.drawable.ui_button_outline, R.color.ui_control_text); addSpaced(footer,restore,8);
        View footerBorder = new View(this); footerBorder.setBackgroundColor(color(R.color.ui_border));
        inspector.addView(footerBorder, new LinearLayout.LayoutParams(-1,dp(1))); inspector.addView(footer);
        body.addView(inspector, new LinearLayout.LayoutParams(-1, -2));
        // Use side-by-side panes in wide windows; keep long settings scrollable at large font sizes.
        Runnable adaptLayout = () -> {
            int available = body.getHeight();
            if (available == 0) return;
            boolean wide = body.getWidth() > available;
            int orientation = wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
            if (body.getOrientation() != orientation) {
                body.setOrientation(orientation);
                mapPanel.setLayoutParams(new LinearLayout.LayoutParams(wide?0:-1,wide?-1:0,1));
                inspector.setLayoutParams(new LinearLayout.LayoutParams(wide?0:-1,wide?-1:-2,wide?1:0));
            }
            int mapAllowance = Math.max(dp(120), available-footer.getMeasuredHeight()-dp(181));
            int height = wide ? 0 : Math.min(mapAllowance,
                    Math.min(dp(296),Math.max(dp(120),Math.round(available*0.50f))));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)scroller.getLayoutParams();
            float weight = wide ? 1 : 0;
            if (params.height != height || params.weight != weight) {
                params.height=height; params.weight=weight; scroller.setLayoutParams(params);
            }
        };
        body.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> adaptLayout.run());
        footer.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> adaptLayout.run());
        setContentView(root);
    }
    private static WebResourceResponse denied() { return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0])); }
    private boolean editable() { return !planning && !startPending && realAction == null && !MockLocationService.status.active() && !MockLocationService.hasPendingCleanup(this); }
    private static String modeLabel(PlaybackMode mode) {
        return switch (mode) {
            case ONCE -> "單次";
            case PING_PONG -> "原路往返";
            case LOOP -> "閉合循環";
        };
    }
    private void changeMode(PlaybackMode mode) {
        if (mode == draft.mode() || !editable()) return;
        routeRevision++;
        draft = draft.withMode(mode);
        if (saveDraft()) localMessage = draft.route().isEmpty()
                ? "模式已變更，請規劃步行路線。" : "模式已變更，可以開始行走。";
        showDraft(false); render();
    }
    private void appendPoint(double lat,double lon) {
        if (!editable()) return;
        if (draft.waypoints().isEmpty()) { toast("請先按「定位」取得目前位置，再加入目的地。"); return; }
        if (draft.waypoints().size()>=8) { toast("最多 8 個途經點"); return; }
        try { List<GeoPoint> points=new ArrayList<>(draft.waypoints()); points.add(new GeoPoint(lat,lon)); replacePoints(points); }
        catch (IllegalArgumentException e) { toast("緯度需在 ±90，經度需在 ±180 之內"); }
    }
    private void replacePoints(List<GeoPoint> points) {
        if (!editable()) return;
        routeRevision++;
        draft=new DraftStore.Draft(points,List.of(),draft.speedKmh(),draft.mode()); saveDraft();
        localMessage=points.isEmpty()?"點按地圖加入途經點，或用「座標」輸入。":"選點已更新；請規劃步行路線，或在最後一點定點。";
        showDraft(false); render();
    }
    private void undo() { if(draft.waypoints().size()>1)replacePoints(draft.waypoints().subList(0,draft.waypoints().size()-1)); }
    private void coordinateDialog() {
        EditText input=new EditText(this); input.setSingleLine(true); input.setHint("緯度, 經度"); input.setContentDescription("緯度與經度");
        input.setInputType(InputType.TYPE_CLASS_TEXT); input.setPadding(dp(24),dp(16),dp(24),dp(16));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("加入目的地座標").setMessage("輸入：緯度, 經度。第 1 點使用目前真實位置。").setView(input).setNegativeButton("取消",null).setPositiveButton("加入",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try { String[] parts=input.getText().toString().trim().split("[,，\\s]+"); if(parts.length!=2)throw new IllegalArgumentException();
                GeoPoint point=new GeoPoint(Double.parseDouble(parts[0]),Double.parseDouble(parts[1])); appendPoint(point.latitude(),point.longitude()); showDraft(true); dialog.dismiss();
            } catch(IllegalArgumentException e){input.setError("請輸入有效的緯度, 經度");}
        })); dialog.show();
    }
    private boolean saveDraft() {
        try { DraftStore.save(this,draft); return true; }
        catch(IOException e){localMessage=e.getMessage();toast(localMessage);return false;}
    }
    private void planRoute() {
        if(!editable() || draft.waypoints().size()<2) return;
        requestRealLocation(RealAction.PLAN, false);
    }
    private void planFromRealFix() {
        if(SystemClock.elapsedRealtime()-lastPlanTime<1500){toast("請稍候再規劃");return;}
        draft = new DraftStore.Draft(draft.waypoints(), List.of(), draft.speedKmh(), draft.mode());
        if (!saveDraft()) return;
        lastPlanTime=SystemClock.elapsedRealtime(); planning=true; localMessage="正在規劃步行路線…"; render();
        List<GeoPoint> points=draft.waypoints(); PlaybackMode mode=draft.mode();
        GeoPoint origin = new GeoPoint(realFix.getLatitude(), realFix.getLongitude());
        float accuracy = realFix.getAccuracy();
        int revision = ++routeRevision;
        network.execute(() -> {
            try { List<GeoPoint> route=WalkingRouter.plan(points,mode);
                if (!RealLocationFinder.nearOrigin(origin, route.get(0), accuracy))
                    throw new IOException("步行路網起點離目前位置太遠，請靠近可步行路段後重新規劃。");
                ui.post(() -> {if(isDestroyed() || revision!=routeRevision)return; planning=false;draft=new DraftStore.Draft(points,route,draft.speedKmh(),mode);
                    saveDraft();localMessage="路線已就緒。開始後可切換到其他 App。";showDraft(true);render();});
            }catch(IOException e){ui.post(() -> {if(isDestroyed() || revision!=routeRevision)return;planning=false;localMessage=e.getMessage();render();});}
        });
    }
    private void requestRealLocation(RealAction action, boolean mayRelease) {
        if (planning || startPending || realAction != null) return;
        autoLocateAttempted = true;
        locationFailed = false;
        realAction = action; releaseForLocation = mayRelease;
        long reuseAge = action==RealAction.PLAN ? RealLocationFinder.MAX_PLANNING_AGE_NANOS
                : RealLocationFinder.MAX_SAMPLE_AGE_NANOS;
        if (action!=RealAction.START && canReuseRealFix(reuseAge)) {
            acceptRealLocation(new Location(realFix)); return;
        }
        realFix = null; js("clearRealPosition()");
        localMessage = "正在取得新的真實位置…";
        advanceRealLocation(); render();
    }
    private boolean canReuseRealFix(long maxAgeNanos) {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                && getSystemService(LocationManager.class).isLocationEnabled()
                && !MockLocationService.status.active() && !MockLocationService.hasPendingCleanup(this)
                && RealLocationFinder.isUsableLocation(realFix, MockLocationService.realLocationNotBeforeNanos,
                        SystemClock.elapsedRealtimeNanos(), maxAgeNanos);
    }
    private void advanceRealLocation() {
        if (!resumed || realAction == null || awaitingLocationPermission || realFinder.isRunning()) return;
        MockLocationService.Status session = MockLocationService.status;
        boolean dirty = MockLocationService.hasPendingCleanup(this);
        if (awaitingRestore) {
            if (!session.active() && !dirty) awaitingRestore = false;
            else {
                if (SystemClock.elapsedRealtime() >= restoreDeadline
                        || (session != restoreStartStatus && !session.active()))
                    failRealLocation("模擬定位尚未完成清理，請完成清理後再按「定位」。");
                return;
            }
        }
        if (session.active() || dirty) {
            if (!releaseForLocation) { failRealLocation("請先恢復真實定位，再規劃或開始新路線。"); return; }
            if (!session.active() && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestRealPermission(); return;
            }
            if (!session.active() && !MockLocationService.isSelectedMockApp(this)) {
                failRealLocation("請先重新指定 Route Mock 為模擬位置 App，完成清理後再定位。"); return;
            }
            awaitingRestore = true; restoreStartStatus = session;
            restoreDeadline = SystemClock.elapsedRealtime() + 30_000;
            localMessage = "正在關閉模擬定位，完成後取得真實位置…";
            try {
                if (session.active()) sendCommand(MockLocationService.RELEASE);
                else startForegroundService(new Intent(this,MockLocationService.class).setAction(MockLocationService.CLEANUP));
            } catch (RuntimeException e) { failRealLocation("無法恢復真實定位：" + e.getMessage()); }
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestRealPermission(); return;
        }
        if (!getSystemService(LocationManager.class).isLocationEnabled()) {
            failRealLocation("請先開啟手機定位，再重新定位。"); return;
        }
        realFinder.start(realAction==RealAction.START, MockLocationService.realLocationNotBeforeNanos,
                this::acceptRealLocation, this::failRealLocation);
    }
    private void requestRealPermission() {
        awaitingLocationPermission = true;
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},8);
    }
    private void acceptRealLocation(Location location) {
        if (!resumed || realAction == null) return;
        if (MockLocationService.status.active() || MockLocationService.hasPendingCleanup(this)) {
            failRealLocation("模擬定位狀態已改變，請重新按「定位」。"); return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED
                || !getSystemService(LocationManager.class).isLocationEnabled()) {
            failRealLocation("定位權限或系統定位已關閉，請開啟後重試。"); return;
        }
        RealAction action = realAction;
        long maxAge = action==RealAction.PLAN ? RealLocationFinder.MAX_PLANNING_AGE_NANOS
                : RealLocationFinder.MAX_SAMPLE_AGE_NANOS;
        if (!RealLocationFinder.isUsableLocation(location,MockLocationService.realLocationNotBeforeNanos,
                SystemClock.elapsedRealtimeNanos(),maxAge)) {
            failRealLocation("位置已過期，請重新定位。"); return;
        }
        realAction = null; awaitingRestore = false;
        locationFailed = false;
        realFix = new Location(location);
        GeoPoint point = new GeoPoint(location.getLatitude(), location.getLongitude());
        boolean moved = action == RealAction.PLAN || draft.waypoints().isEmpty() || draft.route().isEmpty()
                || !RealLocationFinder.nearOrigin(point,draft.waypoints().get(0),location.getAccuracy());
        if (moved) {
            DraftStore.Draft updated = draft.withOrigin(point);
            if (updated != draft) {
                draft=updated; routeRevision++;
                if (!saveDraft()) { showRealFix(true); render(); return; }
            }
        }
        localMessage = String.format(Locale.TAIWAN,"已取得真實位置 · 精度約 %.0f m。第 1 點為起點，點地圖加入目的地。",location.getAccuracy());
        showDraft(false); showRealFix(true);
        if (action == RealAction.PLAN && draft.waypoints().size()>=2) planFromRealFix();
        else if (action == RealAction.START) {
            if (draft.route().size()<2) localMessage="目前起點已更新，請重新規劃並確認路線後再開始。";
            else if (!RealLocationFinder.nearOrigin(point,draft.route().get(0),location.getAccuracy())) {
                draft = new DraftStore.Draft(draft.waypoints(),List.of(),draft.speedKmh(),draft.mode());
                saveDraft(); showDraft(false);
                localMessage="路線起點離目前位置太遠，請重新規劃，避免位置跳躍。";
            } else launchMock(false);
        }
        render();
    }
    private void failRealLocation(String reason) {
        realFinder.cancel(); realAction=null; awaitingRestore=false; awaitingLocationPermission=false;
        locationFailed=true;
        localMessage=reason; showDraft(true); render(); if (resumed) toast(reason);
    }
    private void cancelRealLocation() {
        if (realAction == null) return;
        boolean permissionPending = awaitingLocationPermission;
        realFinder.cancel(); realAction=null; awaitingRestore=false; awaitingLocationPermission=false;
        if (!permissionPending) autoLocateAttempted=false;
        localMessage="定位已取消，回到畫面後會重新取得真實位置。";
    }
    private void showRealFix(boolean center) {
        if (realFix==null || MockLocationService.status.active() || MockLocationService.hasPendingCleanup(this)) return;
        js(String.format(Locale.US,"showRealPosition(%.8f,%.8f,%.2f,%s)",realFix.getLatitude(),realFix.getLongitude(),realFix.getAccuracy(),center));
    }
    private void requestSetupPermissions() {
        String[] permissions = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};
        requestPermissions(permissions, 7);
    }
    private boolean preflight() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestSetupPermissions();
            localMessage="允許位置權限後，再按一次開始。";return false;
        }
        if(!getSystemService(LocationManager.class).isLocationEnabled()){
            localMessage="請先開啟系統定位。";startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));return false;
        }
        if(!MockLocationService.isSelectedMockApp(this)){showSetup();return false;}
        return true;
    }
    private void begin(boolean fixed) {
        if(!editable() || (fixed?draft.waypoints().isEmpty():draft.route().size()<2))return;
        if (!fixed) requestRealLocation(RealAction.START, false);
        else launchMock(true);
    }
    private void launchMock(boolean fixed) {
        if (!editable() || !preflight() || !saveDraft()) return;
        realFix=null; js("clearRealPosition()");
        startPending=true;localMessage="正在啟用模擬定位…";render();
        try {startForegroundService(new Intent(this,MockLocationService.class).setAction(fixed?MockLocationService.HOLD:MockLocationService.START));}
        catch(RuntimeException e){startPending=false;localMessage="無法啟動："+e.getMessage();}
        ui.postDelayed(() -> {startPending=false;render();},1500);
    }
    private void sendCommand(String action) {
        if(!MockLocationService.status.active())return;
        startService(new Intent(this,MockLocationService.class).setAction(action).putExtra("speedKmh",draft.speedKmh()));
    }
    private void restoreOrCleanup() {
        if(MockLocationService.status.active())sendCommand(MockLocationService.RELEASE);
        else if(MockLocationService.hasPendingCleanup(this) && preflight()){
            try{startForegroundService(new Intent(this,MockLocationService.class).setAction(MockLocationService.CLEANUP));}
            catch(RuntimeException e){localMessage="清理無法啟動："+e.getMessage();}
        }
    }
    private void showSetup() {
        LinearLayout settings = new LinearLayout(this); settings.setOrientation(LinearLayout.VERTICAL);
        settings.setPadding(dp(20),0,dp(20),0);
        settings.addView(button("背景執行設定", "背景執行設定", () -> startActivity(
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:"+getPackageName())))));
        new AlertDialog.Builder(this).setTitle("設定模擬定位")
                .setMessage("1. 在系統「關於手機」連按版本號碼，啟用開發人員選項。\n2. 在「選取模擬位置應用程式」選 Route Mock。\n3. 開啟系統定位，允許本 App 的精確位置權限。\n\n即使有前景通知，手機仍可能限制背景執行。若切換 App 後位置停止更新，請到本 App 的系統資訊頁，OPPO 可在「耗電管理」開啟「允許完全背景行為」；其他品牌可能稱為「允許背景活動」或「不限制電池用量」，名稱與入口依手機而異。「背景執行設定」會開啟 App 資訊頁。\n\n本 App 使用官方 mock 定位，接收 App 可辨識並拒絕；Pokémon GO／Pikmin 相容性未驗證。\n\n步行規劃會把選取座標送到 routing.openstreetmap.de；地圖來自 OpenStreetMap。下載完成的路線可離線行走。")
                .setView(settings)
                .setPositiveButton("開發人員選項",(d,w)->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(RuntimeException e){startActivity(new Intent(Settings.ACTION_SETTINGS));}})
                .setNeutralButton("位置權限",(d,w)->requestSetupPermissions())
                .setNegativeButton("關閉",null).show();
    }
    private void render() {
        if(status==null)return;
        MockLocationService.Status session=MockLocationService.status;
        if (session != seenStatus) {
            if (session.phase().equals("ERROR") && !session.active()) localMessage=session.message();
            seenStatus=session;
        }
        boolean active=session.active(); boolean dirty=MockLocationService.hasPendingCleanup(this);
        if ((active||dirty) && realFix!=null) { realFix=null; js("clearRealPosition()"); }
        boolean canEdit=editable();
        String phase=switch(session.phase()){
            case "PREPARING" -> "準備中"; case "MOVING","RUNNING" -> "行走中"; case "PAUSED" -> "已暫停 · 維持定點";
            case "HOLDING" -> "定點中"; case "ARRIVED" -> "已到達 · 維持定點"; case "STOPPING" -> "正在停止與清理";
            case "STOPPED" -> active?"已停止行走 · 維持定點":"待命";
            case "ERROR" -> "行程發生錯誤"; default -> "待命";
        };
        if(!active&&dirty)phase="中斷 · 需要清理";
        else if (!active && realAction!=null) phase="取得真實位置中";
        else if (!active && planning) phase="規劃步行路線中";
        else if (!active && realFix!=null) phase="已定位 · 真實位置";
        else if (!active && locationFailed) phase="定位失敗 · 請重試";
        setText(status,phase + (active&&session.platformOnly()?" · 平台定位":""));
        setText(summary,String.format(Locale.TAIWAN,"%d 個途經點%s",draft.waypoints().size(),draft.route().isEmpty()?" · 尚未規劃":String.format(Locale.TAIWAN," · 步行 %.0f m",RouteEngine.lengthMeters(draft.route()))));
        setText(speedText,String.format(Locale.TAIWAN,"%.1f km/h",draft.speedKmh()));
        for (int i=0;i<modeSelector.getChildCount();i++) {
            RadioButton option=(RadioButton)modeSelector.getChildAt(i);
            option.setEnabled(canEdit);
            boolean selected = option.getTag()==draft.mode();
            setText(option,(selected?"✓ ":"")+modeLabel((PlaybackMode)option.getTag()));
            option.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);
            if (option.getTag()==draft.mode() && modeSelector.getCheckedRadioButtonId()!=option.getId())
                modeSelector.check(option.getId());
        }
        setText(modeHint,switch(draft.mode()) {
            case ONCE -> "A → B → C，到達後定點";
            case PING_PONG -> "A → B → C → B → A，持續往返";
            case LOOP -> "A → B → C → A，沿步行路線循環";
        });
        idleActions.setVisibility(active||dirty?View.GONE:View.VISIBLE);
        playbackActions.setVisibility(active?View.VISIBLE:View.GONE);
        restore.setVisibility(active||dirty?View.VISIBLE:View.GONE);
        status.setTextColor(color((dirty&&!active)||locationFailed||session.phase().equals("ERROR")
                ? R.color.ui_warning : R.color.ui_accent));
        coordinate.setEnabled(canEdit&&!draft.waypoints().isEmpty()); clear.setEnabled(canEdit&&draft.waypoints().size()>1);undo.setEnabled(canEdit&&draft.waypoints().size()>1);
        locate.setEnabled(!planning&&!startPending&&realAction==null);
        locate.setTooltipText(realAction==null?"定位到真實位置":"正在取得真實位置");
        plan.setEnabled(canEdit&&draft.waypoints().size()>=2);setText(plan,planning?"規劃中…":"規劃步行");
        hold.setEnabled(canEdit&&!draft.waypoints().isEmpty());start.setEnabled(canEdit&&draft.route().size()>=2);
        pause.setEnabled(active&&(session.phase().equals("MOVING")||session.phase().equals("RUNNING")||session.phase().equals("PAUSED")));
        setText(pause,session.phase().equals("PAUSED")?"繼續":"暫停"); stop.setEnabled(pause.isEnabled());
        restore.setEnabled((active&&!session.phase().equals("STOPPING"))||(!active&&dirty));
        String restoreLabel=!active&&dirty?"清理並恢復真實定位":"恢復真實定位";
        setText(restore,restoreLabel); restore.setContentDescription(restoreLabel);
        speed.setEnabled(!planning&&!startPending&&realAction==null&&(!dirty||active));
        if(session.sample()!=null&&active){RouteEngine.Sample p=session.sample();setText(details,String.format(Locale.TAIWAN,"%.6f, %.6f  ·  路線位置 %.0f / %.0f m",p.point().latitude(),p.point().longitude(),p.routePositionMeters(),p.totalMeters()));
            js(String.format(Locale.US,"showPosition(%.8f,%.8f)",p.point().latitude(),p.point().longitude()));}
        else {setText(details,"規劃時傳送選點至 OSM 路由服務；圖磚需連線。");js("clearPosition()");}
        setText(message,(active||dirty)&&!session.message().isEmpty()?session.message():localMessage);
        js("setEditable("+(canEdit&&!draft.waypoints().isEmpty())+")");
    }
    private void showDraft(boolean fit) {
        try{js("showDraft("+DraftStore.jsonPoints(draft.waypoints())+","+DraftStore.jsonPoints(draft.route())+","+fit+")");}
        catch(JSONException e){localMessage="無法顯示路線";}
    }
    private void js(String script){if(mapReady&&!isDestroyed())map.evaluateJavascript(script,null);}
    private static void setText(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
