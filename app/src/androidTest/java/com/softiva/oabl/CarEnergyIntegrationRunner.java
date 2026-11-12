package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real SQLite/checkpoint and real pricing UI checks; isolated source, emulator only, cleaned up. */
public final class CarEnergyIntegrationRunner extends Instrumentation {
    private int checks;
    private void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;Bundle b=new Bundle();b.putString("stream","PASS "+label+"\n");sendStatus(0,b);}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private long base;
    private CarEnergyTracker.Frame frame(long seconds,double soc,int ready,double speed,double odo,int charging){
        CarEnergyTracker.Frame f=new CarEnergyTracker.Frame();f.time=base+seconds*1000;f.capacity=42.3;f.idleKw=1.2;
        return f.set(CarSignal.SOC,soc).set(CarSignal.READY,ready).set(CarSignal.SPEED,speed).set(CarSignal.ODOMETER,odo).set(CarSignal.CHARGING,charging);
    }
    private static void edits(View v,List<EditText> result){if(v instanceof EditText)result.add((EditText)v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)edits(((ViewGroup)v).getChildAt(i),result);}
    private static String text(View v){StringBuilder s=new StringBuilder();if(v instanceof TextView)s.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)s.append(text(((ViewGroup)v).getChildAt(i)));return s.toString();}
    private static Spinner spinner(View v){if(v instanceof Spinner)return(Spinner)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Spinner s=spinner(((ViewGroup)v).getChildAt(i));if(s!=null)return s;}return null;}
    private void capture(View view,String name){
        if(view.getWidth()==0||view.getHeight()==0)return;
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        view.draw(new android.graphics.Canvas(bitmap));
        try(java.io.OutputStream out=getTargetContext().openFileOutput(name,0)){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}
        catch(Exception failure){throw new RuntimeException(failure);}finally{bitmap.recycle();}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();int outcome=Activity.RESULT_CANCELED;String source="integration-"+UUID.randomUUID();EnergyStore store=null;CarLinkController controller=null;String oldMode=null;
        try{
            if(!"ranchu".equals(Build.HARDWARE)&&!"goldfish".equals(Build.HARDWARE))throw new IllegalStateException("REFUSED non-emulator");
            EnergyJournal.serviceEnabled=false;store=EnergyStore.get(getTargetContext());controller=CarLinkController.get(getTargetContext());oldMode=controller.mode();
            CarLinkController c=controller;runOnMainSync(()->c.setMode(CarLinkController.MODE_MANUAL));
            check(store.getReadableDatabase().getVersion()==EnergyStore.VERSION,"current schema opens existing energy data");
            CarEnergyRepository repository=new CarEnergyRepository(store);base=System.currentTimeMillis()-4200000;
            repository.observe(source,frame(0,70,1,40,100,0));repository.observe(source,frame(30,70,1,0,100.5,0));
            repository.observe(source,frame(60,69.9,1,0,100.5,0));repository.observe(source,frame(90,69.8,1,0,100.5,0));
            repository.observe(source,frame(120,69.8,1,40,100.5,0));repository.observe(source,frame(150,69.5,0,0,101,0));
            CarEnergyRepository.Item stop=null,drive=null;
            for(CarEnergyRepository.Item item:repository.events())if(item.link.equals(source)){if(item.event.kind.equals("stop"))stop=item;if(item.event.kind.equals("drive"))drive=item;}
            check(stop!=null&&Math.abs(stop.event.stoppedSeconds-90)<.01,"car READY/speed records 90 stopped seconds without GPS");
            check(Math.abs(stop.event.modelKwh-.03)<.00001,"1.2 kW stopped load gives 0.03 kWh for 90 seconds");
            check(drive!=null&&Math.abs(drive.event.km-1)<.00001,"car odometer records 1 km");
            repository.observe(source,frame(180,40,0,0,101,1));repository.observe(source,frame(240,42,0,0,101,1));repository.observe(source,frame(270,45,0,0,101,0));
            repository.observe(source,frame(500,45,0,0,101,0));repository.observe(source,frame(4100,50,0,0,101,0));repository.observe(source,frame(4100,50,0,0,101,0));
            CarEnergyRepository.Item candidate=null;int charges=0;
            for(CarEnergyRepository.Item item:repository.events())if(item.link.equals(source)&&item.event.kind.equals("charge")){charges++;if(item.event.inferred)candidate=item;else repository.review(item,"ignored",0,0);}
            check(charges==2,"observed charge and one offline candidate survive persisted reducer checkpoints");
            check(candidate!=null&&candidate.event.offSeen&&candidate.event.gain()==5,"45 to 50 percent after READY-off gap requires confirmation");
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT count(*) FROM car_frame WHERE link=?",new String[]{source})){rows.moveToFirst();check(rows.getInt(0)==11,"replayed frame does not duplicate observations");}
            SystemClock.sleep(8000); // let the app's existing post-install Home alarm finish
            MainActivity main=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            AtomicReference<AlertDialog> dialog=new AtomicReference<>();CarEnergyRepository.Item pending=candidate;
            runOnMainSync(()->dialog.set(CarChargeDialogs.show(main,main.currentPalette(),pending,null)));
            List<EditText> fields=new ArrayList<>();runOnMainSync(()->edits(dialog.get().getWindow().getDecorView(),fields));
            AtomicReference<String> prompt=new AtomicReference<>();runOnMainSync(()->prompt.set(text(dialog.get().getWindow().getDecorView())));
            check(prompt.get().contains("increased by 5 percentage points")&&prompt.get().contains("2.115 kWh")&&prompt.get().contains("42.3 kWh"),"five-point prompt shows gain and the explicit 42.3 kWh estimate basis");
            check(fields.size()==2,"detected charge asks only meter kWh and rate; SOC is read-only");
            SystemClock.sleep(350);runOnMainSync(()->capture(dialog.get().getWindow().getDecorView(),"car-charge-confirmation.png"));
            runOnMainSync(()->dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            check(dialog.get().isShowing(),"location must be chosen before saving");
            runOnMainSync(()->spinner(dialog.get().getWindow().getDecorView()).setSelection(2));waitForIdleSync();
            runOnMainSync(()->{fields.get(0).setText("2.3");fields.get(1).setText("50");});
            SystemClock.sleep(200);runOnMainSync(()->capture(dialog.get().getWindow().getDecorView(),"car-charge-pricing.png"));
            runOnMainSync(()->dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            CarEnergyRepository.Item saved=null;for(CarEnergyRepository.Item item:repository.events())if(item.link.equals(source)&&item.event.inferred)saved=item;
            check(saved!=null&&saved.review.equals("saved")&&saved.chargeId>0,"confirmed offline charge has one persisted review and charge entry");
            EnergyModel.Charge charge=null;for(EnergyModel.Charge item:store.charges())if(item.id==saved.chargeId)charge=item;
            check(charge!=null&&charge.place==EnergyModel.Place.PUBLIC&&charge.before==45&&charge.after==50,"outside location and car SOC endpoints are preserved");
            check(Math.abs(charge.money(1)-115)<.00001,"2.3 metered kWh at 50 per unit costs 115");
            CarEnergyRepository.Item editing=saved;
            runOnMainSync(()->dialog.set(CarChargeDialogs.show(main,main.currentPalette(),editing,null)));
            runOnMainSync(()->dialog.get().getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            try(Cursor rows=store.getReadableDatabase().rawQuery("SELECT count(*) FROM charge WHERE time=? AND superseded_by IS NULL",new String[]{Long.toString(base+4100000)})){rows.moveToFirst();check(rows.getInt(0)==1,"editing a charge replaces it without double counting");}
            runOnMainSync(()->c.setMode(CarLinkController.MODE_CAR));
            Activity energy=startActivitySync(new Intent(getTargetContext(),EnergyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AtomicReference<String> ui=new AtomicReference<>();runOnMainSync(()->ui.set(text(energy.getWindow().getDecorView())));
            check(ui.get().contains("CAR SPEED")&&ui.get().contains("STOPS · CAR READY ON")&&!ui.get().contains("KM BY GPS"),"live Energy page uses car speed/distance and stopped energy");
            runOnMainSync(()->energy.finish());
            result.putString("stream","RESULT PASS · "+checks+" car energy integration checks\n");outcome=Activity.RESULT_OK;
        }catch(Throwable failure){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(failure));}
        finally{
            if(controller!=null&&oldMode!=null){CarLinkController c=controller;String mode=oldMode;runOnMainSync(()->c.setMode(mode));}
            // Host restores the verified emulator backup after the suite; originals are retained.

        }
        finish(outcome,result);
    }
}
