package com.softiva.oabl;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Emulator-only UI fixtures. No car, charge, trip or energy history is written by this runner. */
public final class AdaptiveDashboardTestRunner extends Instrumentation {
    private static final String PREFS="launcher_preferences";
    private static final String REFERENCES="Standard 273 km · NEDC 244 km\nFrom car";
    private MainActivity home;
    private int checks;
    private String onlySize;
    private long originalPromptCheck;

    @Override public void onCreate(Bundle args){super.onCreate(args);try{Field throttle=CarChargeDialogs.class.getDeclaredField("lastCheck");throttle.setAccessible(true);originalPromptCheck=throttle.getLong(null);throttle.setLong(null,Long.MAX_VALUE);}catch(Exception e){throw new RuntimeException(e);}onlySize=args==null?null:args.getString("size");start();}
    @Override public void callActivityOnResume(Activity activity){super.callActivityOnResume(activity);if(activity instanceof MainActivity)home=(MainActivity)activity;}

    @Override public void onStart(){
        Bundle result=new Bundle();boolean success=false;Map<String,?> saved=null;Context context=getTargetContext();
        MediaAccessService.NavigationSnapshot originalNavigation=MediaAccessService.navigationSnapshot();
        RoadSpeedLimit.Listener originalRoadListener=RoadSpeedLimit.get().listener;
        try{
            check("ranchu".equals(Build.HARDWARE)||"goldfish".equals(Build.HARDWARE),"Emulator only");
            saved=new HashMap<>(context.getSharedPreferences(PREFS,0).getAll());
            LauncherPreferences preferences=new LauncherPreferences(context);
            preferences.setScreensaverSeconds(0);preferences.setVisualEffects(false);preferences.setStartupNoticeEnabled(false);
            preferences.setOpenNavigationOnStart(false);preferences.setResumeMusicOnStart(false);preferences.setDrivingModeKmh(0);preferences.setDriverSide("right");
            // A newly installed launcher can schedule a one-shot relaunch before the first fixture.
            SystemClock.sleep(2500);
            for(String size:new String[]{"1920x898","1920x862","1920x970","1920x720","960x970"}){
                if(onlySize!=null&&!onlySize.equals(size))continue;
                closeHome();shell("wm size "+size);SystemClock.sleep(500);
                for(int theme:new int[]{LauncherPreferences.THEME_DARK,LauncherPreferences.THEME_LIGHT}){
                    preferences.setThemeMode(theme);openHome();MainActivity sameActivity=home;
                    String name=size+(theme==LauncherPreferences.THEME_DARK?"-dark":"-light");
                    // All four policy combinations, with every transition on the same Home instance.
                    state(false,false,true);verify(HomeDashboardMode.MEDIA,name);capture("adaptive-home-"+name+"-media");
                    state(false,true,true);verify(HomeDashboardMode.NORMAL,name);capture("adaptive-home-"+name+"-normal");
                    state(true,true,true);verify(HomeDashboardMode.NAVIGATION,name);capture("adaptive-home-"+name+"-navigation");
                    state(true,false,true);verify(HomeDashboardMode.NAVIGATION,name+" connection lost during route");
                    state(false,false,true);verify(HomeDashboardMode.MEDIA,name+" route ends while disconnected");
                    state(true,false,true);verify(HomeDashboardMode.NAVIGATION,name+" disconnected route starts");
                    state(true,true,true);verify(HomeDashboardMode.NAVIGATION,name+" link returns during route");
                    state(false,true,true);verify(HomeDashboardMode.NORMAL,name+" route ends while connected");
                    state(false,true,false);verify(HomeDashboardMode.NORMAL,name+" connected without SOC");
                    ui(()->{
                        text(R.id.energy_soc,"—%","Connected link with missing SOC stays unknown");
                        text(R.id.energy_range,"— km","Missing range stays unknown");
                        check(!value(R.id.energy_reference_row).contains("modelled"),"No entered-rating range modelling");
                    });capture("adaptive-home-"+name+"-connected-unknown");
                    for(String side:new String[]{"left","right"}){
                        preferences.setDriverSide(side);
                        for(HomeDashboardMode mode:HomeDashboardMode.values()){
                            state(mode==HomeDashboardMode.NAVIGATION,mode!=HomeDashboardMode.MEDIA,true);
                            verify(mode,name+" driver "+side);
                            ui(()->{
                                ViewGroup panel=home.findViewById(R.id.dashboard_panel);
                                check(panel.getChildAt(0)==home.findViewById("left".equals(side)?R.id.main_row:R.id.instrument_strip),name+" driving side "+side);
                            });
                            if("right".equals(side)){
                                ui(()->invoke("setDrivingMode",new Class<?>[]{boolean.class},true));
                                verify(mode,name+" driving mode entered");
                                ui(()->invoke("setDrivingMode",new Class<?>[]{boolean.class},false));
                                verify(mode,name+" driving mode exited");
                            }
                        }
                    }
                    check(home==sameActivity,name+" modes switch without recreating Home");closeHome();
                }
            }
            success=true;result.putString("stream","PASS · "+checks+" adaptive dashboard checks; navigation/connected/media, in-place transitions, connected missing SOC, requested sizes, light/dark and both driver sides. UI fixtures only; no energy history written.\n");
        }catch(Throwable error){try{if(home!=null)capture("adaptive-home-failure");}catch(Throwable ignored){}result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(error));}
        finally{try{Field throttle=CarChargeDialogs.class.getDeclaredField("lastCheck");throttle.setAccessible(true);throttle.setLong(null,originalPromptCheck);}catch(Exception ignored){}closeHome();navigationState(originalNavigation);ui(()->RoadSpeedLimit.get().listener=originalRoadListener);try{shell("wm size reset");}catch(Throwable ignored){}if(saved!=null)restore(saved);}
        finish(success?Activity.RESULT_OK:Activity.RESULT_CANCELED,result);
    }

    private void openHome(){
        navigationState(MediaAccessService.NavigationSnapshot.NONE);
        if(home==null || home.isDestroyed()) {
            runOnMainSync(()->getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            long until=SystemClock.uptimeMillis()+10000;
            while(home==null && SystemClock.uptimeMillis()<until)SystemClock.sleep(50);
            check(home!=null,"Home resumes within 10 seconds");
        }
        waitForIdleSync();SystemClock.sleep(300);
        ui(()->invoke("applyAppearance",new Class<?>[]{boolean.class},false));
        ui(()->{
            // External services keep running normally; this Activity receives only the explicit UI fixtures.
            setField(home,"foreground",false);
            if((boolean)field(home,"mediaReceiverRegistered")){
                home.unregisterReceiver((BroadcastReceiver)field(home,"mediaChangedReceiver"));setField(home,"mediaReceiverRegistered",false);
            }
            ((AutoThemeWatcher)field(home,"themeWatcher")).stop();
            ((SpeedTracker)field(home,"speedTracker")).stop();
            ((TravelStatusMonitor)field(home,"travelStatusMonitor")).stop();
            MusicClient.get(home).unobserve((Runnable)field(home,"ownPlaybackChanged"));
            Runnable changed=(Runnable)field(home,"energyChanged");EnergyJournal.get(home).unobserve(changed);EnergyClient.link(home).unobserve(changed);
            RoadSpeedLimit.get().listener=null;
            // This is Home's own Handler, not ViewRoot's frame/layout handler.
            ((Handler)field(home,"handler")).removeCallbacksAndMessages(null);
            home.<TextView>findViewById(R.id.media_title).setText("Midnight City");home.<TextView>findViewById(R.id.media_subtitle).setText("M83 · Hurry Up, We're Dreaming");
        });waitForIdleSync();
    }
    private void closeHome(){if(home==null)return;ui(()->((Handler)field(home,"handler")).removeCallbacksAndMessages(null));waitForIdleSync();}
    private void state(boolean navigating,boolean connected,boolean known){
        MediaAccessService.NavigationSnapshot route=navigating?new MediaAccessService.NavigationSnapshot(true,"com.google.android.apps.maps","Maps",
                new NavigationHintParser.Text("400 m","Turn right onto Baseline Road","12.3 km · 28 min · 11:45 ETA"),null,null,"adaptive-dashboard-route",System.currentTimeMillis()):MediaAccessService.NavigationSnapshot.NONE;
        navigationState(route);HomeEnergySnapshot data=new HomeEnergySnapshot();data.car=true;data.connected=connected;data.live=connected&&known;
        data.soc=known?68:Double.NaN;data.range=known?214:Double.NaN;data.ageSeconds=1;data.vehicle="NAMMI 1 · E3";
        data.usableCapacity=31.4;data.reservePercent=10;data.learnedKwhPerKm=.14;data.power=18.2;
        data.lowVoltage=new CarBatteryHealth.Status(13.8,false,false);
        data.referenceText=known?REFERENCES:"";
        CarEnergyTracker.Event drive=new CarEnergyTracker.Event();drive.kind="drive";drive.state="open";drive.before=78;drive.after=68;drive.rangeStart=260;
        drive.km=37.3;drive.movingSeconds=1800;drive.start=System.currentTimeMillis()-1800000;drive.last=System.currentTimeMillis();
        drive.regenKwh=.72;data.latestDrive=drive;
        data.cost=new HomeCost();data.cost.currency="LKR";data.cost.hasSession=true;data.cost.sessionKwh=3.14;data.cost.sessionCost=165;
        data.performance=CarDrivePerformance.evaluate(drive,System.currentTimeMillis(),data.live);
        ui(()->{
            ((Handler)field(home,"handler")).removeCallbacksAndMessages(null);
            home.<MediaProgressView>findViewById(R.id.media_progress).state(127000,244000,SystemClock.elapsedRealtime(),1f,false);
            setField(home,"home",data);home.renderHomeEnergy(data);invoke("renderCarConditions",new Class<?>[0]);setField(home,"activeNavigation",route);invoke("renderNavigationAppChip",new Class<?>[0]);
            if(navigating)invoke("renderGuidance",new Class<?>[]{MediaAccessService.NavigationSnapshot.class},route);
            else{setField(home,"lastGuidance",null);setField(home,"arrivalUntil",0L);invoke("renderIdleNavigation",new Class<?>[0]);}
            invoke("applyCompactLayoutIfNeeded",new Class<?>[0]);
            SpeedGaugeView gauge=home.findViewById(R.id.speed_cluster);gauge.reading(72,0,home.currentPalette());
            home.<TextView>findViewById(R.id.launcher_tagline).setText(connected?"CAR SPEED":"GPS SPEED");
            home.<TextView>findViewById(R.id.telemetry_label).setText(connected?"DRIVE · CAR SPEED":"DRIVE · GPS SPEED");
            home.<TextView>findViewById(R.id.current_road).setText("Baseline Road");
            home.<TextView>findViewById(R.id.trip_distance).setText("37.3");home.<TextView>findViewById(R.id.trip_time).setText("30");
            home.<TextView>findViewById(R.id.trip_average).setText("75");home.<TextView>findViewById(R.id.trip_peak).setText("83");
        });
    }
    private void verify(HomeDashboardMode expected,String name){settle(name);ui(()->{
        HomeLayout layout=(HomeLayout)field(home,"homeLayout");check(layout.mode()==expected,name+" mode is "+expected+", actual "+layout.mode());
        check(!verticalScroll(home.findViewById(R.id.home_content)),name+" has no page scrolling");
        for(int id:new int[]{R.id.telemetry_card,R.id.navigation_hint_card,R.id.calendar_card,R.id.media_card,R.id.dock,R.id.media_previous,R.id.media_play_pause,R.id.media_next})fullyVisible(home.findViewById(id),name+" visible "+id);
        if(expected!=HomeDashboardMode.NAVIGATION)fullyVisible(home.findViewById(R.id.media_expand),name+" media layout toggle");
        primaryText(R.id.speed_value,name+" speed");primaryText(R.id.media_title,name+" media title");
        check(home.<TextView>findViewById(R.id.speed_value).getLineCount()==1,name+" speed stays on one line");
        for(int id:new int[]{R.id.media_previous,R.id.media_play_pause,R.id.media_next}){
            View button=home.findViewById(id);check(button.isClickable()&&button.isFocusable()&&button.hasOnClickListeners(),name+" media control wired and focusable "+id);
            check(button.getContentDescription()!=null&&button.getContentDescription().length()>0,name+" labelled media control "+id);
            check(button.getWidth()>=dp(44)&&button.getHeight()>=dp(44),name+" media touch target "+id);
        }
        if(expected!=HomeDashboardMode.NAVIGATION){View button=home.findViewById(R.id.media_expand);check(button.isClickable()&&button.isFocusable()&&button.hasOnClickListeners(),name+" media layout toggle wired");}
        if(expected==HomeDashboardMode.NORMAL){
            check(((ViewGroup.MarginLayoutParams)home.findViewById(R.id.telemetry_card).getLayoutParams()).topMargin==0,name+" normal speed card resets its top gap after navigation");
            for(int id:new int[]{R.id.energy_card,R.id.trip_card,R.id.energy_battery_icon})fullyVisible(home.findViewById(id),name+" normal visible "+id);
            for(int id:new int[]{R.id.energy_soc,R.id.energy_range,R.id.trip_verdict})primaryText(id,name+" normal primary");
            if(home.findViewById(R.id.trip_performance).getVisibility()==View.VISIBLE)primaryText(R.id.trip_performance,name+" normal comparison detail");
            for(int id:new int[]{R.id.energy_usable,R.id.energy_reserve,R.id.energy_12v,R.id.trip_consumption,R.id.trip_energy,R.id.trip_regen,R.id.trip_cost})
                if(home.findViewById(id).getVisibility()==View.VISIBLE)primaryText(id,name+" EV stat");
            if(home.findViewById(R.id.energy_reference_row).getVisibility()==View.VISIBLE)primaryText(R.id.energy_reference_row,name+" normal references");
            for(int id:new int[]{R.id.trip_distance,R.id.trip_time,R.id.trip_average,R.id.trip_peak,R.id.trip_distance_label,R.id.trip_time_label,R.id.trip_average_label,R.id.trip_peak_label})primaryText(id,name+" trip metric");
            check(area(R.id.energy_card)>area(R.id.media_card),name+" normal energy dominates media");
            float references=home.<TextView>findViewById(R.id.energy_reference_row).getTextSize();
            check(home.<TextView>findViewById(R.id.energy_soc).getTextSize()>references*2,name+" battery number leads reference ranges");
            check(home.<ImageView>findViewById(R.id.energy_battery_icon).getDrawable()!=null,name+" battery draws");
            if(home.findViewById(R.id.energy_reference_row).getVisibility()==View.VISIBLE)
                for(String reference:new String[]{"Standard","NEDC"})contains(R.id.energy_reference_row,reference,name+" direct reference range");
            else check(home.findViewById(R.id.energy_reference_row).getVisibility()!=View.VISIBLE,name+" safely omits secondary reference ranges");
            check(!value(R.id.energy_reference_row).contains("modelled")&&!value(R.id.energy_reference_row).contains("entered"),name+" no entered reference estimates");
        }else if(expected==HomeDashboardMode.NAVIGATION){
            fullyVisible(home.findViewById(R.id.energy_card),name+" navigation battery");
            primaryText(R.id.energy_soc,name+" navigation battery number");
            check(!home.findViewById(R.id.trip_card).isShown(),name+" trip yields to navigation");
            check(area(R.id.navigation_hint_card)>area(R.id.energy_card)&&area(R.id.navigation_hint_card)>area(R.id.media_card),name+" navigation is dominant panel");
            for(int id:new int[]{R.id.navigation_hint_title,R.id.navigation_hint_detail,R.id.navigation_hint_trip,R.id.navigation_eta})primaryText(id,name+" guidance");
            text(R.id.navigation_hint_title,"400 m",name+" maneuver distance");text(R.id.navigation_eta,"11:45",name+" ETA");
            contains(R.id.navigation_hint_detail,"Baseline Road",name+" maneuver instruction");
        }else{
            check(!home.findViewById(R.id.energy_card).isShown()&&!home.findViewById(R.id.trip_card).isShown(),name+" unavailable energy yields to media");
            check(area(R.id.media_card)>area(R.id.navigation_hint_card)&&area(R.id.media_card)>area(R.id.telemetry_card),name+" media is dominant panel");
            fullyVisible(home.findViewById(R.id.media_artwork),name+" expanded artwork");
            check(home.findViewById(R.id.media_progress).getVisibility()==View.GONE,name+" Home progress stays hidden by design");
            check(home.findViewById(R.id.navigation_idle).isShown(),name+" idle navigation available");
        }
        if(expected!=HomeDashboardMode.MEDIA)check(home.findViewById(R.id.media_progress).getVisibility()==View.GONE,name+" compact media progress hidden");
        if(expected!=HomeDashboardMode.NAVIGATION) {
            for(int rowId:new int[]{R.id.navigation_chips,R.id.navigation_chips_2}) {
                ViewGroup row=home.findViewById(rowId);
                if(row.isShown())for(int i=0;i<row.getChildCount();i++) {
                    TextView tile=(TextView)row.getChildAt(i);fullyVisible(tile,name+" destination "+tile.getText());
                    check(tile.getHeight()>=dp(48),name+" destination touch height");
                    Layout text=tile.getLayout();check(text!=null && text.getHeight()<=tile.getHeight()-tile.getTotalPaddingTop()-tile.getTotalPaddingBottom()+1,name+" destination text fits");
                }
            }
        }
        int[] cards={R.id.energy_card,R.id.trip_card,R.id.telemetry_card,R.id.navigation_hint_card,R.id.calendar_card,R.id.media_card,R.id.dock};
        for(int a=0;a<cards.length;a++)for(int b=a+1;b<cards.length;b++){
            View first=home.findViewById(cards[a]),second=home.findViewById(cards[b]);if(!first.isShown()||!second.isShown())continue;
            Rect left=new Rect(),right=new Rect();first.getGlobalVisibleRect(left);second.getGlobalVisibleRect(right);
            check(!Rect.intersects(left,right),name+" cards do not overlap "+cards[a]+" and "+cards[b]);
        }
    });}
    /** Text auto-size and reparenting can schedule another traversal after the main queue is idle. */
    private void settle(String label){
        waitForIdleSync();CountDownLatch frames=new CountDownLatch(1);View root=home.findViewById(R.id.root);
        String[] pending={"No pre-draw frame"};ViewTreeObserver.OnPreDrawListener[] listener={null};
        ui(()->{
            listener[0]=new ViewTreeObserver.OnPreDrawListener(){
                private int stable;
                @Override public boolean onPreDraw(){
                    pending[0]=unsettled(root);stable=pending[0]==null?stable+1:0;
                    if(stable>=2){root.getViewTreeObserver().removeOnPreDrawListener(this);frames.countDown();}
                    else root.postOnAnimation(root::invalidate);
                    return true;
                }
            };
            root.getViewTreeObserver().addOnPreDrawListener(listener[0]);root.requestLayout();root.invalidate();
        });
        boolean ready;
        try{ready=frames.await(3,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new AssertionError(label+" layout wait interrupted",error);}
        ui(()->{if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(listener[0]);});
        check(ready,label+" layout settles within 3s; pending="+pending[0]);waitForIdleSync();
    }
    private String unsettled(View view){
        if(!view.isShown()||view.getWidth()<=0||view.getHeight()<=0)return null;
        if(view.isLayoutRequested())return "layout requested on "+view.getId();
        if(view instanceof TextView&&((TextView)view).getLayout()==null)return "text layout missing on "+view.getId();
        if(view instanceof ViewGroup)for(int index=0;index<((ViewGroup)view).getChildCount();index++){
            String pending=unsettled(((ViewGroup)view).getChildAt(index));if(pending!=null)return pending;
        }
        return null;
    }
    private int dp(int value){return Math.round(value*home.getResources().getDisplayMetrics().density);}
    private long area(int id){View view=home.findViewById(id);return (long)view.getWidth()*view.getHeight();}
    private boolean verticalScroll(View view){if(view instanceof ScrollView)return true;if(view instanceof ViewGroup)for(int index=0;index<((ViewGroup)view).getChildCount();index++)if(verticalScroll(((ViewGroup)view).getChildAt(index)))return true;return false;}
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
