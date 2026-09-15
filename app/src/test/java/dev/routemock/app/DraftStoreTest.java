package dev.routemock.app;

import dev.routemock.core.GeoPoint;
import dev.routemock.core.PlaybackMode;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class DraftStoreTest {
    private static final List<GeoPoint> POINTS = List.of(new GeoPoint(25, 121), new GeoPoint(25.001, 121));
    private static DraftStore.Draft draft(PlaybackMode mode) {
        List<GeoPoint> route = mode == PlaybackMode.LOOP
                ? List.of(POINTS.get(0), POINTS.get(1), POINTS.get(0)) : POINTS;
        return new DraftStore.Draft(POINTS, route, 6.5, mode);
    }
    @Test public void migratesVersionOneWithoutLosingData() throws Exception {
        JSONObject json = new JSONObject(DraftStore.encode(draft(PlaybackMode.PING_PONG)));
        json.put("version", 1).remove("mode");
        assertEquals(draft(PlaybackMode.ONCE), DraftStore.decode(json.toString()));
    }
    @Test public void versionTwoRoundTripsEveryMode() throws Exception {
        for (PlaybackMode mode : PlaybackMode.values()) {
            String text = DraftStore.encode(draft(mode));
            assertEquals(2, new JSONObject(text).getInt("version"));
            assertEquals(mode.name(), new JSONObject(text).getString("mode"));
            assertEquals(draft(mode), DraftStore.decode(text));
        }
    }
    @Test public void rejectsMissingUnknownModeAndUnknownVersion() throws Exception {
        JSONObject json = new JSONObject(DraftStore.encode(draft(PlaybackMode.ONCE)));
        json.remove("mode");
        assertThrows(IOException.class, () -> DraftStore.decode(json.toString()));
        json.put("mode", "FUTURE");
        assertThrows(IOException.class, () -> DraftStore.decode(json.toString()));
        json.put("mode", "ONCE").put("version", 3);
        assertThrows(IOException.class, () -> DraftStore.decode(json.toString()));
    }
    @Test public void snapshotsAreImmutableAndLimitsApply() {
        ArrayList<GeoPoint> source = new ArrayList<>(POINTS);
        DraftStore.Draft draft = new DraftStore.Draft(source, source, 5, PlaybackMode.ONCE);
        source.clear();
        assertEquals(POINTS, draft.waypoints());
        assertEquals(POINTS, draft.route());
        assertThrows(UnsupportedOperationException.class, () -> draft.route().clear());
        assertThrows(UnsupportedOperationException.class, () -> draft.waypoints().clear());
        new DraftStore.Draft(Collections.nCopies(8, POINTS.get(0)), Collections.nCopies(10000, POINTS.get(0)), 5, PlaybackMode.ONCE);
        assertThrows(IllegalArgumentException.class, () -> new DraftStore.Draft(Collections.nCopies(9, POINTS.get(0)), POINTS, 5, PlaybackMode.ONCE));
        assertThrows(IllegalArgumentException.class, () -> new DraftStore.Draft(POINTS, Collections.nCopies(10001, POINTS.get(0)), 5, PlaybackMode.ONCE));
        assertThrows(IllegalArgumentException.class, () -> new DraftStore.Draft(POINTS, POINTS, Double.NaN, PlaybackMode.ONCE));
        assertThrows(NullPointerException.class, () -> new DraftStore.Draft(POINTS, POINTS, 5, null));
    }
    @Test public void geometryInvalidatesOnlyAcrossLoopBoundary() {
        DraftStore.Draft once = draft(PlaybackMode.ONCE);
        DraftStore.Draft returning = once.withMode(PlaybackMode.PING_PONG);
        assertEquals(POINTS, returning.route());
        assertEquals(once, returning.withMode(PlaybackMode.ONCE));
        assertTrue(once.withMode(PlaybackMode.LOOP).route().isEmpty());
        assertTrue(returning.withMode(PlaybackMode.LOOP).route().isEmpty());
        DraftStore.Draft loop = draft(PlaybackMode.LOOP);
        assertTrue(loop.withMode(PlaybackMode.ONCE).route().isEmpty());
        assertTrue(loop.withMode(PlaybackMode.PING_PONG).route().isEmpty());
        for (PlaybackMode mode : PlaybackMode.values()) {
            assertEquals(draft(mode), draft(mode).withMode(mode));
            DraftStore.Draft changed = once.withMode(mode);
            assertEquals(POINTS, changed.waypoints());
            assertEquals(6.5, changed.speedKmh(), 0);
            assertEquals(mode, changed.mode());
        }
    }
    @Test public void originSeedsEmptyDraft() {
        DraftStore.Draft seeded = DraftStore.empty().withOrigin(POINTS.get(0));
        assertEquals(List.of(POINTS.get(0)), seeded.waypoints());
        assertTrue(seeded.route().isEmpty());
        assertEquals(5, seeded.speedKmh(), 0);
        assertEquals(PlaybackMode.ONCE, seeded.mode());
    }
    @Test public void changedOriginPreservesDestinationsAndSettingsButClearsGeometry() {
        List<GeoPoint> waypoints = new ArrayList<>();
        for (int i = 0; i < 8; i++) waypoints.add(new GeoPoint(25 + i * .001, 121));
        GeoPoint origin = new GeoPoint(25.000000001, 121);
        for (PlaybackMode mode : PlaybackMode.values()) {
            DraftStore.Draft original = new DraftStore.Draft(waypoints, draft(mode).route(), 6.5, mode);
            DraftStore.Draft updated = original.withOrigin(origin);
            assertEquals(8, updated.waypoints().size());
            assertEquals(origin, updated.waypoints().get(0));
            assertEquals(waypoints.subList(1, 8), updated.waypoints().subList(1, 8));
            assertTrue(updated.route().isEmpty());
            assertEquals(6.5, updated.speedKmh(), 0);
            assertEquals(mode, updated.mode());
            assertEquals(waypoints, original.waypoints());
            assertEquals(draft(mode).route(), original.route());
        }
    }
    @Test public void unchangedOriginRetainsDraftAndCachedGeometry() {
        for (PlaybackMode mode : PlaybackMode.values()) {
            DraftStore.Draft original = draft(mode);
            assertSame(original, original.withOrigin(new GeoPoint(25, 121)));
            assertThrows(NullPointerException.class, () -> original.withOrigin(null));
        }
    }
    @Test public void rejectsMalformedAndOversizedDrafts() {
        assertThrows(IOException.class, () -> DraftStore.decode("{}"));
        assertThrows(IOException.class, () -> DraftStore.decode(" ".repeat(2 * 1024 * 1024 + 1)));
    }
}
