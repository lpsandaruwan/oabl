package com.softiva.oabl;

import static org.junit.Assert.assertEquals;

import com.softiva.oabl.MapsTile.Watch.Change;
import org.junit.Test;

public class MapsTileTest {
    private final MapsTile.Watch watch = new MapsTile.Watch();

    @Test public void aRouteStartingAndEndingIsSeenOnce() {
        assertEquals(Change.NONE, watch.update(false, null));
        assertEquals(Change.STARTED, watch.update(true, MapsTile.MAPS));
        assertEquals("guidance updating", Change.NONE, watch.update(true, MapsTile.MAPS));
        assertEquals(Change.ENDED, watch.update(false, null));
        assertEquals(Change.NONE, watch.update(false, null));
    }

    @Test public void aRouteAlreadyRunningWhenTheListenerConnectsHasNotJustStarted() {
        assertEquals(Change.NONE, watch.update(true, MapsTile.MAPS));
        assertEquals(Change.ENDED, watch.update(false, null));
    }

    @Test public void reconnectingSetsANewBaseline() {
        watch.update(false, null);
        watch.update(true, MapsTile.MAPS);
        watch.reset();
        assertEquals("not an end", Change.NONE, watch.update(false, null));
        watch.reset();
        assertEquals("not a start", Change.NONE, watch.update(true, MapsTile.MAPS));
    }

    @Test public void otherNavigationAppsDoNothing() {
        watch.update(false, null);
        assertEquals(Change.NONE, watch.update(true, "com.waze"));
        assertEquals(Change.NONE, watch.update(false, null));
    }
}
