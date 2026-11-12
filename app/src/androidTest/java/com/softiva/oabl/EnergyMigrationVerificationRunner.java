package com.softiva.oabl;

import android.app.*;
import android.os.*;

/** Opens/migrates restored emulator data without starting collection or adding fixtures. */
public final class EnergyMigrationVerificationRunner extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();int code=Activity.RESULT_CANCELED;
        try{
            if(!Build.HARDWARE.equals("ranchu")&&!Build.HARDWARE.equals("goldfish"))throw new IllegalStateException("Emulator only");
            EnergyStore store=EnergyStore.get(getTargetContext());
            if(store.getReadableDatabase().getVersion()!=EnergyStore.VERSION||!store.getReadableDatabase().isReadOnly())throw new AssertionError("Owner migration/read-only facade");
            result.putString("stream","RESULT PASS · restored data opens at schema "+EnergyStore.VERSION+" through owner; UI handle read-only\n");code=Activity.RESULT_OK;
        }catch(Throwable failure){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(failure));}
        finish(code,result);
    }
}
