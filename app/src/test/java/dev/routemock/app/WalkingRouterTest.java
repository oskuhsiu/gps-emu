package dev.routemock.app;

import dev.routemock.core.GeoPoint;
import dev.routemock.core.PlaybackMode;
import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class WalkingRouterTest {
    private static final GeoPoint A = new GeoPoint(25, 121);
    private static final GeoPoint B = new GeoPoint(25.001, 121);
    private static String response(String coordinates) {
        return "{\"code\":\"Ok\",\"routes\":[{\"geometry\":{\"coordinates\":" + coordinates + "}}]}";
    }
    @Test public void loopWithEightUserPointsRequestsNineCoordinatesAndRadii() throws Exception {
        List<GeoPoint> waypoints = new ArrayList<>();
        for (int i = 0; i < 8; i++) waypoints.add(new GeoPoint(25 + i * .001, 121));
        URL url = WalkingRouter.requestUrl(waypoints, PlaybackMode.LOOP);
        String[] coordinates = url.getPath().substring(url.getPath().lastIndexOf('/') + 1).split(";");
        assertEquals(9, coordinates.length);
        assertEquals(coordinates[0], coordinates[8]);
        assertEquals(9, url.getQuery().split("radiuses=")[1].split(";").length);
        assertEquals(8, waypoints.size());
        assertEquals("routing.openstreetmap.de", url.getHost());
        assertTrue(url.getPath().startsWith("/routed-foot/route/v1/foot/"));
    }
    @Test public void alreadyClosedLoopDoesNotAppendAgain() throws Exception {
        URL url = WalkingRouter.requestUrl(List.of(A, B, A), PlaybackMode.LOOP);
        assertEquals(3, url.getQuery().split("radiuses=")[1].split(";").length);
        assertEquals(3, url.getPath().substring(url.getPath().lastIndexOf('/') + 1).split(";").length);
    }
    @Test public void onceAndPingPongRequestSameWalkingRoute() throws Exception {
        assertEquals(WalkingRouter.requestUrl(List.of(A, B), PlaybackMode.ONCE).toString(),
                WalkingRouter.requestUrl(List.of(A, B), PlaybackMode.PING_PONG).toString());
        assertThrows(IOException.class, () -> WalkingRouter.requestUrl(List.of(A), PlaybackMode.LOOP));
        assertThrows(IOException.class, () -> WalkingRouter.requestUrl(java.util.Collections.nCopies(9, A), PlaybackMode.ONCE));
    }
    @Test public void loopRejectsOpenGeometryAndAcceptsExactClosure() throws Exception {
        String open = response("[[121,25],[121,25.001]]");
        IOException error = assertThrows(IOException.class, () -> WalkingRouter.parseRoute(open, PlaybackMode.LOOP));
        assertTrue(error.getMessage().contains("閉合"));
        assertEquals(List.of(A, B), WalkingRouter.parseRoute(open, PlaybackMode.ONCE));
        assertEquals(List.of(A, B), WalkingRouter.parseRoute(open, PlaybackMode.PING_PONG));
        List<GeoPoint> closed = WalkingRouter.parseRoute(response("[[121,25],[121,25.001],[121,25]]"), PlaybackMode.LOOP);
        assertEquals(List.of(A, B, A), closed);
        assertThrows(UnsupportedOperationException.class, () -> closed.clear());
        assertThrows(IOException.class, () -> WalkingRouter.parseRoute(response("[[121,25],[121,25.001],[121,25.000000001]]"), PlaybackMode.LOOP));
    }
    @Test public void rejectsBadResponsesAndUnusableGeometry() {
        for (String text : List.of("invalid", "{\"code\":\"NoRoute\"}", response("[]"), response("[[121,25]]"),
                response("[[121,25],[121,25]]"), response("[[121,95],[121,25]]"), response("[[121],[121,25]]"),
                response("[" + "[121,25],".repeat(10000) + "[121,25]]"), " ".repeat(2 * 1024 * 1024 + 1))) {
            assertThrows(IOException.class, () -> WalkingRouter.parseRoute(text, PlaybackMode.ONCE));
        }
    }
}
