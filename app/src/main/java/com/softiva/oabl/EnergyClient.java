package com.softiva.oabl;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.json.JSONObject;

/** Same-UID requests to the :energy owner. File payloads keep full archives off Binder's size limit. */
final class EnergyClient {
    static final Uri URI=Uri.parse("content://com.softiva.oabl.energy");
    static boolean owner(Context context){
        String name=android.os.Build.VERSION.SDK_INT>=28?android.app.Application.getProcessName():null;
        if(name==null){
            android.app.ActivityManager manager=(android.app.ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
            for(android.app.ActivityManager.RunningAppProcessInfo p:manager.getRunningAppProcesses())if(p.pid==android.os.Process.myPid())name=p.processName;
        }
        return (context.getPackageName()+":energy").equals(name);
    }
    static EnergyStore store(Context context){return EnergyStore.get(context);}
    static CarLinkController link(Context context){return CarLinkController.get(context);}
    static HomeEnergySnapshot home(Context context){return HomeEnergySnapshot.capture(context);}
    static void observe(Context context,Runnable changed){context.getContentResolver().registerContentObserver(URI,true,new ContentObserver(new Handler(Looper.getMainLooper())){
        @Override public void onChange(boolean self){changed.run();}
    });}
    static void changed(Context context){context.getContentResolver().notifyChange(URI,null);}
    static File directory(Context context)throws IOException{
        File dir=new File(context.getCacheDir(),"energy-ipc");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot open energy requests");return dir;
    }
    static Object call(Context context,String command,Object...args){
        File request=null,response=null;
        try{
            request=new File(directory(context),"request-"+java.util.UUID.randomUUID()+".bin");response=new File(request.getPath()+".reply");
            byte[] payload=encode(args);Bundle input=new Bundle();input.putString("file",request.getName());
            if(payload.length<=131072)input.putByteArray("payload",payload);else write(request,args);
            Bundle result=context.getContentResolver().call(URI,command,null,input);
            if(result==null)throw new IOException("Energy process unavailable");
            if(result.containsKey("error")){if(IllegalArgumentException.class.getName().equals(result.getString("error_type")))throw new IllegalArgumentException(result.getString("error"));throw new IllegalStateException(result.getString("error"));}
            Object[] reply=(Object[])(result.containsKey("payload")?decode(result.getByteArray("payload")):read(response));
            // A few existing store operations assign an ID to their input DTO.
            Object[] updated=(Object[])reply[1];
            for(int i=0;i<args.length;i++)if(args[i]!=null&&updated[i]!=null&&args[i].getClass().getName().startsWith("com.softiva.oabl.")){
                for(Field field:args[i].getClass().getDeclaredFields())if(!Modifier.isFinal(field.getModifiers())&&!Modifier.isStatic(field.getModifiers())){
                    field.setAccessible(true);field.set(args[i],field.get(updated[i]));
                }
            }
            return reply[0];
        }catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("Energy request failed: "+command,failure);}
        finally{if(request!=null)request.delete();if(response!=null)response.delete();}
    }
    private static final class JsonValue implements Serializable {final String value;JsonValue(JSONObject object){value=object.toString();}}
    private static final class LocationValue implements Serializable {
        final byte[] value;
        LocationValue(android.location.Location location){android.os.Parcel p=android.os.Parcel.obtain();try{location.writeToParcel(p,0);value=p.marshall();}finally{p.recycle();}}
        android.location.Location location(){android.os.Parcel p=android.os.Parcel.obtain();try{p.unmarshall(value,0,value.length);p.setDataPosition(0);return android.location.Location.CREATOR.createFromParcel(p);}finally{p.recycle();}}
    }
    static byte[] encode(Object value)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();write(out,value);return out.toByteArray();}
    static Object decode(byte[] value)throws Exception{return read(new ByteArrayInputStream(value));}
    static void write(File file,Object value)throws Exception{write(new FileOutputStream(file),value);}
    private static void write(OutputStream stream,Object value)throws Exception{
        try(ObjectOutputStream out=new ObjectOutputStream(new BufferedOutputStream(stream)){
            {enableReplaceObject(true);}
            @Override protected Object replaceObject(Object object){return object instanceof JSONObject?new JsonValue((JSONObject)object):object instanceof android.location.Location?new LocationValue((android.location.Location)object):object;}
        }){out.writeObject(value);}
    }
    static Object read(File file)throws Exception{return read(new FileInputStream(file));}
    private static Object read(InputStream stream)throws Exception{
        try(ObjectInputStream in=new ObjectInputStream(new BufferedInputStream(stream)){
            {enableResolveObject(true);}
            @Override protected Object resolveObject(Object object)throws IOException{
                try{return object instanceof JsonValue?new JSONObject(((JsonValue)object).value):object instanceof LocationValue?((LocationValue)object).location():object;}
                catch(Exception failure){throw new IOException(failure);}
            }
        }){return in.readObject();}
    }
}
