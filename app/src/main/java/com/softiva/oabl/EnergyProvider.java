package com.softiva.oabl;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.util.concurrent.FutureTask;

/** Non-exported single writer. Mutations, settings and archive transactions execute in :energy. */
public final class EnergyProvider extends ContentProvider {
    @Override public boolean onCreate(){
        EnergyStore store=EnergyStore.get(getContext());store.getWritableDatabase();
        if(store.metaLong("summary_revision",0)!=6){new CarEnergyRepository(store).rebuildSummaries();store.setMeta("summary_revision","6");}
        return true;
    }
    @Override public Bundle call(String command,String ignored,Bundle input){
        if(android.os.Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException("Private energy interface");
        Bundle result=new Bundle();
        try{
            File dir=EnergyClient.directory(getContext()),request=new File(dir,input.getString("file",""));
            if(!request.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile())||!request.getName().startsWith("request-"))throw new SecurityException("Invalid energy request file");
            Object[] args=(Object[])(input.containsKey("payload")?EnergyClient.decode(input.getByteArray("payload")):EnergyClient.read(request));
            Object value;
            if(command.startsWith("link.")||command.startsWith("journal.")){
                FutureTask<Object> task=new FutureTask<>(()->EnergyDispatch.run(getContext(),command,args));
                if(Looper.myLooper()==Looper.getMainLooper())task.run();else new Handler(Looper.getMainLooper()).post(task);
                value=task.get();
            }else if(command.equals("archive")){
                value=EnergyArchive.perform(getContext(),EnergyStore.get(getContext()),EnergyArchive.document(getContext(),Uri.parse((String)args[0])),(Boolean)args[1],args[2]==null?null:new File((String)args[2]));
            }else value=EnergyDispatch.run(getContext(),command,args);
            // Any request other than Home's own read may have written charges (restore, reset, edits).
            if(!command.equals("home.snapshot"))CarEnergyRepository.chargesChanged();
            byte[] payload=EnergyClient.encode(new Object[]{value,args});
            if(payload.length<=131072)result.putByteArray("payload",payload);else EnergyClient.write(new File(request.getPath()+".reply"),new Object[]{value,args});
            if(command.startsWith("repository.save")||command.startsWith("repository.review")||command.equals("repository.join")||command.equals("archive"))EnergyClient.changed(getContext());
        }catch(Throwable failure){
            Throwable cause=failure instanceof java.util.concurrent.ExecutionException?failure.getCause():failure;
            android.util.Log.e("CarEnergy","Energy request failed: "+command,cause);result.putString("error",cause.toString());result.putString("error_type",cause.getClass().getName());
        }
        return result;
    }
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){throw new UnsupportedOperationException("Use energy requests");}
    @Override public String getType(Uri uri){return "application/vnd.oabl.energy";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
}
