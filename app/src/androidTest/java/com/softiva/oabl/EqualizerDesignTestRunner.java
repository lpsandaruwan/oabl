package com.softiva.oabl;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Emulator-only layout, touch, persistence, dialogs and logarithmic editor regression. */
public final class EqualizerDesignTestRunner extends Instrumentation {
    private volatile EqualizerActivity activity;
    private int checks;private long gestureDown;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnResume(Activity a){super.callActivityOnResume(a);if(a instanceof EqualizerActivity)activity=(EqualizerActivity)a;}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @Override public void onStart(){
        Context c=getTargetContext();Bundle result=new Bundle();int code=Activity.RESULT_OK;
        SharedPreferences savedPrefs=c.getSharedPreferences("equalizer_profile",0);Map<String,?> saved=savedPrefs.getAll();
        LauncherPreferences themes=new LauncherPreferences(c);int savedTheme=themes.themeMode();EqualizerProfile p=new EqualizerProfile(c);
        try{
            check(Build.MODEL.contains("sdk_gphone"),"visual tests restricted to emulator");
            SystemClock.sleep(7000);shell("wm size reset");
            p.takeFourteenBandNotice();p.preset(0);p.setEnabled(false);p.setMasterGain(0);p.setNormalization("none");p.setLinkWidth(0);p.flush();
            themes.setThemeMode(LauncherPreferences.THEME_DARK);open();
            ui(()->{
                check(count(root(),EqualizerCurveView.class)==1,"one frequency editor");
                check(root().findViewWithTag("eq_bands_31")==null&&root().findViewWithTag("eq_bands_15")==null,"fixed 31 bands: no band-count switch");
                check(curve().getWidth()>0&&new EqualizerModel(p).count()==31,"the one curve edits 31 bands");
                check(count(root(),SeekBar.class)==2,"master gain and the exciter, no duplicate faders");
                check(curve().getWidth()>1000&&curve().getHeight()>200,"curve has measured content");geometry(root());
            });
            tap("eq_link");choose("Linked · 1 octave");check(p.linkWidth()==1,"link mode persists from menu");
            tap("eq_band");choose("40 Hz · Depth");
            ui(()->check(curve().selected()==3,"frequency selector changes selected band"));
            tap("eq_band_plus");check(p.gain(3)==.5f&&p.gain(4)>0&&p.gain(4)<.5,"precise plus adjusts linked neighbors");
            tap("eq_band_minus");check(Math.abs(p.gain(3))<.001,"precise minus reverses selected gain");
            ui(()->{p.preset(0);curve().sync();curve().select(17);});
            float[] point=new float[2];ui(()->{int[] at=new int[2];curve().getLocationOnScreen(at);point[0]=at[0]+curve().bandX(17);point[1]=at[1]+curve().bandY(17);});
            touch(MotionEvent.ACTION_DOWN,point[0],point[1]);touch(MotionEvent.ACTION_MOVE,point[0],point[1]-52);touch(MotionEvent.ACTION_UP,point[0],point[1]-52);
            check(p.gain(17)>0&&p.gain(16)>0&&p.gain(16)<p.gain(17),"actual curve drag links neighboring bands");
            check(p.gain(0)<.001,"linked drag leaves distant bass unchanged");
            check(new EqualizerProfile(c).gain(17)==p.gain(17),"drag saves gain");
            float[] before=p.gains();tap("eq_reset");choose("Cancel");check(Arrays.equals(before,p.gains()),"cancel reset leaves curve intact");

            ui(()->root().findViewWithTag("eq_panel_output").performClick());SystemClock.sleep(150);
            ui(()->check(root().findViewWithTag("master_gain").isShown(),"Output panel shows gain and normalization"));
            tap("eq_normalization");choose("Choose");choose("Track ReplayGain");check("track".equals(new EqualizerProfile(c).normalization()),"normalization menu saves state");
            ui(()->check(root().findViewWithTag("peak_protection")==null,"no peak protection switch"));
            ui(()->((Switch)root().findViewWithTag("equalizer_enabled")).performClick());check(new EqualizerProfile(c).enabled(),"enabled switch saves state");
            ui(()->((Switch)root().findViewWithTag("equalizer_enabled")).performClick());check(!new EqualizerProfile(c).enabled(),"bypass saves without changing curve");
            check(Arrays.equals(before,p.gains()),"processing toggles do not change tone curve");
            float[] gainTouch=new float[3];ui(()->{SeekBar gain=root().findViewWithTag("master_gain");int[] at=new int[2];gain.getLocationOnScreen(at);gainTouch[0]=at[0]+gain.getWidth()*.6f;gainTouch[1]=at[1]+gain.getHeight()/2f;gainTouch[2]=at[0]+gain.getWidth()*.4f;});
            touch(MotionEvent.ACTION_DOWN,gainTouch[0],gainTouch[1]);touch(MotionEvent.ACTION_MOVE,gainTouch[2],gainTouch[1]);touch(MotionEvent.ACTION_UP,gainTouch[2],gainTouch[1]);
            check(p.masterGain()<0,"master gain responds to real touch");float savedGain=p.masterGain();
            tap("eq_reset");choose("Reset");check(p.gain(17)==0&&p.gain(0)==0,"reset clears bands");
            check(p.masterGain()==savedGain&&"track".equals(p.normalization()),"reset preserves output controls");
            tap("eq_preset");choose("Heavy");check("Heavy".equals(p.preset()),"preset menu applies and saves");
            ui(()->{curve().select(7);geometry(root());});screenshot("equalizer-ui-dark.png");
            EqualizerActivity prior=activity;ui(prior::recreate);
            waitUi(()->activity!=prior&&curve().getWidth()>0,"editor recreated");
            ui(()->check(curve().selected()==7,"selected frequency survives recreation: "+curve().selected()));
            check(p.masterGain()==savedGain&&p.linkWidth()==1,"configured output state survives recreation");
            close();themes.setThemeMode(LauncherPreferences.THEME_LIGHT);open();ui(()->geometry(root()));screenshot("equalizer-ui-light.png");close();
            shell("wm size 960x970");SystemClock.sleep(500);
            for(int mode:new int[]{LauncherPreferences.THEME_DARK,LauncherPreferences.THEME_LIGHT}){
                themes.setThemeMode(mode);open();ui(()->geometry(root()));screenshot(mode==LauncherPreferences.THEME_DARK?"equalizer-ui-narrow-dark.png":"equalizer-ui-narrow-light.png");
                float[] unchanged=p.gains();
                float[] swipe=new float[3];ui(()->{HorizontalScrollView sc=root().findViewWithTag("eq_curve_scroll");int[] at=new int[2];sc.getLocationOnScreen(at);swipe[0]=at[0]+sc.getWidth()-50;swipe[1]=at[1]+sc.getHeight()-20;swipe[2]=at[0]+60;});
                touch(MotionEvent.ACTION_DOWN,swipe[0],swipe[1]);for(int i=1;i<=8;i++){touch(MotionEvent.ACTION_MOVE,swipe[0]+(swipe[2]-swipe[0])*i/8,swipe[1]);SystemClock.sleep(35);}touch(MotionEvent.ACTION_UP,swipe[2],swipe[1]);SystemClock.sleep(350);
                ui(()->{HorizontalScrollView sc=root().findViewWithTag("eq_curve_scroll");check(curve().bandX(EqualizerProfile.FREQUENCIES.length-1)-sc.getScrollX()<=sc.getWidth(),"narrow graph shows up to 16 kHz without panning");});
                check(Arrays.equals(unchanged,p.gains()),"panning never changes saved gains");
                ui(()->check(!(root().findViewWithTag("equalizer_page") instanceof ScrollView),"EQ page never scrolls"));
                ui(()->root().findViewWithTag("eq_panel_output").performClick());SystemClock.sleep(400);
                ui(()->{View[] sheets;try{java.lang.reflect.Field f=EqualizerActivity.class.getDeclaredField("panels");f.setAccessible(true);sheets=(View[])f.get(activity);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
                    check(sheets[1].findViewWithTag("eq_normalization").getGlobalVisibleRect(new Rect()),"normalization reachable in narrow pane");check(sheets[1].findViewWithTag("master_gain").getGlobalVisibleRect(new Rect()),"master gain reachable in narrow pane");});
                if(mode==LauncherPreferences.THEME_LIGHT)screenshot("equalizer-ui-narrow-output.png");
                shell("input keyevent KEYCODE_BACK");SystemClock.sleep(300);ui(()->geometry(root()));
                if(mode==LauncherPreferences.THEME_LIGHT)screenshot("equalizer-ui-narrow-controls.png");close();
            }
            result.putString("stream","PASS · "+checks+" EQ design, interaction and persistence checks\n");
        }catch(Throwable t){code=Activity.RESULT_CANCELED;result.putString("stream","FAIL after "+checks+": "+android.util.Log.getStackTraceString(t));}
        finally{close();if(Build.MODEL.contains("sdk_gphone"))shell("wm size reset");themes.setThemeMode(savedTheme);restore(savedPrefs,saved);ui(()->SoundEqualizerService.refresh(c));}
        finish(code,result);
    }
    private View root(){return activity.getWindow().getDecorView();}
    private EqualizerCurveView curve(){return root().findViewWithTag("eq_curve");}
    private void open(){activity=(EqualizerActivity)startActivitySync(new Intent(getTargetContext(),EqualizerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();waitUi(()->curve()!=null&&curve().getWidth()>0&&activity.hasWindowFocus(),"EQ visible");}
    private void close(){if(activity!=null){EqualizerActivity old=activity;ui(old::finish);activity=null;waitForIdleSync();}}
    private void tap(String tag){ui(()->{View v=root().findViewWithTag(tag);check(v!=null&&v.performClick(),"tap "+tag);});waitForIdleSync();}
    private void choose(String label){
        long end=SystemClock.elapsedRealtime()+5000;
        while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo top=getUiAutomation().getRootInActiveWindow();if(top!=null){for(AccessibilityNodeInfo n:top.findAccessibilityNodeInfosByText(label)){
            if(n.getText()!=null&&label.equalsIgnoreCase(n.getText().toString())){AccessibilityNodeInfo click=n;while(click!=null&&!click.isClickable())click=click.getParent();if(click!=null&&click.performAction(AccessibilityNodeInfo.ACTION_CLICK)){checks++;waitForIdleSync();SystemClock.sleep(150);return;}}
        }scrollMenuUp(top);}SystemClock.sleep(100);}throw new AssertionError("menu choice not found: "+label);
    }
    private boolean scrollMenuUp(AccessibilityNodeInfo node){if("android.widget.ListView".contentEquals(node.getClassName()))return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child!=null&&scrollMenuUp(child))return true;}return false;}
    private int count(View v,Class<?> type){int n=type.isInstance(v)?1:0;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)n+=count(g.getChildAt(i),type);}return n;}
    private boolean inGraphScroll(View v){android.view.ViewParent p=v.getParent();while(p!=null){if(p instanceof HorizontalScrollView)return true;p=p.getParent();}return false;}
    private void geometry(View parent){
        Rect screen=new Rect();root().getGlobalVisibleRect(screen);ArrayList<View> controls=new ArrayList<>();collect(parent,controls);
        for(View v:controls){if(inGraphScroll(v))continue;int[] at=new int[2];v.getLocationOnScreen(at);
            check(at[0]>=screen.left&&at[0]+v.getWidth()<=screen.right,"control fits horizontally: "+v.getTag());
            check(v.getWidth()>=dp(48)&&v.getHeight()>=dp(48),"interactive target at least 48dp: "+v.getTag());
        }
        for(int i=0;i<controls.size();i++)for(int j=i+1;j<controls.size();j++){
            View a=controls.get(i),b=controls.get(j);if(a.getParent()!=b.getParent())continue;Rect ar=new Rect(),br=new Rect();a.getGlobalVisibleRect(ar);b.getGlobalVisibleRect(br);
            check(!Rect.intersects(ar,br),"sibling controls do not overlap");
        }
    }
    private void collect(View v,List<View> out){if(!v.isShown()||!v.getGlobalVisibleRect(new Rect()))return;if(v.isClickable())out.add(v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)collect(g.getChildAt(i),out);}}
    private int dp(int n){return Math.round(n*getTargetContext().getResources().getDisplayMetrics().density);}
    private void touch(int action,float x,float y){long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_DOWN)gestureDown=now;MotionEvent event=MotionEvent.obtain(gestureDown,now,action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);getUiAutomation().injectInputEvent(event,true);event.recycle();}
    private void waitUi(BooleanSupplier predicate,String message){long end=SystemClock.elapsedRealtime()+8000;while(SystemClock.elapsedRealtime()<end){boolean[] yes={false};ui(()->yes[0]=predicate.getAsBoolean());if(yes[0]){checks++;return;}SystemClock.sleep(100);}throw new AssertionError(message);}
    private void ui(Runnable task){Throwable[] error={null};runOnMainSync(()->{try{task.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void shell(String command){try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[1024];while(in.read(b)!=-1){}}catch(IOException e){throw new RuntimeException(e);}}
    private void screenshot(String name)throws Exception{waitForIdleSync();SystemClock.sleep(350);Bitmap b=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    private static void restore(SharedPreferences prefs,Map<String,?> values){SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){Object v=entry.getValue();String k=entry.getKey();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);}e.commit();}
}
