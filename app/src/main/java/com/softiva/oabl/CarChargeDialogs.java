package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/** Confirms inferred charging and asks for site/tariff, without asking the user to re-enter SOC. */
final class CarChargeDialogs {
    private static AlertDialog active;
    private static long lastCheck;
    static void maybe(Activity activity, LauncherTheme.Palette palette, Runnable changed) {
        if(EnergyDataActivity.BUSY.get())return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastCheck < 5000 || activity.isFinishing() || !activity.hasWindowFocus() || (active != null && active.isShowing())) return;
        lastCheck = now;
        CarLinkController link = EnergyClient.link(activity);
        if (!link.expectsCarBattery() || !(link.liveValue(CarSignal.SPEED) >= 0 && link.liveValue(CarSignal.SPEED) <= 1)
                || link.liveValue(CarSignal.CHARGING) != 0) return;
        CarEnergyRepository.Item item = new CarEnergyRepository(EnergyClient.store(activity)).pending(true);
        if (item != null) show(activity,palette,item,changed);
    }
    /** Home's automatic prompt, with the candidate already read in Home's background snapshot. */
    static void prompt(Activity activity, LauncherTheme.Palette palette, CarEnergyRepository.Item item, Runnable changed) {
        if(EnergyDataActivity.BUSY.get())return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastCheck < 5000 || activity.isFinishing() || !activity.hasWindowFocus() || (active != null && active.isShowing())) return;
        lastCheck = now;
        show(activity,palette,item,changed);
    }
    static AlertDialog show(Activity activity, LauncherTheme.Palette palette, CarEnergyRepository.Item item, Runnable changed) {
        if (active != null && active.isShowing()) return active;
        EnergyStore store = EnergyClient.store(activity); CarEnergyRepository repository = new CarEnergyRepository(store);
        CarEnergyTracker.Event event = item.event;
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(pad,0,pad,pad);
        TextView reference = new TextView(activity); reference.setTextColor(palette.ink); reference.setTextSize(17);
        reference.setText(EnergyModel.percent(event.before) + " % → " + EnergyModel.percent(event.after) + " %  ·  +" + EnergyModel.percent(event.gain()) + " points\n"
                + (event.inferred ? (event.gap?"Battery rose while disconnected.":"Battery rose with READY off.")+" Confirm whether it was charged."
                + (event.offSeen ? " READY was off at the last observation." : " Car-off was not observed.") : "Charging was reported by the car.")
                + "\n" + EnergyDialogs.when(activity,event.end)); form.addView(reference);
        android.widget.CheckBox external = new android.widget.CheckBox(activity);
        external.setText("I used an external charger");
        external.setTextColor(palette.ink); external.setTextSize(16);
        external.setChecked(event.externalCharge && !event.inferred);
        if (!event.externalCharge || event.inferred) form.addView(external);
        Spinner site = new Spinner(activity); site.setContentDescription("Charging location");
        java.util.List<CarCharger> chargers=repository.chargers();
        java.util.List<String> names=new java.util.ArrayList<>(java.util.Arrays.asList("Choose location","Home","Outside / public · new charger"));
        for(CarCharger charger:chargers)names.add(charger.home?"Home · charger "+charger.id:charger.name.isEmpty()?"Charger "+charger.id:charger.name);
        site.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,names)); form.addView(site);
        EditText rate = field(activity,form,"Price per kWh, " + store.currency(),palette);
        EditText grid = field(activity,form,"Meter kWh, if the charger shows it (optional)",palette);

        EnergyModel.Charge existing = null;
        for (EnergyModel.Charge charge : store.charges()) if (charge.id == item.chargeId) existing = charge;
        if (existing != null) {
            site.setSelection(existing.place == EnergyModel.Place.HOME ? 1 : existing.place == EnergyModel.Place.PUBLIC ? 2 : 0);
            value(grid,existing.kwh); value(rate,existing.rate);
            for(int i=0;i<chargers.size();i++)if(chargers.get(i).id==existing.chargerId)site.setSelection(i+3);
        }
        if(existing==null){CarCharger nearest=repository.charger(event,false,0,false);for(int i=0;i<chargers.size();i++)if(chargers.get(i).id==nearest.id)site.setSelection(i+3);}
        final boolean editing = existing != null;
        final String[] homeAuto = {null};
        site.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if(view instanceof TextView)((TextView)view).setTextColor(palette.ink);
                if(position>0)reference.setError(null);
                if(position>0&&!editing){
                    long selected=position>=3?chargers.get(position-3).id:0;
                    boolean home=position==1||position>=3&&chargers.get(position-3).home;
                    rate.setText("");value(rate,repository.chargerRate(event,home,selected));
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        // The energy this charge will be billed on, and the running cost as the price is typed.
        CarChargeEnergy.Estimate whole = CarChargeEnergy.estimate(event, CarChargeEnergy.gridKwhPerPoint(store.charges()), store.profile().usableKwh);
        TextView total = new TextView(activity); total.setTextColor(palette.ink); total.setTextSize(22);
        TextView basis = new TextView(activity); basis.setTextColor(palette.muted); basis.setTextSize(13);
        form.addView(total); form.addView(basis);
        Runnable preview = () -> {
            double typed, tariff;
            try { typed = number(grid); tariff = number(rate); } catch (IllegalArgumentException invalid) { typed = Double.NaN; tariff = Double.NaN; }
            double kwh = EnergyModel.usable(typed) ? typed : whole.kwh;
            total.setText((Double.isFinite(kwh) ? String.format(java.util.Locale.US, "%.2f kWh", kwh) : "— kWh")
                    + (Double.isFinite(kwh) && Double.isFinite(tariff) ? String.format(java.util.Locale.US, "  ·  %s %s", EnergyModel.percent(Math.round(kwh * tariff * 100) / 100.0), store.currency()) : ""));
            basis.setText(EnergyModel.usable(typed) ? "Meter kWh, as you entered · the car's figure is kept beside it" : CarChargeEnergy.describe(whole, event));
        };
        android.text.TextWatcher watcher = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(android.text.Editable text) { preview.run(); }
        };
        grid.addTextChangedListener(watcher); rate.addTextChangedListener(watcher); preview.run();
        TextView note = new TextView(activity); note.setTextColor(palette.muted);
        note.setText("Price per kWh. Pre-filled with the last price at this charger; it stays with this charge.");
        form.addView(note);
        ScrollView scroll = new ScrollView(activity); scroll.addView(form);
        active = new ThemedDialog(activity,palette).setTitle(event.inferred ? "Was the car charged?" : "Charge complete · confirm the price")
                .setView(scroll).setPositiveButton("Save charge",null).setNegativeButton("Later",(d,w) -> repository.review(item,"later",item.chargeId,System.currentTimeMillis()+12*3600000L))
                .setNeutralButton("Not a charge / regen",(d,w) -> { repository.review(item,"ignored",item.chargeId,0); if(changed!=null)changed.run(); }).show();
        AlertDialog dialog = active;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                if (!external.isChecked()) { reference.setError("Confirm you used an external charger"); return; }
                if (site.getSelectedItemPosition()==0) { reference.setError("Choose Home or Outside"); return; }
                double energy = number(grid), tariff = number(rate);
                if(!Double.isFinite(tariff)){rate.setError("Confirm the price per kWh; use 0 for free charging");return;}
                if (energy==0) { grid.setError("Enter a positive value or leave blank"); return; }
                EnergyModel.Charge charge = new EnergyModel.Charge(); charge.time=event.end;charge.before=event.before;charge.after=event.after;
                int selected=site.getSelectedItemPosition();
                charge.chargerId=selected>=3?chargers.get(selected-3).id:0;
                charge.place=selected==1||selected>=3&&chargers.get(selected-3).home?EnergyModel.Place.HOME:EnergyModel.Place.PUBLIC;
                charge.kwh=energy;charge.rate=tariff;
                event.externalCharge = true;
                repository.saveCharge(item,charge);dialog.dismiss();if(changed!=null)changed.run();
            } catch (IllegalArgumentException invalid) { reference.setError(invalid.getMessage()); }
        });
        dialog.setOnCancelListener(d -> repository.review(item,"later",item.chargeId,System.currentTimeMillis()+12*3600000L));
        dialog.setOnDismissListener(d -> { active=null;if(changed!=null)changed.run(); });
        return dialog;
    }
    private static EditText field(Activity activity,LinearLayout parent,String label,LauncherTheme.Palette palette) {
        TextView caption=new TextView(activity);caption.setText(label);caption.setTextSize(12);caption.setTextColor(palette.muted);parent.addView(caption);
        EditText field=new EditText(activity);field.setHint(label);field.setContentDescription(label);field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);parent.addView(field);return field;
    }
    private static void value(EditText field,double value) { if(Double.isFinite(value))field.setText(EnergyModel.percent(value)); }
    private static double number(EditText field) {
        String value=field.getText().toString().trim();if(value.isEmpty())return Double.NaN;
        try { double result=Double.parseDouble(value);if(!Double.isFinite(result)||result<0||result>999999)throw new NumberFormatException();return result; }
        catch(NumberFormatException invalid){throw new IllegalArgumentException("Use a valid non-negative amount");}
    }
}
