package com.softiva.oabl;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Raw snapshots + atomic reducer checkpoints/events. A replay cannot create a second charge prompt. */
final class CarEnergyRepository {
    private final EnergyStore store;
    CarEnergyRepository(EnergyStore store) { this.store = store; }
    static void createTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE car_frame (link TEXT NOT NULL,t INTEGER NOT NULL,data TEXT NOT NULL,PRIMARY KEY(link,t))");
        db.execSQL("CREATE TABLE car_energy_state (link TEXT PRIMARY KEY,data TEXT NOT NULL)");
        db.execSQL("CREATE TABLE car_event (link TEXT NOT NULL,event_key TEXT NOT NULL,kind TEXT NOT NULL,start INTEGER NOT NULL,end_time INTEGER NOT NULL,state TEXT NOT NULL,data TEXT NOT NULL,PRIMARY KEY(link,event_key))");
        db.execSQL("CREATE INDEX car_event_start ON car_event(start)");
        db.execSQL("CREATE TABLE car_charge_review (link TEXT NOT NULL,event_key TEXT NOT NULL,status TEXT NOT NULL,charge_id INTEGER,remind_after INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(link,event_key))");
    }
    /** Bumped by every write that can change which charges still need a price; Home's cache keys on it. */
    private static volatile long chargeGeneration;
    static long chargeGeneration(){return chargeGeneration;}
    static void chargesChanged(){chargeGeneration++;}
    void observe(String link, CarEnergyTracker.Frame frame) {
        if(store.remote()){EnergyClient.call(store.context,"repository.observe(String,CarEnergyTracker.Frame)",new Object[]{link,frame});return;}
        if (link.isEmpty() || frame.time <= 0) return;
        SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
        try {
            if(frame.time<=store.metaLong("car_reset_after",0)){db.setTransactionSuccessful();return;}
            String saved = null;
            try (Cursor c = db.rawQuery("SELECT data FROM car_energy_state WHERE link=?", new String[]{link})) { if (c.moveToFirst()) saved = c.getString(0); }
            CarEnergyTracker.State state = CarEnergyCodec.state(saved);
            if (state.last != null && state.last.time >= frame.time) { db.setTransactionSuccessful(); return; }
            ContentValues raw = new ContentValues(); raw.put("link", link); raw.put("t", frame.time); raw.put("data", CarEnergyCodec.frame(frame).toString());
            db.insertWithOnConflict("car_frame", null, raw, SQLiteDatabase.CONFLICT_IGNORE);
            java.util.Set<String> touched=new java.util.HashSet<>();
            for (CarEnergyTracker.Event event : new CarEnergyTracker(state).accept(frame)) {
                touched.add(event.key);
                if("charge".equals(event.kind))chargesChanged();
                if(!joined(db,link,event.key))updateSummary(db,link,event);
                ContentValues row = new ContentValues(); row.put("link", link); row.put("event_key", event.key); row.put("kind", event.kind);
                row.put("start", event.start); row.put("end_time", event.end); row.put("state", event.state); row.put("last_time",event.last); row.put("data", CarEnergyCodec.event(event).toString());
                db.insertWithOnConflict("car_event", null, row, SQLiteDatabase.CONFLICT_REPLACE);
            }
            // Only joins containing a drive this frame changed; the others would be rewritten unchanged.
            if(!touched.isEmpty())refreshJoins(db,link,touched);
            ContentValues checkpoint = new ContentValues(); checkpoint.put("link", link); checkpoint.put("data", CarEnergyCodec.state(state));
            db.insertWithOnConflict("car_energy_state", null, checkpoint, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } catch (Exception failure) { throw new IllegalStateException("Could not persist car energy observation", failure); }
        finally { db.endTransaction(); }
    }
    static final class Item implements java.io.Serializable {
        String link, review = "";
        long remindAfter, chargeId;
        CarEnergyTracker.Event event;
    }
    List<Item> events() {return events(0,Long.MAX_VALUE,null,0);}
    List<Item> events(long from,long to,String kind,int limit){
        if(store.remote())return (List<Item>)EnergyClient.call(store.context,"repository.window",from,to,kind,limit);
        if(kind!=null&&!java.util.Arrays.asList("drive","stop","charge").contains(kind))throw new IllegalArgumentException("Unknown event kind");
        List<Item> result = new ArrayList<>();
        try (Cursor c = store.getReadableDatabase().rawQuery("SELECT e.link,e.data,r.status,r.remind_after,r.charge_id FROM car_event e LEFT JOIN car_charge_review r ON e.link=r.link AND e.event_key=r.event_key WHERE NOT EXISTS(SELECT 1 FROM car_drive_join j WHERE j.link=e.link AND j.superseded_by IS NULL AND (j.first_key=e.event_key OR j.second_key=e.event_key)) AND (CASE WHEN e.end_time>0 THEN e.end_time WHEN e.last_time>0 THEN e.last_time ELSE e.start END)>=CAST(? AS INTEGER) AND (CASE WHEN e.end_time>0 THEN e.end_time WHEN e.last_time>0 THEN e.last_time ELSE e.start END)<CAST(? AS INTEGER)"+(kind==null?"":" AND e.kind='"+kind+"'")+" ORDER BY e.start DESC"+(limit>0?" LIMIT "+limit:""), new String[]{Long.toString(from),Long.toString(to)})) {
            while (c.moveToNext()) {
                try {
                    Item item = new Item(); item.link = c.getString(0); item.event = CarEnergyCodec.event(new JSONObject(c.getString(1)));
                    item.review = c.isNull(2) ? "" : c.getString(2); item.remindAfter = c.isNull(3) ? 0 : c.getLong(3); item.chargeId = c.isNull(4) ? 0 : c.getLong(4); result.add(item);
                } catch (Exception failure) { android.util.Log.e("CarEnergy", "Unreadable derived event", failure); }
            }
        }
        return result;
    }
    /** Finished charges that still need a price (not saved, not dismissed); Home shows the count. */
    int unpriced(){
        if(store.remote())return (int)EnergyClient.call(store.context,"repository.unpriced");
        int count=0;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT e.data FROM car_event e LEFT JOIN car_charge_review r ON e.link=r.link AND e.event_key=r.event_key WHERE e.kind='charge' AND e.state!='open' AND (r.status IS NULL OR r.status NOT IN ('saved','ignored'))",null)){
            while(c.moveToNext())try{if(CarChargeReviewPolicy.eligible(CarEnergyCodec.event(new JSONObject(c.getString(0))),false))count++;}catch(Exception unreadable){/* skipped, as in pending() */}
        }
        return count;
    }
    Item pending(boolean automatic) {
        if(store.remote()){return (Item)EnergyClient.call(store.context,"repository.pending(boolean)",new Object[]{automatic});}
        long now=System.currentTimeMillis();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT e.link,e.data,r.status,r.remind_after,r.charge_id FROM car_event e LEFT JOIN car_charge_review r ON e.link=r.link AND e.event_key=r.event_key WHERE e.kind='charge' AND e.state!='open' AND (r.status IS NULL OR r.status NOT IN ('saved','ignored'))"+(automatic?" AND (r.remind_after IS NULL OR r.remind_after<=?)":"")+" ORDER BY e.start DESC",automatic?new String[]{Long.toString(now)}:null)){
            while(c.moveToNext()){
                Item item=new Item();item.link=c.getString(0);item.event=CarEnergyCodec.event(new JSONObject(c.getString(1)));item.review=c.isNull(2)?"":c.getString(2);item.remindAfter=c.isNull(3)?0:c.getLong(3);item.chargeId=c.isNull(4)?0:c.getLong(4);
                if(CarChargeReviewPolicy.eligible(item.event,automatic))return item;
            }
        }catch(Exception failure){throw new IllegalStateException(failure);}
        return null;
    }
    CarEnergyTracker.Event latestDrive() {
        if(store.remote()){return (CarEnergyTracker.Event)EnergyClient.call(store.context,"repository.latestDrive()");}
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT e.data FROM car_event e WHERE kind='drive' AND NOT EXISTS(SELECT 1 FROM car_drive_join j WHERE j.link=e.link AND j.superseded_by IS NULL AND (j.first_key=e.event_key OR j.second_key=e.event_key)) ORDER BY start DESC LIMIT 1",null)){
            if(c.moveToFirst())return CarEnergyCodec.event(new JSONObject(c.getString(0)));
            return null;
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    /** The charge in progress, if the car is reporting one; null otherwise. */
    CarEnergyTracker.Event openCharge(){
        if(store.remote())return (CarEnergyTracker.Event)EnergyClient.call(store.context,"repository.openCharge");
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT data FROM car_event WHERE kind='charge' AND state='open' ORDER BY start DESC LIMIT 1",null)){
            return c.moveToFirst()?CarEnergyCodec.event(new JSONObject(c.getString(0))):null;
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    CarEnergyTracker.Event latestDrive(String link){
        if(store.remote())return (CarEnergyTracker.Event)EnergyClient.call(store.context,"repository.sourceDrive",link);
        if(link==null||link.isEmpty())return null;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT e.data FROM car_event e WHERE e.link=? AND e.kind='drive' AND NOT EXISTS(SELECT 1 FROM car_drive_join j WHERE j.link=e.link AND j.superseded_by IS NULL AND (j.first_key=e.event_key OR j.second_key=e.event_key)) ORDER BY e.start DESC LIMIT 1",new String[]{link})){
            return c.moveToFirst()?CarEnergyCodec.event(new JSONObject(c.getString(0))):null;
        }catch(Exception failure){throw new IllegalStateException(failure);}
    }
    void review(Item item, String status, long chargeId, long remindAfter) {
        if(store.remote()){EnergyClient.call(store.context,"repository.review(Item,String,long,long)",new Object[]{item,status,chargeId,remindAfter});return;}
        ContentValues row = new ContentValues(); row.put("link", item.link); row.put("event_key", item.event.key); row.put("status", status);
        if (chargeId > 0) row.put("charge_id", chargeId); row.put("remind_after", remindAfter);
        store.getWritableDatabase().insertWithOnConflict("car_charge_review", null, row, SQLiteDatabase.CONFLICT_REPLACE);
        if ("ignored".equals(status) && chargeId > 0) store.withdrawCharge(chargeId);
        if ("saved".equals(status) || "ignored".equals(status)) rebuildSummaries();
        chargesChanged();
    }
    void saveCharge(Item item, EnergyModel.Charge charge) {
        if(store.remote()){EnergyClient.call(store.context,"repository.saveCharge(Item,EnergyModel.Charge)",new Object[]{item,charge});return;}
        if (!CarChargeReviewPolicy.counts(item.event)) throw new IllegalArgumentException("No external charging evidence; a battery rise or regen cannot be priced as a charge");
        chargesChanged();
        SQLiteDatabase db = store.getWritableDatabase(); db.beginTransaction();
        try {
            // Saving an already reviewed event is an edit, never a duplicate.
            long oldId = item.chargeId;
            try (Cursor c = db.rawQuery("SELECT charge_id FROM car_charge_review WHERE link=? AND event_key=?", new String[]{item.link,item.event.key})) { if (c.moveToFirst() && !c.isNull(0)) oldId = c.getLong(0); }
            // A legacy SOC-only rise can be restored to billing only after explicit user confirmation.
            ContentValues evidence = new ContentValues();
            evidence.put("data", CarEnergyCodec.event(item.event).toString());
            db.update("car_event", evidence, "link=? AND event_key=?", new String[]{item.link,item.event.key});
            charge.live=true;charge.measuredKwh=item.event.measuredKwh();charge.coverageSeconds=item.event.coveredSeconds;
            charge.measuredComplete=CarChargeEnergy.complete(item.event);
            // Anything short of a complete measurement gets a charger-side estimate for the whole charge.
            CarChargeEnergy.Estimate whole=CarChargeEnergy.estimate(item.event,CarChargeEnergy.gridKwhPerPoint(store.charges()),store.profile().usableKwh);
            charge.estimatedKwh=charge.measuredComplete?Double.NaN:whole.kwh;
            if(!Double.isFinite(charge.rate)||charge.rate<0)throw new IllegalArgumentException("Confirm the price per kWh, including 0 for a free charger");
            CarCharger charger=charger(item.event,charge.place==EnergyModel.Place.HOME,charge.chargerId,true);charge.chargerId=charger.id;
            charge.cost=Double.NaN;
            long id = store.addCharge(charge, item.event.inferred ? "car-confirmed" : "car");
            if (oldId > 0) { ContentValues old = new ContentValues(); old.put("superseded_by", id); db.update("charge",old,"id=?",new String[]{Long.toString(oldId)}); }
            review(item,"saved",id,0); db.setTransactionSuccessful();
        } catch (Exception failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
        finally { db.endTransaction(); }
    }

    List<CarCharger> chargers(){
        if(store.remote())return (List<CarCharger>)EnergyClient.call(store.context,"repository.chargers");
        List<CarCharger> result=new ArrayList<>();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,name,latitude,longitude,home FROM charger ORDER BY home DESC,id",null)){
            while(c.moveToNext()){CarCharger value=new CarCharger();value.id=c.getLong(0);value.name=c.isNull(1)?"":c.getString(1);value.latitude=c.isNull(2)?Double.NaN:c.getDouble(2);value.longitude=c.isNull(3)?Double.NaN:c.getDouble(3);value.home=c.getInt(4)!=0;result.add(value);}
        }return result;
    }
    CarCharger charger(CarEnergyTracker.Event event,boolean home,long selected,boolean create){
        if(store.remote())return (CarCharger)EnergyClient.call(store.context,"repository.charger",event,home,selected,create);
        CarCharger found=null;
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT id,name,latitude,longitude,home FROM charger ORDER BY id",null)){
            while(c.moveToNext()){
                CarCharger value=new CarCharger();value.id=c.getLong(0);value.name=c.isNull(1)?"":c.getString(1);value.latitude=c.isNull(2)?Double.NaN:c.getDouble(2);value.longitude=c.isNull(3)?Double.NaN:c.getDouble(3);value.home=c.getInt(4)!=0;
                if(selected>0&&value.id==selected||selected==0&&(home&&value.home||CarCharger.near(event.latitude,event.longitude,value.latitude,value.longitude)))found=value;
            }
        }
        if(found!=null)return found;
        found=new CarCharger();found.home=home;found.latitude=event.latitude;found.longitude=event.longitude;
        if(create){ContentValues row=new ContentValues();row.put("home",home?1:0);if(Double.isFinite(event.latitude))row.put("latitude",event.latitude);if(Double.isFinite(event.longitude))row.put("longitude",event.longitude);found.id=store.getWritableDatabase().insertOrThrow("charger",null,row);}
        return found;
    }
    double chargerRate(CarEnergyTracker.Event event,boolean home,long selected){
        if(store.remote())return (double)EnergyClient.call(store.context,"repository.chargerRate",event,home,selected);
        if(home)return store.homeRate();
        CarCharger charger=charger(event,home,selected,false);
        if(charger.id>0) {
            List<EnergyModel.Charge> priced=store.charges();
            for(int i=priced.size()-1;i>=0;i--) {
                EnergyModel.Charge charge=priced.get(i);
                if(charge.chargerId==charger.id && Double.isFinite(charge.rate) && charge.rate>=0)return charge.rate;
            }
        }
        return home?store.homeRate():Double.NaN;
    }
    static long day(long at){java.util.Calendar c=java.util.Calendar.getInstance();c.setTimeInMillis(at);c.set(java.util.Calendar.HOUR_OF_DAY,0);c.set(java.util.Calendar.MINUTE,0);c.set(java.util.Calendar.SECOND,0);c.set(java.util.Calendar.MILLISECOND,0);return c.getTimeInMillis();}
    private void updateSummary(SQLiteDatabase db,String link,CarEnergyTracker.Event event)throws Exception{
        String oldData=null;long oldDay=0;
        try(Cursor c=db.rawQuery("SELECT data,last FROM car_summary WHERE link=? AND event_key=?",new String[]{link,event.key})){if(c.moveToFirst()){oldData=c.getString(0);oldDay=day(c.getLong(1));}}
        long at=event.closed()?event.end:event.last,newDay=day(at);
        if(oldData!=null)bucket(db,link,oldDay,CarEnergySummary.read(oldData),-1);
        CarEnergySummary summary=CarEnergySummary.of(event);
        if ("charge".equals(event.kind)) try(Cursor review=db.rawQuery("SELECT status FROM car_charge_review WHERE link=? AND event_key=?",new String[]{link,event.key})) {
            if(review.moveToFirst() && "ignored".equals(review.getString(0))) summary.charges=0;
        }
        bucket(db,link,newDay,summary,1);
        ContentValues row=new ContentValues();row.put("link",link);row.put("event_key",event.key);row.put("kind",event.kind);row.put("start",event.start);row.put("last",at);row.put("data",summary.json().toString());
        db.insertWithOnConflict("car_summary",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    private void bucket(SQLiteDatabase db,String link,long at,CarEnergySummary delta,int sign)throws Exception{
        String key="day:"+at;CarEnergySummary total=new CarEnergySummary();
        try(Cursor c=db.rawQuery("SELECT data FROM car_summary WHERE link=? AND event_key=?",new String[]{link,key})){if(c.moveToFirst())total=CarEnergySummary.read(c.getString(0));}
        total.add(delta,sign);ContentValues row=new ContentValues();row.put("link",link);row.put("event_key",key);row.put("kind","day");row.put("start",at);row.put("last",at);row.put("data",total.json().toString());
        db.insertWithOnConflict("car_summary",null,row,SQLiteDatabase.CONFLICT_REPLACE);
    }
    CarEnergySummary summary(long from,long to){
        if(store.remote())return (CarEnergySummary)EnergyClient.call(store.context,"repository.summary",from,to);
        if(!"1".equals(store.meta("soc_zero_repair")))repairZeroBattery();
        if(!"1".equals(store.meta("unset_odometer_repair")))repairUnsetOdometer();
        if(!java.util.TimeZone.getDefault().getID().equals(store.meta("summary_zone")) || !"2".equals(store.meta("energy_accounting")))rebuildSummaries();
        CarEnergySummary total=new CarEnergySummary();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT data FROM car_summary WHERE kind='day' AND last>=? AND last<?",new String[]{Long.toString(from),Long.toString(to)})){while(c.moveToNext())total.add(CarEnergySummary.read(c.getString(0)),1);}
        catch(Exception failure){throw new IllegalStateException(failure);}return total;
    }
    CarEnergySummary currentStops(long from,long to){
        if(store.remote())return (CarEnergySummary)EnergyClient.call(store.context,"repository.currentStops",from,to);
        CarEnergySummary total=new CarEnergySummary();
        try(Cursor c=store.getReadableDatabase().rawQuery("SELECT data FROM car_summary WHERE kind='stop' AND start>=? AND start<=?",new String[]{Long.toString(from),Long.toString(to)})){while(c.moveToNext())total.add(CarEnergySummary.read(c.getString(0)),1);}
        catch(Exception failure){throw new IllegalStateException(failure);}return total;
    }
    /**
     * Once: Link sends a battery of 0 for a moment when it connects. A charge that began from such a
     * reading shows "0 → 63 %". It takes the last real level before it instead, from the stored frames.
     */
    private void repairZeroBattery(){
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try{
            java.util.List<Object[]> found=new java.util.ArrayList<>();
            try(Cursor c=db.rawQuery("SELECT link,data FROM car_event WHERE data LIKE '%\"before\":0,%' OR data LIKE '%\"before\":0}%'",null)){
                while(c.moveToNext())found.add(new Object[]{c.getString(0),c.getString(1)});
            }
            for(Object[] row:found){
                String link=(String)row[0];CarEnergyTracker.Event event=CarEnergyCodec.event(new JSONObject((String)row[1]));
                if(!(event.before==0)||event.start<=0)continue;
                double real=Double.NaN;
                try(Cursor c=db.rawQuery("SELECT data FROM car_frame WHERE link=? AND t<=? ORDER BY t DESC LIMIT 600",new String[]{link,Long.toString(event.start)})){
                    while(c.moveToNext()){
                        JSONObject frame=new JSONObject(c.getString(0)),values=frame.optJSONObject("values"),times=frame.optJSONObject("times");
                        if(values==null||times==null||!values.has("soc"))continue;
                        double soc=values.optDouble("soc",Double.NaN);
                        if(CarSignal.plausible(CarSignal.SOC,soc)&&event.start-times.optLong("soc",0)<=CarEnergyTracker.SOC_CARRY_MS){real=soc;break;}
                    }
                }
                if(Double.isNaN(real))continue;
                event.before=real;
                ContentValues update=new ContentValues();update.put("data",CarEnergyCodec.event(event).toString());
                db.update("car_event",update,"link=? AND event_key=?",new String[]{link,event.key});
                updateSummary(db,link,event);
            }
            store.setMeta("soc_zero_repair","1");db.setTransactionSuccessful();
        }catch(Exception failure){android.util.Log.w("OABL","Zero-battery repair skipped",failure);}finally{db.endTransaction();}
    }
    /**
     * Once: the head unit's first answer after it wakes has an odometer of 1,048,575 ("not read yet"). Across an
     * overnight gap it became an offline drive of a million km and hid the charge that happened meanwhile.
     * Such a record becomes the charge it really was, from the battery either side, or goes if there was none.
     */
    private void repairUnsetOdometer(){
        boolean changed=false;SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try{
            java.util.List<Object[]> found=new java.util.ArrayList<>();
            try(Cursor c=db.rawQuery("SELECT link,data FROM car_event WHERE kind='drive' AND event_key LIKE 'offline-drive:%'",null)){
                while(c.moveToNext())found.add(new Object[]{c.getString(0),c.getString(1)});
            }
            for(Object[] row:found){
                String link=(String)row[0];CarEnergyTracker.Event event=CarEnergyCodec.event(new JSONObject((String)row[1]));
                if(!(event.gapKm>=CarSignal.ODOMETER_UNSET/2||event.km>=CarSignal.ODOMETER_UNSET/2))continue;
                double after=Double.NaN;
                try(Cursor c=db.rawQuery("SELECT data FROM car_frame WHERE link=? AND t>=? ORDER BY t LIMIT 60",new String[]{link,Long.toString(event.last)})){
                    while(c.moveToNext()){
                        JSONObject values=new JSONObject(c.getString(0)).optJSONObject("values");
                        double soc=values==null?Double.NaN:values.optDouble("soc",Double.NaN);
                        if(CarSignal.plausible(CarSignal.SOC,soc)){after=soc;break;}
                    }
                }
                db.delete("car_event","link=? AND event_key=?",new String[]{link,event.key});
                db.delete("car_summary","link=? AND event_key=?",new String[]{link,event.key});
                changed=true;
                if(Double.isNaN(after)||Double.isNaN(event.before)||after-event.before<CarEnergyTracker.MIN_INFERRED_RISE)continue;
                event.kind="charge";event.key=event.key.replace("offline-drive:","offline-charge:");event.after=after;
                event.km=0;event.gapKm=0;event.distanceSource="";event.externalCharge=true;event.inferred=true;event.gap=true;event.state="complete";
                ContentValues insert=new ContentValues();insert.put("link",link);insert.put("event_key",event.key);insert.put("kind",event.kind);insert.put("start",event.start);insert.put("end_time",event.end);insert.put("last_time",event.last);insert.put("state",event.state);insert.put("data",CarEnergyCodec.event(event).toString());
                db.insertWithOnConflict("car_event",null,insert,SQLiteDatabase.CONFLICT_REPLACE);
            }
            store.setMeta("unset_odometer_repair","1");db.setTransactionSuccessful();
        }catch(Exception failure){android.util.Log.w("OABL","Unset-odometer repair skipped",failure);changed=false;}finally{db.endTransaction();}
        if(changed)rebuildSummaries();
    }
    void rebuildSummaries(){
        if(store.remote()){EnergyClient.call(store.context,"repository.rebuildSummaries");return;}
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try{db.delete("car_summary",null,null);for(Item item:events())updateSummary(db,item.link,item.event);store.setMeta("summary_zone",java.util.TimeZone.getDefault().getID());store.setMeta("energy_accounting","2");db.setTransactionSuccessful();}
        catch(Exception failure){throw new IllegalStateException(failure);}finally{db.endTransaction();}
    }
    /** Replays only frames belonging to each active checkpoint; retained later generations stay raw. */
    void rebuildFromFrames(){
        if(store.remote()){EnergyClient.call(store.context,"repository.rebuildFromFrames");return;}
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try{
            java.util.Map<String,Long> until=new java.util.HashMap<>();
            try(Cursor c=db.rawQuery("SELECT link,data FROM car_energy_state",null)){while(c.moveToNext()){CarEnergyTracker.State state=CarEnergyCodec.state(c.getString(1));if(state.last!=null)until.put(c.getString(0),state.last.time);}}
            java.util.Map<String,CarEnergyTracker.State> states=new java.util.HashMap<>();
            java.util.Map<String,java.util.Map<String,CarEnergyTracker.Event>> events=new java.util.HashMap<>();
            long reset=store.metaLong("car_reset_after",0);
            try(Cursor c=db.rawQuery("SELECT link,t,data FROM car_frame ORDER BY link,t",null)){
                while(c.moveToNext()){
                    String link=c.getString(0);long time=c.getLong(1);if(time<=reset||time>until.getOrDefault(link,0L))continue;
                    CarEnergyTracker.State state=states.computeIfAbsent(link,k->new CarEnergyTracker.State());
                    java.util.Map<String,CarEnergyTracker.Event> found=events.computeIfAbsent(link,k->new java.util.LinkedHashMap<>());
                    for(CarEnergyTracker.Event e:new CarEnergyTracker(state).accept(CarEnergyCodec.frame(new JSONObject(c.getString(2)))))found.put(e.key,e);
                }
            }
            chargesChanged();
            EnergyRetention.retain(db,"car_event",System.currentTimeMillis());
            db.delete("car_event",null,null);db.delete("car_energy_state",null,null);db.delete("car_summary",null,null);
            for(String link:states.keySet()){
                for(CarEnergyTracker.Event e:events.get(link).values()){
                    ContentValues row=new ContentValues();row.put("link",link);row.put("event_key",e.key);row.put("kind",e.kind);row.put("start",e.start);row.put("end_time",e.end);row.put("last_time",e.last);row.put("state",e.state);row.put("data",CarEnergyCodec.event(e).toString());db.insertOrThrow("car_event",null,row);
                }
                ContentValues row=new ContentValues();row.put("link",link);row.put("data",CarEnergyCodec.state(states.get(link)));db.insertOrThrow("car_energy_state",null,row);
                refreshJoins(db,link);
            }
            rebuildSummaries();db.setTransactionSuccessful();
        }catch(Exception failure){throw new IllegalStateException("Raw replay rolled back",failure);}finally{db.endTransaction();}
    }
    private boolean joined(SQLiteDatabase db,String link,String key){
        try(Cursor c=db.rawQuery("SELECT 1 FROM car_drive_join WHERE link=? AND superseded_by IS NULL AND (first_key=? OR second_key=?)",new String[]{link,key,key})){return c.moveToFirst();}
    }
    private CarEnergyTracker.Event event(SQLiteDatabase db,String link,String key)throws Exception{
        try(Cursor c=db.rawQuery("SELECT data FROM car_event WHERE link=? AND event_key=?",new String[]{link,key})){if(!c.moveToFirst())throw new IllegalArgumentException("Original drive unavailable");return CarEnergyCodec.event(new JSONObject(c.getString(0)));}
    }
    private void refreshJoins(SQLiteDatabase db,String link)throws Exception{refreshJoins(db,link,null);}
    /** Rebuilds the merged drive of each live join; with {@code touched}, only joins containing one of those keys. */
    private void refreshJoins(SQLiteDatabase db,String link,java.util.Set<String> touched)throws Exception{
        try(Cursor c=db.rawQuery("SELECT id,first_key,second_key FROM car_drive_join WHERE link=? AND superseded_by IS NULL ORDER BY id",new String[]{link})){
            while(c.moveToNext()){
                if(touched!=null&&!touched.contains(c.getString(1))&&!touched.contains(c.getString(2)))continue;
                CarEnergyTracker.Event a=event(db,link,c.getString(1)),b=event(db,link,c.getString(2));
                CarEnergyTracker.Event merged=CarEnergyCodec.event(CarEnergyCodec.event(a));merged.key="joined:"+c.getLong(0);merged.end=b.end;merged.last=b.last;merged.state=b.state;merged.after=b.after;
                merged.km+=b.km;merged.movingSeconds+=b.movingSeconds;merged.stoppedSeconds+=b.stoppedSeconds;merged.modelKwh+=b.modelKwh;
                merged.gap=true;merged.gapSeconds+=b.gapSeconds+Math.max(0,b.start-a.last)/1000.0;merged.tripKm=b.tripKm;merged.tripKwh100=b.tripKwh100;merged.joinable=b.joinable;
                merged.temperatureSum+=b.temperatureSum;merged.temperatureSeconds+=b.temperatureSeconds;merged.peakSpeed=Math.max(a.peakSpeed,b.peakSpeed);
                for(int i=0;i<6;i++){merged.temperatureKm[i]+=b.temperatureKm[i];merged.temperaturePoints[i]+=b.temperaturePoints[i];}
                // No distance or stopped-load model is invented for an owner's uncertain join.
                ContentValues row=new ContentValues();row.put("link",link);row.put("event_key",merged.key);row.put("kind","drive");row.put("start",merged.start);row.put("end_time",merged.end);row.put("state",merged.state);row.put("last_time",merged.last);row.put("data",CarEnergyCodec.event(merged).toString());
                db.insertWithOnConflict("car_event",null,row,SQLiteDatabase.CONFLICT_REPLACE);
                if(!joined(db,link,merged.key))updateSummary(db,link,merged);
            }
        }
    }
    void join(Item first,Item second){
        if(store.remote()){EnergyClient.call(store.context,"repository.join",first,second);return;}
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try{
            CarEnergyTracker.Event a=event(db,first.link,first.event.key),b=event(db,second.link,second.event.key);
            if(!first.link.equals(second.link)||!a.kind.equals("drive")||!b.kind.equals("drive")||a.start>=b.start||!a.joinable)throw new IllegalArgumentException("Only uncertain interrupted drives can be joined");
            if(joined(db,first.link,a.key)||joined(db,first.link,b.key))throw new IllegalArgumentException("Drive already joined");
            ContentValues row=new ContentValues();row.put("link",first.link);row.put("first_key",a.key);row.put("second_key",b.key);row.put("created",System.currentTimeMillis());db.insertOrThrow("car_drive_join",null,row);
            for(CarEnergyTracker.Event original:new CarEnergyTracker.Event[]{a,b}){
                String data=null;long at=0;try(Cursor c=db.rawQuery("SELECT data,last FROM car_summary WHERE link=? AND event_key=?",new String[]{first.link,original.key})){if(c.moveToFirst()){data=c.getString(0);at=c.getLong(1);}}
                if(data!=null){bucket(db,first.link,day(at),CarEnergySummary.read(data),-1);db.delete("car_summary","link=? AND event_key=?",new String[]{first.link,original.key});}
            }
            refreshJoins(db,first.link);db.setTransactionSuccessful();
        }catch(Exception failure){throw new IllegalArgumentException(failure.getMessage(),failure);}finally{db.endTransaction();}
    }
}
