package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class UiRefreshPolicyTest {
    @Test public void homeKeepsTheFastCadenceWhetherOrNotGpsSawDriving() {
        assertEquals(1_500, UiRefreshPolicy.homeDelay(true, false));
        assertEquals(1_500, UiRefreshPolicy.homeDelay(false, true));
        assertEquals(1_500, UiRefreshPolicy.homeDelay(false, false));
    }

    @Test public void theOverlaySpeedTickDoesNotSlowWhenTheBoxIsHot() {
        assertEquals(1_500, UiRefreshPolicy.overlayDelay(false));
        assertEquals(1_500, UiRefreshPolicy.overlayDelay(true));
    }
}
