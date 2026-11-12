package com.softiva.oabl;

/** Persisted charger identity; a missing GPS fix never merges unrelated public chargers. */
final class CarCharger implements java.io.Serializable {
    long id;String name="";double latitude=Double.NaN,longitude=Double.NaN;boolean home;
    static boolean near(double a,double b,double c,double d){
        if(!Double.isFinite(a)||!Double.isFinite(b)||!Double.isFinite(c)||!Double.isFinite(d))return false;
        double lat=Math.toRadians(c-a),lon=Math.toRadians(d-b);
        double h=Math.sin(lat/2)*Math.sin(lat/2)+Math.cos(Math.toRadians(a))*Math.cos(Math.toRadians(c))*Math.sin(lon/2)*Math.sin(lon/2);
        return 6371000*2*Math.asin(Math.sqrt(Math.min(1,h)))<=150;
    }
}
