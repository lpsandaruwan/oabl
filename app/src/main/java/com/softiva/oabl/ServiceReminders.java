package com.softiva.oabl;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Distance-based service reminders (oil, tyres, filters). Distance comes from the box's own GPS
 * odometer, lined up once with the car's odometer reading so the numbers match the dashboard.
 */
final class ServiceReminders {
    /** Remind this far ahead of the due distance. */
    static final int AHEAD_KM = 300;

    static final class Item {
        String name;
        int everyKm;
        double doneAtKm;
        double dueAtKm() { return doneAtKm + everyKm; }
    }

    private final Context context;
    private final SharedPreferences prefs;

    ServiceReminders(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences("service_reminders", 0);
    }

    /** The car's odometer: the box's distance plus the offset set from the dashboard reading. */
    double odometerKm() {
        return TripComputer.get(context).odometerKm() + prefs.getFloat("offset_km", 0);
    }

    boolean odometerSet() { return prefs.contains("offset_km"); }

    void setOdometer(double carKm) {
        prefs.edit().putFloat("offset_km", (float) (carKm - TripComputer.get(context).odometerKm())).apply();
    }

    List<Item> items() {
        List<Item> items = new ArrayList<>();
        try {
            JSONArray all = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject o = all.getJSONObject(i);
                Item item = new Item();
                item.name = o.getString("name");
                item.everyKm = o.getInt("every");
                item.doneAtKm = o.getDouble("done");
                items.add(item);
            }
        } catch (Exception ignored) { }
        return items;
    }

    void save(List<Item> items) {
        JSONArray all = new JSONArray();
        try {
            for (Item item : items) all.put(new JSONObject().put("name", item.name).put("every", item.everyKm).put("done", item.doneAtKm));
        } catch (Exception ignored) { }
        prefs.edit().putString("items", all.toString()).apply();
    }

    /** Items due within {@link #AHEAD_KM} or already overdue. */
    List<Item> due() {
        List<Item> due = new ArrayList<>();
        double now = odometerKm();
        for (Item item : items()) if (item.dueAtKm() - now <= AHEAD_KM) due.add(item);
        return due;
    }

    String status(Item item) {
        double left = item.dueAtKm() - odometerKm();
        return left < 0 ? String.format(Locale.US, "Overdue by %,.0f km", -left)
                : String.format(Locale.US, "Due in %,.0f km · every %,d km", left, item.everyKm);
    }

    String summary() {
        List<Item> items = items();
        if (items.isEmpty()) return "Add oil, tyre and filter reminders";
        List<Item> due = due();
        return due.isEmpty() ? items.size() + " reminders · nothing due" : due.get(0).name + " · " + status(due.get(0));
    }

    // ---- management dialog (opened from Settings) ----

    static void manage(MainActivity host, LauncherTheme.Palette palette, Runnable changed) {
        ServiceReminders reminders = new ServiceReminders(host);
        if (!reminders.odometerSet()) { reminders.askOdometer(host, palette, () -> manage(host, palette, changed)); return; }
        List<Item> items = reminders.items();
        String[] rows = new String[items.size() + 2];
        for (int i = 0; i < items.size(); i++) rows[i] = items.get(i).name + "  ·  " + reminders.status(items.get(i));
        rows[items.size()] = "Add a reminder…";
        rows[items.size() + 1] = String.format(Locale.US, "Car odometer: %,.0f km · correct it…", reminders.odometerKm());
        new ThemedDialog(host, palette).setTitle("Service reminders").setItems(rows, (d, which) -> {
            if (which < items.size()) reminders.itemMenu(host, palette, items, which, () -> { changed.run(); manage(host, palette, changed); });
            else if (which == items.size()) reminders.add(host, palette, () -> { changed.run(); manage(host, palette, changed); });
            else reminders.askOdometer(host, palette, () -> { changed.run(); manage(host, palette, changed); });
        }).setNegativeButton("Close", null).show();
    }

    private void itemMenu(MainActivity host, LauncherTheme.Palette palette, List<Item> items, int index, Runnable done) {
        Item item = items.get(index);
        new ThemedDialog(host, palette).setTitle(item.name).setMessage(status(item))
                .setPositiveButton("Done today", (d, w) -> { item.doneAtKm = odometerKm(); save(items); done.run(); })
                .setNeutralButton("Delete", (d, w) -> { items.remove(index); save(items); done.run(); })
                .setNegativeButton("Back", (d, w) -> done.run()).show();
    }

    private void add(MainActivity host, LauncherTheme.Palette palette, Runnable done) {
        String[] presets = {"Engine oil · every 5,000 km", "Oil filter · every 10,000 km", "Tyre rotation · every 10,000 km",
                "Air filter · every 15,000 km", "Brake fluid · every 40,000 km", "Custom…"};
        String[] names = {"Engine oil", "Oil filter", "Tyre rotation", "Air filter", "Brake fluid"};
        int[] every = {5000, 10000, 10000, 15000, 40000};
        new ThemedDialog(host, palette).setTitle("Add a reminder").setItems(presets, (d, which) -> {
            if (which < names.length) { store(names[which], every[which]); done.run(); return; }
            LinearLayout form = new LinearLayout(host);
            form.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(24 * host.getResources().getDisplayMetrics().density);
            form.setPadding(pad, 0, pad, 0);
            EditText name = field(host, palette, "Name, e.g. Coolant", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            EditText km = field(host, palette, "Every how many km", InputType.TYPE_CLASS_NUMBER);
            form.addView(name);
            form.addView(km);
            new ThemedDialog(host, palette).setTitle("Custom reminder").setView(form)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Add", (x, y) -> {
                        try {
                            int distance = Integer.parseInt(km.getText().toString().trim());
                            if (!name.getText().toString().trim().isEmpty() && distance > 0) store(name.getText().toString().trim(), distance);
                        } catch (NumberFormatException ignored) { }
                        done.run();
                    }).show();
        }).show();
    }

    /** New reminders count from now; mark one done when it was serviced. */
    private void store(String name, int everyKm) {
        List<Item> items = items();
        Item item = new Item();
        item.name = name;
        item.everyKm = everyKm;
        item.doneAtKm = odometerKm();
        items.add(item);
        save(items);
    }

    void askOdometer(MainActivity host, LauncherTheme.Palette palette, Runnable done) {
        LinearLayout form = new LinearLayout(host);
        int pad = Math.round(24 * host.getResources().getDisplayMetrics().density);
        form.setPadding(pad, 0, pad, 0);
        EditText km = field(host, palette, "Odometer on the dashboard, in km", InputType.TYPE_CLASS_NUMBER);
        if (odometerSet()) km.setText(String.format(Locale.US, "%.0f", odometerKm()));
        form.addView(km, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new ThemedDialog(host, palette).setTitle("Car odometer")
                .setMessage("Enter the reading on the dashboard once. The box then counts on from it with GPS while it is on.")
                .setView(form)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    try { setOdometer(Double.parseDouble(km.getText().toString().trim())); done.run(); }
                    catch (NumberFormatException ignored) { }
                }).show();
    }

    private static EditText field(Context context, LauncherTheme.Palette palette, String hint, int type) {
        EditText field = new EditText(context);
        field.setHint(hint);
        field.setInputType(type);
        field.setSingleLine(true);
        field.setTextColor(palette.ink);
        field.setHintTextColor(palette.muted);
        return field;
    }
}
