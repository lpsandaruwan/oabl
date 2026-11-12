package com.softiva.oabl;

import android.content.pm.ServiceInfo;

/** Runtime foreground-service ownership for the driving overlay on Android 10 through 13+. */
final class OverlayForegroundPolicy {
    private OverlayForegroundPolicy() { }

    static int types(boolean ownsLocation, boolean ownsConnectedDevice) {
        int result = 0;
        if (ownsLocation) result |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        if (ownsConnectedDevice) result |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        return result;
    }
}
