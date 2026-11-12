package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public final class NavigationGuidanceTest {
    @Test public void recognisesRoundaboutExitAndEta() {
        NavigationGuidance result = NavigationGuidance.read(new NavigationHintParser.Text(
                "400 m", "At the roundabout take the third exit", "8.2 km · 14 min · ETA 20:15"));
        assertEquals(NavigationGuidance.Maneuver.ROUNDABOUT, result.maneuver);
        assertEquals(3, result.roundaboutExit);
        assertEquals("20:15", result.eta);
        assertEquals("8.2 km · 14 min", result.remaining);
    }

    @Test public void recognisesDirectionalAndLaneGuidance() {
        NavigationGuidance result = NavigationGuidance.read(new NavigationHintParser.Text(
                "Turn slight left", "Use the left lane", "2 km · 5 min delay"));
        assertEquals(NavigationGuidance.Maneuver.SLIGHT_LEFT, result.maneuver);
        assertEquals("Use the left lane", result.laneGuidance);
        assertEquals("5 min delay", result.trafficDelay);
    }

    @Test public void arrivalUsesTheDestinationManeuver() {
        NavigationGuidance result = NavigationGuidance.read(new NavigationHintParser.Text(
                "You have arrived", null, null));
        assertEquals(NavigationGuidance.Maneuver.DESTINATION, result.maneuver);
    }
}
