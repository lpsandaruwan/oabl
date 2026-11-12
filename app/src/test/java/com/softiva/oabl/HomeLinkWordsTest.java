package com.softiva.oabl;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HomeLinkWordsTest {
    private static HomeEnergySnapshot snapshot(boolean connected, boolean live, boolean ready, boolean charging, String state) {
        HomeEnergySnapshot s = new HomeEnergySnapshot();
        s.car = true; s.connected = connected; s.live = live; s.ready = ready; s.charging = charging; s.linkState = state;
        return s;
    }

    @Test public void noLinkIsCarOffline() {
        assertEquals("Car offline", snapshot(false, false, false, false, "").linkWords());
    }
    @Test public void anyLinkIsCarOnline() {
        assertEquals("Car online", snapshot(true, true, true, false, "Connected · Bluetooth · syncing history").linkWords());
        assertEquals("Car online", snapshot(true, false, false, false, "Connected").linkWords());
        assertEquals("Car online", snapshot(true, true, false, false, "Connected · Bluetooth · encrypted").linkWords());
    }
}
