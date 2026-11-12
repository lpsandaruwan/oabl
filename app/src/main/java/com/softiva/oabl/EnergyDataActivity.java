package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Energy-only retention tools. The driver chooses a durable external backup before reset/restore. */
public final class EnergyDataActivity extends Activity {
    private static final int CREATE=741,OPEN=742,BACKUP=0,RESET=1,RESTORE=2,COPY=3;
    static final AtomicBoolean BUSY=new AtomicBoolean();
    private static volatile String status="";
    private int operation;
    private Uri source;
    private String copyPath;
    private LauncherTheme.Palette palette;
    private LinearLayout body;
    private boolean wasBusy;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable refresh=new Runnable(){public void run(){if(wasBusy!=BUSY.get()){wasBusy=BUSY.get();render();}main.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);palette=LauncherTheme.resolve(this,new LauncherPreferences(this));
        if(state!=null){operation=state.getInt("operation");copyPath=state.getString("copy");String uri=state.getString("source");if(uri!=null)source=Uri.parse(uri);}
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(palette.root);
        body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);int pad=dp(24);body.setPadding(pad,pad,pad,pad);scroll.addView(body);setContentView(scroll);ImmersiveWindow.apply(getWindow(),scroll);render();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);state.putInt("operation",operation);state.putString("copy",copyPath);state.putString("source",source==null?null:source.toString());}
    @Override protected void onResume(){super.onResume();wasBusy=BUSY.get();render();main.post(refresh);}
    @Override protected void onPause(){main.removeCallbacks(refresh);super.onPause();}
    private void render(){
        body.removeAllViews();action("Back",this::finish);label("Energy data and backups",26);
        label("Backups include raw car samples, drives, stops, charges, check-ins, counters and the vehicle profile. No automatic backup deletion.",16);
        label("Reset starts a new history generation and totals; originals and raw car samples stay in the archive tables. Vehicle capacity, tariffs, pairing and other launcher settings stay. Live battery %, car odometer and reference ranges keep their actual values.",16);
        label("Collection: head unit polls about every 1 second; Wi-Fi streams changes and Bluetooth reads about every 2 seconds. Unchanged values are refreshed separately. Missing connection time is not invented.",16);
        label(BUSY.get()?"Saving and verifying backup. If verification fails, existing data is kept.":status,16);
        if(BUSY.get())return;
        action("Back up all energy data",()->{operation=BACKUP;create();});
        action("Reset energy history to zero…",()->challenge(RESET));
        action("Restore energy backup…",()->{
            Intent open=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try{startActivityForResult(open,OPEN);}catch(RuntimeException missing){message("No file picker is available. Existing data was kept.");}
        });
        label("Local backup copies · tap to export another copy",20);
        label("Local copies survive app updates, but not uninstall or clearing app data. Keep the external backup on storage you retain, such as an SD card, USB drive or cloud folder.",14);
        try{
            File[] files=EnergyArchive.directory(this).listFiles((dir,name)->name.endsWith(".json.gz"));
            if(files!=null){Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));for(File file:files)action(file.getName()+" · "+file.length()+" bytes",()->{operation=COPY;copyPath=file.getAbsolutePath();create();});}
        }catch(Exception failure){label("Cannot list local backups: "+failure.getMessage(),14);}
    }
    AlertDialog challenge(int op){
        String phrase="0000";
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);int pad=dp(20);form.setPadding(pad,0,pad,pad);
        TextView explanation=new TextView(this);explanation.setTextColor(palette.ink);explanation.setText(op==RESET
                ?"This starts new energy totals while keeping every original record. First choose where to save a complete backup. Nothing is cleared unless the saved copy passes verification.\n\nEnter "+phrase+" to continue."
                :"This activates the selected backup; current records are retained as a superseded generation. Current vehicle settings and pairing stay. First save a verified backup of today's data.\n\nEnter "+phrase+" to continue.");form.addView(explanation);
        EditText field=new EditText(this);field.setSingleLine(true);field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);field.setHint(phrase);field.setContentDescription("Confirmation phrase");form.addView(field);
        AlertDialog dialog=new ThemedDialog(this,palette).setTitle(op==RESET?"Back up and reset energy?":"Back up and restore energy?")
                .setView(form).setNegativeButton("Cancel",null).setPositiveButton("Choose backup location",null).show();
        Button confirm=dialog.getButton(AlertDialog.BUTTON_POSITIVE);confirm.setEnabled(false);
        field.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){confirm.setEnabled(phrase.equals(s.toString().trim()));}public void afterTextChanged(Editable s){}});
        confirm.setOnClickListener(v->{if(!phrase.equals(field.getText().toString().trim()))return;operation=op;dialog.dismiss();create();});return dialog;
    }
    private void create(){
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/gzip")
                .putExtra(Intent.EXTRA_TITLE,EnergyArchive.filename());
        try{startActivityForResult(save,CREATE);}catch(RuntimeException missing){message("No file picker is available. Existing data was kept.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null){if(request==CREATE||request==OPEN)message("Cancelled. Energy data was kept.");return;}
        if(request==OPEN){source=data.getData();challenge(RESTORE);}
        else if(request==CREATE){Uri target=data.getData();if(operation==RESTORE&&(source==null||source.equals(target))){message("Choose a different file for the safety backup. Existing data was kept.");return;}work(target);}
    }
    private void work(Uri target){
        if(!BUSY.compareAndSet(false,true))return;
        int op=operation;Uri restoreSource=source;String copy=copyPath;
        if(op!=COPY)EnergyJournal.get(this).prepareDataChange();
        wasBusy=true;render();android.content.Context context=getApplicationContext();
        new Thread(()->{
            File staged=null;
            try{
                EnergyArchive.Target output=EnergyArchive.document(context,target);
                if(op==COPY){EnergyArchive.copyVerified(new File(copy),output);status="Backup copy saved and verified. "+target;}
                else{
                    if(op==RESTORE)staged=EnergyArchive.stage(context,context.getContentResolver().openInputStream(restoreSource));
                    EnergyArchive.Result saved=(EnergyArchive.Result)EnergyClient.call(context,"archive",target.toString(),op==RESET,staged==null?null:staged.getPath());
                    status=(op==RESET?"Energy history reset. ":op==RESTORE?"Energy history restored. ":"Energy backup saved. ")+"Verified copy: "+target+"\nSHA-256: "+saved.sha256;
                }
            }catch(Exception failure){status="Operation failed; energy history was kept. "+(failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());}
            finally{
                if(staged!=null)staged.delete();BUSY.set(false);
                main.post(()->{EnergyJournal.get(context).ensureRunning();if(!ActivityState.isGone(this)){wasBusy=false;render();}});
            }
        },"energy-archive").start();
    }
    private void message(String text){status=text;render();}
    private void label(String value,int size){if(value.isEmpty())return;TextView t=new TextView(this);t.setText(value);t.setTextColor(palette.ink);t.setTextSize(size);t.setPadding(0,dp(8),0,dp(8));body.addView(t);}
    private void action(String title,Runnable run){Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextColor(palette.ink);b.setBackground(LauncherTheme.dockItem(this,palette));b.setOnClickListener(v->run.run());body.addView(b,new LinearLayout.LayoutParams(-1,dp(56)));}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
