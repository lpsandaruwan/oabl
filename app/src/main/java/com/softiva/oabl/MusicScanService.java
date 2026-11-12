package com.softiva.oabl;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;

/** A user-started local-file indexing job can finish while Maps is in front. */
public final class MusicScanService extends Service {
    private MusicLibrary library;
    private final Runnable progress=()->{
        if(library==null)return;
        if(!library.scanning){stopSelf();return;}
        getSystemService(NotificationManager.class).notify(4210,notification());
    };
    static void start(Context context,Uri root){
        Intent i=new Intent(context,MusicScanService.class).setData(root);
        try{if(Build.VERSION.SDK_INT>=26)context.startForegroundService(i);else context.startService(i);}
        catch(RuntimeException e){MusicLibrary.get(context).scanStatus="Open Music again to start indexing";MusicLibrary.get(context).changed();}
    }
    @Override public void onCreate(){super.onCreate();library=MusicLibrary.get(this);if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("music_library","Music library indexing",NotificationManager.IMPORTANCE_LOW));library.observe(progress);}
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(Build.VERSION.SDK_INT>=29)startForeground(4210,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);else startForeground(4210,notification());
        if(i==null||"cancel".equals(i.getAction())){library.cancelScan=true;stopSelf();return START_NOT_STICKY;}
        if(i.getData()==null){stopSelf();return START_NOT_STICKY;}
        library.scan(i.getData());return START_NOT_STICKY;
    }
    private Notification notification(){
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"music_library"):new Notification.Builder(this);
        PendingIntent open=PendingIntent.getActivity(this,91,new Intent(this,MusicActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,92,new Intent(this,MusicScanService.class).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(R.drawable.ic_stat_drive).setContentTitle("Indexing your music").setContentText(library.scanStatus).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setProgress(0,0,true).addAction(new Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel,"Stop scan",stop).build()).build();
    }
    @Override public void onTimeout(int id,int type){library.cancelScan=true;stopSelf();}
    @Override public void onDestroy(){library.unobserve(progress);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}
