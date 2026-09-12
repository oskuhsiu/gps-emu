package dev.routemock.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import dev.routemock.core.GeoPoint;
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
    private TextView status, summary, speedText, details, message;
    private SeekBar speed;
    private Button plan, start, pause, stop, clear, undo, coordinate, hold;
    private boolean mapReady, planning, startPending;
    private long lastPlanTime;
    private String localMessage = "點按地圖加入途經點，或用「座標」輸入。";
    private final Runnable commitSpeed = () -> {
        if (saveDraft() && MockLocationService.status.active()) sendCommand(MockLocationService.SPEED);
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); ui.postDelayed(this, 500); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { draft = DraftStore.load(this); } catch (IOException e) { localMessage = e.getMessage(); }
        buildScreen();
        speed.setProgress((int) Math.round(draft.speedKmh() * 2) - 1);
        render();
    }
    @Override protected void onResume() {
        super.onResume();
        if (!planning) {
            try {
                draft = DraftStore.load(this);
                speed.setProgress((int) Math.round(draft.speedKmh() * 2) - 1);
                showDraft(false);
            } catch (IOException e) { localMessage = e.getMessage(); }
        }
        map.onResume();
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }
    @Override protected void onPause() {
        ui.removeCallbacks(refresh);
        map.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        network.shutdownNow();
        map.removeJavascriptInterface("RouteMock");
        map.destroy();
        super.onDestroy();
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); return row;
    }
    private Button button(String title, String locator, Runnable action) {
        Button b = new Button(this); b.setText(title); b.setAllCaps(false); b.setMinHeight(dp(48));
        b.setTextSize(13); b.setPadding(dp(6), 0, dp(6), 0); b.setContentDescription(locator);
        b.setOnClickListener(v -> action.run()); return b;
    }
    private void addButton(LinearLayout row, Button button) { row.addView(button, new LinearLayout.LayoutParams(0, -2, 1)); }

    @SuppressLint("SetJavaScriptEnabled")
    private void buildScreen() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.rgb(246,248,246));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        LinearLayout header = row(); header.setPadding(dp(16), dp(8), dp(12), dp(6));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Route Mock", 24, Color.rgb(24,50,44)); title.setTypeface(null, Typeface.BOLD); titles.addView(title);
        status = text("待命", 13, Color.rgb(8,126,120)); status.setContentDescription("行程狀態"); titles.addView(status);
        header.addView(titles, new LinearLayout.LayoutParams(0,-2,1));
        header.addView(button("設定", "開啟設定引導", this::showSetup)); root.addView(header);
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
            @Override public void onPageFinished(WebView view, String url) { mapReady = true; showDraft(true); }
        });
        root.addView(map, new LinearLayout.LayoutParams(-1,0,1));
        map.loadUrl("https://routemock.local/map.html");

        ScrollView scroller = new ScrollView(this); scroller.setFillViewport(false);
        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setPadding(dp(16),dp(8),dp(16),dp(8));
        summary = text("",15,Color.rgb(24,50,44)); summary.setTypeface(null,Typeface.BOLD); controls.addView(summary);
        LinearLayout editRow = row();
        coordinate = button("座標", "輸入座標", this::coordinateDialog); undo = button("撤回", "撤回途經點", this::undo);
        clear = button("清除", "清除路線", () -> replacePoints(List.of())); plan = button("規劃步行", "規劃步行路線", this::planRoute);
        addButton(editRow,coordinate); addButton(editRow,undo); addButton(editRow,clear); addButton(editRow,plan); controls.addView(editRow);
        speedText = text("",15,Color.rgb(24,50,44)); controls.addView(speedText);
        speed = new SeekBar(this); speed.setMax(59); speed.setMinHeight(dp(48)); speed.setContentDescription("移動速度"); controls.addView(speed);
        speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int p, boolean user) {
                double value=(p+1)/2.0; speedText.setText(String.format(Locale.TAIWAN,"移動速度  %.1f km/h",value));
                if (user) {
                    draft = new DraftStore.Draft(draft.waypoints(),draft.route(),value);
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
        LinearLayout actions = row(); hold = button("定點", "開始定點", () -> begin(true)); start = button("開始行走", "開始行走", () -> begin(false));
        addButton(actions,hold); addButton(actions,start); controls.addView(actions);
        LinearLayout playback = row(); pause = button("暫停", "暫停或繼續", () -> sendCommand("PAUSED".equals(MockLocationService.status.phase()) ? MockLocationService.RESUME : MockLocationService.PAUSE));
        stop = button("停止", "停止並清理", this::stopOrCleanup); addButton(playback,pause); addButton(playback,stop); controls.addView(playback);
        details = text("",12,Color.rgb(70,88,80)); details.setTextIsSelectable(true); controls.addView(details);
        message = text("",12,Color.rgb(88,100,90)); message.setPadding(0,dp(4),0,0); message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); controls.addView(message);
        scroller.addView(controls); root.addView(scroller,new LinearLayout.LayoutParams(-1,dp(320)));
        // Small or landscape windows can scroll the controls while preserving a usable map.
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> {
            int available=b-t-root.getPaddingTop()-root.getPaddingBottom();
            int height=Math.min(dp(340),Math.max(dp(150),(int)(available*0.56)));
            if(scroller.getLayoutParams().height!=height){scroller.getLayoutParams().height=height;scroller.requestLayout();}
        });
        setContentView(root);
    }
    private static WebResourceResponse denied() { return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0])); }
    private boolean editable() { return !planning && !startPending && !MockLocationService.status.active() && !MockLocationService.hasPendingCleanup(this); }
    private void appendPoint(double lat,double lon) {
        if (!editable()) return;
        if (draft.waypoints().size()>=8) { toast("最多 8 個途經點"); return; }
        try { List<GeoPoint> points=new ArrayList<>(draft.waypoints()); points.add(new GeoPoint(lat,lon)); replacePoints(points); }
        catch (IllegalArgumentException e) { toast("緯度需在 ±90，經度需在 ±180 之內"); }
    }
    private void replacePoints(List<GeoPoint> points) {
        if (!editable()) return;
        draft=new DraftStore.Draft(points,List.of(),draft.speedKmh()); saveDraft();
        localMessage=points.isEmpty()?"點按地圖加入途經點，或用「座標」輸入。":"選點已更新；請規劃步行路線，或在最後一點定點。";
        showDraft(false); render();
    }
    private void undo() { if(!draft.waypoints().isEmpty())replacePoints(draft.waypoints().subList(0,draft.waypoints().size()-1)); }
    private void coordinateDialog() {
        EditText input=new EditText(this); input.setSingleLine(true); input.setHint("25.0330, 121.5654"); input.setContentDescription("緯度與經度");
        input.setInputType(InputType.TYPE_CLASS_TEXT); input.setPadding(dp(24),dp(16),dp(24),dp(16));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("加入座標").setMessage("輸入：緯度, 經度").setView(input).setNegativeButton("取消",null).setPositiveButton("加入",null).create();
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
        if(SystemClock.elapsedRealtime()-lastPlanTime<1500){toast("請稍候再規劃");return;}
        lastPlanTime=SystemClock.elapsedRealtime(); planning=true; localMessage="正在規劃步行路線…"; render();
        List<GeoPoint> points=draft.waypoints();
        network.execute(() -> {
            try { List<GeoPoint> route=WalkingRouter.plan(points);
                ui.post(() -> {if(isDestroyed())return; planning=false;draft=new DraftStore.Draft(points,route,draft.speedKmh());
                    saveDraft();localMessage="路線已就緒。開始後可切換到其他 App。";showDraft(true);render();});
            }catch(IOException e){ui.post(() -> {if(isDestroyed())return;planning=false;localMessage=e.getMessage();render();});}
        });
    }
    private boolean preflight() {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS},7);
            localMessage="允許位置權限後，再按一次開始。";return false;
        }
        if(!getSystemService(LocationManager.class).isLocationEnabled()){
            localMessage="請先開啟系統定位。";startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));return false;
        }
        if(!MockLocationService.isSelectedMockApp(this)){showSetup();return false;}
        return true;
    }
    private void begin(boolean fixed) {
        if(!editable() || (fixed?draft.waypoints().isEmpty():draft.route().size()<2) || !preflight() || !saveDraft())return;
        startPending=true;localMessage="正在啟用模擬定位…";render();
        try {startForegroundService(new Intent(this,MockLocationService.class).setAction(fixed?MockLocationService.HOLD:MockLocationService.START));}
        catch(RuntimeException e){startPending=false;localMessage="無法啟動："+e.getMessage();}
        ui.postDelayed(() -> {startPending=false;render();},1500);
    }
    private void sendCommand(String action) {
        if(!MockLocationService.status.active())return;
        startService(new Intent(this,MockLocationService.class).setAction(action).putExtra("speedKmh",draft.speedKmh()));
    }
    private void stopOrCleanup() {
        if(MockLocationService.status.active())sendCommand(MockLocationService.STOP);
        else if(MockLocationService.hasPendingCleanup(this) && preflight()){
            try{startForegroundService(new Intent(this,MockLocationService.class).setAction(MockLocationService.CLEANUP));}
            catch(RuntimeException e){localMessage="清理無法啟動："+e.getMessage();}
        }
    }
    private void showSetup() {
        new AlertDialog.Builder(this).setTitle("設定模擬定位")
                .setMessage("1. 在系統「關於手機」連按版本號碼，啟用開發人員選項。\n2. 在「選取模擬位置應用程式」選 Route Mock。\n3. 開啟系統定位，允許本 App 的精確位置權限。\n\n本 App 使用官方 mock 定位，接收 App 可辨識並拒絕；Pokémon GO／Pikmin 相容性未驗證。\n\n步行規劃會把選取座標送到 routing.openstreetmap.de；地圖來自 OpenStreetMap。下載完成的路線可離線行走。")
                .setPositiveButton("開發人員選項",(d,w)->{try{startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));}catch(RuntimeException e){startActivity(new Intent(Settings.ACTION_SETTINGS));}})
                .setNeutralButton("位置權限",(d,w)->requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS},7))
                .setNegativeButton("關閉",null).show();
    }
    private void render() {
        if(status==null)return;
        MockLocationService.Status session=MockLocationService.status;
        boolean active=session.active(); boolean dirty=MockLocationService.hasPendingCleanup(this);
        boolean canEdit=editable();
        String phase=switch(session.phase()){
            case "PREPARING" -> "準備中"; case "MOVING","RUNNING" -> "行走中"; case "PAUSED" -> "已暫停 · 維持定點";
            case "HOLDING" -> "定點中"; case "ARRIVED" -> "已到達 · 維持定點"; case "STOPPING" -> "正在停止與清理";
            case "ERROR" -> "行程發生錯誤"; default -> "待命";
        };
        if(!active&&dirty)phase="中斷 · 需要清理";
        setText(status,phase + (active&&session.platformOnly()?" · 平台定位":""));
        setText(summary,String.format(Locale.TAIWAN,"%d 個途經點%s",draft.waypoints().size(),draft.route().isEmpty()?" · 尚未規劃":String.format(Locale.TAIWAN," · 步行 %.0f m",RouteEngine.lengthMeters(draft.route()))));
        setText(speedText,String.format(Locale.TAIWAN,"移動速度  %.1f km/h",draft.speedKmh()));
        coordinate.setEnabled(canEdit); clear.setEnabled(canEdit&&!draft.waypoints().isEmpty());undo.setEnabled(canEdit&&!draft.waypoints().isEmpty());
        plan.setEnabled(canEdit&&draft.waypoints().size()>=2);setText(plan,planning?"規劃中…":"規劃步行");
        hold.setEnabled(canEdit&&!draft.waypoints().isEmpty());start.setEnabled(canEdit&&draft.route().size()>=2);
        pause.setEnabled(active&&(session.phase().equals("MOVING")||session.phase().equals("RUNNING")||session.phase().equals("PAUSED")));
        setText(pause,session.phase().equals("PAUSED")?"繼續":"暫停");stop.setEnabled(active||dirty);setText(stop,!active&&dirty?"清理中斷行程":"停止");
        speed.setEnabled(!planning&&!startPending&&(!dirty||active));
        if(session.sample()!=null&&active){RouteEngine.Sample p=session.sample();setText(details,String.format(Locale.TAIWAN,"%.6f, %.6f  ·  %.0f / %.0f m",p.point().latitude(),p.point().longitude(),p.traveledMeters(),p.totalMeters()));
            js(String.format(Locale.US,"showPosition(%.8f,%.8f)",p.point().latitude(),p.point().longitude()));}
        else {setText(details,"規劃時傳送選點至 OSM 路由服務；圖磚需連線。");js("clearPosition()");}
        setText(message,(active||dirty||session.phase().equals("ERROR"))&&!session.message().isEmpty()?session.message():localMessage);
        js("setEditable("+canEdit+")");
    }
    private void showDraft(boolean fit) {
        try{js("showDraft("+DraftStore.jsonPoints(draft.waypoints())+","+DraftStore.jsonPoints(draft.route())+","+fit+")");}
        catch(JSONException e){localMessage="無法顯示路線";}
    }
    private void js(String script){if(mapReady&&!isDestroyed())map.evaluateJavascript(script,null);}
    private static void setText(TextView view,String value){if(!value.contentEquals(view.getText()))view.setText(value);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
}
