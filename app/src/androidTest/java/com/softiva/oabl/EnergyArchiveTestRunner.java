package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.JSONObject;

/** Destructive archive checks use a separate named database and temporary backup directory only. */
public final class EnergyArchiveTestRunner extends Instrumentation {
    private int checks;
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;Bundle b=new Bundle();b.putString("stream","PASS "+label+"\n");sendStatus(0,b);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private static EnergyArchive.Target target(File file){return new EnergyArchive.Target(){public OutputStream write()throws Exception{return new FileOutputStream(file);}public InputStream read()throws Exception{return new FileInputStream(file);}};}
    private static long count(EnergyStore store,String table){try(Cursor c=store.getReadableDatabase().rawQuery("SELECT count(*) FROM "+table,null)){c.moveToFirst();return c.getLong(0);}}
    private static String snapshot(EnergyStore store)throws Exception{
        JSONObject all=store.export();for(String table:EnergyStore.NOT_BACKED_UP){org.json.JSONArray rows=new org.json.JSONArray();try(Cursor c=store.getReadableDatabase().query(table,null,null,null,null,null,null)){
            while(c.moveToNext()){org.json.JSONArray row=new org.json.JSONArray();for(int i=0;i<c.getColumnCount();i++)row.put(c.isNull(i)?JSONObject.NULL:c.getString(i));rows.put(row);}
        }all.put(table,rows);}return all.toString();
    }
    private static JSONObject contents(File file)throws Exception{
        try(InputStream in=new GZIPInputStream(new FileInputStream(file));ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new JSONObject(out.toString("UTF-8"));}
    }
    private static EditText edit(View view){if(view instanceof EditText)return(EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText found=edit(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null;}
    private void capture(View view,String name)throws Exception{
        android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(image));
        try(OutputStream out=getTargetContext().openFileOutput(name,0)){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
    }
    private static void remove(File file){if(file.isDirectory()){File[] files=file.listFiles();if(files!=null)for(File child:files)remove(child);}file.delete();}
    @Override public void onStart(){
        Bundle result=new Bundle();int outcome=Activity.RESULT_CANCELED;EnergyStore store=null;File folder=null;String database="energy-archive-test-"+UUID.randomUUID()+".db";
        try{
            if(!"ranchu".equals(Build.HARDWARE)&&!"goldfish".equals(Build.HARDWARE))throw new IllegalStateException("REFUSED non-emulator");
            Context app=getTargetContext();folder=new File(app.getCacheDir(),database);if(!folder.mkdirs())throw new IOException("No temporary directory");
            File work=folder;Context context=new ContextWrapper(app){@Override public File getNoBackupFilesDir(){return work;}};
            long existing=count(EnergyStore.get(app),"checkin");String profile=app.getSharedPreferences(EnergyStore.PROFILE,0).getAll().toString();
            store=new EnergyStore(context,database);long time=System.currentTimeMillis()-10000;double[] counters={1200,60,30,0,90,0};
            long session=store.createSession("test",time,"test",counters);store.addCheckIn(time,50,"start",EnergyModel.Charged.NO,session,counters,store.profile());store.saveCounters(counters);
            EnergyModel.Charge charge=new EnergyModel.Charge();charge.time=time;charge.before=40;charge.after=50;charge.kwh=4.5;charge.rate=50;store.addCharge(charge);
            store.insertCarSample("fixture",7,time,"{\"soc\":50}");
            CarEnergyTracker.Frame frame=new CarEnergyTracker.Frame();frame.time=time;frame.capacity=42.3;frame.set(CarSignal.SOC,50).set(CarSignal.READY,1).set(CarSignal.SPEED,0).set(CarSignal.CHARGING,0);
            new CarEnergyRepository(store).observe("fixture",frame);
            String original=snapshot(store);File exported=new File(folder,"original.json.gz");
            EnergyArchive.Result first=EnergyArchive.perform(context,store,target(exported),false,null);
            check(snapshot(store).equals(original),"backup alone preserves all existing rows");
            JSONObject archive=contents(exported);JSONObject tables=archive.getJSONObject("tables");
            check(tables.length()==EnergyStore.TABLES.length&&tables.getJSONArray("car_sample").length()==1&&tables.getJSONArray("car_frame").length()==1,"archive contains every table including both raw telemetry tables");
            check(first.file.isFile()&&first.sha256.length()==64,"verified local archive and SHA-256 receipt are retained");
            EnergyArchive.Target broken=new EnergyArchive.Target(){public OutputStream write()throws Exception{throw new IOException("Simulated full destination");}public InputStream read(){return new ByteArrayInputStream(new byte[0]);}};
            boolean refused=false;try{EnergyArchive.perform(context,store,broken,true,null);}catch(IOException expected){refused=true;}
            check(refused&&snapshot(store).equals(original),"backup write failure refuses reset and preserves all rows");
            EnergyArchive.Target corrupt=new EnergyArchive.Target(){public OutputStream write(){return new ByteArrayOutputStream();}public InputStream read(){return new ByteArrayInputStream(new byte[]{1});}};
            refused=false;try{EnergyArchive.perform(context,store,corrupt,true,null);}catch(IOException expected){refused=true;}
            check(refused&&snapshot(store).equals(original),"read-back checksum mismatch refuses reset");
            EnergyArchive.perform(context,store,target(new File(folder,"reset.json.gz")),true,null);
            boolean empty=true;for(String table:EnergyStore.TABLES)if(!table.equals("meta")&&!EnergyRetention.raw(table))empty&=count(store,table)==0;
            check(empty&&store.counters()[0]==0,"backed-up reset clears active totals and keeps raw telemetry plus superseded originals");
            check(store.lastCarSeq("fixture")==7,"reset preserves a replay watermark for each link");
            long after=store.metaLong("car_reset_after",0)+1000;
            check(!store.insertCarSample("fixture",8,time,"{}")&&!store.insertCarSample("fixture",7,after,"{}"),"old-time and already-seen samples cannot repopulate reset history");
            new CarEnergyRepository(store).observe("fixture",frame);
            check(count(store,"car_event")==0&&count(store,"car_frame")==1,"pre-reset snapshot retains its raw row without recreating a drive or charge");
            check(store.insertCarSample("fixture",8,after,"{}"),"new post-reset sample is retained");
            EnergyArchive.perform(context,store,target(new File(folder,"before-restore.json.gz")),false,exported);
            check(store.checkIns().size()==1&&store.charges().size()==1&&store.counters()[0]==1200&&count(store,"car_sample")==2&&count(store,"energy_retained")>0,"restore keeps original IDs and counters, post-backup raw rows and superseded records");
            original=snapshot(store);
            File malformed=new File(folder,"malformed.json.gz");try(OutputStream out=new FileOutputStream(malformed)){out.write(new byte[]{1,2,3});}
            refused=false;try{EnergyArchive.perform(context,store,target(new File(folder,"before-invalid.json.gz")),false,malformed);}catch(IOException expected){refused=true;}
            check(refused&&snapshot(store).equals(original),"malformed restore rolls back and leaves current history intact");
            archive.getJSONObject("row_counts").put("car_sample",999);File mismatch=new File(folder,"wrong-count.json.gz");try(GZIPOutputStream out=new GZIPOutputStream(new FileOutputStream(mismatch))){out.write(archive.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            refused=false;try{EnergyArchive.perform(context,store,target(new File(folder,"before-count.json.gz")),false,mismatch);}catch(IOException expected){refused=true;}
            check(refused&&snapshot(store).equals(original),"incorrect archive row counts roll back the restore");
            check(profile.equals(app.getSharedPreferences(EnergyStore.PROFILE,0).getAll().toString()),"vehicle capacity, tariffs and profile settings are unchanged");
            store.close();store=new EnergyStore(context,database);
            check(snapshot(store).equals(original),"restored history survives database close and reopen");
            check(count(EnergyStore.get(app),"checkin")==existing,"user database was never reset by destructive tests");
            if(Build.VERSION.SDK_INT>=29){
                android.content.ContentValues document=new android.content.ContentValues();String name=EnergyArchive.filename();
                document.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name);
                document.put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/gzip");
                document.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/OABL");
                android.net.Uri uri=app.getContentResolver().insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,document);
                if(uri==null)throw new IOException("Cannot create Downloads backup");
                EnergyArchive.Result saved=EnergyArchive.perform(app,EnergyStore.get(app),EnergyArchive.document(app,uri),false,null);
                check(saved.file.isFile()&&count(EnergyStore.get(app),"checkin")==existing,"real Android Downloads provider writes and verifies a complete backup without resetting user history");
                JSONObject receipt=new JSONObject().put("uri",uri.toString()).put("name",name).put("sha256",saved.sha256).put("local",saved.file.toString());
                try(OutputStream out=app.openFileOutput("energy-document-verification.json",0)){out.write(receipt.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            }
            EnergyDataActivity activity=(EnergyDataActivity)startActivitySync(new Intent(app,EnergyDataActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AtomicReference<AlertDialog> dialog=new AtomicReference<>();runOnMainSync(()->dialog.set(activity.challenge(1)));
            check(!dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"reset confirmation starts disabled");
            runOnMainSync(()->edit(dialog.get().getWindow().getDecorView()).setText("000"));
            check(!dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"partial confirmation phrase cannot continue");
            runOnMainSync(()->edit(dialog.get().getWindow().getDecorView()).setText("1234"));
            check(!dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"incorrect four-digit code cannot continue");
            runOnMainSync(()->edit(dialog.get().getWindow().getDecorView()).setText("0000"));
            check(dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"exact 0000 code enables backup-location selection");
            android.os.SystemClock.sleep(250);runOnMainSync(()->{try{capture(dialog.get().getWindow().getDecorView(),"energy-reset-challenge.png");}catch(Exception e){throw new RuntimeException(e);}});
            runOnMainSync(()->dialog.get().getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            runOnMainSync(()->activity.onActivityResult(741,Activity.RESULT_CANCELED,null));
            check(count(EnergyStore.get(app),"checkin")==existing&&!EnergyDataActivity.BUSY.get(),"cancel challenge or file picker leaves user data untouched");
            runOnMainSync(()->{try{capture(activity.getWindow().getDecorView(),"energy-data-tools.png");}catch(Exception e){throw new RuntimeException(e);}});
            runOnMainSync(activity::finish);
            result.putString("stream","RESULT PASS · "+checks+" energy archive integration checks\n");outcome=Activity.RESULT_OK;
        }catch(Throwable failure){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(failure));}
        finally{if(store!=null)store.close();getTargetContext().deleteDatabase(database);if(folder!=null)remove(folder);}
        finish(outcome,result);
    }
}
