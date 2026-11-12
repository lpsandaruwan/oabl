package com.softiva.oabl;

/** Rebuildable totals. Each energy contribution retains its matching distance and source. */
final class CarEnergySummary implements java.io.Serializable {
    double km, used, points, seconds, model, acSeconds, regenKwh;
    double tripKwh, tripEnergyKm, socPoints, socEnergyKm;
    int drives, charges, stops, energyDrives, tripDrives, socDrives;
    final double[] temperatureKm = new double[6], temperaturePoints = new double[6];

    static CarEnergySummary of(CarEnergyTracker.Event e) {
        CarEnergySummary s = new CarEnergySummary();
        if (e.kind.equals("drive")) {
            s.drives = 1; s.km = e.km; s.regenKwh = e.regenKwh;
            if (Double.isFinite(e.points())) s.points = e.points();
            double trip = e.tripNetKwh();
            if (Double.isFinite(trip)) {
                s.tripKwh = trip; s.tripEnergyKm = e.km; s.tripDrives = 1; s.energyDrives = 1;
            } else if (Double.isFinite(e.points()) && e.points() > 0) {
                s.socPoints = e.points(); s.socEnergyKm = e.km; s.socDrives = 1; s.energyDrives = 1;
            }
            System.arraycopy(e.temperatureKm, 0, s.temperatureKm, 0, 6);
            System.arraycopy(e.temperaturePoints, 0, s.temperaturePoints, 0, 6);
        }
        if (e.kind.equals("stop")) { s.stops = 1; s.seconds = e.stoppedSeconds; s.model = e.modelKwh; s.acSeconds = e.acSeconds; }
        if (e.kind.equals("charge") && CarChargeReviewPolicy.counts(e)) s.charges = 1;
        return s;
    }
    double usedKwh(double capacity) {
        return tripDrives > 0 || capacity > 0 && socDrives > 0 ? tripKwh + (capacity > 0 ? socPoints * capacity / 100 : 0) : Double.NaN;
    }
    double energyKm(double capacity) { return tripEnergyKm + (capacity > 0 ? socEnergyKm : 0); }
    void add(CarEnergySummary s, int sign) {
        km += sign*s.km; used += sign*s.used; points += sign*s.points; seconds += sign*s.seconds;
        acSeconds += sign*s.acSeconds; regenKwh += sign*s.regenKwh; model += sign*s.model;
        tripKwh += sign*s.tripKwh; tripEnergyKm += sign*s.tripEnergyKm; socPoints += sign*s.socPoints; socEnergyKm += sign*s.socEnergyKm;
        drives += sign*s.drives; charges += sign*s.charges; stops += sign*s.stops; energyDrives += sign*s.energyDrives;
        tripDrives += sign*s.tripDrives; socDrives += sign*s.socDrives;
        for (int i=0;i<6;i++) { temperatureKm[i] += sign*s.temperatureKm[i]; temperaturePoints[i] += sign*s.temperaturePoints[i]; }
    }
    org.json.JSONObject json() throws Exception {
        return new org.json.JSONObject().put("km",km).put("used",used).put("points",points).put("seconds",seconds)
                .put("ac_seconds",acSeconds).put("regen_kwh",regenKwh).put("model",model).put("drives",drives).put("charges",charges)
                .put("stops",stops).put("energy_drives",energyDrives).put("trip_kwh",tripKwh).put("trip_energy_km",tripEnergyKm)
                .put("soc_points",socPoints).put("soc_energy_km",socEnergyKm).put("trip_drives",tripDrives).put("soc_drives",socDrives)
                .put("temperature_km",new org.json.JSONArray(temperatureKm)).put("temperature_points",new org.json.JSONArray(temperaturePoints));
    }
    static CarEnergySummary read(String text) throws Exception {
        CarEnergySummary s = new CarEnergySummary(); if(text==null)return s;
        org.json.JSONObject o = new org.json.JSONObject(text);
        s.km=o.optDouble("km",0); s.used=o.optDouble("used",0); s.points=o.optDouble("points",0); s.seconds=o.optDouble("seconds",0);
        s.acSeconds=o.optDouble("ac_seconds",0); s.regenKwh=o.optDouble("regen_kwh",0); s.model=o.optDouble("model",0);
        s.drives=o.optInt("drives"); s.charges=o.optInt("charges"); s.stops=o.optInt("stops"); s.energyDrives=o.optInt("energy_drives");
        s.tripKwh=o.optDouble("trip_kwh",0); s.tripEnergyKm=o.optDouble("trip_energy_km",0);
        s.socPoints=o.optDouble("soc_points",s.points); s.socEnergyKm=o.optDouble("soc_energy_km",s.km);
        s.tripDrives=o.optInt("trip_drives"); s.socDrives=o.optInt("soc_drives",s.energyDrives);
        for(int i=0;i<6;i++){s.temperatureKm[i]=o.getJSONArray("temperature_km").optDouble(i,0);s.temperaturePoints[i]=o.getJSONArray("temperature_points").optDouble(i,0);}
        return s;
    }
}
