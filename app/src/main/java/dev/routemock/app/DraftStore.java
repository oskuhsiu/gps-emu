package dev.routemock.app;

import android.content.Context;
import android.util.AtomicFile;
import dev.routemock.core.GeoPoint;
import dev.routemock.core.RouteEngine;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The one saved draft, independent of the currently running session. */
public final class DraftStore {
    private DraftStore() {}
    public record Draft(List<GeoPoint> waypoints, List<GeoPoint> route, double speedKmh) {
        public Draft {
            waypoints = List.copyOf(waypoints);
            route = List.copyOf(route);
            if (waypoints.size() > 8 || route.size() > 10000) throw new IllegalArgumentException("路線點數超過上限");
            RouteEngine.validateSpeed(speedKmh);
        }
    }
    public static Draft empty() { return new Draft(List.of(), List.of(), 5); }
    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "draft.json")); }

    public static synchronized Draft load(Context c) throws IOException {
        AtomicFile file = file(c);
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return empty();
        try {
            byte[] bytes = file.readFully();
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("儲存路線過大");
            JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (json.getInt("version") != 1) throw new IOException("不支援這份草稿版本");
            return new Draft(points(json.getJSONArray("waypoints")), points(json.getJSONArray("route")), json.getDouble("speedKmh"));
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("無法讀取儲存路線，請重新選點", e);
        }
    }
    public static synchronized void save(Context c, Draft draft) throws IOException {
        AtomicFile file = file(c);
        FileOutputStream stream = null;
        try {
            JSONObject json = new JSONObject().put("version", 1).put("speedKmh", draft.speedKmh())
                    .put("waypoints", jsonPoints(draft.waypoints())).put("route", jsonPoints(draft.route()));
            stream = file.startWrite();
            stream.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
        } catch (IOException | JSONException e) {
            if (stream != null) file.failWrite(stream);
            throw new IOException("無法儲存路線", e);
        }
    }
    static JSONArray jsonPoints(List<GeoPoint> points) throws JSONException {
        JSONArray array = new JSONArray();
        for (GeoPoint p : points) array.put(new JSONArray().put(p.latitude()).put(p.longitude()));
        return array;
    }
    private static List<GeoPoint> points(JSONArray array) throws JSONException {
        if (array.length() > 10000) throw new IllegalArgumentException("點數過多");
        List<GeoPoint> points = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONArray p = array.getJSONArray(i);
            points.add(new GeoPoint(p.getDouble(0), p.getDouble(1)));
        }
        return points;
    }
}
