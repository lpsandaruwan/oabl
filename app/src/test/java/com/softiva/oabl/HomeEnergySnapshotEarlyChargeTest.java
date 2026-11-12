package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;

public class HomeEnergySnapshotEarlyChargeTest {
    @Test public void aRiseOfFivePointsWhileAwayIsACharge() {
        assertTrue(HomeEnergySnapshot.chargedWhileAway(45, 80, false));
        assertTrue(HomeEnergySnapshot.chargedWhileAway(45, 50, false));
    }
    @Test public void smallRisesDropsAndLiveChargingAreNot() {
        assertFalse(HomeEnergySnapshot.chargedWhileAway(45, 48, false)); // SOC rounding, regen
        assertFalse(HomeEnergySnapshot.chargedWhileAway(60, 52, false)); // an offline drive
        assertFalse(HomeEnergySnapshot.chargedWhileAway(45, 80, true));  // still charging: the live charge covers it
        assertFalse(HomeEnergySnapshot.chargedWhileAway(Double.NaN, 80, false));
    }
}
