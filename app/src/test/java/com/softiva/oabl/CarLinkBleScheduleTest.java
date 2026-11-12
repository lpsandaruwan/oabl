package com.softiva.oabl;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.softiva.oabl.CarLinkBleSchedule.Task.*;

public class CarLinkBleScheduleTest {
    private static final class Clock { long now; void advance(long ms) { now += ms; } }

    @Test public void startupSnapshotIsSeededBeforeSpeedAndHistory() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 100);
        assertEquals(SPEED, schedule.next(100, true, true));
        schedule.completed(SPEED, 100, 200);
        assertEquals(HISTORY, schedule.next(200, true, true));
        schedule.completed(HISTORY, 200, 350);
        assertEquals(WAIT, schedule.next(350, true, true));
        assertEquals(250, schedule.waitMs(350, true, true));
    }

    @Test public void speedDeadlineIsCheckedBetweenCatchUpPages() {
        Clock clock = new Clock();
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.completed(SPEED, 0, 100);
        clock.now = 100;
        assertEquals(HISTORY, schedule.next(clock.now, true, true));
        long began = clock.now; clock.advance(800);
        schedule.completed(HISTORY, began, clock.now);
        assertEquals(SPEED, schedule.next(clock.now, true, true));
        schedule.completed(SPEED, clock.now, clock.now + 100); clock.advance(100);
        assertEquals(WAIT, schedule.next(clock.now, true, true));
        clock.advance(100);
        assertEquals(HISTORY, schedule.next(clock.now, true, true));
    }

    @Test public void speedRunsBeforeAndAfterABlockingFullSnapshot() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.completed(SPEED, 0, 100);
        schedule.completed(HISTORY, 4000, 4100);
        assertEquals(SPEED, schedule.next(5000, true, true));
        schedule.completed(SPEED, 5000, 5100);
        assertEquals(SNAPSHOT, schedule.next(5100, true, true));
        schedule.completed(SNAPSHOT, 5100, 5900);
        assertEquals(SPEED, schedule.next(5900, true, true));
        schedule.completed(SPEED, 5900, 6000);
        assertEquals(HISTORY, schedule.next(6000, true, true));
    }

    @Test public void sixSecondSnapshotsCannotStarveHistoryBehindPrioritySpeed() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.completed(HISTORY, 4000, 4100);
        schedule.completed(SPEED, 5000, 5100);
        long now = 5100;
        for (int cycle = 0; cycle < 4; cycle++) {
            assertEquals(SNAPSHOT, schedule.next(now, true, true));
            schedule.completed(SNAPSHOT, now, now + 6000); now += 6000;
            assertEquals(SPEED, schedule.next(now, true, true));
            schedule.completed(SPEED, now, now + 100); now += 100;
            assertEquals("history gets one page even though snapshot is overdue again",
                    HISTORY, schedule.next(now, true, true));
            schedule.completed(HISTORY, now, now + 200); now += 200;
        }
    }

    @Test public void sixSecondLegacySnapshotsAlsoYieldOneHistoryPageWhenBothAreDue() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.completed(HISTORY, 4000, 4100);
        long now = 5000;
        for (int cycle = 0; cycle < 4; cycle++) {
            assertEquals(SNAPSHOT, schedule.next(now, false, false));
            schedule.completed(SNAPSHOT, now, now + 6000); now += 6060;
            assertEquals("history fairness must not depend on a short recovery window",
                    HISTORY, schedule.next(now, false, false));
            schedule.completed(HISTORY, now, now + 200); now += 200;
        }
    }

    @Test public void overdueSpeedWorkLeavesRoomForHistoryInsteadOfBursting() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.completed(SPEED, 0, 2000);
        assertEquals(HISTORY, schedule.next(2000, true, true));
        schedule.completed(HISTORY, 2000, 2020);
        assertEquals(WAIT, schedule.next(2020, true, true));
        assertEquals(30, schedule.waitMs(2020, true, true));
        assertEquals(SPEED, schedule.next(2050, true, true));
    }

    @Test public void slowSnapshotDoesNotAccumulateMissedSnapshots() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 8000);
        schedule.completed(SPEED, 7900, 8000);
        assertEquals(HISTORY, schedule.next(8000, true, true));
        schedule.completed(HISTORY, 8000, 8020);
        assertEquals(WAIT, schedule.next(8020, true, true));
        assertEquals(30, schedule.waitMs(8020, true, true));
        assertEquals(SNAPSHOT, schedule.next(8050, true, true));
        schedule.completed(SNAPSHOT, 8050, 8150);
        assertEquals(WAIT, schedule.next(8150, true, true));
    }

    @Test public void ordinaryTransferTimeCountsTowardEachTargetInterval() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 100);
        schedule.completed(SPEED, 100, 350);
        schedule.completed(HISTORY, 350, 500);
        assertEquals(100, schedule.waitMs(500, true, true));
        assertEquals(SPEED, schedule.next(600, true, true));
        schedule.completed(SPEED, 600, 700);
        assertEquals(WAIT, schedule.next(700, true, true));
        assertEquals(400, schedule.waitMs(700, true, true));
    }

    @Test public void legacyPeerRetainsOneSecondSnapshotsWithoutSpeedRequests() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 100);
        assertEquals(HISTORY, schedule.next(100, false, false));
        schedule.completed(HISTORY, 100, 200);
        assertEquals(800, schedule.waitMs(200, false, false));
        assertEquals(SNAPSHOT, schedule.next(1000, false, false));
        schedule.completed(SNAPSHOT, 1000, 1100);
        assertEquals(WAIT, schedule.next(1100, false, false));
        assertEquals(900, schedule.waitMs(1100, false, false));
    }

    @Test public void refusedFastSpeedImmediatelyRestoresLegacySnapshotDeadline() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 100);
        schedule.completed(SPEED, 0, 100);
        schedule.completed(HISTORY, 100, 200);
        assertEquals(SNAPSHOT, schedule.next(1200, false, true));
    }

    @Test public void parkedProbeDetectsMotionWithoutRequiringAnExistingMovingSpeed() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.completed(SPEED, 0, 100);
        schedule.completed(HISTORY, 100, 200);
        assertEquals(WAIT, schedule.next(500, true, false));
        assertEquals(1000, schedule.waitMs(500, true, false));
        assertEquals(SPEED, schedule.next(1500, true, false));
        assertEquals(SPEED, schedule.next(500, true, true)); // READY or moving selects the faster cadence.
    }

    @Test public void pageSizeAndDeadlineChangeWithActivityWithoutDroppingSamples() {
        assertEquals(2, CarLinkBleSchedule.historyLimit(true, 25));
        assertEquals(5, CarLinkBleSchedule.historyLimit(false, 25));
        assertEquals(2, CarLinkBleSchedule.historyLimit(true, 5));
        assertEquals(1, CarLinkBleSchedule.historyLimit(false, 1));
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.completed(SPEED, 0, 100);
        schedule.completed(HISTORY, 100, 200);
        assertEquals(WAIT, schedule.next(1100, true, false));
        schedule.completed(SPEED, 1000, 1050);
        assertEquals(HISTORY, schedule.next(1100, true, true));
    }

    @Test public void successfulLateRequestSchedulesFromItsOwnStartInsteadOfReplayingTicks() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.completed(SPEED, 0, 100);
        schedule.completed(HISTORY, 0, 100);
        assertEquals(SPEED, schedule.next(10000, true, true));
        schedule.completed(SPEED, 10000, 10100);
        assertEquals(SNAPSHOT, schedule.next(10100, true, true));
        schedule.completed(SNAPSHOT, 10100, 10200);
        assertEquals(HISTORY, schedule.next(10200, true, true));
        schedule.completed(HISTORY, 10200, 10300);
        assertEquals(WAIT, schedule.next(10300, true, true));
        assertEquals(200, schedule.waitMs(10300, true, true));
    }

    @Test public void serialCatchUpCanDrainOneHertzHistoryWhileServingSpeedAndSnapshots() {
        Clock clock = new Clock(); clock.now = 600;
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, clock.now);
        int samples = 0, speedReads = 0, snapshots = 1, turns = 0;
        while (clock.now < 60_000) {
            assertTrue("scheduler must not spin", ++turns < 500);
            CarLinkBleSchedule.Task task = schedule.next(clock.now, true, true);
            if (task == WAIT) {
                long delay = schedule.waitMs(clock.now, true, true);
                assertTrue(delay > 0); clock.advance(delay); continue;
            }
            long began = clock.now;
            // Representative simulated work, not a hardware latency guarantee.
            if (task == SPEED) { speedReads++; clock.advance(150); }
            else if (task == SNAPSHOT) { snapshots++; clock.advance(600); }
            else { samples += CarLinkBleSchedule.historyLimit(true, 25); clock.advance(650); }
            schedule.completed(task, began, clock.now);
        }
        assertTrue("history drains faster than one new sample per second", samples > 60);
        assertTrue("speed continues throughout backfill", speedReads > 30);
        assertTrue("full snapshots continue throughout backfill", snapshots >= 9);
    }

    @org.junit.Test
    public void catchUpUsesFullPagesWhenParkedAndSmallPagesWhenActive() {
        assertEquals(5, CarLinkBleSchedule.historyLimit(false, 25, false));
        assertEquals(2, CarLinkBleSchedule.historyLimit(true, 25, false));
        assertEquals(25, CarLinkBleSchedule.historyLimit(false, 25, true));
        assertEquals(5, CarLinkBleSchedule.historyLimit(true, 25, true));
        assertEquals(5, CarLinkBleSchedule.historyLimit(false, 5, true));
    }

    @org.junit.Test
    public void catchUpPagesComeBackToBackButSpeedAndSnapshotKeepTheirTurns() {
        CarLinkBleSchedule schedule = new CarLinkBleSchedule(0, 0);
        schedule.behind(true);
        schedule.completed(SPEED, 1000, 1000);       // parked: next speed read at 2500
        schedule.completed(HISTORY, 1000, 1200);     // behind: next page 50 ms after this one finished
        assertEquals(WAIT, schedule.next(1240, true, false));
        assertEquals(HISTORY, schedule.next(1250, true, false));
        // Overdue speed still goes first, and a due snapshot gets its turn between pages.
        assertEquals(SPEED, schedule.next(2600, true, false));
        schedule.completed(SPEED, 5000, 5000);
        schedule.completed(HISTORY, 4900, 5000);
        assertEquals(SNAPSHOT, schedule.next(5100, true, false));
        // Caught up: back to the normal 2 s idle interval between pages.
        schedule.behind(false);
        schedule.completed(SNAPSHOT, 5100, 5100);
        schedule.completed(SPEED, 5100, 5100);
        schedule.completed(HISTORY, 5100, 5200);
        assertEquals(WAIT, schedule.next(6000, true, false));
        schedule.completed(SPEED, 6600, 6600);
        assertEquals(WAIT, schedule.next(7000, true, false));
        assertEquals(HISTORY, schedule.next(7100, true, false)); // 5100 + 2000
    }
}
