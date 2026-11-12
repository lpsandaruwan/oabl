package com.softiva.oabl;

import android.app.*;
import android.os.*;
import android.content.*;
import android.database.Cursor;

/** SQLite regression checks in an isolated database, never the driver's energy history. */
public final class EnergyAccountingRepairTestRunner extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    @Override public void onStart(){
        Bundle out=new Bundle();int result=Activity.RESULT_CANCELED;EnergyStore store=null;
        String name="energy-accounting-repair-test.db";
        try {
            if(!"ranchu".equals(Build.HARDWARE)&&!"goldfish".equals(Build.HARDWARE))throw new IllegalStateException("Emulator only");
            Context context=getTargetContext();context.deleteDatabase(name);store=new EnergyStore(context,name);
            CarEnergyRepository repository=new CarEnergyRepository(store);
            long now=System.currentTimeMillis()-600000;
            CarEnergyTracker.Frame a=new CarEnergyTracker.Frame();a.time=now;
            a.set(CarSignal.SOC,40).set(CarSignal.SPEED,0).set(CarSignal.ODOMETER,100).set(CarSignal.READY,0).set(CarSignal.CHARGING,0);
            repository.observe("repair-fixture",a);
            CarEnergyTracker.Frame b=new CarEnergyTracker.Frame();b.time=now+500000;
            b.set(CarSignal.SOC,60).set(CarSignal.SPEED,0).set(CarSignal.ODOMETER,100).set(CarSignal.READY,0).set(CarSignal.CHARGING,0);
            repository.observe("repair-fixture",b);
            CarEnergyRepository.Item item=repository.events().get(0);
            check(item.event.inferred&&item.event.externalCharge,"stationary charge gap requires external-charge review");
            // Reproduce a previously saved legacy SOC-only rise with a price attached.
            item.event.externalCharge=false;
            ContentValues old=new ContentValues();old.put("data",CarEnergyCodec.event(item.event).toString());
            store.getWritableDatabase().update("car_event",old,"event_key=?",new String[]{item.event.key});
            EnergyModel.Charge legacy=new EnergyModel.Charge();legacy.live=true;legacy.time=b.time;legacy.before=40;legacy.after=60;legacy.estimatedKwh=8;legacy.rate=50;
            long id=store.addCharge(legacy,"car-confirmed");repository.review(item,"saved",id,0);item.chargeId=id;
            check(store.charges().isEmpty(),"unverified legacy rise contributes no priced energy");
            check(repository.summary(0,Long.MAX_VALUE).charges==0,"unverified rise excluded from charge count");
            boolean rejected=false;try{repository.saveCharge(item,legacy);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"unconfirmed SOC-only rise cannot be billed");
            item.event.externalCharge=true;
            repository.saveCharge(item,legacy);
            check(store.charges().size()==1,"explicit external-charge confirmation restores one active charge");
            check(repository.summary(0,Long.MAX_VALUE).charges==1,"confirmed charge summary rebuilt");
            long activeId=store.charges().get(0).id;
            repository.review(item,"ignored",activeId,0);
            check(store.charges().isEmpty(),"not-a-charge removes its saved price from active costs");
            check(Double.isNaN(repository.chargerRate(item.event,false,legacy.chargerId)),"withdrawn charges cannot supply a future charger tariff");
            check(repository.summary(0,Long.MAX_VALUE).charges==0,"dismissed record excluded from charge totals");
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM car_frame",null)){c.moveToFirst();check(c.getInt(0)==2,"raw telemetry preserved");}
            try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM charge",null)){c.moveToFirst();check(c.getInt(0)==2,"superseded and withdrawn prices retained for audit");}
            out.putString("stream","PASS · "+checks+" SQLite charging repair checks; isolated database; original rows retained\n");result=Activity.RESULT_OK;
        }catch(Throwable failure){out.putString("stream","FAIL after "+checks+": "+android.util.Log.getStackTraceString(failure));}
        finally{if(store!=null)store.close();getTargetContext().deleteDatabase(name);}
        finish(result,out);
    }
}
