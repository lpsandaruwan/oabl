package com.softiva.oabl;

import android.content.Context;
import java.util.Locale;

/** One read of Home's energy state, prepared by the :energy owner rather than the launcher UI. */
final class HomeEnergySnapshot implements java.io.Serializable {
    private static final long serialVersionUID=1L;
    boolean car,live,charging,lowReserve,connected;
    /** The car is switched on (READY), as the car reports it. */
    boolean ready;
    double soc=Double.NaN,range=Double.NaN,chargeTarget=Double.NaN;
    /** Profile and learned economy used for truthful battery-left and arrival estimates. */
    double usableCapacity=Double.NaN,reservePercent=Double.NaN,learnedKwhPerKm=Double.NaN;
    long ageSeconds;
    /** The battery level the car last left behind (end of its newest drive, stop or charge) and when; NaN/0 if none. */
    double lastSoc=Double.NaN;long lastSocAt;
    /** A charge seen at once from the battery level (higher than before the car went quiet); its details come with the history. */
    double earlyFrom=Double.NaN,earlyTo=Double.NaN;long earlySince;
    String vehicle="",referenceText="",chargeDetail="";
    CarDrivePerformance.Result performance;
    // Everything else Home shows from the car, read in the same request so Home makes one call per update.
    long capturedAt,speedAt;
    double speed=Double.NaN,odometer=Double.NaN,temperature=Double.NaN,power=Double.NaN;
    DriveMode driveMode;boolean matchDriveMode;
    CarEnergyTracker.Event openCharge,latestDrive;
    /** True while the car reports live and {@code latestDrive} is the drive in progress; false when it is the last finished one. */
    boolean driveCurrent;
    /** A finished charge that may be prompted now, and how many still need a price. */
    CarEnergyRepository.Item promptCharge;int unpricedCharges;
    CarBatteryHealth.Status lowVoltage;
    /** The A/C fan level and set temperature the car shows (raw, unconfirmed); NaN when unread. */
    double acFan=Double.NaN,acSetTemp=Double.NaN;
    /** The link's own status line, for the Home chip's transport word. */
    String linkState="";
    /** Today's driving cost and economy and this session's cost, for the header strip. */
    HomeCost cost;
    /** The Battery page's data, read in the same request. */
    BatteryFacts battery;
    static final int LINK_OFFLINE=0,LINK_ONLINE=2;
    /** Online with data, syncing (connected, data or history still arriving), or offline: the header dot. */
    int linkStatus(){
        // Two states only: a separate "syncing" state flickered while history caught up.
        return connected?LINK_ONLINE:LINK_OFFLINE;
    }
    /** The header's words for the dot: car online or car offline. */
    String linkWords(){
        return linkStatus()==LINK_ONLINE?"Car online":"Car offline";
    }
    /** Expire against the measured source time; copying into Home must not renew an old reading. */
    double currentSpeed(long now){return CarLinkController.freshSpeedAt(speedAt,now)?speed:Double.NaN;}
    static HomeEnergySnapshot capture(Context context){
        if(!EnergyClient.owner(context))return (HomeEnergySnapshot)EnergyClient.call(context,"home.snapshot");
        EnergyStore store=EnergyStore.get(context);EnergyModel.Profile profile=store.profile();
        CarLinkController link=CarLinkController.get(context);HomeEnergySnapshot result=new HomeEnergySnapshot();
        result.usableCapacity=profile.usableKwh;result.reservePercent=profile.reservePercent;
        result.car=link.expectsCarBattery();result.connected=link.connected();result.live=result.car&&result.connected&&link.hasFreshLive();
        result.vehicle=store.vehicleName().toUpperCase(Locale.getDefault());
        result.linkState=link.state();
        if(result.live){
            result.soc=link.liveSoc();result.live=Double.isFinite(result.soc)&&result.soc>=0&&result.soc<=100;
            result.range=link.liveRangeKm();if(!Double.isFinite(result.range)||result.range<0)result.range=Double.NaN;
            result.charging=link.liveCharging();result.ageSeconds=link.liveAgeSeconds(CarSignal.SOC);
            if(result.charging)result.chargeTarget=link.liveValue(CarSignal.CHARGE_TARGET);
            if(result.charging)result.chargeDetail=chargeDetail(link.liveValue(CarSignal.POWER),link.liveValue(CarSignal.CHARGE_TARGET),link.liveValue(CarSignal.CHARGE_REMAINING_RAW));
        }
        if(!result.live)result.soc=Double.NaN;
        result.range=result.car&&result.connected?validRange(link.liveRangeKm()):Double.NaN;
        if(!result.car){EnergyJournal.Status status=EnergyJournal.get(context).status();if(status.lastReading!=null)result.soc=status.lastReading.percent;}
        result.lowReserve=result.live&&result.soc<=profile.reservePercent+15;
        boolean ranges=result.car&&result.connected;
        result.referenceText=references(ranges?link.liveValue(CarSignal.RANGE_STANDARD):Double.NaN,ranges?link.liveValue(CarSignal.RANGE_NEDC):Double.NaN);
        CarEnergyRepository repository=new CarEnergyRepository(store);
        CarEnergySummary learned=repository.summary(0,Long.MAX_VALUE);
        double learnedKwh=learned.usedKwh(profile.usableKwh),learnedKm=learned.energyKm(profile.usableKwh);
        if(Double.isFinite(learnedKwh)&&learnedKwh>0&&learnedKm>=1)result.learnedKwhPerKm=learnedKwh/learnedKm;
        result.latestDrive=repository.latestDrive(link.currentSource());
        if(result.car&&!result.live){
            // With the car unreachable, the level it last reported is still worth showing, marked as old.
            for(CarEnergyRepository.Item item:repository.events(0,Long.MAX_VALUE,null,6)){
                CarEnergyTracker.Event e=item.event;
                double level=Double.isFinite(e.after)?e.after:e.before;
                if(Double.isFinite(level)&&level>=0&&level<=100){
                    result.lastSoc=level;result.lastSocAt=e.end>0?e.end:e.last>0?e.last:e.start;break;
                }
            }
        }
        // A drive left "open" by a link that dropped is not the drive now: with no live data it is shown as the
        // last finished drive instead, so a parked car does not claim to be learning a drive.
        result.driveCurrent=result.latestDrive!=null&&!result.latestDrive.closed()&&result.live;
        if(!result.driveCurrent){
            CarEnergyTracker.Event finished=lastFinishedDrive(repository);
            result.latestDrive=finished;
        }
        result.performance=CarDrivePerformance.evaluate(result.latestDrive,System.currentTimeMillis(),result.live&&!result.charging);
        result.capturedAt=System.currentTimeMillis();
        if(result.car){
            CarLinkController.SpeedReading speed=link.liveSpeedReading();
            result.speed=speed.current(result.capturedAt);result.speedAt=speed.measuredAt;
            result.ready=link.liveValue(CarSignal.READY)==1;
            result.odometer=link.liveValue(CarSignal.ODOMETER);result.temperature=link.liveValue(CarSignal.TEMP_OUT);
            result.power=link.liveValue(CarSignal.POWER);result.driveMode=link.liveDriveMode();result.matchDriveMode=link.matchDriveMode();
            if(result.connected&&result.charging)result.openCharge=repository.openCharge();
            result.unpricedCharges=unpriced(repository);
            boolean stationary=Double.isFinite(result.speed)&&result.speed<=1&&link.liveValue(CarSignal.CHARGING)==0;
            if(stationary&&result.unpricedCharges>0)result.promptCharge=pending(repository);
            early(result,repository);
            result.acFan=link.liveValue(CarSignal.AC_FAN_RAW);result.acSetTemp=link.liveValue(CarSignal.AC_SET_TEMP_RAW);
            result.lowVoltage=CarBatteryHealth.status(link.liveValue(CarSignal.V12_RAW),link.liveValue(CarSignal.READY),link.liveValue(CarSignal.CHARGING));
            result.cost=cost(store,repository,profile,result.latestDrive);
            result.battery=BatteryFacts.read(link,store,profile,result.charging);
            if(result.battery!=null)result.battery.learnedKwhPerKm=result.learnedKwhPerKm;
        }
        return result;
    }
    private static long finishedAt;private static CarEnergyTracker.Event finished;private static long finishedGeneration=-1;
    /** The newest drive that ended and covered at least 300 m, kept for 10 s (or until a write): blips of 0 km are not "the last drive". */
    private static synchronized CarEnergyTracker.Event lastFinishedDrive(CarEnergyRepository repository){
        long now=android.os.SystemClock.elapsedRealtime(),generation=CarEnergyRepository.chargeGeneration();
        if(generation==finishedGeneration&&now-finishedAt<10_000)return finished;
        CarEnergyTracker.Event found=null;
        for(CarEnergyRepository.Item item:repository.events(0,Long.MAX_VALUE,"drive",12)){
            if(item.event.closed()&&item.event.km>=0.3){found=item.event;break;}
        }
        finished=found;finishedAt=now;finishedGeneration=generation;
        return found;
    }
    // Today's totals and the prices change slowly; Home asks about once a second, so they are kept for
    // 10 s (or until a charge-related write). The session is rebuilt from the live drive every time.
    private static long costAt,costGeneration=-1,costDay;
    private static CarEnergySummary costToday;
    private static double costPrice=Double.NaN,costGrid=Double.NaN;
    private static synchronized HomeCost cost(EnergyStore store,CarEnergyRepository repository,EnergyModel.Profile profile,CarEnergyTracker.Event drive){
        long now=android.os.SystemClock.elapsedRealtime(),generation=CarEnergyRepository.chargeGeneration();
        long dayStart=EnergyPeriods.today(System.currentTimeMillis(),java.util.TimeZone.getDefault(),Locale.getDefault()).start;
        if(costToday==null||generation!=costGeneration||dayStart!=costDay||now-costAt>10_000){
            java.util.List<EnergyModel.Charge> charges=store.charges();
            costToday=repository.summary(dayStart,Long.MAX_VALUE);
            costPrice=CarTripCost.averagePrice(charges,store.homeRate());costGrid=CarChargeEnergy.gridKwhPerPoint(charges);
            costGeneration=generation;costDay=dayStart;costAt=now;
        }
        return HomeCost.of(costToday,drive,dayStart,profile.usableKwh,costPrice,costGrid,store.currency());
    }
    // The level the car last reported while it was out of reach (box off, link down). Kept in the
    // energy process across Home refreshes; compared with the first live level once the car is back.
    private static double offlineSoc=Double.NaN;private static long offlineAt;
    static final double EARLY_CHARGE_POINTS=5;
    /** Up by 5 points or more since the car went quiet, and not charging now: it was charged meanwhile. */
    static boolean chargedWhileAway(double before,double now,boolean chargingNow){
        return Double.isFinite(before)&&Double.isFinite(now)&&!chargingNow&&now-before>=EARLY_CHARGE_POINTS;
    }
    private static synchronized void early(HomeEnergySnapshot result,CarEnergyRepository repository){
        if(!result.live){
            if(Double.isFinite(result.lastSoc)){offlineSoc=result.lastSoc;offlineAt=result.lastSocAt;}
            return;
        }
        if(!Double.isFinite(offlineSoc))return;
        // Charging now: the live charge takes it from here. Not higher: nothing was charged (a lower level is
        // an offline drive, which the odometer and the history record).
        if(!chargedWhileAway(offlineSoc,result.soc,result.charging)){offlineSoc=Double.NaN;return;}
        // The history has delivered the charge itself (prompting, reviewed or not): it replaces this note.
        boolean recorded=result.promptCharge!=null&&result.promptCharge.event.end>=offlineAt;
        if(!recorded)for(CarEnergyRepository.Item item:repository.events(offlineAt,Long.MAX_VALUE,"charge",1))
            if(item.event.closed()&&item.event.start>=offlineAt-60_000){recorded=true;break;}
        if(recorded){offlineSoc=Double.NaN;return;}
        result.earlyFrom=offlineSoc;result.earlyTo=result.soc;result.earlySince=offlineAt;
    }

