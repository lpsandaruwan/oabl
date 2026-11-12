package com.softiva.oabl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Idle periods (energy-model.md §4): one at a time, each at its own kW, never summed profiles. */
public class IdleLedgerTest {
    private static final long MIN = 60_000;

    private static final class Book implements IdleLedger.Sink {
        final List<IdleLedger.Period> periods = new ArrayList<>();
        int open;
        @Override public void opened(IdleLedger.Period period) { periods.add(period); open++; }
        @Override public void closed(IdleLedger.Period period) { open--; }
    }

    @Test public void profileChangeMidStandstillDoesNotRepriceEarlierTime() {
        Book book = new Book();
        IdleLedger ledger = new IdleLedger(book, 1, EnergyModel.IdleProfile.D);
        ledger.standstillStarted(0);
        ledger.setProfile(EnergyModel.IdleProfile.A, 10 * MIN);
        ledger.standstillEnded(30 * MIN);
        assertEquals(2, book.periods.size());
        assertEquals(0, book.open);
        IdleLedger.Summary summary = IdleLedger.summarize(book.periods, 30 * MIN);
        assertEquals(3.0 * 10 / 60, summary.kwh[EnergyModel.IdleProfile.D.ordinal()], 1e-9);
        assertEquals(0.3 * 20 / 60, summary.kwh[EnergyModel.IdleProfile.A.ordinal()], 1e-9);
        assertEquals(30 * 60, summary.totalSeconds, 1e-9);
        // Profiles are exclusive: the total is the per-period sum, not D + A for the whole time.
        assertEquals(0.5 + 0.1, summary.totalKwh, 1e-9);
    }

    @Test public void readyAndStandstillNeverOverlap() {
        Book book = new Book();
        IdleLedger ledger = new IdleLedger(book, 1, EnergyModel.IdleProfile.B);
        ledger.standstillStarted(0);
        ledger.setReady(true, 5 * MIN);
        ledger.standstillEnded(8 * MIN);   // GPS says moving: READY is the driver's word, it stays
        ledger.standstillStarted(9 * MIN); // no second period while READY is open
        assertEquals(1, book.open);
        ledger.setReady(false, 15 * MIN);  // still standing: back to a standstill period
        assertEquals(1, book.open);
        ledger.stop(20 * MIN);
        assertEquals(0, book.open);
        assertEquals(3, book.periods.size());
        long previousEnd = Long.MIN_VALUE;
        for (IdleLedger.Period period : book.periods) {
            assertTrue("no overlap", period.start >= previousEnd);
            previousEnd = period.end;
        }
        assertEquals(20 * 60, IdleLedger.summarize(book.periods, 20 * MIN).totalSeconds, 1e-9);
        assertEquals(IdleLedger.Reason.READY, book.periods.get(1).reason);
    }

    @Test public void retroactiveStandstillStartNeverOverlapsAClosedPeriod() {
        Book book = new Book();
        IdleLedger ledger = new IdleLedger(book, 1, EnergyModel.IdleProfile.D);
        ledger.setReady(true, 0);
        ledger.setReady(false, 10 * MIN);
        ledger.standstillStarted(10 * MIN - 20_000); // the 20 s rule reports when the car slowed
        assertEquals(10 * MIN, book.periods.get(1).start);
    }

    @Test public void stopClosesAndNothingAccruesAfter() {
        Book book = new Book();
        IdleLedger ledger = new IdleLedger(book, 1, EnergyModel.IdleProfile.D);
        ledger.standstillStarted(0);
        ledger.stop(MIN);
        assertNull(ledger.openPeriod());
        IdleLedger.Summary summary = IdleLedger.summarize(book.periods, 60 * MIN);
        assertEquals(0.05, summary.totalKwh, 1e-9); // 3 kW × 1 min, not an hour
    }

    @Test public void eachPeriodKeepsItsKw() {
        Book book = new Book();
        IdleLedger ledger = new IdleLedger(book, 7, EnergyModel.IdleProfile.C);
        ledger.standstillStarted(0);
        assertEquals(1.2, book.periods.get(0).kw, 1e-9);
        assertEquals(7, book.periods.get(0).session);
    }
}
