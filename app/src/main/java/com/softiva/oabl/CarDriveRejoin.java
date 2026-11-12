package com.softiva.oabl;

/** Evidence at the two ends decides continuity; elapsed time only bounds plausible distance. */
final class CarDriveRejoin {
    enum Decision { SAME_DRIVE, SAME_STOP, RESTART, CHARGE, INVALID_ODOMETER, UNKNOWN }
    static Decision decide(CarEnergyTracker.Frame before,CarEnergyTracker.Frame after){
        double bootA=before.get(CarSignal.HU_BOOT_ID),bootB=after.get(CarSignal.HU_BOOT_ID);
        double tripA=before.get(CarSignal.TRIP_KM),tripB=after.get(CarSignal.TRIP_KM);
        if(Double.isFinite(bootA)&&Double.isFinite(bootB)&&bootA!=bootB||Double.isFinite(tripA)&&Double.isFinite(tripB)&&tripB+0.05<tripA)return Decision.RESTART;
        if(before.is(CarSignal.CHARGING,1)||after.is(CarSignal.CHARGING,1)||before.is(CarSignal.PLUG,1)||after.is(CarSignal.PLUG,1)
                ||Double.isFinite(before.soc())&&Double.isFinite(after.soc())&&after.soc()-before.soc()>=2)return Decision.CHARGE;
        double a=before.get(CarSignal.ODOMETER),b=after.get(CarSignal.ODOMETER),hours=(after.time-before.time)/3600000.0;
        if(Double.isFinite(a)&&Double.isFinite(b)&&(b<a||b-a>hours*220+1))return Decision.INVALID_ODOMETER;
        if(!before.is(CarSignal.READY,1)||!after.is(CarSignal.READY,1)||!Double.isFinite(a)||!Double.isFinite(b))return Decision.UNKNOWN;
        if(a==b)return Decision.SAME_STOP;
        double drop=before.soc()-after.soc();
        if(!Double.isFinite(drop)||drop< -1||drop>2+(b-a))return Decision.UNKNOWN;
        return Decision.SAME_DRIVE;
    }
}
