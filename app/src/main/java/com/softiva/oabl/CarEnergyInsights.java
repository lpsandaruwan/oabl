package com.softiva.oabl;

/** Live figures have their own provenance; no manual benchmark or learned-k analysis enters here. */
final class CarEnergyInsights {
    static int temperatureBand(double c){return c<0?0:c<10?1:c<20?2:c<30?3:c<40?4:5;}
    static final String[] BANDS={"below 0°C","0–10°C","10–20°C","20–30°C","30–40°C","40°C and above"};
    static double capacityUpperBound(Iterable<EnergyModel.Charge> charges){
        double kwh=0,points=0;
        for(EnergyModel.Charge charge:charges)if(charge.gain()>=10&&Double.isFinite(charge.measuredKwh)&&charge.measuredKwh>0&&charge.measuredComplete){kwh+=charge.measuredKwh;points+=charge.gain();}
        return points>0?kwh/points*100:Double.NaN;
    }
}
