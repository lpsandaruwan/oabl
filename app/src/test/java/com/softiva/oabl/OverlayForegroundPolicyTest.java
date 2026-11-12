package com.softiva.oabl;

import android.content.pm.ServiceInfo;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class OverlayForegroundPolicyTest {
    @Test public void locationAndCarLinkAreClaimedTogether() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                OverlayForegroundPolicy.types(true, true));
    }

    @Test public void anUnavailableResourceIsNeverClaimed() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                OverlayForegroundPolicy.types(true, false));
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                OverlayForegroundPolicy.types(false, true));
        assertEquals(0, OverlayForegroundPolicy.types(false, false));
    }
}
