package com.softiva.oabl;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.media.session.MediaController;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** Actual SAF traversal + Media3 decoding in the ":music" process, playback controls, saved library and DSP settings. */
public final class MusicTestRunner extends Instrumentation {
    private int checks;
    private void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();Context c=getTargetContext();
        SharedPreferences music=c.getSharedPreferences("music",0),session=c.getSharedPreferences(MusicService.STATE,Context.MODE_MULTI_PROCESS),eq=c.getSharedPreferences("equalizer_profile",0),launcher=c.getSharedPreferences("launcher_preferences",0);
        Map<String,?> savedMusic=music.getAll(),savedSession=session.getAll(),savedEq=eq.getAll(),savedLauncher=launcher.getAll();
        MusicActivity activity=null;MusicLibrary library=MusicLibrary.get(c);MusicTestControl player=new MusicTestControl(this);
        Uri root=DocumentsContract.buildTreeDocumentUri("com.softiva.oabl.musicfixtures","root");
        try{
            check(!MusicLibrary.audio("track.APE",null),"APE dropped: the box has no decoder for it");check(!MusicLibrary.audio("track.wma","audio/x-ms-wma"),"WMA dropped");check(MusicLibrary.audio("track.opus",null),"Opus recognized");check(MusicLibrary.audio("track.flac",null),"FLAC recognized");check(MusicLibrary.audio("track.m4a",null),"M4A recognized");check(!MusicLibrary.audio("notes.txt","text/plain"),"nonmusic excluded");
            File album=new File(c.getFilesDir(),"music-fixtures/Test Artist/Test Album");album.mkdirs();
            wav(new File(album,"01 - Test drive.wav"),40);wav(new File(album,"02 - Second track.wav"),40);wav(new File(album,"100%_mix.wav"),40);
            try(FileOutputStream out=new FileOutputStream(new File(album,"cover.png"))){Bitmap b=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(75,102,126));b.compress(Bitmap.CompressFormat.PNG,100,out);b.recycle();}
            library.scan(root);long until=SystemClock.elapsedRealtime()+60000;while(library.scanning&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
            check(!library.scanning,"recursive scan completes");
            List<MusicLibrary.Track> tracks=library.query("Test drive","Folder","All",0);check(tracks.size()==1,"recursive filename indexed");MusicLibrary.Track first=tracks.get(0);
            check(first.folder.equals("Test Artist/Test Album"),"relative folder retained");check(first.duration>=39000,"native duration parsed");check(first.art.contains("cover.png"),"folder art indexed");
            check(library.query("100%_mix","Title","All",0).size()==1,"search escapes wildcard characters");
            library.getWritableDatabase().execSQL("UPDATE tracks SET plays=0 WHERE _id=?",new Object[]{first.id});
            library.favorite(first.id,true);check(library.track(first.id).favorite,"manual star saved");
            library.played(first.id);library.played(first.id);library.played(first.id);check(library.query("Test drive","Title","Frequent",0).size()==1,"automatic favorite after three plays");
            long playlist=library.createPlaylist("OABL test drive");library.add(playlist,first.id);library.add(playlist,first.id);check(library.query("","Title","All",playlist).size()==1,"playlist insertion deduplicates");
            check(library.createPlaylist("oabl TEST drive")==playlist,"playlist names case insensitive");library.remove(playlist,first.id);check(library.query("","Title","All",playlist).isEmpty(),"playlist removal leaves file intact");library.add(playlist,first.id);
            library.scan(root);until=SystemClock.elapsedRealtime()+60000;while(library.scanning&&SystemClock.elapsedRealtime()<until)SystemClock.sleep(100);
            check(library.track(first.id).favorite&&library.track(first.id).plays==3,"rescan preserves stable IDs stars and plays");check(library.query("","Title","All",playlist).size()==1,"rescan preserves playlist");
            EqualizerProfile p=new EqualizerProfile(c);p.setMasterGain(2);p.setLinkWidth(1);p.setNormalization("track");p.setEnabled(true);p.flush();
            p.preset(0);p.setGain(4,6);check(p.inputGain()==-4.5f,"headroom covers the boost, less the +2 dB master gain");check(new EqualizerProfile(c).masterGain()==2,"master gain persists");check("track".equals(new EqualizerProfile(c).normalization()),"normalization persists");
            float[] flat=new float[EqualizerProfile.FREQUENCIES.length],linked=EqualizerProfile.linkedGains(flat,7,6,1);check(linked[7]==6&&linked[6]>0&&linked[6]<6,"linked curve moves adjacent frequencies");check(linked[0]<.001,"linked curve leaves distant bass alone");check(EqualizerProfile.linkedGains(flat,7,6,0)[6]==0,"independent mode isolates bands");
            launcher.edit().putInt("theme_mode",LauncherPreferences.THEME_DARK).commit();
            activity=(MusicActivity)startActivitySync(new Intent(c,MusicActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));waitForIdleSync();
            List<MusicLibrary.Track> queue=library.query(".wav","Folder","All",0);if(queue.isEmpty())queue=library.query("","Folder","All",0);
            final List<MusicLibrary.Track> playable=queue;player.play(playable,first.id);
            check(player.waitPlaying(true,15000),"Media3 playback starts from SAF URI");check(player.running(),"playback runs in the :music process");
            check(player.waitFor(()->player.position()>500,5000),"playback clock advances");
            check(player.waitFor(()->{MediaController controller=player.platformController();return controller!=null&&controller.getMetadata()!=null&&"01 - Test drive".equals(controller.getMetadata().getString(android.media.MediaMetadata.METADATA_KEY_TITLE));},5000),"dashboard metadata published without notification access");
            player.command("pause");check(player.waitPlaying(false,3000),"pause works");player.seek(10000);check(player.waitFor(()->player.position()>=9000,3000),"seek works");player.command("play");check(player.waitPlaying(true,3000),"resume works");
            player.command("next");check(player.waitFor(()->player.current()!=first.id,3000),"next track works");
            player.shuffle();check(player.waitFor(()->player.queue().size()==playable.size()&&player.queue().get(0)==player.current(),3000),"shuffle keeps the current song first and the queue's members");
            screenshot("music-dark.png");
            player.command("pause");check(player.waitPlaying(false,3000),"paused before the process ends");
            check(player.stop(),"music process ends");check(!player.playing(),"launcher sees playback stop");check(session.getLong("current",-1)>0,"resume track persisted");
            player.command("play");check(player.waitPlaying(true,8000),"saved queue resumes on explicit Play in a new music process");
            for(String extension:new String[]{"flac","mp3","m4a"}) {
                List<MusicLibrary.Track> sample=library.query("codec."+extension,"Title","All",0);
                check(sample.size()==1,"format fixture present: "+extension);
                player.play(sample,sample.get(0).id);
                check(player.waitFor(()->player.current()==sample.get(0).id&&player.playing(),12000),"platform decoder starts: "+extension);
                check(player.waitFor(()->player.position()>400,12000),"platform decoder advances: "+extension+" · "+player.error());
            }
            launcher.edit().putInt("theme_mode",LauncherPreferences.THEME_LIGHT).commit();MusicActivity old=activity;runOnMainSync(old::recreate);SystemClock.sleep(1800);screenshot("music-light.png");
            library.deletePlaylist(playlist);check(library.track(first.id)!=null,"deleting playlist leaves library and source intact");
            result.putString("stream","PASS · "+checks+" music checks\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){result.putString("stream","FAIL after "+checks+" checks: "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
        finally{player.command("pause");player.waitPlaying(false,3000);player.stop();restore(music,savedMusic);restore(session,savedSession);restore(eq,savedEq);restore(launcher,savedLauncher);}
    }
    private void screenshot(String name)throws Exception{Bitmap b=getUiAutomation().takeScreenshot();try(FileOutputStream o=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name))){b.compress(Bitmap.CompressFormat.PNG,100,o);}finally{b.recycle();}}
    private static void restore(SharedPreferences prefs,Map<String,?> values){SharedPreferences.Editor e=prefs.edit().clear();for(Map.Entry<String,?> entry:values.entrySet()){Object v=entry.getValue();String k=entry.getKey();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);}e.commit();}
    private static void wav(File file,int seconds)throws Exception{try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))){int size=48000*seconds*2;out.writeBytes("RIFF");le(out,size+36,4);out.writeBytes("WAVEfmt ");le(out,16,4);le(out,1,2);le(out,1,2);le(out,48000,4);le(out,96000,4);le(out,2,2);le(out,16,2);out.writeBytes("data");le(out,size,4);for(int i=0;i<size/2;i++)le(out,(int)(Math.sin(i*2*Math.PI*220/48000)*1000),2);}}
    private static void le(DataOutputStream out,int v,int bytes)throws Exception{for(int i=0;i<bytes;i++)out.writeByte((v>>>(i*8))&255);}
}
