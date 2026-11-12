package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Emulator-only acceptance of the energy-first Home. Fixtures render directly, never enter history. */
public final class DashboardPriorityTestRunner extends Instrumentation {
    private static final String PREFS="launcher_preferences";
    private static final String REFERENCES="CLTC ≈292 km · WLTP ≈224 km · NEDC ≈248 km\nAt current charge · modelled from entered ratings";
    private MainActivity home;
    private int checks;
    private String onlySize;

    @Override public void onCreate(Bundle args){super.onCreate(args);onlySize=args==null?null:args.getString("size");start();}
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof MainActivity)home=(MainActivity)activity;}

    @Override public void onStart(){
        Bundle result=new Bundle();boolean success=false;Map<String,?> saved=null;Context context=getTargetContext();MediaAccessService.NavigationSnapshot originalNavigation=MediaAccessService.navigationSnapshot();
        try{
            check("ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE),"Emulator only");
            saved=new HashMap<>(context.getSharedPreferences(PREFS,0).getAll());
            LauncherPreferences preferences=new LauncherPreferences(context);
            preferences.setScreensaverSeconds(0);preferences.setVisualEffects(false);preferences.setStartupNoticeEnabled(false);
            preferences.setOpenNavigationOnStart(false);preferences.setResumeMusicOnStart(false);preferences.setDrivingModeKmh(0);preferences.setDriverSide("right");
            // Installation can schedule a one-shot Home relaunch. Let it finish before resizing.
            SystemClock.sleep(2500);
            for(String size:new String[]{"1920x970","1920x720","960x970"}){
                if(onlySize!=null&&!onlySize.equals(size))continue;
                closeHome();shell("wm size "+size);SystemClock.sleep(500);
                for(int theme:new int[]{LauncherPreferences.THEME_DARK,LauncherPreferences.THEME_LIGHT}){
                    preferences.setThemeMode(theme);openHome();String name=size+(theme==LauncherPreferences.THEME_DARK?"-dark":"-light");
                    HomeEnergySnapshot live=snapshot(68,214,false,performance(37.3));
                    fixture(live,72);ui(()->{
                        layout(name);text(R.id.energy_soc,"68%","Live battery %");text(R.id.energy_range,"214 km","Car range");
                        for(String reference:new String[]{"CLTC","WLTP","NEDC","modelled"})contains(R.id.energy_reference_row,reference,"All reference ranges carry provenance");
                        text(R.id.trip_verdict,"Overperforming","Verdict leads the performance card");
                        for(int id:new int[]{R.id.media_previous,R.id.media_play_pause,R.id.media_next}){
                            View button=home.findViewById(id);check(button.isClickable()&&button.isFocusable()&&button.hasOnClickListeners(),"Media control remains wired and focusable: "+id);
                            check(button.getContentDescription()!=null&&button.getContentDescription().length()>0,"Media control is labelled: "+id);
                        }
                    });capture("dashboard-priority-"+name+"-overperforming");
                    guidanceAndDriverSide(name,preferences,live);
                    fixture(snapshot(68,214,false,performance(33.333333333)),72);ui(()->{text(R.id.trip_verdict,"On point","On-point state");primaryText(R.id.trip_verdict,name+" on point");});
                    fixture(snapshot(68,214,false,performance(29)),72);ui(()->{text(R.id.trip_verdict,"Underperforming","Underperforming state");primaryText(R.id.trip_verdict,name+" underperforming");});capture("dashboard-priority-"+name+"-underperforming");
                    ui(()->{home.renderDrivePerformance(null);text(R.id.trip_verdict,"Learning this drive","Manual mode clears the previous live verdict");check(!value(R.id.trip_performance).contains("below"),"Manual mode clears the previous live comparison detail");});
                    CarEnergyTracker.Event starting=drive(3);starting.after=77;
                    fixture(snapshot(77,250,false,CarDrivePerformance.evaluate(starting,System.currentTimeMillis(),true)),0);
                    ui(()->{text(R.id.trip_verdict,"Learning this drive","No premature performance verdict");primaryText(R.id.trip_verdict,name+" learning");});
                    fixture(snapshot(68,214,true,CarDrivePerformance.evaluate(null,System.currentTimeMillis(),true)),0);
                    ui(()->{contains(R.id.energy_label,"CHARGING","Charging state is visible");check(!value(R.id.trip_verdict).equals("Overperforming"),"Charging does not retain a previous verdict");});capture("dashboard-priority-"+name+"-charging");
                    HomeEnergySnapshot waiting=snapshot(Double.NaN,Double.NaN,false,CarDrivePerformance.evaluate(null,System.currentTimeMillis(),false));waiting.live=false;
                    fixture(waiting,-1);ui(()->{
                        text(R.id.energy_soc,"—%","Missing battery stays unknown");check(!value(R.id.energy_range).contains("214"),"Stale range is cleared");
                        check(!value(R.id.trip_verdict).equals("Overperforming"),"Missing live readings clear the prior verdict");
                        primaryText(R.id.trip_verdict,name+" unknown verdict");primaryText(R.id.energy_soc,name+" unknown battery");
                    });capture("dashboard-priority-"+name+"-waiting");
                    batteryStates(name);manualMode(name);weatherDetails(name,preferences);closeHome();
                }
            }
            success=true;result.putString("stream","PASS · "+checks+" dashboard acceptance checks; three sizes, light/dark, live/charging/unknown, verdicts, colour boundaries and media controls. Fixtures only rendered, never written to energy history.\n");
        }catch(Throwable error){try{if(home!=null)capture("dashboard-priority-failure");}catch(Throwable ignored){}result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(error));}
        finally{closeHome();navigationState(originalNavigation);try{shell("wm size reset");}catch(Throwable ignored){}if(saved!=null)restore(saved);}
        finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    private void openHome(){
        home=(MainActivity)startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitForIdleSync();SystemClock.sleep(600);
        ui(()->{
            Handler handler=(Handler)field(home,"handler");handler.removeCallbacks((Runnable)field(home,"mediaRefresh"));
            Runnable changed=(Runnable)field(home,"energyChanged");EnergyJournal.get(home).unobserve(changed);EnergyClient.link(home).unobserve(changed);
        });
    }
    private void closeHome(){if(home==null)return;MainActivity old=home;ui(old::finish);waitForIdleSync();home=null;}
    private HomeEnergySnapshot snapshot(double soc,double range,boolean charging,CarDrivePerformance.Result performance){
        HomeEnergySnapshot data=new HomeEnergySnapshot();data.car=true;data.live=true;data.soc=soc;data.range=range;data.charging=charging;
        data.ageSeconds=1;data.vehicle="NAMMI 1 · E3";data.referenceText=REFERENCES;data.performance=performance;return data;
    }
    private CarEnergyTracker.Event drive(double km){
        CarEnergyTracker.Event drive=new CarEnergyTracker.Event();drive.kind="drive";drive.state="open";drive.before=78;drive.after=68;drive.rangeStart=260;
        drive.km=km;drive.movingSeconds=1800;drive.peakSpeed=83;drive.start=System.currentTimeMillis()-1800000;drive.last=System.currentTimeMillis();return drive;
    }
    private CarDrivePerformance.Result performance(double km){return CarDrivePerformance.evaluate(drive(km),System.currentTimeMillis(),true);}
    private void fixture(HomeEnergySnapshot data,int speed){
        ui(()->{
            home.renderHomeEnergy(data);SpeedGaugeView gauge=home.findViewById(R.id.speed_cluster);
            if(speed<0)gauge.unknown(0,home.currentPalette());else gauge.reading(speed,0,home.currentPalette());
            home.<TextView>findViewById(R.id.trip_distance).setText("37.3");home.<TextView>findViewById(R.id.trip_time).setText("30");
            home.<TextView>findViewById(R.id.trip_average).setText("75");home.<TextView>findViewById(R.id.trip_peak).setText("83");
        });waitForIdleSync();SystemClock.sleep(100);
    }
    private void layout(String name){
        check(!verticalScroll(home.findViewById(R.id.home_content)),name+" dashboard has no page scrolling");
        for(int id:new int[]{R.id.energy_card,R.id.telemetry_card,R.id.trip_card,R.id.media_card,R.id.navigation_hint_card,R.id.calendar_card,R.id.dock,R.id.energy_battery_icon,R.id.media_previous,R.id.media_play_pause,R.id.media_next})fullyVisible(home.findViewById(id),name+" visible "+id);
        for(int id:new int[]{R.id.energy_soc,R.id.energy_soc_note,R.id.energy_range,R.id.energy_range_note,R.id.energy_reference_row,R.id.trip_verdict,R.id.trip_performance,R.id.speed_value,R.id.trip_distance,R.id.trip_time,R.id.trip_average,R.id.trip_peak})primaryText(id,name);
        check(home.<ImageView>findViewById(R.id.energy_battery_icon).getDrawable()!=null,name+" battery icon draws");
        float references=home.<TextView>findViewById(R.id.energy_reference_row).getTextSize();
        check(home.<TextView>findViewById(R.id.energy_soc).getTextSize()>references*2,name+" battery % leads reference ranges");
        check(home.<TextView>findViewById(R.id.energy_range).getTextSize()>references*2,name+" current range leads reference ranges");
        check(home.<TextView>findViewById(R.id.speed_value).getTextSize()>references*2,name+" speed is a primary number");
        check(home.<TextView>findViewById(R.id.trip_verdict).getTextSize()>home.<TextView>findViewById(R.id.trip_performance).getTextSize(),name+" verdict leads explanation");
        View energy=home.findViewById(R.id.energy_card),media=home.findViewById(R.id.media_card);
        check((long)energy.getWidth()*energy.getHeight()>(long)media.getWidth()*media.getHeight(),name+" energy receives more space than media");
        int[] cards={R.id.energy_card,R.id.trip_card,R.id.telemetry_card,R.id.navigation_hint_card,R.id.calendar_card,R.id.media_card,R.id.dock};
        for(int a=0;a<cards.length;a++)for(int b=a+1;b<cards.length;b++){
            Rect first=new Rect(),second=new Rect();home.findViewById(cards[a]).getGlobalVisibleRect(first);home.findViewById(cards[b]).getGlobalVisibleRect(second);
            check(!Rect.intersects(first,second),name+" cards do not overlap: "+cards[a]+" and "+cards[b]);
        }
    }
    private void guidanceAndDriverSide(String name,LauncherPreferences preferences,HomeEnergySnapshot live)throws Exception{
        MediaAccessService.NavigationSnapshot route=new MediaAccessService.NavigationSnapshot(true,"com.google.android.apps.maps","Maps",
                new NavigationHintParser.Text("400 m","Turn right onto Baseline Road","12.3 km · 28 min · 11:45 ETA"),null,null,"dashboard-priority-route",System.currentTimeMillis());
        for(String side:new String[]{"right","left"}){
            preferences.setDriverSide(side);navigationState(route);
            ui(()->{
                invoke("setDrivingMode",new Class<?>[]{boolean.class},true);
                // Exercise the actual guidance renderer even if this emulator has no notification grant.
                setField(home,"activeNavigation",route);invoke("renderNavigationAppChip",new Class<?>[0]);
                invoke("renderGuidance",new Class<?>[]{MediaAccessService.NavigationSnapshot.class},route);
                invoke("applyCompactLayoutIfNeeded",new Class<?>[0]);home.renderHomeEnergy(live);
            });waitForIdleSync();SystemClock.sleep(150);
            ui(()->{
                layout(name+" guidance "+side);
                for(int id:new int[]{R.id.navigation_hint_title,R.id.navigation_hint_detail,R.id.navigation_hint_trip,R.id.navigation_eta})primaryText(id,name+" guidance "+side);
                text(R.id.navigation_hint_title,"400 m","Maneuver distance remains visible");text(R.id.navigation_eta,"11:45","Arrival time remains visible");
                ViewGroup panel=home.findViewById(R.id.dashboard_panel);
                check(panel.getChildAt(0)==home.findViewById("left".equals(side)?R.id.main_row:R.id.instrument_strip),name+" speed column follows driver side "+side);
            });capture("dashboard-priority-"+name+"-guidance-"+side);
        }
        preferences.setDriverSide("right");navigationState(MediaAccessService.NavigationSnapshot.NONE);
        ui(()->{setField(home,"lastGuidance",null);setField(home,"arrivalUntil",0L);invoke("setDrivingMode",new Class<?>[]{boolean.class},false);invoke("renderNavigationHints",new Class<?>[0]);invoke("applyCompactLayoutIfNeeded",new Class<?>[0]);});waitForIdleSync();
    }
    private void batteryStates(String name){
        int[] colours=new int[7];double[] levels={100,31,30,16,15,0,Double.NaN};
        for(int i=0;i<levels.length;i++){
            HomeEnergySnapshot data=snapshot(levels[i],214,false,performance(37.3));data.live=Double.isFinite(levels[i]);fixture(data,72);final int index=i;
            ui(()->{
                ImageView icon=home.findViewById(R.id.energy_battery_icon);check(icon.getImageTintList()!=null,name+" battery icon carries state colour");colours[index]=icon.getImageTintList().getDefaultColor();
                check(icon.getContentDescription()!=null&&icon.getContentDescription().length()>0,name+" battery level has accessible description");
            });
        }
        check(colours[0]==colours[1],name+" good level includes 31 and 100");check(colours[2]==colours[3],name+" moderate level includes 16 through 30");
        check(colours[4]==colours[5],name+" low level includes 0 through 15");
        check(colours[1]!=colours[2]&&colours[2]!=colours[4]&&colours[1]!=colours[4],name+" three battery levels have different colours");
        check(colours[6]!=colours[1]&&colours[6]!=colours[2]&&colours[6]!=colours[4],name+" unavailable battery is neutral");
        float[] hsv=new float[3];Color.colorToHSV(colours[1],hsv);check(hsv[0]>=80&&hsv[0]<=170,name+" good level is green");
        Color.colorToHSV(colours[2],hsv);check(hsv[0]>=35&&hsv[0]<=70,name+" moderate level is yellow");
        Color.colorToHSV(colours[4],hsv);check(hsv[0]<20||hsv[0]>340,name+" low level is red");
    }
    private void manualMode(String name)throws Exception{
        CarLinkController link=EnergyClient.link(getTargetContext());String previous=link.mode();
        try{
            link.setMode(CarLinkController.MODE_MANUAL);
            ui(()->{setField(home,"renderedEnergyKey",null);invoke("renderEnergyCard",new Class<?>[0]);});waitForIdleSync();SystemClock.sleep(100);
            ui(()->{
                check(!value(R.id.trip_verdict).equals("Overperforming"),name+" manual mode clears live verdict");
                contains(R.id.energy_reference_row,"entered battery level",name+" manual references identify entered SOC");
                primaryText(R.id.energy_soc,name+" manual SOC");primaryText(R.id.energy_range,name+" manual range");primaryText(R.id.energy_range_note,name+" manual provenance");
            });capture("dashboard-priority-"+name+"-manual");
        }finally{link.setMode(previous);}
    }
    private void weatherDetails(String name,LauncherPreferences preferences)throws Exception{
        preferences.setWeatherEnabled(true);ui(()->home.findViewById(R.id.calendar_card).performClick());waitForIdleSync();SystemClock.sleep(100);
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        check(root!=null&&!root.findAccessibilityNodeInfosByText("Local weather").isEmpty(),name+" weather tile opens details");
        java.util.List<android.view.accessibility.AccessibilityNodeInfo> close=root.findAccessibilityNodeInfosByText("Close");
        check(!close.isEmpty(),name+" forecast can be dismissed");capture("dashboard-priority-"+name+"-weather");
        check(close.get(0).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),name+" close weather details");waitForIdleSync();
    }
    private boolean verticalScroll(View view){if(view instanceof ScrollView)return true;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(verticalScroll(((ViewGroup)view).getChildAt(i)))return true;return false;}
    private void primaryText(int id,String label){
        TextView view=home.findViewById(id);fullyVisible(view,label+" text "+id);Layout layout=view.getLayout();
        check(layout!=null,label+" text laid out "+id);check(layout.getHeight()<=view.getHeight()-view.getTotalPaddingTop()-view.getTotalPaddingBottom()+1,label+" lines fit "+id+": layout="+layout.getHeight()+", view="+view.getHeight()+", text="+view.getTextSize());
        for(int line=0;line<layout.getLineCount();line++)check(layout.getEllipsisCount(line)==0,label+" no lost primary text "+id+": "+view.getText());
    }
    private void fullyVisible(View view,String label){
        check(view!=null&&view.isShown()&&view.getWidth()>0&&view.getHeight()>0,label+" is shown");Rect bounds=new Rect();
        check(view.getGlobalVisibleRect(bounds)&&bounds.width()==view.getWidth()&&bounds.height()==view.getHeight(),label+" fits screen "+bounds+" / "+view.getWidth()+"x"+view.getHeight());
    }
    private String value(int id){return home.<TextView>findViewById(id).getText().toString();}
    private void text(int id,String expected,String label){check(value(id).equals(expected),label+": "+value(id));}
    private void contains(int id,String expected,String label){check(value(id).contains(expected),label+": "+value(id));}
    private void check(boolean pass,String label){checks++;if(!pass)throw new AssertionError(label);}
    private Object field(Object owner,String name){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    private void setField(Object owner,String name,Object value){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}catch(Exception error){throw new AssertionError(error);}}
    private void navigationState(MediaAccessService.NavigationSnapshot snapshot){try{Field field=MediaAccessService.class.getDeclaredField("currentNavigation");field.setAccessible(true);field.set(null,snapshot);}catch(Exception error){throw new AssertionError(error);}}
    private void invoke(String name,Class<?>[] types,Object...args){try{Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);method.invoke(home,args);}catch(Exception error){throw new AssertionError(error);}}
    private void ui(Runnable action){Throwable[] failure={null};runOnMainSync(()->{try{action.run();}catch(Throwable error){failure[0]=error;}});if(failure[0]!=null)throw new AssertionError(failure[0].toString(),failure[0]);}
    private void capture(String name)throws Exception{Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap==null)throw new IllegalStateException("Screenshot unavailable");try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getFilesDir(),name+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}}
    private void shell(String command)throws Exception{try(ParcelFileDescriptor descriptor=getUiAutomation().executeShellCommand(command);FileInputStream input=new FileInputStream(descriptor.getFileDescriptor())){byte[] bytes=new byte[4096];while(input.read(bytes)!=-1){}}}
    @SuppressWarnings("unchecked") private void restore(Map<String,?> values){SharedPreferences.Editor edit=getTargetContext().getSharedPreferences(PREFS,0).edit().clear();for(Map.Entry<String,?> item:values.entrySet()){String key=item.getKey();Object value=item.getValue();if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));}edit.commit();}
}
