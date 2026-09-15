package dev.routemock.app;

import android.content.Context;
import android.util.AtomicFile;
import dev.routemock.core.GeoPoint;
import dev.routemock.core.PlaybackMode;
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
import java.util.Objects;

/** The one saved draft, independent of the currently running session. */
public final class DraftStore {
    private DraftStore() {}
    public record Draft(List<GeoPoint> waypoints, List<GeoPoint> route, double speedKmh, PlaybackMode mode) {
        public Draft {
            waypoints = List.copyOf(waypoints);
            route = List.copyOf(route);
            if (waypoints.size() > 8 || route.size() > 10000) throw new IllegalArgumentException("路線點數超過上限");
            RouteEngine.validateSpeed(speedKmh);
            Objects.requireNonNull(mode, "mode");
        }
        public Draft withMode(PlaybackMode next) {
            Objects.requireNonNull(next, "mode");
            boolean invalidate = mode != next && (mode == PlaybackMode.LOOP || next == PlaybackMode.LOOP);
            return new Draft(waypoints, invalidate ? List.of() : route, speedKmh, next);
        }
        public Draft withOrigin(GeoPoint origin) {
            Objects.requireNonNull(origin, "origin");
            if (!waypoints.isEmpty() && waypoints.get(0).equals(origin)) return this;
            List<GeoPoint> updated = new ArrayList<>(waypoints);
            if (updated.isEmpty()) updated.add(origin);
            else updated.set(0, origin);
            return new Draft(updated, List.of(), speedKmh, mode);
        }
    }
    public static Draft empty() { return new Draft(List.of(), List.of(), 5, PlaybackMode.ONCE); }
    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getFilesDir(), "draft.json")); }

    public static synchronized Draft load(Context c) throws IOException {
        AtomicFile file = file(c);
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) return empty();
        byte[] bytes = file.readFully();
        if (bytes.length > 2 * 1024 * 1024) throw new IOException("儲存路線過大");
        return decode(new String(bytes, StandardCharsets.UTF_8));
    }
    static Draft decode(String text) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw new IOException("儲存路線過大");
        try {
            JSONObject json = new JSONObject(text);
            int version = json.getInt("version");
            if (version != 1 && version != 2) throw new IOException("不支援這份草稿版本");
            PlaybackMode mode = version == 1 ? PlaybackMode.ONCE : PlaybackMode.valueOf(json.getString("mode"));
            return new Draft(points(json.getJSONArray("waypoints")), points(json.getJSONArray("route")), json.getDouble("speedKmh"), mode);
        } catch (JSONException | IllegalArgumentException e) {
            throw new IOException("無法讀取儲存路線，請重新選點", e);
        }
    }
    static String encode(Draft draft) throws IOException {
        try {
            String text = new JSONObject().put("version", 2).put("speedKmh", draft.speedKmh())
                    .put("mode", draft.mode().name()).put("waypoints", jsonPoints(draft.waypoints()))
                    .put("route", jsonPoints(draft.route())).toString();
            if (text.getBytes(StandardCharsets.UTF_8).length > 2 * 1024 * 1024) throw new IOException("儲存路線過大");
            return text;
        } catch (JSONException e) {
            throw new IOException("無法儲存路線", e);
        }
    }
    public static synchronized void save(Context c, Draft draft) throws IOException {
        AtomicFile file = file(c);
        FileOutputStream stream = null;
        try {
            byte[] bytes = encode(draft).getBytes(StandardCharsets.UTF_8);
            stream = file.startWrite();
            stream.write(bytes);
            file.finishWrite(stream);
        } catch (IOException e) {
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