    // Both queries parse every unreviewed charge; Home asks about once a second, so the answers are
    // kept until a charge-related write (or 30 s, for the reminder times and anything missed).
    private static long cacheGeneration=-1,cacheAt,pendingAt;
    private static int cachedUnpriced;
    private static CarEnergyRepository.Item cachedPending;
    private static long pendingGeneration=-1;
    private static synchronized int unpriced(CarEnergyRepository repository){
        long now=android.os.SystemClock.elapsedRealtime(),generation=CarEnergyRepository.chargeGeneration();
        if(generation!=cacheGeneration||now-cacheAt>30_000){cachedUnpriced=repository.unpriced();cacheGeneration=generation;cacheAt=now;}
        return cachedUnpriced;
    }
    private static synchronized CarEnergyRepository.Item pending(CarEnergyRepository repository){
        long now=android.os.SystemClock.elapsedRealtime(),generation=CarEnergyRepository.chargeGeneration();
        if(generation!=pendingGeneration||now-pendingAt>30_000){cachedPending=repository.pending(true);pendingGeneration=generation;pendingAt=now;}
        return cachedPending;
    }
    /** The car's own other two ranges, named as its display names them; the headline is "Actual". */
    /** The car's rated ranges that it actually reports, or "" when it reports none (the row hides). */
    static String references(double standard,double nedc){
        boolean s=Double.isFinite(validRange(standard)),n=Double.isFinite(validRange(nedc));
        if(!s&&!n)return "";
        return (s?"Standard "+reference(standard):"")+(s&&n?"  ·  ":"")+(n?"NEDC "+reference(nedc):"");
    }
    /** "2.6 kW · to 95 % · full in 1 h 20 min (car's estimate)"; only the parts the car reports. */
    static String chargeDetail(double power,double target,double remainingRaw){
        StringBuilder text=new StringBuilder();
        if(Double.isFinite(power)&&power>0)text.append(String.format(Locale.US,"%.1f kW",power));
        if(Double.isFinite(target)&&target>0&&target<=100)text.append(text.length()>0?" · ":"").append("to ").append(Math.round(target)).append(" %");
        String left=CarChargeTime.remaining(remainingRaw);
        if(!left.isEmpty())text.append(text.length()>0?" · ":"").append("full in ").append(left).append(" (car's estimate)");
        return text.length()>0?text.toString():"Drive comparison resumes on the next drive";
    }
    static double validRange(double value){return Double.isFinite(value)&&value>=0&&value<2000?value:Double.NaN;}
    private static String reference(double value){return Double.isFinite(validRange(value))?Math.round(value)+" km":"—";}
}
