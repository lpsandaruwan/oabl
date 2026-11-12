package com.softiva.oabl;

import java.util.Locale;

/** Current drive comparison against the car's range at drive start; no learned/manual benchmark. */
final class CarDrivePerformance {
    enum Verdict { OVERPERFORMING, UNDERPERFORMING, ON_POINT, LEARNING }
    static final double MIN_POINTS=10,MIN_KM=5,TOLERANCE_PERCENT=10;
    static final class Result implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        final Verdict verdict;
        final String title,detail;
        final double relativePercent,expectedKm;
        final boolean eligible;
        private Result(Verdict verdict,String title,String detail,double relativePercent,double expectedKm,boolean eligible){
            this.verdict=verdict;this.title=title;this.detail=detail;this.relativePercent=relativePercent;this.expectedKm=expectedKm;this.eligible=eligible;
        }
    }
    private static Result learning(String title,String detail){return new Result(Verdict.LEARNING,title,detail,Double.NaN,Double.NaN,false);}
    private static Result unavailable(String detail){return learning("Comparison unavailable",detail);}
    static Result evaluate(CarEnergyTracker.Event drive,long now,boolean fresh){
        if(!fresh)return learning("Waiting for car data","Fresh car readings are needed");
        if(drive==null)return learning("Learning this drive","No active drive");
        if(!"drive".equals(drive.kind))return unavailable("A drive is needed for comparison");
        if(drive.odometerFlag)return unavailable("Odometer reading needs review");
        // A gap the tracker rejoined from READY/odometer/SOC evidence keeps measured endpoints and odometer
        // distance, so it can still be compared; an unresolved split cannot.
        if(drive.joinable||"interrupted".equals(drive.state))
            return unavailable("Drive has a gap in car readings");
        // latestDrive() can return an older completed drive; it must never be shown as this drive.
        if(!"open".equals(drive.state))return learning("Learning this drive","No active drive");
        if(now<=0||drive.last<=0||now-drive.last>CarEnergyTracker.FRESH_MS||now-drive.last< -5000)
            return learning("Waiting for car data","Fresh drive readings are needed");
        if(!Double.isFinite(drive.before)||drive.before<=0||drive.before>100||!Double.isFinite(drive.after)||drive.after<0||drive.after>100)
            return unavailable("Battery readings are unavailable");
        if(!Double.isFinite(drive.rangeStart)||drive.rangeStart<=0)return unavailable("Car's start range is unavailable");
        if(!Double.isFinite(drive.km)||drive.km<0)return unavailable("Drive distance is unavailable");
        if(drive.after>drive.before)return unavailable("Battery level rose during this drive");
        double points=drive.points();
        if(points<MIN_POINTS||drive.km<MIN_KM)return learning("Learning this drive","Needs 10 battery points and 5 km");
        double expectedKm=drive.rangeStart*(points/drive.before);
        if(!Double.isFinite(expectedKm)||expectedKm<=0)return unavailable("Car's start range is unavailable");
        double relativePercent=(drive.km-expectedKm)/expectedKm*100;
        if(!Double.isFinite(relativePercent))return unavailable("Comparison could not be calculated");
        // Ignore round-off at the inclusive ±10% boundary, far below display precision.
        Verdict verdict=relativePercent>TOLERANCE_PERCENT+1e-9?Verdict.OVERPERFORMING:
                relativePercent< -TOLERANCE_PERCENT-1e-9?Verdict.UNDERPERFORMING:Verdict.ON_POINT;
        String title=verdict==Verdict.OVERPERFORMING?"Overperforming":verdict==Verdict.UNDERPERFORMING?"Underperforming":"On point";
        String detail=verdict==Verdict.ON_POINT?"Within ±10% of the car's start estimate":String.format(Locale.US,"%.0f%% %s car's start estimate",Math.abs(relativePercent),relativePercent>0?"above":"below");
        return new Result(verdict,title,detail,relativePercent,expectedKm,true);
    }
}
