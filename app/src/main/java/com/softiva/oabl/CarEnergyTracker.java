package com.softiva.oabl;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

/** Incremental, restartable car-only accounting. A missing connection is never treated as car-off. */
final class CarEnergyTracker {
    static final long FRESH_MS = 60_000, GAP_MS = 90_000;
    /** How old the last battery reading may be and still be the level a gap started from. */
    static final long SOC_CARRY_MS = 30 * 60_000L;
    static final class Frame implements java.io.Serializable {
        String run = "";
        long time;
        double idleKw, capacity = Double.NaN,latitude=Double.NaN,longitude=Double.NaN;
        final EnumMap<CarSignal, Double> values = new EnumMap<>(CarSignal.class);
        final EnumMap<CarSignal, Long> times = new EnumMap<>(CarSignal.class);
        Frame set(CarSignal signal, double value) { values.put(signal, value); times.put(signal, time); return this; }
        double get(CarSignal signal) {
            Long at = times.get(signal);
            return at == null || time - at < -5000 || time - at > FRESH_MS ? Double.NaN : values.getOrDefault(signal, Double.NaN);
        }
        boolean is(CarSignal signal, double value) { return get(signal) == value; }
        double soc() { return get(CarSignal.SOC); }
        /** The last battery reading even when it is no longer fresh, if no older than {@code maxAgeMs}: the level before a gap. */
        double carriedSoc(long maxAgeMs) {
            Long at = times.get(CarSignal.SOC); Double value = values.get(CarSignal.SOC);
            return at == null || value == null || !CarSignal.plausible(CarSignal.SOC, value) || time - at > maxAgeMs ? Double.NaN : value;
        }
    }
    static final class Event implements java.io.Serializable {
        String key, kind, state = "open", distanceSource = "car odometer";
        long start, end, last;
        double before = Double.NaN, after = Double.NaN, capacity = Double.NaN;
        double km, movingSeconds, stoppedSeconds, modelKwh;
        double peakSpeed;
        double gapSeconds, gapKm, gapStopSeconds, chargerKwh, coveredSeconds;
        /** Seconds with the AC on (stops: while stopped; drives: whole drive), and modelled regen kWh. */
        double acSeconds, regenKwh;
        double tripKm=Double.NaN,tripKwh100=Double.NaN,tripCKm=Double.NaN,tripCKwh100=Double.NaN;
        /** Trip A as first read with the car READY in this drive: the baseline for {@link #tripNetKwh()}. */
        double tripStartKm=Double.NaN,tripStartKwh100=Double.NaN;
        double rangeStart=Double.NaN,temperatureSum,temperatureSeconds;
        double latitude=Double.NaN,longitude=Double.NaN;
        /** Only for the still watcher: the odometer when the car came to rest. Not stored with finished events. */
        double startOdometer=Double.NaN;
        boolean odometerFlag,joinable;
        final double[] temperatureKm=new double[6],temperaturePoints=new double[6];
        double measuredKwh(){return coveredSeconds>0?chargerKwh:Double.NaN;}
        double averageKph(){return movingSeconds>0?Math.max(0,km-gapKm)/movingSeconds*3600:Double.NaN;}
        double realKmPerPoint(){return points()>0?km/points():Double.NaN;}
        double rangeAtStartSoc(){return realKmPerPoint()*before;}
        double temperature(){return temperatureSeconds>0?temperatureSum/temperatureSeconds:Double.NaN;}
        boolean inferred, gap, offSeen, acSeen;
        /** Corroborated external charging; a SOC rise or regenerative power is not sufficient. */
        boolean externalCharge;
        /**
         * Net battery energy for this drive from the car's own trip computer: trip A's distance × average
         * kWh/100 at the end, minus the same at the start. It is resolved to about 0.01 kWh, where SOC
         * points are about 0.3 kWh each. NaN when the drive has no start reading or trip A was reset.
         */
        double tripNetKwh() {
            if (!Double.isFinite(tripStartKm) || !Double.isFinite(tripStartKwh100) || !Double.isFinite(tripKm) || !Double.isFinite(tripKwh100)) return Double.NaN;
            if (tripKm < tripStartKm) return Double.NaN;
            double net = (tripKm * tripKwh100 - tripStartKm * tripStartKwh100) / 100;
            return net >= -0.02 ? Math.max(0, net) : Double.NaN;
        }
        /** Net energy for the drive: the trip computer's when known, else whole SOC points × capacity. */
        double netKwh(double usable) { double trip = tripNetKwh(); return Double.isFinite(trip) ? trip : usedKwh(usable); }
        double points() { return Double.isNaN(before) || Double.isNaN(after) || after > before ? Double.NaN : before - after; }
        /** At the capacity in force when the event began; screens use {@link #usedKwh(double)} with today's. */
        double usedKwh() { return usedKwh(capacity); }
        double usedKwh(double usable) { return usable > 0 ? points() * usable / 100 : Double.NaN; }
        double gain() { return Double.isNaN(before) || Double.isNaN(after) ? Double.NaN : Math.max(0, after - before); }
        boolean closed() { return !state.equals("open"); }
    }
    static final class State implements java.io.Serializable {
        Frame last;
        Event drive, stop, charge;
        /** Plugged in with no charging state reported: becomes a possible charge only if the battery rose. */
        Event plug;
        /** Not moving, not otherwise charging: becomes a possible charge if the battery rose by the time the car moves. */
        Event still;
        /** Trip C as last read with the car READY: with the cluster off the car reports 0. */
        double tripCKm = Double.NaN, tripCKwh100 = Double.NaN;
        /** Seconds the car has been slowing, for {@link CarRegenModel}. */
        double slowingFor;
    }
    /** Whole-point SOC wobbles by one; smaller rises are rounding, never a charge. */
    static final double MIN_INFERRED_RISE = 2;
    /** Driving across a gap cannot lift the battery by this much, so a rise this big was a charge even if the car also moved. */
    static final double UNEXPLAINED_RISE = 4;
    static boolean unexplainedRise(Frame before, Frame after, double rise) {
        // Back after at least an hour, car on or off and even a few kilometres further on (31 October: 73 to 86 %,
        // found already running, 5 km on): a long, big rise was time on a charger. A short gap could be downhill regen.
        return rise >= UNEXPLAINED_RISE && after.time - before.time >= UNEXPLAINED_GAP_MS;
    }
    static final long UNEXPLAINED_GAP_MS = 3_600_000;
    static final long STILL_FRESH_MS = 10_000;
    /** Odometer distance across a gap below this is resolution or creep, not a drive. */
    static final double OFFLINE_DRIVE_MIN_KM = 0.5;
    private final State state;
    CarEnergyTracker(State state) { this.state = state; }
    private Event create(String kind, Frame frame) {
        Event event = new Event(); event.kind = kind; event.start = event.last = frame.time;
        event.key = kind + ":" + frame.time; event.before = event.after = frame.soc(); event.capacity = frame.capacity;
        event.tripCKm=frame.get(CarSignal.TRIP_C_KM);event.tripCKwh100=frame.get(CarSignal.TRIP_C_KWH100);
        event.offSeen = frame.is(CarSignal.READY, 0);event.rangeStart=frame.get(CarSignal.RANGE_EST);
        event.latitude=frame.latitude;event.longitude=frame.longitude;return event;
    }
    /** An event that began before the first fresh battery reading takes the first one that arrives. */
    private static void fill(Event event, Frame now) {
        if (!Double.isNaN(event.before) || Double.isNaN(now.soc())) return;
        event.before = now.soc();
        // The drive's start range is only a baseline when it was read with its start SOC.
        if (event.kind.equals("drive") && Double.isNaN(event.rangeStart)) event.rangeStart = now.get(CarSignal.RANGE_EST);
    }
    private void close(Event event, long at, boolean gap, List<Event> changed) {
        if (event == null) return;
        event.end = Math.max(event.start, at); event.state = gap ? "interrupted" : "complete"; event.gap |= gap; changed.add(event);
    }
    static boolean externalCharging(Frame frame) {
        double speed = frame.get(CarSignal.SPEED);
        return frame.is(CarSignal.CHARGING, 1) && !frame.is(CarSignal.PLUG, 0)
                && !(speed > 1) && (speed >= 0 || frame.is(CarSignal.READY, 0) || frame.is(CarSignal.PLUG, 1));
    }
    /** The car didn't move across the gap, on or off: regen needs a moving car, so a rise here was a charger. */
    static boolean stationaryChargeGap(Frame before, Frame after) {
        double a = before.get(CarSignal.ODOMETER), b = after.get(CarSignal.ODOMETER);
        return Double.isFinite(a) && Double.isFinite(b) && Math.abs(b - a) <= 0.05;
    }
    /** Returns only new/changed derived events. Replayed or out-of-order frames have no effect. */
    List<Event> accept(Frame now) {
        List<Event> changed = new ArrayList<>(); Frame previous = state.last;
        if (now.time <= 0 || (previous != null && now.time <= previous.time)) return changed;
        boolean gap = previous != null && now.time - previous.time > GAP_MS;
        CarDriveRejoin.Decision decision=previous==null?CarDriveRejoin.Decision.UNKNOWN:CarDriveRejoin.decide(previous,now);
        // A changed boot identity is proof on its own. A trip A drop counts only across a gap: a real
        // restart reboots the head unit, while a one-reading 0 (cluster blanking) mid-drive is not one.
        double bootA=previous==null?Double.NaN:previous.get(CarSignal.HU_BOOT_ID),bootB=now.get(CarSignal.HU_BOOT_ID);
        boolean rebooted=Double.isFinite(bootA)&&Double.isFinite(bootB)&&bootA!=bootB;
        boolean restarted=(gap||rebooted)&&decision==CarDriveRejoin.Decision.RESTART;
        boolean rejoin=gap&&(decision==CarDriveRejoin.Decision.SAME_DRIVE||decision==CarDriveRejoin.Decision.SAME_STOP);
        double seconds = previous == null || gap ? 0 : (now.time - previous.time) / 1000.0;
        boolean charging = externalCharging(now);
        double speed = now.get(CarSignal.SPEED);
        boolean stopped = !charging && now.is(CarSignal.CHARGING, 0) && now.is(CarSignal.READY, 1) && speed >= 0 && speed <= 1;
        boolean drive = !charging && (speed > 1 || now.is(CarSignal.READY, 1));
        if (gap||restarted) {
            if(!rejoin){
                if(state.drive!=null){state.drive.odometerFlag|=decision==CarDriveRejoin.Decision.INVALID_ODOMETER;state.drive.joinable=gap&&decision==CarDriveRejoin.Decision.UNKNOWN;if(gap)state.drive.gapSeconds+=(now.time-previous.time)/1000.0;}
                close(state.stop, previous.time, true, changed); state.stop = null;
                close(state.drive, previous.time, true, changed); state.drive = null;
            }else if(state.drive!=null){
                state.drive.gap=true;state.drive.gapSeconds+=(now.time-previous.time)/1000.0;
                double distance=now.get(CarSignal.ODOMETER)-previous.get(CarSignal.ODOMETER);
                state.drive.km+=distance;state.drive.gapKm+=distance;
                if(decision==CarDriveRejoin.Decision.SAME_STOP){state.drive.gapStopSeconds+=(now.time-previous.time)/1000.0;}
            }
            // A rise while disconnected is a candidate, never proof of charging or of ignition-off.
        }
        if (previous != null && gap && !rejoin && decision != CarDriveRejoin.Decision.INVALID_ODOMETER) {
            // Driven while nothing was received (link or box off): the odometer still tells the distance.
            double fromOdo = previous.get(CarSignal.ODOMETER), toOdo = now.get(CarSignal.ODOMETER);
            if (Double.isFinite(fromOdo) && Double.isFinite(toOdo) && toOdo - fromOdo >= OFFLINE_DRIVE_MIN_KM) {
                Event offline = create("drive", previous); offline.key = "offline-drive:" + previous.time + ":" + now.time;
                offline.km = offline.gapKm = toOdo - fromOdo; offline.gap = offline.inferred = true;
                offline.gapSeconds = (now.time - previous.time) / 1000.0; offline.last = now.time;
                offline.distanceSource = "car odometer · no live data";
                double after = now.soc();
                // A charge in between makes the battery change meaningless for this drive.
                offline.after = decision == CarDriveRejoin.Decision.CHARGE || !(after <= offline.before) ? Double.NaN : after;
                close(offline, now.time, false, changed);
            }
        }
        if (previous != null && gap) {
            double before = previous.carriedSoc(SOC_CARRY_MS);
            if (state.charge == null && state.plug == null && !previous.is(CarSignal.CHARGING,1) && !charging && !Double.isNaN(now.soc()) && !Double.isNaN(before)
                    && now.soc() - before >= MIN_INFERRED_RISE && (stationaryChargeGap(previous, now) || unexplainedRise(previous, now, now.soc() - before))) {
                Event candidate = create("charge", previous); candidate.before = before; candidate.key = "offline-charge:" + previous.time + ":" + now.time;
                candidate.inferred = true; candidate.externalCharge = true; candidate.gap = gap; candidate.after = now.soc(); candidate.last = now.time;
                close(candidate, now.time, false, changed);
            }
        }
        // Charging can span a gap, but time across that gap is not presented as measured coverage.
        if (now.is(CarSignal.READY, 1)) {
            double km = now.get(CarSignal.TRIP_C_KM), kwh100 = now.get(CarSignal.TRIP_C_KWH100);
            if (km > 0) { state.tripCKm = km; state.tripCKwh100 = kwh100; }
        }
        if (charging && state.charge == null){state.charge = create("charge", now);
            state.charge.externalCharge = true;
            state.charge.tripCKm = state.tripCKm; state.charge.tripCKwh100 = state.tripCKwh100;
            // Seen charging only after a gap: it may have begun unobserved. Start from the last reading
            // before the gap, so the rise and the unmeasured time both stay in this charge.
            Event plugged = state.plug;
            if (plugged != null && !Double.isNaN(plugged.before) && (plugged.gap || gap)) { state.charge.start = plugged.start; state.charge.before = plugged.before; state.charge.gap |= gap; }
            else if (gap && previous != null && !Double.isNaN(previous.carriedSoc(SOC_CARRY_MS))
                    && (Double.isNaN(now.soc()) || previous.carriedSoc(SOC_CARRY_MS) <= now.soc())) {
                state.charge.start = previous.time; state.charge.before = previous.carriedSoc(SOC_CARRY_MS); state.charge.gap = true;
            }
            state.plug = null;
        }
        if (state.charge != null) {
            Event charge = state.charge; charge.gap |= gap; charge.last = now.time; fill(charge, now);
            // The car can't move while it reports charging, so any fresh fix during the charge is the charger.
            if(Double.isNaN(charge.latitude)&&charging&&Double.isFinite(now.latitude)&&Double.isFinite(now.longitude)){charge.latitude=now.latitude;charge.longitude=now.longitude;}
            if(previous!=null&&externalCharging(previous)&&charging){
                double powerA=previous.get(CarSignal.POWER),powerB=now.get(CarSignal.POWER);
                long atA=previous.times.getOrDefault(CarSignal.POWER,0L),atB=now.times.getOrDefault(CarSignal.POWER,0L);
                long span=now.time-previous.time;
                if(span>0&&span<=5000&&atB>atA&&atA==previous.time&&atB==now.time&&powerA>=0&&powerB>=0&&powerA<=500&&powerB<=500){
                    charge.chargerKwh+=(powerA+powerB)/2*span/3600000.0;charge.coveredSeconds+=span/1000.0;
                }
            }

            if (!Double.isNaN(now.soc())) charge.after = now.soc();
            charge.offSeen |= now.is(CarSignal.READY, 0);
            if (now.is(CarSignal.CHARGING, 0) || speed > 1 || now.is(CarSignal.PLUG, 0)) {
                // Do not include a driving/regen endpoint in the charger energy or SOC gain. The exception is a
                // charge that finished while the car was out of reach: the first reading back, if higher, is
                // where it got to, and the last one before the gap is only where the box lost sight of it.
                boolean finishedUnseen = gap && now.soc() > previous.soc();
                if (previous != null && (speed > 1 || now.is(CarSignal.PLUG, 0)) && !finishedUnseen) charge.after = previous.soc();
                close(charge, now.time, false, changed); state.charge = null;
            }
            else changed.add(charge);
        }
        // Plugged in while the car never reports charging (for example a state code not yet mapped):
        // a battery rise of two points or more by unplugging or READY becomes a possible charge.
        boolean plugged = !charging && state.charge == null && now.is(CarSignal.PLUG, 1) && !now.is(CarSignal.READY, 1) && !(speed > 1);
        if (plugged && state.plug == null) { state.plug = create("plug", now); state.plug.key = "plug-charge:" + now.time; }
        if (state.plug != null) {
            Event plug = state.plug; fill(plug, now); plug.last = now.time; plug.gap |= gap;
            if (plugged && !Double.isNaN(now.soc())) plug.after = now.soc();
            plug.offSeen |= now.is(CarSignal.READY, 0);
            if (!plugged) {
                if (gap && now.soc() > plug.after) plug.after = now.soc();
                if (plug.after - plug.before >= MIN_INFERRED_RISE) {
                    plug.kind = "charge"; plug.inferred = true; plug.externalCharge = true; close(plug, now.time, false, changed);
                }
                state.plug = null;
            }
        }
        // Stopped and the battery climbs, whatever the car reports (it has never been seen saying charging or
        // plugged with the car on): moving is regen, stopped is a charger. Settled when the car moves off.
        if (gap) state.still = null;
        boolean still = !charging && state.charge == null && state.plug == null && !(speed > 1);
        double stillOdo = state.still == null ? Double.NaN : state.still.startOdometer;
        boolean rolled = Double.isFinite(stillOdo) && now.get(CarSignal.ODOMETER) - stillOdo > 0.05;
        if (state.still != null && (!still || rolled)) {
            Event parked = state.still; state.still = null;
            if (parked.after - parked.before >= MIN_INFERRED_RISE) {
                parked.kind = "charge"; parked.key = "still-charge:" + parked.start; parked.inferred = true; parked.externalCharge = true;
                close(parked, now.time, false, changed);
            }
        }
        // A freshly woken head unit can repeat a level that's nearly a minute old (29 October: 27 %, when the
        // car was at 42), so the watcher takes only readings from the last few seconds.
        double level = now.time - now.times.getOrDefault(CarSignal.SOC, 0L) <= STILL_FRESH_MS ? now.soc() : Double.NaN;
        if (still && !rolled && state.still == null) { state.still = create("still", now); state.still.startOdometer = now.get(CarSignal.ODOMETER); state.still.before = state.still.after = level; }
        if (state.still != null) {
            if (Double.isNaN(state.still.before)) state.still.before = level;
            if (!Double.isNaN(level)) state.still.after = level;
            state.still.last = now.time; state.still.offSeen |= now.is(CarSignal.READY, 0);
        }
        boolean continuingDrive = state.drive != null;
        if (drive && state.drive == null) state.drive = create("drive", now);
        if (state.drive != null) {
            Event event = state.drive; fill(event, now);
            double trip=now.get(CarSignal.TRIP_KM),tripEnergy=now.get(CarSignal.TRIP_KWH100);
            if (now.is(CarSignal.READY,1) || speed>1) {
                if(Double.isFinite(trip))event.tripKm=trip;if(Double.isFinite(tripEnergy))event.tripKwh100=tripEnergy;
            }
            if(Double.isNaN(event.tripStartKm)&&now.is(CarSignal.READY,1)&&Double.isFinite(trip)&&Double.isFinite(tripEnergy)){event.tripStartKm=trip;event.tripStartKwh100=tripEnergy;}
            double oldKm=event.km;
            if (speed>=0 && speed<=220)event.peakSpeed=Math.max(event.peakSpeed,speed);
            if (continuingDrive && seconds > 0 && previous != null && !externalCharging(previous)) {
                double beforeSpeed = previous.get(CarSignal.SPEED);
                if (beforeSpeed >= 0 && speed >= 0 && (beforeSpeed > 1 || speed > 1)) event.movingSeconds += seconds;
                event.regenKwh += CarRegenModel.kwh(beforeSpeed, speed, seconds, state.slowingFor,
                        previous.is(CarSignal.BRAKE_PEDAL, 1) || now.is(CarSignal.BRAKE_PEDAL, 1));
                state.slowingFor = CarRegenModel.slowingAfter(state.slowingFor, beforeSpeed, speed, seconds);
                if (previous.is(CarSignal.HVAC_ON, 1)) event.acSeconds += seconds;
                double fromOdo = previous.get(CarSignal.ODOMETER), toOdo = now.get(CarSignal.ODOMETER);
                if (Double.isFinite(fromOdo) && Double.isFinite(toOdo) && toOdo >= fromOdo && toOdo - fromOdo <= seconds / 3600 * 220 + 1) event.km += toOdo - fromOdo;
                else if(Double.isFinite(fromOdo)&&Double.isFinite(toOdo)){event.odometerFlag=true;}
                else if (beforeSpeed >= 0 && speed >= 0 && beforeSpeed <= 220 && speed <= 220) {
                    event.km += (beforeSpeed + speed) / 2 * seconds / 3600;
                    event.distanceSource = "estimated from car speed";
                }
            }
            if(previous!=null&&seconds>0){
                double temp=previous.get(CarSignal.TEMP_OUT);
                if(Double.isFinite(temp)){
                    event.temperatureSum+=temp*seconds;event.temperatureSeconds+=seconds;
                    int band=CarEnergyInsights.temperatureBand(temp);double drop=previous.soc()-now.soc();
                    if(drop>=0&&Double.isFinite(drop)){event.temperatureKm[band]+=event.km-oldKm;event.temperaturePoints[band]+=drop;}
                }
            }
            if (!Double.isNaN(now.soc())) event.after = now.soc();
            event.last = now.time;
            if (charging || (now.is(CarSignal.READY, 0) && speed >= 0 && speed <= 1)) {
                close(event, now.time, false, changed); state.drive = null;
            } else changed.add(event);
        }
        if (stopped && state.stop == null) state.stop = create("stop", now);
        if (state.stop != null) {
            Event event = state.stop; fill(event, now);
            boolean previouslyStopped = previous != null && previous.is(CarSignal.READY, 1)
                    && previous.is(CarSignal.CHARGING, 0) && previous.get(CarSignal.SPEED) >= 0 && previous.get(CarSignal.SPEED) <= 1;
            if (seconds > 0 && previouslyStopped) {
                event.stoppedSeconds += seconds;
                if (previous.is(CarSignal.HVAC_ON, 1)) event.acSeconds += seconds;
                if (Double.isFinite(previous.idleKw) && previous.idleKw >= 0) event.modelKwh += previous.idleKw * seconds / 3600;
                if (state.drive != null) state.drive.stoppedSeconds += seconds;
            }
            // A moving endpoint is not a battery measurement taken at the stop; retain last stopped SOC.
            if (stopped && !Double.isNaN(now.soc())) event.after = now.soc();
            event.acSeen |= now.is(CarSignal.HVAC_ON, 1); event.last = now.time;
            if (!stopped) { close(event, now.time, false, changed); state.stop = null; }
            else changed.add(event);
        }
        state.last = now;
        return changed;
    }
}
