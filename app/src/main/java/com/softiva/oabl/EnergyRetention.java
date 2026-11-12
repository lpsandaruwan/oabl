package com.softiva.oabl;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;

/** A new generation supersedes active records while retaining each original and all raw telemetry. */
final class EnergyRetention {
    static boolean raw(String table){return table.equals("car_sample")||table.equals("car_frame")||table.equals("energy_retained");}
    static void supersede(SQLiteDatabase db)throws Exception{
        long at=System.currentTimeMillis();
        for(String table:EnergyStore.TABLES)if(!raw(table)){
            retain(db,table,at);
            db.delete(table,null,null);
        }
    }
    static void retain(SQLiteDatabase db,String table,long at)throws Exception{
            try(Cursor c=db.query(table,null,null,null,null,null,null)){
                while(c.moveToNext()){
                    JSONObject original=new JSONObject();
                    for(int i=0;i<c.getColumnCount();i++){
                        Object value=c.isNull(i)?JSONObject.NULL:c.getType(i)==Cursor.FIELD_TYPE_INTEGER?c.getLong(i):c.getType(i)==Cursor.FIELD_TYPE_FLOAT?c.getDouble(i):c.getString(i);
                        original.put(c.getColumnName(i),value);
                    }
                    ContentValues row=new ContentValues();row.put("retained",at);row.put("table_name",table);row.put("data",original.toString());db.insertWithOnConflict("energy_retained",null,row,SQLiteDatabase.CONFLICT_IGNORE);
                }
            }
    }

    static void mergeRaw(SQLiteDatabase db,String table,ContentValues row){
        if(db.insertWithOnConflict(table,null,row,SQLiteDatabase.CONFLICT_IGNORE)!=-1)return;
        String key=table.equals("car_sample")?"seq":"t";
        try(Cursor c=db.query(table,new String[]{"data",table.equals("car_sample")?"t":"t"},"link=? AND "+key+"=?",new String[]{row.getAsString("link"),row.getAsString(key)},null,null,null)){
            if(!c.moveToFirst()||!c.getString(0).equals(row.getAsString("data"))||c.getLong(1)!=row.getAsLong("t"))throw new IllegalArgumentException("Archive conflicts with an immutable raw car record");
        }
    }

}
