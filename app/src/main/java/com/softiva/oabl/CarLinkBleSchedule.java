package com.softiva.oabl;

/** Independent deadlines for one serial BLE owner; all times are supplied monotonic milliseconds. */
final class CarLinkBleSchedule {
    enum Task { SPEED, SNAPSHOT, HISTORY, WAIT }

    static final long SPEED_ACTIVE_MS = 500, SPEED_IDLE_MS = 1500;
    static final long SNAPSHOT_MS = 5000, LEGACY_SNAPSHOT_MS = 1000;
    static final long HISTORY_ACTIVE_MS = 1000, HISTORY_IDLE_MS = 2000;
    /**
     * Behind Link's history (the box was off while Link kept recording, about one sample a second
     * while charging): pages come back to back. Parked, full pages with a short breather; driving,
     * small pages twice a second so speed stays first. At 5 samples every 2 s an hour-long charge
     * took about an hour to replay, and "Charge complete" waited for it.
     */
    static final long HISTORY_CATCHUP_ACTIVE_MS = 500, HISTORY_CATCHUP_IDLE_MS = 100;
    private boolean behind;
    /** Slow work must leave an opportunity for another task instead of replaying missed ticks. */
    static final long RECOVERY_MS = 50;
    private long speedStarted = -1, speedFinished = -1;
    private long snapshotStarted = -1, snapshotFinished = -1;
    private long historyStarted = -1, historyFinished = -1;

    /** The caller has already fetched the initial snapshot before scheduling other reads. */
    CarLinkBleSchedule(long initialSnapshotStarted, long initialSnapshotFinished) {
        completed(Task.SNAPSHOT, initialSnapshotStarted, initialSnapshotFinished);
    }

    Task next(long now, boolean fastSpeed, boolean active) {
        if (fastSpeed && now >= speedAt(active)) return Task.SPEED;
        boolean snapshotDue = now >= snapshotAt(fastSpeed), historyDue = now >= historyAt(active);
        // Long snapshots can be overdue again after the priority speed read. Give history one
        // page before repeating that bulk task; both queues still have independent deadlines.
        if (historyDue && snapshotDue && snapshotFinished > historyFinished) return Task.HISTORY;
        if (snapshotDue) return Task.SNAPSHOT;
        if (historyDue) return Task.HISTORY;
        return Task.WAIT;
    }

    long waitMs(long now, boolean fastSpeed, boolean active) {
        long due = Math.min(snapshotAt(fastSpeed), historyAt(active));
        if (fastSpeed) due = Math.min(due, speedAt(active));
        return Math.max(0, due - now);
    }

    void completed(Task task, long began, long finished) {
        if (task == Task.SPEED) { speedStarted = began; speedFinished = finished; }
        else if (task == Task.SNAPSHOT) { snapshotStarted = began; snapshotFinished = finished; }
        else if (task == Task.HISTORY) { historyStarted = began; historyFinished = finished; }
        else throw new IllegalArgumentException("WAIT is not a request");
    }

    static int historyLimit(boolean active, int transportLimit) {
        return Math.max(1, Math.min(active ? 2 : 5, transportLimit));
    }

    /** While catching up: the transport's full page when parked, a little more than usual when active. */
    static int historyLimit(boolean active, int transportLimit, boolean behind) {
        if (!behind) return historyLimit(active, transportLimit);
        return Math.max(1, active ? Math.min(5, transportLimit) : transportLimit);
    }

    /** Whether the box is behind Link's recorded history; set after every history page. */
    void behind(boolean value) { behind = value; }
    boolean behind() { return behind; }

    private long speedAt(boolean active) { return deadline(speedStarted, speedFinished, active ? SPEED_ACTIVE_MS : SPEED_IDLE_MS); }
    private long snapshotAt(boolean fastSpeed) { return deadline(snapshotStarted, snapshotFinished, fastSpeed ? SNAPSHOT_MS : LEGACY_SNAPSHOT_MS); }
    private long historyAt(boolean active) {
        long interval = behind ? (active ? HISTORY_CATCHUP_ACTIVE_MS : HISTORY_CATCHUP_IDLE_MS) : (active ? HISTORY_ACTIVE_MS : HISTORY_IDLE_MS);
        return deadline(historyStarted, historyFinished, interval);
    }
    private static long deadline(long began, long finished, long interval) {
        return began < 0 ? 0 : Math.max(began + interval, finished + RECOVERY_MS);
    }
}
