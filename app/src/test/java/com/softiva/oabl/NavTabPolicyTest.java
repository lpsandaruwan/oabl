package com.softiva.oabl;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class NavTabPolicyTest {
    private static final String OURS = "com.softiva.oabl";
    private final Set<String> ignored = new HashSet<>(Arrays.asList("com.android.systemui", "com.google.android.inputmethod.latin"));

    @Test public void anotherAppShowsTheTab() {
        assertEquals(NavTabPolicy.Action.SHOW, NavTabPolicy.decide("com.google.android.youtube", "com.google.android.youtube.app.honeycomb.Shell$HomeActivity", OURS, ignored));
        assertEquals(NavTabPolicy.Action.SHOW, NavTabPolicy.decide("com.android.settings", "com.android.settings.SubSettings", OURS, ignored));
        assertEquals(NavTabPolicy.Action.SHOW, NavTabPolicy.decide("com.carsyso.bluetooth", "android.widget.FrameLayout", OURS, ignored));
    }

    @Test public void anyOablScreenHidesIt() {
        assertEquals(NavTabPolicy.Action.HIDE, NavTabPolicy.decide(OURS, "com.softiva.oabl.MainActivity", OURS, ignored));
        assertEquals(NavTabPolicy.Action.HIDE, NavTabPolicy.decide(OURS, "com.softiva.oabl.MusicActivity", OURS, ignored));
        assertEquals(NavTabPolicy.Action.HIDE, NavTabPolicy.decide(OURS, "com.softiva.oabl.EqualizerActivity", OURS, ignored));
    }

    @Test public void ourOwnPopupsAndOverlaysChangeNothing() {
        assertEquals(NavTabPolicy.Action.IGNORE, NavTabPolicy.decide(OURS, "android.widget.LinearLayout", OURS, ignored));
        assertEquals(NavTabPolicy.Action.IGNORE, NavTabPolicy.decide(OURS, null, OURS, ignored));
    }

    @Test public void theShadeKeyboardsAndSystemDialogsChangeNothing() {
        assertEquals(NavTabPolicy.Action.IGNORE, NavTabPolicy.decide("com.android.systemui", "com.android.systemui.volume.VolumeDialog", OURS, ignored));
        assertEquals(NavTabPolicy.Action.IGNORE, NavTabPolicy.decide("com.google.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow", OURS, ignored));
        assertEquals(NavTabPolicy.Action.IGNORE, NavTabPolicy.decide("android", "android.app.AlertDialog", OURS, ignored));
        assertEquals(NavTabPolicy.Action.IGNORE, NavTabPolicy.decide(null, "x", OURS, ignored));
        assertEquals(NavTabPolicy.Action.IGNORE, NavTabPolicy.decide("", "x", OURS, ignored));
    }
}
