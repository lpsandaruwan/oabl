package com.softiva.oabl;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

/** Emulator-only visual/interaction regression; restores the native display size in finally. */
public final class MusicDesignTestRunner extends Instrumentation {
    private int checks;
    private MusicActivity activity;
    private final MusicTestControl player=new MusicTestControl(this);
    private volatile MainActivity resumedHome;
    @Override public void callActivityOnResume(Activity value){super.callActivityOnResume(value);if(value instanceof MainActivity)resumedHome=(MainActivity)value;}
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context c=getTargetContext();
        LauncherPreferences prefs=new LauncherPreferences(c);int oldTheme=prefs.themeMode();
        MusicLibrary library=MusicLibrary.get(c);MusicLibrary.Track first=null;
        try{
            check(Build.MODEL.contains("sdk_gphone"),"display tests restricted to emulator");
            shell("wm size reset");
            List<MusicLibrary.Track> songs=library.query("","Artist","All",0);
            check(!songs.isEmpty(),"seeded music fixtures available");first=songs.get(0);
            prefs.setThemeMode(LauncherPreferences.THEME_DARK);
            open();waitForSongs();
            MusicLibrary.Track selected=first;
            player.play(songs,selected.id);check(player.waitFor(()->player.current()==selected.id,5000),"selected song is current");
            ui(()->{
                View root=activity.getWindow().getDecorView();
                check(root.findViewWithTag("music_now_playing")!=null,"wide layout has artwork-led Now Playing");
                check(root.findViewWithTag("music_mini_player")==null,"wide layout has no duplicate MiniPlayer");
                MusicIconView rowStar=root.findViewWithTag("track_favorite_"+selected.id);
                check(rowStar!=null,"visible song has a favorite button");
                LinearLayout row=(LinearLayout)rowStar.getParent();
                check(row.getChildAt(row.getChildCount()-1)==rowStar,"favorite is far-right control in song row");
                check(!rowStar.isFocusable(),"row star does not take focus, so tapping the row plays the song");
                boolean old=library.track(selected.id).favorite;long playing=MusicClient.get(c).currentId();
                rowStar.performClick();
                check(library.track(selected.id).favorite!=old,"row star persists favorite immediately");
                check(MusicClient.get(c).currentId()==playing,"favorite tap does not start or skip playback");
                MusicIconView nowStar=root.findViewWithTag("now_favorite");
                check(nowStar.chosen()==library.track(selected.id).favorite,"Now Playing star stays synchronized");
                nowStar.performClick();check(library.track(selected.id).favorite==old,"Now Playing star toggles back");
                assertGeometry(root);
                check(!containsText(root,"Maps + music"),"Maps + music removed");
                check(!containsText(root,"Your collection. Your drive."),"placeholder marketing heading removed");
            });
            screenshot("music-ui-wide-dark.png");
            android.content.ContentValues lengthy=new android.content.ContentValues();
            lengthy.put("title","A Very Long Song Title — Live at the Midnight Festival, Extended Remastered Edition");
            lengthy.put("artist","An Artist With A Very Long Name & Special Guests");
            library.getWritableDatabase().update("tracks",lengthy,"_id=?",new String[]{""+selected.id});library.changed();
            SystemClock.sleep(500);ui(()->assertGeometry(activity.getWindow().getDecorView()));
            lengthy.put("title",selected.title);lengthy.put("artist",selected.artist);
            library.getWritableDatabase().update("tracks",lengthy,"_id=?",new String[]{""+selected.id});library.changed();
            finishMusic();
            prefs.setThemeMode(LauncherPreferences.THEME_LIGHT);open();waitForSongs();
            ui(()->assertGeometry(activity.getWindow().getDecorView()));screenshot("music-ui-wide-light.png");finishMusic();

            shell("wm size 960x970");SystemClock.sleep(500);
            prefs.setThemeMode(LauncherPreferences.THEME_DARK);open();waitForSongs();
            ui(()->{
                View root=activity.getWindow().getDecorView();
                check(root.findViewWithTag("music_mini_player")!=null,"half-width pane uses a MiniPlayer");
                check(root.findViewWithTag("music_now_playing")==null,"library is not crowded by full controls");
                assertGeometry(root);
            });
            screenshot("music-ui-half-dark.png");
            ui(()->activity.getWindow().getDecorView().findViewWithTag("music_mini_player").performClick());waitForIdleSync();
            ui(()->{
                View root=activity.getWindow().getDecorView();
                check(root.findViewWithTag("music_now_playing")!=null,"MiniPlayer expands to Now Playing");
                check(root.findViewWithTag("now_add_playlist")!=null,"Add to Playlist remains accessible");
                assertGeometry(root);
            });screenshot("music-ui-half-now-playing.png");finishMusic();
            prefs.setThemeMode(LauncherPreferences.THEME_LIGHT);open();waitForSongs();
            ui(()->assertGeometry(activity.getWindow().getDecorView()));screenshot("music-ui-half-light.png");finishMusic();
            shell("wm size reset");SystemClock.sleep(500);

            prefs.setSampleWallpaper(true);
            for(int mode:new int[]{LauncherPreferences.THEME_DARK,LauncherPreferences.THEME_LIGHT}){
                prefs.setThemeMode(mode);
                ui(()->c.startActivity(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
                long end=SystemClock.elapsedRealtime()+8000;
                while(resumedHome==null&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
                check(resumedHome!=null,"dashboard resumed for wallpaper preview");
                MainActivity previous=resumedHome;ui(previous::recreate);
                end=SystemClock.elapsedRealtime()+8000;
                while((resumedHome==previous||!resumedHome.hasWindowFocus())&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);
                MainActivity home=resumedHome;
                waitForIdleSync();SystemClock.sleep(700);
                ui(()->{
                    ImageView wallpaper=home.findViewById(R.id.wallpaper);
                    check(wallpaper.getDrawable()!=null,"sample desktop wallpaper is rendered");
                    check(prefs.sampleWallpaper(),"sample wallpaper selection is durable");
                });
                screenshot(mode==LauncherPreferences.THEME_DARK?"desktop-sample-dark.png":"desktop-sample-light.png");
            }
            result.putString("stream","PASS · "+checks+" design and touch-layout checks; wide and half-width, dark and light\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream","FAIL after "+checks+": "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
        finally{
            if(first!=null){android.content.ContentValues original=new android.content.ContentValues();original.put("title",first.title);original.put("artist",first.artist);library.getWritableDatabase().update("tracks",original,"_id=?",new String[]{""+first.id});}
            finishMusic();shell("wm size reset");prefs.setThemeMode(oldTheme);
            player.command("pause");player.stop();
        }
    }
    private void open(){activity=(MusicActivity)startActivitySync(new Intent(getTargetContext(),MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();SystemClock.sleep(600);}
    private void waitForSongs(){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){final boolean[] ready={false};ui(()->{ListView list=activity.getWindow().getDecorView().findViewWithTag("music_tracks");ready[0]=list!=null&&list.getChildCount()>0;});if(ready[0])return;SystemClock.sleep(100);}throw new AssertionError("Library list did not render");}
    private void finishMusic(){if(activity!=null){MusicActivity old=activity;ui(old::finish);activity=null;waitForIdleSync();}}
    private void assertGeometry(View root){
        Rect screen=new Rect();root.getGlobalVisibleRect(screen);
        ArrayList<View> actions=new ArrayList<>();collect(root,actions);
        for(View v:actions){
            if(inHorizontalScroll(v))continue;
            int[] point=new int[2];v.getLocationOnScreen(point);
            Rect full=new Rect(point[0],point[1],point[0]+v.getWidth(),point[1]+v.getHeight());
            // Scrolling song rows can be partially visible by design; fixed chrome cannot.
            if(!inSongList(v))check(screen.contains(full),"control stays inside pane: "+v.getContentDescription()+" "+full+" / "+screen);
            if(v instanceof MusicIconView)check(v.getWidth()>=dp(48)&&v.getHeight()>=dp(48),"icon touch target at least 48dp: "+v.getContentDescription()+" "+v.getWidth()+"x"+v.getHeight());
        }
        for(int i=0;i<actions.size();i++)for(int j=i+1;j<actions.size();j++){
            View a=actions.get(i),b=actions.get(j);if(a.getParent()!=b.getParent()||inHorizontalScroll(a)||inHorizontalScroll(b))continue;
            Rect ar=new Rect(),br=new Rect();a.getGlobalVisibleRect(ar);b.getGlobalVisibleRect(br);
            check(!Rect.intersects(ar,br),"sibling controls do not overlap");
        }
    }
    private int dp(int value){return Math.round(value*getTargetContext().getResources().getDisplayMetrics().density);}
    private void collect(View view,List<View> out){if(!view.isShown()||view.getWidth()==0||view.getHeight()==0)return;Rect rect=new Rect();if(!view.getGlobalVisibleRect(rect))return;if(view.isClickable())out.add(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),out);}}
    private boolean inHorizontalScroll(View v){android.view.ViewParent p=v.getParent();while(p!=null){if(p instanceof HorizontalScrollView)return true;p=p.getParent();}return false;}
    private boolean inSongList(View v){android.view.ViewParent p=v.getParent();while(p!=null){if(p instanceof ListView)return true;p=p.getParent();}return false;}
    private boolean containsText(View v,String term){if(v instanceof TextView&&((TextView)v).getText().toString().contains(term))return true;if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++)if(containsText(g.getChildAt(i),term))return true;}return false;}
    private void ui(Runnable task){Throwable[] error={null};runOnMainSync(()->{try{task.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void shell(String command){try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] bytes=new byte[1024];while(in.read(bytes)!=-1){}}catch(IOException e){throw new RuntimeException(e);}}
    private void screenshot(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
}
