package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Saves every OABL setting (look, dock, places, equalizer curves, music options, trips, service
 * reminders, the vehicle profile and the energy records) to one JSON file, e.g. on the SD card,
 * and restores it after a firmware reset.
 */
final class SettingsBackup {
    /** Preference files worth keeping; caches and per-boot state are left out. */
    static final String[] FILES = {"launcher_preferences", "equalizer_profile", "equalizer_ui", "music",
            "local_trip", "sun_area", "service_reminders", EnergyStore.PROFILE};

    private SettingsBackup() { }

    static void export(Context context, Uri target) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "oabl-settings").put("version", 2).put("saved", System.currentTimeMillis());
        JSONObject files = new JSONObject();
        for (String name : FILES) {
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> entry : ((name.equals(EnergyStore.PROFILE)||name.equals("local_trip"))&&!EnergyClient.owner(context)?(Map<String,?>)EnergyClient.call(context,"profile.preferences",name):context.getSharedPreferences(name, 0).getAll()).entrySet()) {
                Object value = entry.getValue();
                JSONObject typed = new JSONObject();
                if (value instanceof Boolean) typed.put("t", "b");
                else if (value instanceof Integer) typed.put("t", "i");
                else if (value instanceof Long) typed.put("t", "l");
                else if (value instanceof Float) typed.put("t", "f");
                else if (value instanceof Set) { typed.put("t", "s"); value = new JSONArray((Set<?>) value); }
                else typed.put("t", "str");
                typed.put("v", value instanceof Float ? Double.valueOf((Float) value) : value);
                values.put(entry.getKey(), typed);
            }
            files.put(name, values);
        }
        root.put("files", files);
        // A streaming energy archive avoids a Binder/JSON heap limit as retained raw data grows.
        File staged=File.createTempFile("settings-energy-",".json.gz",context.getCacheDir());
        try{
            EnergyArchive.perform(context,EnergyClient.store(context),EnergyArchive.document(context,Uri.fromFile(staged)),false,null);
            try(OutputStream output=context.getContentResolver().openOutputStream(target,"wt");ZipOutputStream zip=new ZipOutputStream(output)){
                zip.putNextEntry(new ZipEntry("settings.json"));zip.write(root.toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
                zip.putNextEntry(new ZipEntry("energy.json.gz"));try(InputStream in=new FileInputStream(staged)){byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1)zip.write(block,0,n);}zip.closeEntry();
            }
        }finally{staged.delete();}
    }

    /** Replaces the saved files' settings; returns how many settings were restored. */
    static int restore(Context context, Uri source) throws Exception {
        String text;File energyFile=null;
        try(java.io.BufferedInputStream input=new java.io.BufferedInputStream(context.getContentResolver().openInputStream(source))){
            input.mark(4);boolean zipped=input.read()==80&&input.read()==75;input.reset();
            if(zipped){
                text=null;
                try(ZipInputStream zip=new ZipInputStream(input)){
                    ZipEntry entry;
                    while((entry=zip.getNextEntry())!=null){
                        if(entry.getName().equals("settings.json")){
                            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[16384];int n;while((n=zip.read(block))!=-1){out.write(block,0,n);if(out.size()>8000000)throw new java.io.IOException("Settings metadata too large");}text=out.toString("UTF-8");
                        }else if(entry.getName().equals("energy.json.gz")){
                            if(energyFile!=null)throw new java.io.IOException("Repeated energy archive");
                            energyFile=File.createTempFile("settings-energy-restore-",".gz",context.getCacheDir());
                            try(OutputStream out=new FileOutputStream(energyFile)){byte[] block=new byte[65536];int n;while((n=zip.read(block))!=-1)out.write(block,0,n);}
                        }else throw new java.io.IOException("Unexpected settings archive entry");
                    }
                }
                if(text==null||energyFile==null)throw new java.io.IOException("Incomplete settings archive");
            }else{
                ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1){out.write(buffer,0,n);if(out.size()>8000000)throw new java.io.IOException("Too large");}text=out.toString("UTF-8");
            }
        }
        JSONObject root = new JSONObject(text);
        if (!"oabl-settings".equals(root.optString("format"))) throw new IllegalArgumentException("Not an OABL settings file");
        JSONObject files = root.getJSONObject("files");
        int restored = 0;
        if(energyFile!=null){
            File safety=File.createTempFile("settings-energy-safety-",".gz",context.getCacheDir());
            try{EnergyArchive.perform(context,EnergyClient.store(context),EnergyArchive.document(context,Uri.fromFile(safety)),false,energyFile);}
            finally{energyFile.delete();safety.delete();}
        }
        for (String name : FILES) {
            JSONObject values = files.optJSONObject(name);
            if (values == null) continue;
            if((name.equals(EnergyStore.PROFILE)||name.equals("local_trip"))&&!EnergyClient.owner(context)){restored+=(int)EnergyClient.call(context,"profile.restore",values,name);continue;}
            SharedPreferences.Editor editor = context.getSharedPreferences(name, 0).edit().clear();
            for (java.util.Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                String key = keys.next();
                JSONObject typed = values.getJSONObject(key);
                switch (typed.getString("t")) {
                    case "b": editor.putBoolean(key, typed.getBoolean("v")); break;
                    case "i": editor.putInt(key, typed.getInt("v")); break;
                    case "l": editor.putLong(key, typed.getLong("v")); break;
                    case "f": editor.putFloat(key, (float) typed.getDouble("v")); break;
                    case "s": {
                        JSONArray items = typed.getJSONArray("v");
                        java.util.Set<String> set = new java.util.HashSet<>();
                        for (int i = 0; i < items.length(); i++) set.add(items.getString(i));
                        editor.putStringSet(key, set);
                        break;
                    }
                    default: editor.putString(key, typed.getString("v"));
                }
                restored++;
            }
            editor.commit();
        }
        // Older backups have no energy records: the current ones are kept as they are.
        JSONObject energy = root.optJSONObject("energy");
        if (energy != null) restored += EnergyClient.store(context).restore(energy);
        return restored;
    }
}
