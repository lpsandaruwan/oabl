package com.softiva.oabl;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Live cards (including nonempty trip, range and temperature figures) fit every supported shape. */
public final class LiveEnergyLayoutTestRunner extends Instrumentation {
    EnergyActivity page;int checks;final List<String> failures=new ArrayList<>();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof EnergyActivity)page=(EnergyActivity)activity;}
    Object field(Object owner,String key){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new RuntimeException(e);}}
    void set(Object owner,String key,Object value){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(key);f.setAccessible(true);f.set(owner,value);}catch(Exception e){throw new RuntimeException(e);}}
    void fit(boolean ok,String label){checks++;if(!ok)failures.add(label);}
    boolean inScroll(View v){android.view.ViewParent p=v.getParent();while(p instanceof View){if(p instanceof ScrollView)return true;p=p.getParent();}return false;}
    void shown(View v,String where){
        if(v.getVisibility()!=View.VISIBLE)return;
        if(v instanceof TextView){
            TextView t=(TextView)v;
            if(t.getText().length()>0){
                Rect bounds=new Rect();String label=where+": "+t.getText();
                if(!inScroll(v))fit(t.getGlobalVisibleRect(bounds)&&bounds.height()==t.getHeight()&&bounds.width()==t.getWidth(),label+" visible");
                android.text.Layout l=t.getLayout();
                fit(l!=null&&l.getHeight()<=t.getHeight()-t.getTotalPaddingTop()-t.getTotalPaddingBottom()+1,label+" lines fit");
                if(l!=null)for(int line=0;line<l.getLineCount();line++) {
                    fit(l.getLineMax(line)<=t.getWidth()-t.getTotalPaddingLeft()-t.getTotalPaddingRight()+1,label+" width fits");
                    fit(l.getEllipsisCount(line)==0,label+" no truncated text");
                }
            }
        }
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)shown(((ViewGroup)v).getChildAt(i),where);
    }
    void shell(String value)throws Exception{try(ParcelFileDescriptor p=getUiAutomation().executeShellCommand(value);java.io.InputStream in=new java.io.FileInputStream(p.getFileDescriptor())){byte[] b=new byte[4096];while(in.read(b)!=-1){}}}
    @Override public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try{
            if(!Build.HARDWARE.equals("ranchu")&&!Build.HARDWARE.equals("goldfish"))throw new IllegalStateException("Emulator only");
            Context c=getTargetContext();runOnMainSync(()->CarLinkController.get(c).setMode(CarLinkController.MODE_CAR));
            CarEnergyRepository repo=new CarEnergyRepository(EnergyStore.get(c));long at=System.currentTimeMillis()+10000;
            for(int i=0;i<2;i++){
                CarEnergyTracker.Frame f=new CarEnergyTracker.Frame();f.time=at+i*60000;f.capacity=40;
                f.set(CarSignal.SOC,80-i).set(CarSignal.ODOMETER,100+i).set(CarSignal.READY,1).set(CarSignal.SPEED,60).set(CarSignal.CHARGING,0).set(CarSignal.TRIP_KM,123.4+i).set(CarSignal.TRIP_KWH100,14.1).set(CarSignal.RANGE_EST,300-i*4).set(CarSignal.TEMP_OUT,35);
                repo.observe("layout-fixture",f);
            }
            shell("wm density 238");
            for(String size:new String[]{"1920x898","1920x862","1920x720","960x970"}){
                shell("wm size "+size);SystemClock.sleep(800);startActivitySync(new Intent(c,EnergyActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();SystemClock.sleep(500);
                for(String tab:new String[]{"tabDrive","tabWeek","tabCharging","tabAll","tabToday","tabMonth"}){
                    runOnMainSync(()->((View)field(page,tab)).performClick());waitForIdleSync();SystemClock.sleep(600);
                    runOnMainSync(()->{
                        ViewGroup body=(ViewGroup)field(page,"body");fit(body.getChildCount()==3,size+" "+tab+" has three cards");fit(!(body.getParent() instanceof ScrollView),"page does not scroll");shown((View)body.getParent(),size+" "+tab);
                        View decor=page.getWindow().getDecorView();android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(decor.getWidth(),decor.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);decor.draw(new android.graphics.Canvas(image));
                        try(java.io.OutputStream out=c.openFileOutput("live-"+size+"-"+tab+".png",0)){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{image.recycle();}
                    });
                    if(size.equals("1920x898") && tab.equals("tabAll")) {
                        final int[] offset={0};
                        runOnMainSync(()->{LinearLayout b=(LinearLayout)field(page,"body");ScrollView list=(ScrollView)((LinearLayout)b.getChildAt(2)).getChildAt(1);list.scrollTo(0,240);});
                        waitForIdleSync();
                        runOnMainSync(()->{LinearLayout b=(LinearLayout)field(page,"body");offset[0]=((ScrollView)((LinearLayout)b.getChildAt(2)).getChildAt(1)).getScrollY();
                            try{java.lang.reflect.Method render=EnergyActivity.class.getDeclaredMethod("render");render.setAccessible(true);render.invoke(page);}catch(Exception failure){throw new RuntimeException(failure);}});
                        SystemClock.sleep(600);waitForIdleSync();
                        runOnMainSync(()->{LinearLayout b=(LinearLayout)field(page,"body");int after=((ScrollView)((LinearLayout)b.getChildAt(2)).getChildAt(1)).getScrollY();fit(offset[0]>0&&offset[0]==after,"history scroll survives data refresh");});
                    }
                }
                // Charging UI fixture: exact same measured views, without adding a charge or price to history.
                CarEnergyPage charging=CarEnergyPage.capture(c,EnergyActivity.DRIVE);
                CarEnergyTracker.Event charge=new CarEnergyTracker.Event();charge.kind="charge";charge.externalCharge=true;
                charge.start=System.currentTimeMillis()-1200000;charge.last=System.currentTimeMillis();charge.before=40;charge.after=45;
                charge.chargerKwh=1.2;charge.coveredSeconds=1200;
                set(charging,"car",true);set(charging,"fresh",true);set(charging,"charging",true);set(charging,"charge",charge);set(charging,"prompt",null);set(charging,"pending",null);
                CarCharger home=new CarCharger();home.id=1;home.home=true;set(charging,"site",home);set(charging,"homeRate",50d);
                @SuppressWarnings("unchecked") java.util.Map<CarSignal,Double> values=(java.util.Map<CarSignal,Double>)field(charging,"values");
                values.put(CarSignal.SOC,45d);values.put(CarSignal.RANGE_EST,180d);values.put(CarSignal.CHARGING,1d);values.put(CarSignal.PLUG,1d);
                values.put(CarSignal.SPEED,0d);values.put(CarSignal.POWER,3.6);values.put(CarSignal.CHARGE_TARGET,95d);values.put(CarSignal.CHARGE_REMAINING_RAW,354d);
                runOnMainSync(()->{
                    Runnable changed=(Runnable)field(page,"changed");EnergyJournal.get(c).unobserve(changed);EnergyClient.link(c).unobserve(changed);
                    ((Handler)field(page,"handler")).removeCallbacksAndMessages(null);set(page,"page",charging);set(page,"tab",EnergyActivity.DRIVE);
                    try{java.lang.reflect.Method draw=EnergyActivity.class.getDeclaredMethod("draw");draw.setAccessible(true);draw.invoke(page);}catch(Exception e){throw new RuntimeException(e);}
                });
                waitForIdleSync();SystemClock.sleep(300);
                runOnMainSync(()->{
                    View root=(View)((View)field(page,"body")).getParent();shown(root,size+" charging now");
                    android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);root.draw(new android.graphics.Canvas(image));
                    try(java.io.OutputStream out=c.openFileOutput("live-"+size+"-charging-now.png",0)){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}catch(Exception e){throw new RuntimeException(e);}finally{image.recycle();}
                });
                runOnMainSync(()->page.finishAndRemoveTask());waitForIdleSync();SystemClock.sleep(200);
            }
            if(!failures.isEmpty())throw new AssertionError(String.join("\n",failures));result.putString("stream","RESULT PASS · "+checks+" live layout checks\n");code=Activity.RESULT_OK;
        }catch(Throwable failure){result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(failure));}
        finally{try{shell("wm size reset");shell("wm density reset");}catch(Exception ignored){}}
        finish(code,result);
    }
}
