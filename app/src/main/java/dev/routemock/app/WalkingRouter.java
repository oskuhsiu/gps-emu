package dev.routemock.app;

import dev.routemock.core.GeoPoint;
import dev.routemock.core.RouteEngine;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WalkingRouter {
    static final String USER_AGENT = "RouteMock/0.1 (Android route simulation prototype)";
    private static long lastRequestNanos;
    private WalkingRouter() {}

    static List<GeoPoint> plan(List<GeoPoint> waypoints) throws IOException {
        if (waypoints.size() < 2 || waypoints.size() > 8) throw new IOException("請先選擇 2–8 個途經點");
        synchronized (WalkingRouter.class) {
            long now = android.os.SystemClock.elapsedRealtimeNanos();
            if (lastRequestNanos != 0 && now - lastRequestNanos < 1_500_000_000L) throw new IOException("請稍候再規劃");
            lastRequestNanos = now;
        }
        StringBuilder coordinates = new StringBuilder();
        for (GeoPoint p : waypoints) {
            if (coordinates.length() > 0) coordinates.append(';');
            coordinates.append(String.format(Locale.US, "%.7f,%.7f", p.longitude(), p.latitude()));
        }
        URL url = new URL("https://routing.openstreetmap.de/routed-foot/route/v1/foot/" + coordinates
                + "?overview=full&geometries=geojson&steps=false&radiuses=" + "100;".repeat(waypoints.size() - 1) + "100");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setInstanceFollowRedirects(false);
        try {
            int response = connection.getResponseCode();
            if (response == 400) throw new IOException("選點附近找不到可用的步行路線，請調整選點");
            if (response != 200) throw new IOException("步行規劃暫時無法使用（HTTP " + response + "），請稍後重試");
            byte[] data;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 2 * 1024 * 1024) throw new IOException("路線太長，請縮短範圍");
                    output.write(buffer, 0, count);
                }
                data = output.toByteArray();
            }
            JSONObject json = new JSONObject(new String(data, StandardCharsets.UTF_8));
            if (!"Ok".equals(json.optString("code"))) throw new IOException("這些點之間沒有可用的步行路線，請調整選點");
            JSONArray coordinatesJson = json.getJSONArray("routes").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates");
            if (coordinatesJson.length() < 2 || coordinatesJson.length() > 10000) throw new IOException("路線點數不適用，請縮短路線");
            List<GeoPoint> points = new ArrayList<>();
            for (int i = 0; i < coordinatesJson.length(); i++) {
                JSONArray point = coordinatesJson.getJSONArray(i);
                points.add(new GeoPoint(point.getDouble(1), point.getDouble(0)));
            }
            if (RouteEngine.lengthMeters(points) < 1) throw new IOException("起終點太接近，請改用定點或重新選點");
            return List.copyOf(points);
        } catch (SocketTimeoutException e) {
            throw new IOException("步行規劃逾時，請稍後重試", e);
        } catch (UnknownHostException | SocketException e) {
            throw new IOException("無法連線到步行規劃服務，請檢查網路後重試", e);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("規劃服務回傳無效路線，請稍後重試", e);
        } finally {
            connection.disconnect();
        }
    }
}
