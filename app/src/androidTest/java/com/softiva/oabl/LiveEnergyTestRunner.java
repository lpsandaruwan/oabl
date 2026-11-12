package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import java.util.*;

/** Real Android SQLite/IPC/migration checks. Host restores both pre-test app snapshots afterward. */
public final class LiveEnergyTestRunner extends Instrumentation {
    int checks;
    void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;Bundle b=new Bundle();b.putString("stream","PASS "+label+"\n");sendStatus(0,b);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    CarEnergyTracker.Frame frame(long time,double soc,double odo){CarEnergyTracker.Frame f=new CarEnergyTracker.Frame();f.time=time;f.capacity=40;return f.set(CarSignal.SOC,soc).set(CarSignal.READY,1).set(CarSignal.SPEED,30).set(CarSignal.CHARGING,0).set(CarSignal.ODOMETER,odo);}
    @Override public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try{
            if(!Build.HARDWARE.equals("ranchu")&&!Build.HARDWARE.equals("goldfish"))throw new IllegalStateException("Emulator only");
            Context c=getTargetContext();EnergyStore store=EnergyStore.get(c);CarEnergyRepository repo=new CarEnergyRepository(store);
            check(store.remote(),"UI uses a remote store facade");
            boolean refused=false;try{store.getWritableDatabase();}catch(IllegalStateException expected){refused=true;}check(refused,"UI cannot acquire energy.db writer");
            check(store.getReadableDatabase().isReadOnly(),"UI SQL handle is read-only");
            check(store.getReadableDatabase().getVersion()==4,"schema 4 migration opens existing database");
            repo.rebuildSummaries();
            String link="live-integration-"+UUID.randomUUID();long now=System.currentTimeMillis()-7200000;
            repo.observe(link,frame(now,80,100));repo.observe(link,frame(now+1000,79,101));
            long count;try(Cursor x=store.getReadableDatabase().rawQuery("SELECT count(*) FROM car_frame WHERE link=?",new String[]{link})){x.moveToFirst();count=x.getLong(0);}check(count==2,"private IPC persists frames through the writer");
            CarEnergyTracker.Frame unknown=frame(now+600000,78,102);unknown.values.remove(CarSignal.READY);unknown.times.remove(CarSignal.READY);repo.observe(link,unknown);
            List<CarEnergyRepository.Item> drives=new ArrayList<>();for(CarEnergyRepository.Item item:repo.events())if(item.link.equals(link)&&item.event.kind.equals("drive"))drives.add(item);
            check(drives.size()==2&&drives.stream().anyMatch(x->x.event.joinable),"insufficient gap evidence retains two original drives; count="+drives.size()+" flags="+drives.stream().map(x->x.event.key+":"+x.event.joinable).collect(java.util.stream.Collectors.joining(",")));
            drives.sort(java.util.Comparator.comparingLong(x->x.event.start));repo.join(drives.get(0),drives.get(1));int effective=0;for(CarEnergyRepository.Item item:repo.events())if(item.link.equals(link)&&item.event.kind.equals("drive"))effective++;
            check(effective==1,"owner join creates one effective drive");
            try(Cursor x=store.getReadableDatabase().rawQuery("SELECT count(*) FROM car_event WHERE link=? AND kind='drive'",new String[]{link})){x.moveToFirst();check(x.getLong(0)==3,"join preserves both original drive records");}
            CarEnergySummary before=repo.summary(0,Long.MAX_VALUE);repo.rebuildSummaries();CarEnergySummary after=repo.summary(0,Long.MAX_VALUE);check(Math.abs(before.km-after.km)<1e-8&&before.drives==after.drives,"incremental daily/weekly totals equal rebuilt totals");
            CarEnergyRepository.Item item=new CarEnergyRepository.Item();item.link=link;item.event=new CarEnergyTracker.Event();item.event.kind="charge";item.event.externalCharge=true;item.event.key="charge-fixture";item.event.start=now;item.event.end=now+10000;item.event.state="complete";item.event.before=40;item.event.after=60;item.event.chargerKwh=8.1;item.event.coveredSeconds=10;item.event.latitude=6.9;item.event.longitude=79.86;
            EnergyModel.Charge receipt=new EnergyModel.Charge();receipt.time=item.event.end;receipt.before=40;receipt.after=60;receipt.rate=50;receipt.place=EnergyModel.Place.PUBLIC;repo.saveCharge(item,receipt);
            EnergyModel.Charge saved=store.charges().stream().filter(x->x.time==receipt.time).reduce((a,b)->b).get();check(saved.chargerId>0&&saved.live&&saved.measuredKwh==8.1,"charger identity and measured car kWh persist independently");
            check(Math.abs(saved.money(100)-405)<1e-8,"receipt uses confirmed per-kWh price");
            check(repo.chargerRate(item.event,false,0)==50,"charger recalls its own last confirmed price");
            receipt.kwh=9;receipt.rate=60;repo.saveCharge(item,receipt);long active=store.charges().stream().filter(x->x.time==receipt.time).count();check(active==1,"charge correction supersedes rather than doubles receipt");
            EnergyModel.Charge edited=store.charges().stream().filter(x->x.time==receipt.time).findFirst().get();check(edited.kwh==9&&edited.measuredKwh==8.1&&edited.money(100)==540,"entered meter overrides measured kWh for cost and keeps both");
            repo.rebuildFromFrames();int replayed=0;for(CarEnergyRepository.Item drive:repo.events())if(drive.link.equals(link)&&drive.event.kind.equals("drive"))replayed++;
            check(replayed==1,"raw-frame replay reapplies persisted joins deterministically");
            org.json.JSONObject archive=store.export();check(archive.has("charger")&&archive.has("car_summary")&&archive.has("car_drive_join")&&archive.has("car_sample")&&archive.has("car_frame"),"settings export covers every new table and all raw rows");
            org.json.JSONObject conflicting=new org.json.JSONObject(archive.toString());conflicting.getJSONArray("car_frame").getJSONObject(0).put("data","{}");
            boolean conflict=false;try{store.restore(conflicting);}catch(IllegalArgumentException expected){conflict=true;}
            check(conflict,"restore rejects conflicting immutable raw records and rolls back");
            java.io.File settings=java.io.File.createTempFile("live-settings-",".zip",c.getCacheDir());
            SettingsBackup.export(c,android.net.Uri.fromFile(settings));
            try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(settings)){check(zip.getEntry("energy.json.gz")!=null&&zip.getEntry("settings.json")!=null,"settings backup streams complete energy data in a ZIP bundle");}
            check(SettingsBackup.restore(c,android.net.Uri.fromFile(settings))>0,"settings ZIP restores through the energy owner");settings.delete();
            result.putString("stream","RESULT PASS · "+checks+" live energy IPC/storage checks\n");code=Activity.RESULT_OK;
        }catch(Throwable failure){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(failure));}
        finish(code,result);
    }
}
