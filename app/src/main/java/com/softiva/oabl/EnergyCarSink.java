package com.softiva.oabl;

import android.content.Context;

/** Raw insertion and chronological reduction share one transaction, including the recovery cursor. */
final class EnergyCarSink implements CarLinkService.CarSampleSink {
    private final EnergyStore store;
    private final Context context;
    private volatile android.location.Location chargeFix;
    private final Clock clock=new Clock();
    private long lastFixRequest;
    static final long FIX_AGE_MS=5000,FIX_RETRY_MS=60000;
    /** Head-unit and box clocks differ; the smallest recent lag is that offset, so lag above it means catch-up. */
    static final class Clock{
        static final long LIVE_SLACK_MS=15000,OFFSET_WINDOW_MS=300000;
        private long offsetMs=Long.MAX_VALUE,offsetAt;
        /** True when this sample is being read as it happens, judged on the box's own clock. */
        boolean live(long sampleTime,long boxNow){
            long lag=boxNow-sampleTime;
            if(offsetMs==Long.MAX_VALUE||lag<offsetMs||boxNow-offsetAt>OFFSET_WINDOW_MS){offsetMs=lag;offsetAt=boxNow;}
            return lag-offsetMs<=LIVE_SLACK_MS;
        }
    }
    EnergyCarSink(Context context){this.context=context;store=EnergyClient.store(context);}
    @Override public void store(String link,CarLinkProtocol.CarSample sample){
        if(sample.values.isEmpty())return;
        android.database.sqlite.SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try{
            if(!store.insertCarSample(link,sample.seq,sample.time,CarLinkProtocol.encodeValues(sample))){db.setTransactionSuccessful();return;}
            CarEnergyTracker.Frame frame=new CarEnergyTracker.Frame();frame.time=sample.time;boolean needFix=true;
            try(android.database.Cursor c=db.rawQuery("SELECT data FROM car_energy_state WHERE link=?",new String[]{link})){
                if(c.moveToFirst()){
                    CarEnergyTracker.State checkpoint=CarEnergyCodec.state(c.getString(0));needFix=checkpoint.charge==null||Double.isNaN(checkpoint.charge.latitude);
                    if(checkpoint.last!=null){frame.values.putAll(checkpoint.last.values);frame.times.putAll(checkpoint.last.times);}
                }
            }
            EnergyModel.Profile profile=store.profile();frame.idleKw=profile.idle.kw;frame.capacity=profile.usableKwh;
            for(CarSignal signal:sample.values.keySet())frame.set(signal,sample.values.get(signal));
            locate(frame,needFix&&frame.is(CarSignal.CHARGING,1));new CarEnergyRepository(store).observe(link,frame);
            db.setTransactionSuccessful();
        }catch(Exception failure){throw new IllegalStateException("Car sample and checkpoint were not committed",failure);}
        finally{db.endTransaction();}
    }
    /** A fix's age from the monotonic clock, so neither device's wall clock matters. */
    static boolean freshFix(android.location.Location fix,long elapsedNanos){
        return fix!=null&&fix.hasAccuracy()&&fix.getAccuracy()<=50&&elapsedNanos-fix.getElapsedRealtimeNanos()<=FIX_AGE_MS*1_000_000L;
    }
    private void locate(CarEnergyTracker.Frame frame,boolean charging){
        // A backfilled sample happened earlier: the box's current position says nothing about it.
        if(!clock.live(frame.time,System.currentTimeMillis()))return;
        try{
            android.location.LocationManager locations=(android.location.LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
            long elapsed=android.os.SystemClock.elapsedRealtimeNanos();
            android.location.Location best=freshFix(chargeFix,elapsed)?chargeFix:null;
            for(String provider:locations.getProviders(true)){
                android.location.Location fix=locations.getLastKnownLocation(provider);
                if(freshFix(fix,elapsed)&&(best==null||fix.getElapsedRealtimeNanos()>best.getElapsedRealtimeNanos()))best=fix;
            }
            if(best!=null){frame.latitude=best.getLatitude();frame.longitude=best.getLongitude();}
            else if(charging&&elapsed/1_000_000L-lastFixRequest>=FIX_RETRY_MS){
                lastFixRequest=elapsed/1_000_000L;
                android.location.LocationListener one=new android.location.LocationListener(){
                    @Override public void onLocationChanged(android.location.Location fix){if(fix.hasAccuracy()&&fix.getAccuracy()<=50)chargeFix=new android.location.Location(fix);locations.removeUpdates(this);}
                    @Override public void onProviderEnabled(String name){}
                    @Override public void onProviderDisabled(String name){}
                    @Override public void onStatusChanged(String name,int state,android.os.Bundle extras){}
                };
                locations.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER,one,android.os.Looper.getMainLooper());
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(()->locations.removeUpdates(one),30000);
            }
        }catch(SecurityException unavailable){/* Permission can be revoked while a charge starts; location stays unknown. */}
        catch(RuntimeException unavailable){/* No fix means unknown location, never an invented charger. */}
    }
    @Override public long lastSeq(String link){return store.lastCarSeq(link);}
    @Override public void observe(String source,CarLink link){/* /now is display-only; numbered samples drive accounting. */}
}
