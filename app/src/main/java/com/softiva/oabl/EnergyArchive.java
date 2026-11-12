package com.softiva.oabl;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;
import android.util.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.json.JSONObject;

/** Complete streaming archives. A verified external copy is mandatory before a destructive commit. */
final class EnergyArchive {
    interface Target { OutputStream write() throws Exception; InputStream read() throws Exception;default Uri uri(){return null;} }
    static Target document(Context context,Uri uri) {
        return new Target() {
            public Uri uri(){return uri;}
            public OutputStream write() throws Exception { OutputStream out=context.getContentResolver().openOutputStream(uri,"wt");if(out==null)throw new IOException("Cannot write backup");return out; }
            public InputStream read() throws Exception { InputStream in=context.getContentResolver().openInputStream(uri);if(in==null)throw new IOException("Cannot verify backup");return in; }
        };
    }
    static File directory(Context context) throws IOException {
        File dir=new File(context.getNoBackupFilesDir(),"energy-archives");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Cannot create energy backup folder");
        return dir;
    }
    static String filename() { return "oabl-energy-"+new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.ROOT).format(new Date())+"-"+UUID.randomUUID().toString().substring(0,8)+".json.gz"; }
    static final class Result implements java.io.Serializable { final File file; final String sha256; Result(File file,String hash){this.file=file;sha256=hash;} }

    /** Holds one database transaction so the archive and the cleared/restored dataset have no race. */
    static Result perform(Context context,EnergyStore store,Target backup,boolean reset,File restore) throws Exception {
        if(store.remote()){
            if(backup.uri()==null)throw new IllegalArgumentException("Cross-process archive needs a document URI");
            return (Result)EnergyClient.call(context,"archive",backup.uri().toString(),reset,restore==null?null:restore.getPath());
        }
        SQLiteDatabase db=store.getWritableDatabase();db.beginTransaction();
        try {
            File part=new File(directory(context),filename()+".part");
            write(context,db,part);
            String hash;try(InputStream in=new FileInputStream(part)){hash=digest(in);}
            File archive=new File(part.getParentFile(),part.getName().substring(0,part.getName().length()-5));
            if(!part.renameTo(archive))throw new IOException("Cannot finalize backup; data was kept");
            copyVerified(archive,backup);
            // A retained local copy is additional protection; the selected external copy survives uninstall.
            JSONObject receipt=new JSONObject().put("format","oabl-energy-receipt").put("sha256",hash)
                    .put("bytes",archive.length()).put("saved",System.currentTimeMillis());
            try(FileOutputStream out=new FileOutputStream(new File(archive.getPath()+".receipt.json"))){out.write(receipt.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            if(restore!=null){read(db,restore);new CarEnergyRepository(store).rebuildSummaries();}
            else if(reset)reset(store,db);
            db.setTransactionSuccessful();return new Result(archive,hash);
        }finally{db.endTransaction();}
    }
    private static void reset(EnergyStore store,SQLiteDatabase db) throws Exception {
        Map<String,Long> cursors=new HashMap<>();
        try(Cursor c=db.rawQuery("SELECT link,MAX(seq) FROM car_sample GROUP BY link",null)){while(c.moveToNext())cursors.put(c.getString(0),c.getLong(1));}
        try(Cursor c=db.rawQuery("SELECT key,value FROM meta WHERE key LIKE 'car_cursor:%'",null)){
            while(c.moveToNext()){String link=c.getString(0).substring(11);cursors.put(link,Math.max(cursors.getOrDefault(link,0L),Long.parseLong(c.getString(1))));}
        }
        EnergyRetention.supersede(db);
        store.setMeta("car_reset_after",Long.toString(System.currentTimeMillis()));
        for(Map.Entry<String,Long> cursor:cursors.entrySet())store.setMeta("car_cursor:"+cursor.getKey(),Long.toString(cursor.getValue()));
    }
    static void copyVerified(File file,Target target) throws Exception {
        String expected;try(InputStream in=new FileInputStream(file)){expected=digest(in);}
        try(InputStream in=new FileInputStream(file);OutputStream out=target.write()){copy(in,out);out.flush();}
        String actual;try(InputStream in=target.read()){actual=digest(in);}
        if(!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII)))
            throw new IOException("Backup verification failed; existing energy data was kept");
    }
    static File stage(Context context,InputStream source) throws IOException {
        if(source==null)throw new IOException("Cannot read selected backup");
        File temp=File.createTempFile("energy-restore-",".json.gz",context.getCacheDir());
        try(InputStream in=source;FileOutputStream out=new FileOutputStream(temp)){copy(in,out);out.getFD().sync();return temp;}
        catch(IOException failure){temp.delete();throw failure;}
    }
    private static void copy(InputStream in,OutputStream out) throws IOException { byte[] block=new byte[65536];int n;while((n=in.read(block))!=-1)out.write(block,0,n); }
    private static String digest(InputStream in) throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] block=new byte[65536];int n;
        while((n=in.read(block))!=-1)sha.update(block,0,n);
        StringBuilder result=new StringBuilder();for(byte b:sha.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static void write(Context context,SQLiteDatabase db,File file) throws Exception {
        try(FileOutputStream out=new FileOutputStream(file);
                GZIPOutputStream gzip=new GZIPOutputStream(out);
                JsonWriter w=new JsonWriter(new OutputStreamWriter(gzip,StandardCharsets.UTF_8))){
            w.beginObject().name("format").value("oabl-energy").name("version").value(1).name("schema").value(EnergyStore.VERSION)
                    .name("saved").value(System.currentTimeMillis()).name("profile").beginObject();
            for(Map.Entry<String,?> entry:context.getSharedPreferences(EnergyStore.PROFILE,0).getAll().entrySet()){
                w.name(entry.getKey());Object v=entry.getValue();
                if(v instanceof Number)w.value((Number)v);else if(v instanceof Boolean)w.value((Boolean)v);else w.value(String.valueOf(v));
            }
            w.endObject().name("tables").beginObject();Map<String,Long> counts=new LinkedHashMap<>();
            for(String table:EnergyStore.TABLES){
                w.name(table).beginArray();long count=0;
                try(Cursor c=db.query(table,null,null,null,null,null,null)){
                    while(c.moveToNext()){
                        w.beginObject();for(int i=0;i<c.getColumnCount();i++){
                            w.name(c.getColumnName(i));
                            switch(c.getType(i)){
                                case Cursor.FIELD_TYPE_NULL:w.nullValue();break;
                                case Cursor.FIELD_TYPE_INTEGER:w.value(c.getLong(i));break;
                                case Cursor.FIELD_TYPE_FLOAT:w.value(c.getDouble(i));break;
                                case Cursor.FIELD_TYPE_STRING:w.value(c.getString(i));break;
                                default:throw new IOException("Unsupported binary column; reset refused");
                            }
                        }w.endObject();count++;
                    }
                }w.endArray();counts.put(table,count);
            }
            w.endObject().name("row_counts").beginObject();for(Map.Entry<String,Long> count:counts.entrySet())w.name(count.getKey()).value(count.getValue());
            w.endObject().endObject();w.flush();gzip.finish();out.getFD().sync();
        }
    }
    /** Strict, streaming restore. The caller's transaction rolls back malformed or truncated archives. */
    private static void read(SQLiteDatabase db,File file) throws Exception {
        try(JsonReader r=new JsonReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(file)),StandardCharsets.UTF_8))){
            r.beginObject();expect(r,"format");if(!r.nextString().equals("oabl-energy"))throw new IOException("Not an OABL energy backup");
            expect(r,"version");if(r.nextInt()!=1)throw new IOException("Unknown backup version");
            expect(r,"schema");int schema=r.nextInt();if(schema<3||schema>EnergyStore.VERSION)throw new IOException("Unsupported energy schema");
            expect(r,"saved");r.nextLong();expect(r,"profile");r.skipValue();expect(r,"tables");r.beginObject();
            Set<String> remaining=new HashSet<>(Arrays.asList(EnergyStore.TABLES));
            if(schema<4)remaining.removeAll(Arrays.asList("charger","car_summary","car_drive_join","energy_retained"));
            Map<String,Long> counts=new HashMap<>();
            EnergyRetention.supersede(db);
            while(r.hasNext()){
                String table=r.nextName();if(!remaining.remove(table))throw new IOException("Unknown or repeated table");
                Set<String> columns=new HashSet<>();try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){while(c.moveToNext())columns.add(c.getString(1));}
                long count=0;r.beginArray();while(r.hasNext()){
                    ContentValues row=new ContentValues();Set<String> seen=new HashSet<>();r.beginObject();while(r.hasNext()){
                        String key=r.nextName();if(!columns.contains(key)||!seen.add(key))throw new IOException("Unknown or repeated column");
                        JsonToken type=r.peek();
                        if(type==JsonToken.NULL){r.nextNull();row.putNull(key);}
                        else if(type==JsonToken.STRING)row.put(key,r.nextString());
                        else if(type==JsonToken.NUMBER){String value=r.nextString();try{row.put(key,Long.parseLong(value));}catch(NumberFormatException decimal){double v=Double.parseDouble(value);if(!Double.isFinite(v))throw new IOException("Invalid numeric value");row.put(key,v);}}
                        else throw new IOException("Invalid energy value");
                    }r.endObject();Set<String> expected=new HashSet<>(columns);
                    if(schema<4&&table.equals("charge"))expected.removeAll(Arrays.asList("charger_id","measured_kwh","estimated_kwh","coverage_seconds","measured_complete","live"));
                    if(schema<4&&table.equals("car_event"))expected.remove("last_time");
                    if(!seen.equals(expected))throw new IOException("Incomplete energy row");
                    if(table.equals("energy_retained"))row.remove("id");
                    if(EnergyRetention.raw(table)&&!table.equals("energy_retained"))EnergyRetention.mergeRaw(db,table,row);else if(table.equals("energy_retained"))db.insertWithOnConflict(table,null,row,SQLiteDatabase.CONFLICT_IGNORE);else db.insertOrThrow(table,null,row);count++;
                }r.endArray();counts.put(table,count);
            }r.endObject();if(!remaining.isEmpty())throw new IOException("Incomplete backup tables");
            expect(r,"row_counts");r.beginObject();Set<String> verified=new HashSet<>();while(r.hasNext()){
                String table=r.nextName();long expected=r.nextLong();
                if(!counts.containsKey(table)||!verified.add(table)||counts.get(table)!=expected)throw new IOException("Backup row count mismatch");
            }r.endObject();if(verified.size()!=counts.size())throw new IOException("Incomplete backup counts");
            r.endObject();if(r.peek()!=JsonToken.END_DOCUMENT)throw new IOException("Unexpected backup content");
        }
    }
    private static void expect(JsonReader r,String key) throws IOException {if(!r.nextName().equals(key))throw new IOException("Invalid energy backup header");}
}
