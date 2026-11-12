package com.softiva.oabl;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Seeded emulator: tag grouping, literal/exact filters, scoped queues, and browser navigation. */
public final class MusicBrowseTestRunner extends Instrumentation {
    private final MusicTestControl player=new MusicTestControl(this);
    private int checks;
    private volatile MusicActivity activity;
    private final ArrayList<ContentValues> originals=new ArrayList<>();
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void callActivityOnResume(Activity value){super.callActivityOnResume(value);if(value instanceof MusicActivity)activity=(MusicActivity)value;}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    @Override public void onStart(){
        Bundle result=new Bundle();int resultCode=Activity.RESULT_OK;Context c=getTargetContext();MusicLibrary library=MusicLibrary.get(c);
        SharedPreferences music=c.getSharedPreferences("music",0);Map<String,?> saved=music.getAll();
        LauncherPreferences prefs=new LauncherPreferences(c);int oldTheme=prefs.themeMode();
        try{
            check(Build.MODEL.contains("sdk_gphone"),"browser tests restricted to emulator");
            // Package replacement schedules this launcher's boot-restoration alarm after six seconds.
            // Let it complete before testing foreground navigation or taking screenshots.
            SystemClock.sleep(7000);shell("wm size reset");
            List<MusicLibrary.Track> all=library.query("","Filename","All",0);
            check(all.size()>=5,"seeded files available");
            List<MusicLibrary.Track> tagged=library.query("codec.flac","Title","All",0);
            check(!tagged.isEmpty()&&!tagged.get(0).artist.equals("Unknown artist"),"index has already identified embedded FLAC artist tags");
            long[] ids=new long[5];
            String[] artists={"Metallica","metallica","Metallica Tribute","100%_Metal","Unknown artist"};
            String[] albums={"Black","black","Tribute","Special",""};
            String[] folders={"Rock/Metal","Rock/Metal","Rock/Metal Live","Rock/Metal","Untagged"};
            for(int i=0;i<5;i++){
                MusicLibrary.Track t=all.get(i);ids[i]=t.id;
                ContentValues original=new ContentValues();original.put("_id",t.id);original.put("artist",t.artist);original.put("album",t.album);original.put("folder",t.folder);original.put("root",t.root);originals.add(original);
                ContentValues edit=new ContentValues();edit.put("artist",artists[i]);edit.put("album",albums[i]);edit.put("folder",folders[i]);edit.put("root",i==1?"content://com.softiva.oabl.musicfixtures/tree/cardB":"content://com.softiva.oabl.musicfixtures/tree/cardA");
                library.getWritableDatabase().update("tracks",edit,"_id=?",new String[]{""+ids[i]});
            }
            List<MusicLibrary.BrowseGroup> metal=library.groups("Artists","Metallica");
            check(metal.size()==2,"artist search returns artists, not individual songs");
            MusicLibrary.BrowseGroup selected=null;for(MusicLibrary.BrowseGroup g:metal)if(g.key.equalsIgnoreCase("Metallica"))selected=g;
            check(selected!=null&&selected.songs==2,"case variants share one artist");check(selected.albums==1,"album count is case insensitive");
            check(library.groups("Artists","100%_").size()==1,"artist search treats percent and underscore literally");
            check(!library.groups("Artists","Unknown artist").isEmpty(),"missing tags remain browsable as Unknown artist");
            Set<Long> expected=new HashSet<>(Arrays.asList(ids[0],ids[1]));
            for(String order:new String[]{"Artist","Folder","Title","Filename","Random"})
                check(trackIds(library.query("",order,"Artists",0,"METALLICA",null)).equals(expected),"artist drill-down is exact and supports "+order+" order");
            check(library.query("Metallica Tribute","Title","Artists",0,"Metallica",null).isEmpty(),"song search cannot escape selected artist");
            List<MusicLibrary.BrowseGroup> dirs=library.groups("Folders","Rock/Metal");
            check(dirs.size()==3,"same relative folders on separate roots do not merge");
            String cardA="content://com.softiva.oabl.musicfixtures/tree/cardA";
            check(trackIds(library.query("","Filename","Folders",0,"Rock/Metal",cardA)).equals(new HashSet<>(Arrays.asList(ids[0],ids[3]))),"folder drill-down matches exact path and root, not prefix");

            prefs.setThemeMode(LauncherPreferences.THEME_DARK);
            activity=(MusicActivity)startActivitySync(new Intent(c,MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            waitUi(()->list().getChildCount()>0,"song library visible");
            tap("music_tab_Artists");waitHeading("Artists");
            ui(()->{
                check(root().findViewWithTag("music_play_all").getVisibility()==View.GONE,"group view asks for an artist before Play");
                check(list().getAdapter().getItem(0) instanceof MusicLibrary.BrowseGroup,"artist browser shows groups");
                ((EditText)root().findViewWithTag("music_search")).setText("Metallica");
            });
            waitUi(()->list().getAdapter().getCount()==2,"artist search filters groups");screenshot("music-artists-dark.png");
            ui(()->{
                for(int i=0;i<list().getAdapter().getCount();i++){
                    MusicLibrary.BrowseGroup g=(MusicLibrary.BrowseGroup)list().getAdapter().getItem(i);
                    if(g.key.equalsIgnoreCase("Metallica")){list().performItemClick(list().getChildAt(i),i,list().getAdapter().getItemId(i));return;}
                }throw new AssertionError("artist not found");
            });
            waitUi(()->list().getAdapter().getCount()==2&&list().getAdapter().getItem(0) instanceof MusicLibrary.Track,"artist opens its songs");
            ui(()->{
                check(((EditText)root().findViewWithTag("music_search")).getText().length()==0,"artist search does not accidentally filter its songs");
                check(root().findViewWithTag("music_collection_back").isShown(),"artist has a visible return control");
                check(adapterIds().equals(expected),"UI contains only selected artist songs");
            });
            screenshot("music-artist-songs-dark.png");
            MusicActivity before=activity;ui(before::recreate);
            waitUi(()->activity!=before&&root().findViewWithTag("music_collection_back")!=null&&root().findViewWithTag("music_collection_back").isShown()&&list().getAdapter().getCount()==2,"artist selection survives recreation");
            ui(()->check(adapterIds().equals(expected),"recreated artist remains scoped"));
            tap("music_play_all");
            check(player.waitFor(()->new HashSet<>(player.queue()).equals(expected),12000),"Play queues selected artist only");
            tap("music_collection_back");waitHeading("Artists");
            ui(()->check("Metallica".contentEquals(((EditText)root().findViewWithTag("music_search")).getText()),"back restores artist search"));
            tap("music_tab_Folders");
            ui(()->((EditText)root().findViewWithTag("music_search")).setText("Rock/Metal"));
            waitUi(()->"Folders".contentEquals(((TextView)root().findViewWithTag("music_collection_title")).getText())&&list().getAdapter().getCount()==3,"folder browser renders indexed directories");
            ui(()->{
                for(int i=0;i<list().getAdapter().getCount();i++){
                    MusicLibrary.BrowseGroup g=(MusicLibrary.BrowseGroup)list().getAdapter().getItem(i);
                    if(g.key.equals("Rock/Metal")&&g.root.equals(cardA)){list().performItemClick(list().getChildAt(i),i,list().getAdapter().getItemId(i));return;}
                }throw new AssertionError("folder not found");
            });
            waitHeading("Rock/Metal");
            ui(()->check(adapterIds().equals(new HashSet<>(Arrays.asList(ids[0],ids[3]))),"folder UI only includes matching directory"));
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitHeading("Folders");
            ui(()->check(!activity.isFinishing(),"system Back returns to folders rather than exiting Music"));
            tap("music_tab_All");waitHeading("Songs");
            ui(()->check(root().findViewWithTag("music_collection_back").getVisibility()==View.GONE,"switching collection clears old artist/folder filter"));
            // Restore source tags before final screenshots; no test names are left in the user's library.
            restoreLibrary(library);library.changed();
            MusicLibrary.Track cached=library.query("01 - Test drive","Filename","All",0).get(0);
            Uri originalRoot=Uri.parse(cached.root);
            try{
                library.scan(DocumentsContract.buildTreeDocumentUri("com.softiva.oabl.musicfixtures","Test Artist"));waitScan(library);
                MusicLibrary.Track moved=library.track(cached.id);
                check("Test Album".equals(moved.folder),"cached scan refreshes relative path when selecting a subtree");
                check(cached.artist.equals(moved.artist)&&cached.duration==moved.duration,"cached subtree keeps identified tags and duration");
            }finally{library.scan(originalRoot);waitScan(library);}
            check(cached.folder.equals(library.track(cached.id).folder),"scanning original root restores original folder path without duplicates");
            tap("music_tab_Artists");waitHeading("Artists");screenshot("music-artists-dark.png");
            prefs.setThemeMode(LauncherPreferences.THEME_LIGHT);MusicActivity old=activity;ui(old::recreate);
            waitUi(()->activity!=old&&root().findViewWithTag("music_tab_Artists")!=null,"light layout recreated");
            tap("music_tab_Artists");waitHeading("Artists");screenshot("music-artists-light.png");
            ui(activity::finish);waitForIdleSync();
            shell("wm size 960x970");SystemClock.sleep(800);
            activity=(MusicActivity)startActivitySync(new Intent(c,MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            waitUi(()->root().findViewWithTag("music_mini_player")!=null,"artist browsing works in narrow pane");
            tap("music_tab_Artists");waitHeading("Artists");screenshot("music-artists-half-light.png");
            ui(()->{
                check(root().findViewWithTag("music_tab_Artists").isShown(),"Artists is directly accessible in narrow layout");
                for(int i=0;i<list().getChildCount();i++){View row=list().getChildAt(i);check(row.getWidth()<=list().getWidth(),"group rows fit pane");}
            });
            result.putString("stream","PASS · "+checks+" artist/folder browsing checks\n");
        }catch(Throwable error){resultCode=Activity.RESULT_CANCELED;result.putString("stream","FAIL after "+checks+": "+android.util.Log.getStackTraceString(error));}
        finally{
            restoreLibrary(library);if(activity!=null)ui(activity::finish);if(Build.MODEL.contains("sdk_gphone"))shell("wm size reset");prefs.setThemeMode(oldTheme);
            player.command("pause");player.stop();restore(music,saved);
        }
        finish(resultCode,result);
    }
    private void restoreLibrary(MusicLibrary library){for(ContentValues value:originals){ContentValues edit=new ContentValues(value);long id=edit.getAsLong("_id");edit.remove("_id");library.getWritableDatabase().update("tracks",edit,"_id=?",new String[]{""+id});}originals.clear();}
    private void waitScan(MusicLibrary library){long end=SystemClock.elapsedRealtime()+30000;while(library.scanning&&SystemClock.elapsedRealtime()<end)SystemClock.sleep(100);check(!library.scanning&&!library.scanStatus.startsWith("Scan "),"index scan completed");}
    private static Set<Long> trackIds(List<MusicLibrary.Track> rows){Set<Long> ids=new HashSet<>();for(MusicLibrary.Track t:rows)ids.add(t.id);return ids;}
    private Set<Long> adapterIds(){Set<Long> ids=new HashSet<>();for(int i=0;i<list().getAdapter().getCount();i++)ids.add(((MusicLibrary.Track)list().getAdapter().getItem(i)).id);return ids;}
    private View root(){return activity.getWindow().getDecorView();}
    private ListView list(){return root().findViewWithTag("music_tracks");}
    private void tap(String tag){ui(()->{View v=root().findViewWithTag(tag);check(v!=null&&v.performClick(),"action: "+tag);});}
    private void waitHeading(String title){waitUi(()->root().findViewWithTag("music_collection_title")!=null&&title.contentEquals(((TextView)root().findViewWithTag("music_collection_title")).getText()),"collection: "+title);}
    private void waitUi(BooleanSupplier condition,String message){long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){boolean[] ready={false};ui(()->ready[0]=condition.getAsBoolean());if(ready[0]){checks++;return;}SystemClock.sleep(100);}throw new AssertionError(message);}
    private void ui(Runnable task){Throwable[] error={null};runOnMainSync(()->{try{task.run();}catch(Throwable t){error[0]=t;}});if(error[0]!=null)throw new AssertionError(error[0]);}
    private void shell(String command){try(ParcelFileDescriptor fd=getUiAutomation().executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] bytes=new byte[1024];while(in.read(bytes)!=-1){}}catch(IOException e){throw new RuntimeException(e);}}
    private void screenshot(String name)throws Exception{waitForIdleSync();SystemClock.sleep(350);Bitmap b=getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}finally{b.recycle();}}
    private static void restore(SharedPreferences prefs,Map<String,?> values){SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){Object v=entry.getValue();String k=entry.getKey();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);}e.commit();}
}
