package com.softiva.oabl;

import java.util.List;

/**
 * Modelled idle energy for the active journey (energy-model.md §4). At most one period is open:
 * standstill (from {@link GpsMotion}) or an explicit "parked with the car on (READY)". Each period
 * keeps the profile and kW it started with, so a later profile change never re-prices earlier time;
 * a change closes the open period and starts a new one. Idle energy is always reported on its own
 * and never added to the battery-derived energy. No Android dependencies.
 */
final class IdleLedger {
    enum Reason { STANDSTILL, READY }

    static final class Period implements java.io.Serializable {
        long id, session, start, end;
        EnergyModel.IdleProfile profile;
        double kw;
        Reason reason;
        boolean open() { return end == 0; }
        double seconds(long now) { return Math.max(0, ((end == 0 ? now : end) - start) / 1000.0); }
    }

    /** Persists periods as they open and close. */
    interface Sink {
        void opened(Period period);
        void closed(Period period);
    }

    private final Sink sink;
    private final long session;
    private EnergyModel.IdleProfile profile;
    private Period open;
    private boolean ready, standing;
    private long lastEnd;

    IdleLedger(Sink sink, long session, EnergyModel.IdleProfile profile) {
        this.sink = sink;
        this.session = session;
        this.profile = profile;
    }

    /** After a restart within the same drive: carry on with the open period, if any. */
    void resume(Period period) {
        open = period;
        ready = period != null && period.reason == Reason.READY;
        standing = period != null && period.reason == Reason.STANDSTILL;
    }

    boolean ready() { return ready; }
    Period openPeriod() { return open; }

    void standstillStarted(long since) {
        standing = true;
        if (!ready && open == null) start(Math.max(since, lastEnd), Reason.STANDSTILL);
    }

    void standstillEnded(long at) {
        standing = false;
        if (open != null && open.reason == Reason.STANDSTILL) close(at);
    }

    void setReady(boolean on, long at) {
        if (on == ready) return;
        ready = on;
        if (open != null) close(at);
        if (on) start(at, Reason.READY);
        else if (standing) start(at, Reason.STANDSTILL);
    }

    void setProfile(EnergyModel.IdleProfile next, long at) {
        if (next == profile) return;
        profile = next;
        if (open != null) {
            Reason reason = open.reason;
            close(at);
            start(at, reason);
        }
    }

    /** The journey ended or was suspended. */
    void stop(long at) {
        if (open != null) close(at);
        ready = false;
        standing = false;
    }

    private void start(long at, Reason reason) {
        Period period = new Period();
        period.session = session;
        period.start = at;
        period.profile = profile;
        period.kw = profile.kw;
        period.reason = reason;
        open = period;
        sink.opened(period);
    }

    private void close(long at) {
        open.end = Math.max(open.start, at);
        lastEnd = open.end;
        sink.closed(open);
        open = null;
    }

    /** Time and modelled kWh per profile, summed over periods at their own kW. */
    static final class Summary implements java.io.Serializable {
        final double[] seconds = new double[EnergyModel.IdleProfile.values().length];
        final double[] kwh = new double[seconds.length];
        double totalSeconds, totalKwh;
    }

    static Summary summarize(List<Period> periods, long now) {
        Summary summary = new Summary();
        for (Period period : periods) {
            double seconds = period.seconds(now);
            int index = period.profile.ordinal();
            summary.seconds[index] += seconds;
            summary.kwh[index] += EnergyModel.idleKwh(period.kw, seconds);
            summary.totalSeconds += seconds;
            summary.totalKwh += EnergyModel.idleKwh(period.kw, seconds);
        }
        return summary;
    }
}
