package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;

public class HomeDashboardModeTest {
    @Test public void navigationHasPriorityWithAConnectedCar() {
        assertEquals(HomeDashboardMode.NAVIGATION,HomeDashboardMode.choose(true,true));
    }
    @Test public void navigationHasPriorityWithoutAConnectedCar() {
        assertEquals(HomeDashboardMode.NAVIGATION,HomeDashboardMode.choose(true,false));
    }
    @Test public void connectedCarWithoutNavigationUsesNormalDashboard() {
        assertEquals(HomeDashboardMode.NORMAL,HomeDashboardMode.choose(false,true));
    }
    @Test public void disconnectedCarWithoutNavigationUsesMediaDashboard() {
        assertEquals(HomeDashboardMode.MEDIA,HomeDashboardMode.choose(false,false));
    }
    @Test public void startingAndEndingNavigationReturnsToTheCurrentConnectionMode() {
        assertSequence(new boolean[][]{{false,false},{true,false},{true,true},{false,true}},
                HomeDashboardMode.MEDIA,HomeDashboardMode.NAVIGATION,HomeDashboardMode.NAVIGATION,HomeDashboardMode.NORMAL);
    }
    @Test public void losingCarConnectionDuringNavigationWaitsUntilRouteEndsToShowMedia() {
        assertSequence(new boolean[][]{{false,true},{true,true},{true,false},{false,false}},
                HomeDashboardMode.NORMAL,HomeDashboardMode.NAVIGATION,HomeDashboardMode.NAVIGATION,HomeDashboardMode.MEDIA);
    }
    @Test public void connectionChangesSwitchIdleDashboardInBothDirections() {
        assertSequence(new boolean[][]{{false,false},{false,true},{false,false},{false,true}},
                HomeDashboardMode.MEDIA,HomeDashboardMode.NORMAL,HomeDashboardMode.MEDIA,HomeDashboardMode.NORMAL);
    }
    private void assertSequence(boolean[][] states,HomeDashboardMode... expected) {
        assertEquals(expected.length,states.length);
        for(int index=0;index<states.length;index++)
            assertEquals("State "+index,expected[index],HomeDashboardMode.choose(states[index][0],states[index][1]));
    }
}
