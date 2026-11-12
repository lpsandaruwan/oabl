package com.softiva.oabllink;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class IlluminationTest {
    @Test public void defaultMappingTreatsZeroAsDay() {
        assertFalse(Illumination.night(0, false));
        assertTrue(Illumination.night(1, false));
    }

    @Test public void calibrationCanInvertTheVendorPolarity() {
        assertTrue(Illumination.night(0, true));
        assertFalse(Illumination.night(1, true));
    }
}
