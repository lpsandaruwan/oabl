package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Battery check-in, end-of-drive, power-cut recovery, charge log, idle profile and reset dialogs,
 * shared by Home and the Energy page. The percentage is typed on a large on-screen pad (no
 * keyboard), it's never pre-filled from an earlier reading, and charging is always asked, never
 * assumed. The odometer, the car's trip computer (trip A, since this start: B runs since a reset and
 * C since the last charge) and the charge log are optional and skippable:
 * leaving them empty changes nothing.
 */
final class EnergyDialogs {
    private EnergyDialogs() { }

    interface Reading { void entered(double percent, EnergyModel.Charged charged); }

    /** Before a drive: the battery % now, whether the car was charged, and what's happening. */
    static AlertDialog checkIn(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        EnergyJournal journal = EnergyJournal.get(activity);
        EnergyJournal.Status status = journal.status();
        Form form = new Form(activity, palette, "Battery % on the car's display now",
                "Charged since the last check-in?", new String[]{"Not charged", "Charged", "Not sure"});
        int odometer = odometerField(form);
        EnergyStore.CheckIn previous = status.lastReading;
        if (previous != null) {
            form.hint("Last entered " + EnergyModel.percent(previous.percent) + " % · "
                    + when(activity, previous.time) + ". The car may have changed since.");
        }
        AlertDialog dialog = form.dialog("Battery check-in");
        form.action("Start a new drive", true, () -> {
            if (!form.complete(true)) return;
            long id = journal.startDrive(form.percent(), form.charged(), figures(form, odometer, -1, -1));
            dialog.dismiss();
            afterCheckIn(activity, palette, previous, form.percent(), form.charged(), id, done);
        });
        if (status.canContinue) {
            form.action("Continue the previous drive", false, () -> {
                if (!form.complete(false)) return;
                if (Double.isNaN(form.percent()) && !Double.isNaN(form.number(odometer))) {
                    form.fail("Enter the battery % too: the odometer is saved with it");
                    return;
                }
                long id = journal.continueDrive(form.percent(), form.charged(), figures(form, odometer, -1, -1));
                dialog.dismiss();
                afterCheckIn(activity, palette, previous, form.percent(), form.charged(), id, done);
            });
        }
        form.action("Not driving · bench use", false, () -> {
            journal.notDriving();
            dialog.dismiss();
            run(done);
        });
        form.action("Cancel", false, dialog::dismiss);
        form.show(dialog);
        return dialog;
    }

    /** End the active journey with the battery % now, or "I don't know". */
    static AlertDialog endDrive(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        EnergyJournal journal = EnergyJournal.get(activity);
        EnergyStore.CheckIn previous = journal.status().lastReading;
        Form form = new Form(activity, palette, "Battery % on the car's display now",
                "Charged during this drive?", new String[]{"Not charged", "Charged", "Not sure"});
        int odometer = odometerField(form);
        int tripKm = form.field("TRIP A KM", "Trip A distance, since this start", "km", 9999, true);
        int tripKwh = form.field("TRIP A AVG", "Trip A average, since this start", "kWh/100 km", 99, true);
        form.hint("Leave empty if unknown. The drive is kept without a battery result.");
        AlertDialog dialog = form.dialog("End this drive");
        form.action("End drive", true, () -> {
            if (!form.complete(!Double.isNaN(form.percent()))) return;
            EnergyModel.Charged charged = Double.isNaN(form.percent()) ? EnergyModel.Charged.UNKNOWN : form.charged();
            long id = journal.endDrive(form.percent(), charged, figures(form, odometer, tripKm, tripKwh));
            dialog.dismiss();
            afterCheckIn(activity, palette, previous, form.percent(), charged, id, done);
        });
        form.action("Keep driving", false, dialog::dismiss);
        form.show(dialog);
        return dialog;
    }

    /** The journey was cut off by a power loss: end it, continue it, or keep it as a trip only. */
    static void recovery(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        EnergyJournal journal = EnergyJournal.get(activity);
        EnergyJournal.Status status = journal.status();
        if (status.recovery == null) return;
        String title = "Unfinished drive from " + clock(activity, status.recovery.started)
                + " · " + String.format(Locale.US, "%.1f km", status.km()) + " recorded";
        new ThemedDialog(activity, palette).setTitle(title)
                .setItems(new String[]{
                        "End it · enter the battery % now",
                        "Continue it · the same drive goes on",
                        "Keep as trip only · no battery result"}, (d, which) -> {
                    if (which == 0) {
                        Form form = new Form(activity, palette, "Battery % on the car's display now",
                                "Charged since the box lost power?", new String[]{"Not charged", "Charged", "Not sure"});
                        int odometer = odometerField(form);
                        int tripKm = form.field("TRIP A KM", "Trip A distance, since this start", "km", 9999, true);
                        int tripKwh = form.field("TRIP A AVG", "Trip A average, since this start", "kWh/100 km", 99, true);
                        form.hint("The drive ends where the box last saved it. Leave the % empty if you don't know it.");
                        AlertDialog end = form.dialog("End the unfinished drive");
                        EnergyStore.CheckIn previous = status.lastReading;
                        form.action("End it", true, () -> {
                            if (!form.complete(!Double.isNaN(form.percent()))) return;
                            EnergyModel.Charged charged = Double.isNaN(form.percent()) ? EnergyModel.Charged.UNKNOWN : form.charged();
                            long id = journal.recoverEnd(form.percent(), charged, figures(form, odometer, tripKm, tripKwh));
                            end.dismiss();
                            afterCheckIn(activity, palette, previous, form.percent(), charged, id, done);
                        });
                        form.action("Back", false, () -> { end.dismiss(); recovery(activity, palette, done); });
                        form.show(end);
                    } else if (which == 1) {
                        journal.recoverContinue();
                        run(done);
                    } else {
                        journal.recoverTripOnly();
                        run(done);
                    }
                }).setNegativeButton("Later", null).show();
    }

    /** The drive started without a battery reading: add it now if the driver knows it. */
    static void startPercent(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        EnergyJournal journal = EnergyJournal.get(activity);
        Form form = new Form(activity, palette, "Battery % when this drive started", null, null);
        form.hint("Only if you know it. Otherwise end the drive and it is kept as distance only.");
        AlertDialog dialog = form.dialog("Starting battery %");
        form.action("Save", true, () -> {
            if (!form.complete(true)) return;
            journal.setStartPercent(form.percent());
            dialog.dismiss();
            run(done);
        });
        form.action("End this drive instead", false, () -> { dialog.dismiss(); endDrive(activity, palette, done); });
        form.action("Cancel", false, dialog::dismiss);
        form.show(dialog);
    }

    private static int odometerField(Form form) {
        form.tile("BATTERY %", false);
        return form.field("ODOMETER", "Odometer on the car's display", "km", 999_999, true);
    }

    /** The optional figures typed from the car's displays (−1 for a field the form doesn't have). */
    private static EnergyModel.CarFigures figures(Form form, int odometer, int tripKm, int tripKwh) {
        EnergyModel.CarFigures figures = new EnergyModel.CarFigures();
        if (odometer >= 0) figures.odometerKm = form.number(odometer);
        if (tripKm >= 0) figures.tripKm = form.number(tripKm);
        if (tripKwh >= 0) figures.tripKwhPer100Km = form.number(tripKwh);
        return figures;
    }

    /**
     * After a check-in that shows the battery rose, or that says the car was charged: offer the
     * charge log. Skipping it loses nothing; the check-in is already saved.
     */
    static void afterCheckIn(Activity activity, LauncherTheme.Palette palette, EnergyStore.CheckIn previous,
                             double percent, EnergyModel.Charged charged, long checkIn, Runnable done) {
        run(done);
        boolean rose = previous != null && !Double.isNaN(previous.percent) && !Double.isNaN(percent) && percent > previous.percent;
        if (!rose && charged != EnergyModel.Charged.YES) return;
        String reference = rose ? "The battery rose from " + EnergyModel.percent(previous.percent) + " % (entered "
                + when(activity, previous.time) + ") to " + EnergyModel.percent(percent) + " %." : "You said the car was charged.";
        new ThemedDialog(activity, palette).setTitle("Log the charge? · optional")
                .setMessage(reference + " A charge log adds cost per 100 km and a usable-capacity estimate. "
                        + "Every entry in it is optional, and nothing is filled in for you.")
                .setNegativeButton("Skip", null)
                .setPositiveButton("Log the charge", (d, w) -> chargeLog(activity, palette, checkIn, reference, done))
                .show();
    }

    /** The charge log: % before and after, grid kWh, cost or rate, AC or DC, home or public. All optional. */
    static AlertDialog chargeLog(Activity activity, LauncherTheme.Palette palette, long checkIn, String reference, Runnable done) {
        return chargeLog(activity, palette, checkIn, reference, done, null);
    }

    /** With {@code editing}, the form starts from that entry and saving replaces it (the original is kept). */
    static AlertDialog chargeLog(Activity activity, LauncherTheme.Palette palette, long checkIn, String reference, Runnable done,
                                 EnergyModel.Charge editing) {
        EnergyJournal journal = EnergyJournal.get(activity);
        String currency = journal.store().currency();
        Form form = new Form(activity, palette, "Battery % when the charge started", null, null).tile("% BEFORE", true);
        int after = form.field("% AFTER", "Battery % when the charge ended", "%", 100, true);
        int kwh = form.field("GRID kWh", "kWh from charger, wallbox or meter", "kWh", 150, true);
        int cost = form.field("COST", "What the charge cost in total", currency, 999_999, true);
        int rate = form.field("RATE / kWh", "Price per kWh, instead of a total", currency + "/kWh", 9_999, true);
        int current = form.question("Current", new String[]{"AC", "DC", "Not sure"});
        int place = form.question("Where", new String[]{"Home", "Public", "Not sure"});
        if (editing != null) {
            form.set(0, editing.before).set(after, editing.after).set(kwh, editing.kwh).set(cost, editing.cost).set(rate, editing.rate);
            form.choose(current, editing.current == EnergyModel.Current.AC ? 0 : editing.current == EnergyModel.Current.DC ? 1 : -1);
            form.choose(place, editing.place == EnergyModel.Place.HOME ? 0 : editing.place == EnergyModel.Place.PUBLIC ? 1 : -1);
            form.hint("Fix a typo. The original stays in the record.");
        } else {
            form.hint((reference == null ? "" : reference + " ") + "Fill in what you know. A total cost or a rate is enough.");
        }
        AlertDialog dialog = form.dialog(editing != null ? "Edit charge · " + when(activity, editing.time) : "Charge log · all optional");
        form.action("Save charge", true, () -> {
            if (!form.complete(false)) return;
            EnergyModel.Charge charge = new EnergyModel.Charge();
            charge.checkIn = editing != null ? editing.checkIn : checkIn;
            if (editing != null) charge.time = editing.time; // a correction keeps when it happened
            charge.before = form.percent();
            charge.after = form.number(after);
            charge.kwh = form.number(kwh);
            charge.cost = form.number(cost);
            charge.rate = form.number(rate);
            int c = form.choice(current), w = form.choice(place);
            charge.current = c == 0 ? EnergyModel.Current.AC : c == 1 ? EnergyModel.Current.DC : EnergyModel.Current.UNKNOWN;
            charge.place = w == 0 ? EnergyModel.Place.HOME : w == 1 ? EnergyModel.Place.PUBLIC : EnergyModel.Place.UNKNOWN;
            if (charge.empty()) { form.fail("Enter at least one value, or skip"); return; }
            if (charge.gain() <= 0) { form.fail("% after should be above % before"); return; }
            if (charge.kwh == 0) { form.fail("Leave the kWh empty if you don't know it"); return; }
            if (editing != null) journal.editCharge(editing.id, charge); else journal.logCharge(charge);
            dialog.dismiss();
            run(done);
        });
        form.action(checkIn > 0 && editing == null ? "Skip" : "Cancel", false, dialog::dismiss);
        form.show(dialog);
        return dialog;
    }

    /** One logged charge: edit it, or remove it. Nothing is deleted; Removed entries can bring it back. */
    static void charge(Activity activity, LauncherTheme.Palette palette, EnergyModel.Charge charge, String summary, Runnable done) {
        new ThemedDialog(activity, palette).setTitle("Charge · " + when(activity, charge.time))
                .setMessage(summary)
                .setNegativeButton("Close", null)
                .setNeutralButton("Remove", (d, w) -> confirmRemove(activity, palette, "this charge",
                        () -> { EnergyJournal.get(activity).withdrawCharge(charge.id); run(done); }))
                .setPositiveButton("Edit", (d, w) -> chargeLog(activity, palette, 0, null, done, charge))
                .show();
    }

    /** A battery reading: edit its %, the "charged since?" answer and the car figures, or remove it. */
    static void reading(Activity activity, LauncherTheme.Palette palette, EnergyStore.CheckIn reading, Runnable done) {
        if (reading == null) return;
        String what = (Double.isNaN(reading.percent) ? "no %" : EnergyModel.percent(reading.percent) + " %")
                + " · " + reading.kind + " · " + when(activity, reading.time);
        new ThemedDialog(activity, palette).setTitle("Battery reading · " + what)
                .setMessage("Charged since the reading before: " + chargedText(reading.charged)
                        + (Double.isNaN(reading.odometerKm) ? "" : "\nOdometer " + EnergyModel.percent(reading.odometerKm) + " km"))
                .setNegativeButton("Close", null)
                .setNeutralButton("Remove", (d, w) -> confirmRemove(activity, palette, "this reading",
                        () -> { EnergyJournal.get(activity).removeReading(reading.id); run(done); }))
                .setPositiveButton("Edit", (d, w) -> editReading(activity, palette, reading, done))
                .show();
    }

    private static String chargedText(EnergyModel.Charged charged) {
        return charged == EnergyModel.Charged.YES ? "charged" : charged == EnergyModel.Charged.NO ? "not charged" : "not sure";
    }

    private static void editReading(Activity activity, LauncherTheme.Palette palette, EnergyStore.CheckIn reading, Runnable done) {
        EnergyJournal journal = EnergyJournal.get(activity);
        Form form = new Form(activity, palette, "Battery % the car showed then",
                "Charged since the reading before?", new String[]{"Not charged", "Charged", "Not sure"});
        int odometer = odometerField(form);
        boolean end = "end".equals(reading.kind);
        int tripKm = end ? form.field("TRIP A KM", "Trip A distance, since this start", "km", 9999, true) : -1;
        int tripKwh = end ? form.field("TRIP A AVG", "Trip A average, since this start", "kWh/100 km", 99, true) : -1;
        form.set(0, reading.percent).set(odometer, reading.odometerKm);
        if (end) form.set(tripKm, reading.tripKm).set(tripKwh, reading.tripKwhPer100Km);
        form.choose(0, reading.charged == EnergyModel.Charged.NO ? 0 : reading.charged == EnergyModel.Charged.YES ? 1 : 2);
        form.hint("Fix a typo. The original stays in the record and every figure updates.");
        AlertDialog dialog = form.dialog("Edit reading · " + when(activity, reading.time));
        form.action("Save correction", true, () -> {
            if (!form.complete(false)) return;
            EnergyModel.Charged charged = Double.isNaN(form.percent()) ? EnergyModel.Charged.UNKNOWN : form.charged();
            journal.correctReading(reading.id, form.percent(), charged, figures(form, odometer, tripKm, tripKwh));
            dialog.dismiss();
            run(done);
        });
        form.action("Cancel", false, dialog::dismiss);
        form.show(dialog);
    }

    private static void confirmRemove(Activity activity, LauncherTheme.Palette palette, String what, Runnable remove) {
        new ThemedDialog(activity, palette).setTitle("Remove " + what + "?")
                .setMessage("It stops counting in every figure. Nothing is deleted: it stays in the record, "
                        + "and Removed entries on the All time tab can bring it back.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Remove", (d, w) -> remove.run())
                .show();
    }

    /** Everything the driver removed, newest first; tap one to bring it back exactly as it was. */
    static void removed(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        EnergyJournal journal = EnergyJournal.get(activity);
        EnergyStore store = journal.store();
        String currency = store.currency();
        List<EnergyStore.CheckIn> readings = store.removedCheckIns();
        List<EnergyModel.Charge> charges = store.removedCharges();
        List<String> items = new ArrayList<>();
        List<Runnable> restores = new ArrayList<>();
        for (EnergyStore.CheckIn r : readings) {
            items.add("Reading · " + (Double.isNaN(r.percent) ? "no %" : EnergyModel.percent(r.percent) + " %") + " · " + r.kind + " · " + when(activity, r.time));
            restores.add(() -> journal.restoreReading(r.id));
        }
        for (EnergyModel.Charge c : charges) {
            double money = c.money(store.homeRate());
            items.add("Charge · " + when(activity, c.time)
                    + (EnergyModel.usable(c.kwh) ? String.format(Locale.US, " · %.1f kWh", c.kwh) : "")
                    + (Double.isNaN(money) ? "" : " · " + currency + String.format(Locale.US, " %,.0f", money)));
            restores.add(() -> journal.restoreCharge(c.id));
        }
        ThemedDialog dialog = new ThemedDialog(activity, palette);
        dialog.setTitle("Removed entries · tap to restore");
        if (items.isEmpty()) dialog.setMessage("Nothing removed. Entries you remove stay here and can be restored.");
        else dialog.setItems(items.toArray(new String[0]), (d, which) -> { restores.get(which).run(); run(done); });
        dialog.setNegativeButton("Close", null).show();
    }

    /** The estimate from charging replaces the entered capacity only on this explicit choice. */
    static void acceptCapacity(Activity activity, LauncherTheme.Palette palette, double estimate, Runnable done) {
        EnergyModel.Profile profile = EnergyJournal.get(activity).store().profile();
        String rounded = EnergyModel.percent(Math.round(estimate * 10) / 10.0) + " kWh";
        new ThemedDialog(activity, palette).setTitle("Use " + rounded + " as usable capacity?")
                .setMessage("This comes from charging and includes charging losses, so the battery's real usable capacity is "
                        + "somewhat lower: it's an upper bound. "
                        + (profile.hasCapacity() ? "You entered " + kwh(profile.usableKwh) + "." : "No capacity is entered yet.")
                        + " Every kWh figure is recomputed from the raw records, which stay as they are.")
                .setNegativeButton(profile.hasCapacity() ? "Keep " + kwh(profile.usableKwh) : "Not now", null)
                .setPositiveButton("Use " + rounded, (d, w) -> { EnergyJournal.get(activity).acceptCapacity(estimate); run(done); })
                .show();
    }

    static void idleProfile(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        EnergyJournal journal = EnergyJournal.get(activity);
        EnergyModel.IdleProfile current = journal.store().profile().idle;
        EnergyModel.IdleProfile[] all = EnergyModel.IdleProfile.values();
        String[] names = new String[all.length];
        for (int i = 0; i < all.length; i++) names[i] = all[i].title();
        new ThemedDialog(activity, palette).setTitle("Idle profile · estimated")
                .setSingleChoiceItems(names, current.ordinal(), (d, which) -> {
                    journal.setIdleProfile(all[which]);
                    d.dismiss();
                    run(done);
                })
                .setNegativeButton("Close", null).show();
    }

    static void resetCurrentDrive(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        new ThemedDialog(activity, palette).setTitle("Reset the current drive?")
                .setMessage("This drive's energy data is set aside and never used for learning. Its check-ins stay in the record. "
                        + "Learned consumption, history, the vehicle profile and trips are not touched.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset drive", (d, w) -> { EnergyJournal.get(activity).resetCurrentDrive(); run(done); }).show();
    }

    static void resetLearning(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        new ThemedDialog(activity, palette).setTitle("Reset learned consumption?")
                .setMessage("Range estimates go back to the benchmark until new drives are learned. "
                        + "Check-ins, drives, idle records and the vehicle profile are kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset learning", (d, w) -> { EnergyJournal.get(activity).resetLearning(); run(done); }).show();
    }

    static void deleteHistory(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        activity.startActivity(new android.content.Intent(activity,EnergyDataActivity.class));
    }

    interface Number { void entered(double value); }

    /** A number for the vehicle profile; empty means "not set" when allowed. */
    static void number(Activity activity, LauncherTheme.Palette palette, String title, String prompt, String unit,
                       double max, double current, boolean allowEmpty, String hint, Number result) {
        Form form = new Form(activity, palette, prompt, null, null).limits(unit, max).value(current);
        if (hint != null) form.hint(hint);
        AlertDialog dialog = form.dialog(title);
        form.action("Save", true, () -> {
            if (!form.complete(!allowEmpty)) return;
            result.entered(form.percent());
            dialog.dismiss();
        });
        form.action("Cancel", false, dialog::dismiss);
        form.show(dialog);
    }

    /** Settings › Vehicle and energy: every field of the profile, one tap each. */
    static void vehicle(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        EnergyJournal journal = EnergyJournal.get(activity);
        EnergyStore store = journal.store();
        EnergyModel.Profile p = store.profile();
        LauncherPreferences preferences = new LauncherPreferences(activity);
        String mode = store.promptMode();
        String[] items = {
                "Vehicle name · " + store.vehicleName(),
                "Benchmark used for results · " + p.benchmarkLabel(),
                "NEDC reference range · " + km(p.nedcKm),
                "CLTC reference range · " + km(p.cltcKm),
                "WLTP reference range · " + km(p.wltpKm),
                "Practical range (your experience) · " + km(p.practicalKm),
                "Usable battery capacity · " + kwh(p.usableKwh),
                "Gross battery capacity (reference only) · " + kwh(p.grossKwh),
                "Reserve · " + EnergyModel.percent(p.reservePercent) + " %",
                "Ask for battery % (manual only) · " + ("never".equals(mode) ? "Never" : "after_off".equals(mode) ? "After the box was off 30 min or more" : "When parked, before a drive"),
                "Idle profile · " + p.idle.title(),
                "Drive card side · " + ("left".equals(preferences.driverSide()) ? "Left" : "Right"),
                "Home electricity rate · " + (EnergyModel.usable(store.homeRate()) || store.homeRate() == 0
                        ? EnergyModel.percent(store.homeRate()) + " " + store.currency() + "/kWh" : "not set"),
                "Currency label · " + store.currency(),
                "Energy data · back up, reset or restore"};
        Runnable again = () -> { run(done); vehicle(activity, palette, done); };
        new ThemedDialog(activity, palette).setTitle("Vehicle and energy").setItems(items, (d, which) -> {
            switch (which) {
                case 14: activity.startActivity(new android.content.Intent(activity,EnergyDataActivity.class));break;
                case 0: {
                    android.widget.EditText name = new android.widget.EditText(activity);
                    name.setText(store.vehicleName());
                    name.setSingleLine(true);
                    new ThemedDialog(activity, palette).setTitle("Vehicle name").setView(name)
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Save", (d2, w) -> { store.setVehicleName(name.getText().toString().trim()); again.run(); }).show();
                    break;
                }
                case 1: {
                    EnergyModel.Benchmark[] all = EnergyModel.Benchmark.values();
                    String[] names = new String[all.length];
                    for (int i = 0; i < all.length; i++) names[i] = all[i].label + " · " + km(p.km(all[i]));
                    new ThemedDialog(activity, palette).setTitle("Compare drives against")
                            .setSingleChoiceItems(names, p.selected.ordinal(), (d2, i) -> {
                                if (!EnergyModel.usable(p.km(all[i]))) {
                                    android.widget.Toast.makeText(activity, "Enter the " + all[i].label + " range first", android.widget.Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                p.selected = all[i];
                                journal.saveProfile(p);
                                d2.dismiss();
                                again.run();
                            }).setNegativeButton("Close", null).show();
                    break;
                }
                case 2: case 3: case 4: case 5: {
                    EnergyModel.Benchmark benchmark = EnergyModel.Benchmark.values()[which - 2];
                    boolean practical = benchmark == EnergyModel.Benchmark.PRACTICAL;
                    number(activity, palette, benchmark.label + " range", benchmark.label + " range in km", "km", 2000,
                            p.km(benchmark), practical, "Enter the figure as published or as you experience it.", value -> {
                                if (!practical && !EnergyModel.usable(value)) return;
                                if (benchmark == EnergyModel.Benchmark.NEDC) p.nedcKm = value;
                                else if (benchmark == EnergyModel.Benchmark.CLTC) p.cltcKm = value;
                                else if (benchmark == EnergyModel.Benchmark.WLTP) p.wltpKm = value;
                                else p.practicalKm = EnergyModel.usable(value) ? value : Double.NaN;
                                journal.saveProfile(p);
                                again.run();
                            });
                    break;
                }
                case 6:
                    number(activity, palette, "Usable battery capacity", "kWh the car can actually use", "kWh", 200, p.usableKwh, true,
                            "Needed for kWh figures. Leave empty if unknown.", value -> {
                                p.usableKwh = EnergyModel.usable(value) ? value : Double.NaN;
                                journal.saveProfile(p);
                                again.run();
                            });
                    break;
                case 7:
                    number(activity, palette, "Gross battery capacity", "Nameplate kWh", "kWh", 200, p.grossKwh, true,
                            "For reference only.", value -> {
                                p.grossKwh = EnergyModel.usable(value) ? value : Double.NaN;
                                journal.saveProfile(p);
                                again.run();
                            });
                    break;
                case 8:
                    number(activity, palette, "Keep battery above", "Charger prompts and the arrival estimate warn below this level", "%", 50, p.reservePercent, false, null, value -> {
                        p.reservePercent = value;
                        journal.saveProfile(p);
                        again.run();
                    });
                    break;
                case 9: {
                    String[] modes = {"parked", "after_off", "never"};
                    String[] names = {"When parked, before a drive", "Only after the box was off 30 min or more", "Never ask"};
                    int current = "after_off".equals(mode) ? 1 : "never".equals(mode) ? 2 : 0;
                    new ThemedDialog(activity, palette).setTitle("Ask for the battery %")
                            .setSingleChoiceItems(names, current, (d2, i) -> { store.setPromptMode(modes[i]); d2.dismiss(); again.run(); })
                            .setNegativeButton("Close", null).show();
                    break;
                }
                case 10: idleProfile(activity, palette, again); break;
                case 11:
                    new ThemedDialog(activity, palette).setTitle("Drive card side")
                            .setSingleChoiceItems(new String[]{"Right (right-hand drive)", "Left"}, "left".equals(preferences.driverSide()) ? 1 : 0,
                                    (d2, i) -> { preferences.setDriverSide(i == 1 ? "left" : "right"); d2.dismiss(); again.run(); })
                            .setNegativeButton("Close", null).show();
                    break;
                case 12:
                    number(activity, palette, "Home electricity rate", "Price per kWh at home", store.currency() + "/kWh", 9_999,
                            store.homeRate(), true, "Optional. Prices home charges that have kWh but no cost.",
                            value -> { journal.setHomeRate(value); again.run(); });
                    break;
                default: {
                    android.widget.EditText code = new android.widget.EditText(activity);
                    code.setText(store.currency());
                    code.setSingleLine(true);
                    new ThemedDialog(activity, palette).setTitle("Currency label").setView(code)
                            .setMessage("Shown next to costs.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Save", (d2, w) -> { journal.setCurrency(code.getText().toString()); again.run(); }).show();
                }
            }
        }).setNegativeButton("Done", null).show();
    }

    static String km(double value) { return EnergyModel.usable(value) ? Math.round(value) + " km" : "not set"; }
    static String kwh(double value) { return EnergyModel.usable(value) ? EnergyModel.percent(value) + " kWh" : "not set"; }

    private static void run(Runnable done) { if (done != null) done.run(); }

    static String clock(Activity activity, long time) {
        return android.text.format.DateFormat.getTimeFormat(activity).format(new Date(time));
    }

    /** "10:30", "yesterday 18:40" or "12 Oct 18:40". */
    static String when(Activity activity, long time) {
        java.util.Calendar then = java.util.Calendar.getInstance();
        then.setTimeInMillis(time);
        java.util.Calendar now = java.util.Calendar.getInstance();
        String clock = clock(activity, time);
        if (then.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) {
            int days = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR);
            if (days == 0) return clock;
            if (days == 1) return "yesterday " + clock;
        }
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(time)) + " " + clock;
    }

    /**
     * Pad and side column at the taller one's height, both stretched to it, so the side's spacer keeps
     * the actions at the bottom. (A plain row would size itself by the pad alone and clip a side column
     * that the optional tiles made taller.)
     */
    private static final class Columns extends LinearLayout {
        Columns(android.content.Context context) { super(context); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            stretch(false);
            super.onMeasure(widthSpec, heightSpec);
            int height = getMeasuredHeight();
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) height = Math.min(height, MeasureSpec.getSize(heightSpec));
            stretch(true);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }

        private void stretch(boolean fill) {
            for (int i = 0; i < getChildCount(); i++) {
                getChildAt(i).getLayoutParams().height = fill ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
            }
        }
    }

    /**
     * A number pad beside the questions and the actions; fixed size, no scrolling. One pad types into
     * whichever field is selected: the battery % first, then any optional figures from the car's
     * displays, shown as tiles. Nothing is pre-filled.
     */
    static final class Form {
        private static final class Field {
            String tile, prompt, unit;
            double max;
            boolean optional;
            String entry = "";
            /** Pre-filled for a correction and not yet touched: the first digit replaces it. */
            boolean replaceOnType;
            LinearLayout view;
            TextView value;
        }

        private final Activity activity;
        private final LauncherTheme.Palette palette;
        private final boolean compact, narrow;
        private final LinearLayout root, questions, tiles, actions;
        private final TextView caption, display, hint, error;
        private final List<Field> fields = new ArrayList<>();
        private final List<TextView[]> chips = new ArrayList<>();
        private final List<Integer> chosen = new ArrayList<>();
        /** The "charged since?" question, when the form asks it (always question 0). */
        private final boolean asksCharged;
        private int active;
        private LinearLayout actionRow;

        Form(Activity activity, LauncherTheme.Palette palette, String prompt, String question, String[] options) {
            this.activity = activity;
            this.palette = palette;
            // Short windows (1920 × 720, about 420 dp without the system bars): smaller keys, and actions
            // two to a row. Narrow ones (960 wide): thinner keys, questions above their choices, tiles
            // in rows of up to three.
            compact = activity.getResources().getConfiguration().screenHeightDp < 500;
            narrow = activity.getResources().getConfiguration().screenWidthDp < 900;
            int keyWidth = narrow ? 70 : 84;
            root = new Columns(activity);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(dp(24), dp(8), dp(24), dp(compact ? 12 : 20));

            Field first = new Field();
            first.tile = "BATTERY %";
            first.prompt = prompt;
            first.unit = "%";
            first.max = 100;
            fields.add(first);

            LinearLayout pad = new LinearLayout(activity);
            pad.setOrientation(LinearLayout.VERTICAL);
            caption = text(prompt, 13, palette.muted, true);
            caption.setTag("form_prompt");
            caption.setMaxLines(1);
            caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
            pad.addView(caption, new LinearLayout.LayoutParams(dp(3 * keyWidth + 16), ViewGroup.LayoutParams.WRAP_CONTENT));
            display = text("— %", compact ? 36 : 44, palette.ink, false);
            display.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            display.setFontFeatureSettings("tnum");
            display.setMaxLines(1);
            display.setPadding(0, dp(2), 0, dp(compact ? 4 : 8));
            pad.addView(display);
            String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "⌫"};
            for (int row = 0; row < 4; row++) {
                LinearLayout line = new LinearLayout(activity);
                for (int col = 0; col < 3; col++) {
                    String key = keys[row * 3 + col];
                    TextView button = text(key, 24, palette.ink, false);
                    button.setGravity(Gravity.CENTER);
                    button.setBackground(LauncherTheme.raised(activity, palette, 14));
                    button.setContentDescription("⌫".equals(key) ? "Delete" : key);
                    button.setOnClickListener(v -> press(key));
                    if ("⌫".equals(key)) button.setOnLongClickListener(v -> { field().entry = ""; render(); return true; });
                    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(dp(keyWidth), dp(compact ? 48 : 58));
                    size.setMargins(0, 0, col < 2 ? dp(8) : 0, dp(8));
                    line.addView(button, size);
                }
                pad.addView(line);
            }
            root.addView(pad);

            LinearLayout side = new LinearLayout(activity);
            side.setOrientation(LinearLayout.VERTICAL);
            side.setPadding(dp(narrow ? 20 : 28), 0, 0, 0);
            questions = new LinearLayout(activity);
            questions.setOrientation(LinearLayout.VERTICAL);
            side.addView(questions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            asksCharged = question != null;
            if (asksCharged) {
                questions.addView(text(question, 13, palette.muted, true));
                LinearLayout row = new LinearLayout(activity);
                row.setBaselineAligned(false);
                row.setPadding(0, dp(8), 0, dp(4));
                addChips(row, options, compact ? 46 : 52);
                questions.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            tiles = new LinearLayout(activity);
            tiles.setOrientation(LinearLayout.VERTICAL);
            tiles.setVisibility(View.GONE);
            side.addView(tiles, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            hint = text("", 13, palette.muted, false);
            hint.setPadding(0, dp(8), 0, 0);
            hint.setVisibility(View.GONE);
            side.addView(hint);
            error = text("", 13, palette.light ? 0xffb3261e : 0xffff8a80, true);
            error.setPadding(0, dp(6), 0, 0);
            error.setVisibility(View.GONE);
            side.addView(error);
            View spacer = new android.widget.Space(activity); // a plain View would claim all the height on offer
            side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
            actions = new LinearLayout(activity);
            actions.setOrientation(LinearLayout.VERTICAL);
            side.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(side, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            render();
        }

        /** For other numbers (km, kWh): the unit shown and the largest value accepted. */
        Form limits(String unit, double max) {
            fields.get(0).unit = unit;
            fields.get(0).max = max;
            render();
            return this;
        }

        /** Starts from a value already saved (profile fields only, never a battery reading). */
        Form value(double value) {
            fields.get(0).entry = Double.isNaN(value) ? "" : EnergyModel.percent(value);
            render();
            return this;
        }

        /** Corrections only: starts a field from the value being corrected (empty when it was NaN). */
        Form set(int index, double value) {
            fields.get(index).entry = Double.isNaN(value) ? "" : EnergyModel.percent(value);
            fields.get(index).replaceOnType = !Double.isNaN(value);
            render();
            return this;
        }

        /** Corrections only: starts a question on the answer being corrected (−1 leaves it unanswered). */
        Form choose(int question, int option) {
            chosen.set(question, option);
            render();
            return this;
        }

        /** The first field's tile label, once the form has more than one field. */
        Form tile(String label, boolean optional) {
            fields.get(0).tile = label;
            fields.get(0).optional = optional;
            render();
            return this;
        }

        /** Another number on the same pad, shown as a tile; returns its index for {@link #number}. */
        int field(String tile, String prompt, String unit, double max, boolean optional) {
            Field field = new Field();
            field.tile = tile;
            field.prompt = prompt;
            field.unit = unit;
            field.max = max;
            field.optional = optional;
            fields.add(field);
            layoutTiles();
            render();
            return fields.size() - 1;
        }

        /** A further question, label and choices on one line; returns its index for {@link #choice}. */
        int question(String label, String[] options) {
            LinearLayout row = new LinearLayout(activity);
            row.setBaselineAligned(false);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, 0, 0, dp(compact ? 6 : 8));
            TextView name = text(label, 13, palette.muted, true);
            if (narrow) {
                name.setPadding(0, 0, 0, dp(4));
                questions.addView(name);
            } else {
                row.addView(name, new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            addChips(row, options, compact ? 44 : 48);
            questions.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            render();
            return chips.size() - 1;
        }

        private void addChips(LinearLayout row, String[] options, int height) {
            int question = chips.size();
            TextView[] views = new TextView[options.length];
            for (int i = 0; i < options.length; i++) {
                final int index = i;
                TextView chip = text(options[i], narrow ? 14 : 15, palette.ink, true);
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(dp(narrow ? 4 : 10), 0, dp(narrow ? 4 : 10), 0);
                chip.setMaxLines(1);
                chip.setOnClickListener(v -> { chosen.set(question, index); render(); });
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(height), 1);
                if (i < options.length - 1) size.setMarginEnd(dp(8));
                row.addView(chip, size);
                views[i] = chip;
            }
            chips.add(views);
            chosen.add(-1);
        }

        /** Tiles in rows: up to three a row (four as two pairs); all in one row in a short, wide window. */
        private void layoutTiles() {
            tiles.removeAllViews();
            int count = fields.size();
            int perRow = count <= 3 || (compact && !narrow) ? count : (count + 1) / 2;
            LinearLayout line = null;
            for (int i = 0; i < count; i++) {
                if (i % perRow == 0) {
                    line = new LinearLayout(activity);
                    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(compact ? 50 : 58));
                    params.topMargin = dp(compact ? 4 : 8);
                    tiles.addView(line, params);
                }
                Field field = fields.get(i);
                if (field.view == null) {
                    final int index = i;
                    field.view = new LinearLayout(activity);
                    field.view.setTag("form_tile");
                    field.view.setOrientation(LinearLayout.VERTICAL);
                    field.view.setGravity(Gravity.CENTER_VERTICAL);
                    field.view.setPadding(dp(12), 0, dp(10), 0);
                    field.view.setOnClickListener(v -> { active = index; render(); });
                    TextView name = text(field.tile, 11, palette.muted, true);
                    name.setTag("form_tile_name");
                    name.setLetterSpacing(.08f);
                    name.setMaxLines(1);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    field.view.addView(name);
                    field.value = text("", compact ? 15 : 17, palette.ink, true);
                    field.value.setFontFeatureSettings("tnum");
                    field.value.setMaxLines(1);
                    field.value.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    field.view.addView(field.value);
                } else if (field.view.getParent() != null) {
                    ((ViewGroup) field.view.getParent()).removeView(field.view);
                }
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                if ((i + 1) % perRow != 0 && i < count - 1) size.setMarginEnd(dp(8));
                line.addView(field.view, size);
            }
            // A last row with fewer tiles keeps the same tile width.
            if (line != null) for (int i = line.getChildCount(); i < perRow; i++) {
                line.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
                ((LinearLayout.LayoutParams) line.getChildAt(i - 1).getLayoutParams()).setMarginEnd(dp(8));
            }
            tiles.setVisibility(count > 1 ? View.VISIBLE : View.GONE);
        }

        void hint(String text) {
            hint.setText(text);
            hint.setVisibility(View.VISIBLE);
        }

        AlertDialog dialog(String title) {
            return new ThemedDialog(activity, palette).setTitle(title).setView(root).create();
        }

        void action(String label, boolean primary, Runnable run) {
            TextView button = text(label, 16, primary ? (palette.light ? 0xffffffff : palette.root) : palette.ink, true);
            button.setGravity(Gravity.CENTER);
            button.setMaxLines(1);
            button.setEllipsize(android.text.TextUtils.TruncateAt.END);
            button.setPadding(dp(8), 0, dp(8), 0);
            button.setBackground(primary ? LauncherTheme.rounded(activity, palette.accent, 14, 0, 0)
                    : LauncherTheme.raised(activity, palette, 14));
            button.setOnClickListener(v -> run.run());
            if (!compact) {
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
                size.topMargin = dp(8);
                actions.addView(button, size);
                return;
            }
            if (actionRow == null || actionRow.getChildCount() >= 2) {
                actionRow = new LinearLayout(activity);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
                params.topMargin = dp(6);
                actions.addView(actionRow, params);
            } else {
                ((LinearLayout.LayoutParams) actionRow.getChildAt(0).getLayoutParams()).setMarginEnd(dp(8));
            }
            actionRow.addView(button, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }

        void show(AlertDialog dialog) {
            dialog.show();
            DrivingDismiss.track(dialog);
            if (dialog.getWindow() != null) {
                int width = Math.min(activity.getResources().getDisplayMetrics().widthPixels - dp(24), dp(860));
                dialog.getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        }

        /** The first field (the battery %); NaN when nothing was entered. */
        double percent() { return number(0); }

        /** Any field; NaN when nothing was entered. */
        double number(int index) {
            String entry = fields.get(index).entry;
            if (entry.isEmpty() || ".".equals(entry)) return Double.NaN;
            try { return Double.parseDouble(entry); } catch (NumberFormatException e) { return Double.NaN; }
        }

        /** The chosen option of a question, or −1 when it wasn't answered. */
        int choice(int question) { return chosen.get(question); }

        EnergyModel.Charged charged() {
            int choice = asksCharged ? chosen.get(0) : -1;
            if (choice == 0) return EnergyModel.Charged.NO;
            if (choice == 1) return EnergyModel.Charged.YES;
            return EnergyModel.Charged.UNKNOWN;
        }

        /** A valid % (when required), every other field in range, and the charging answer (when asked). */
        boolean complete(boolean needPercent) {
            double value = percent();
            String problem = null;
            if (needPercent && Double.isNaN(value)) problem = "%".equals(fields.get(0).unit) ? "Enter the battery % first" : "Enter a value first";
            for (int i = 0; problem == null && i < fields.size(); i++) {
                double number = number(i);
                if (!Double.isNaN(number) && (number < 0 || number > fields.get(i).max)) {
                    String range = "between 0 and " + EnergyModel.percent(fields.get(i).max);
                    problem = fields.size() > 1 ? fields.get(i).tile + ": enter a value " + range : "Enter a value " + range;
                }
            }
            if (problem == null && asksCharged && chosen.get(0) < 0 && !Double.isNaN(value)) problem = "Choose whether the car was charged";
            if (problem != null) return fail(problem);
            error.setVisibility(View.GONE);
            return true;
        }

        /** Shows why the form can't be saved yet; always false. */
        boolean fail(String problem) {
            error.setText(problem);
            error.setVisibility(View.VISIBLE);
            return false;
        }

        private Field field() { return fields.get(active); }

        private void press(String key) {
            Field field = field();
            String entry = field.entry;
            if (field.replaceOnType && !"⌫".equals(key)) entry = ""; // typing over a pre-filled value
            field.replaceOnType = false;
            if ("⌫".equals(key)) {
                if (!entry.isEmpty()) entry = entry.substring(0, entry.length() - 1);
            } else if (".".equals(key)) {
                if (!entry.contains(".")) entry = entry.isEmpty() ? "0." : entry + ".";
            } else {
                int dot = entry.indexOf('.');
                if (dot >= 0 && entry.length() - dot > 1) return; // one decimal at most
                String next = "0".equals(entry) ? key : entry + key;
                try { if (Double.parseDouble(next) > field.max) return; } catch (NumberFormatException ignored) { return; }
                entry = next;
            }
            field.entry = entry;
            render();
        }

        private void render() {
            Field current = field();
            caption.setText(current.prompt);
            display.setText((current.entry.isEmpty() ? "—" : current.entry) + " " + current.unit);
            for (int f = 0; f < fields.size(); f++) {
                Field field = fields.get(f);
                if (field.view == null) continue;
                boolean on = f == active;
                field.view.setBackground(on ? LauncherTheme.rounded(activity, palette.surfaceStrong, 14, 2, palette.accent)
                        : LauncherTheme.raised(activity, palette, 14));
                boolean empty = field.entry.isEmpty() || ".".equals(field.entry);
                field.value.setText(empty ? (field.optional ? "optional" : "—") : field.entry + " " + field.unit);
                field.value.setTextColor(empty ? palette.muted : palette.ink);
                field.view.setContentDescription(field.tile + ", " + field.value.getText() + (on ? ", selected" : ""));
            }
            for (int q = 0; q < chips.size(); q++) {
                TextView[] row = chips.get(q);
                for (int i = 0; i < row.length; i++) {
                    boolean on = i == chosen.get(q);
                    row[i].setBackground(on ? LauncherTheme.rounded(activity, palette.accent, 14, 0, 0)
                            : LauncherTheme.raised(activity, palette, 14));
                    row[i].setTextColor(on ? (palette.light ? 0xffffffff : palette.root) : palette.ink);
                }
            }
            error.setVisibility(View.GONE);
        }

        private TextView text(String value, int size, int color, boolean medium) {
            TextView view = new TextView(activity);
            view.setText(value);
            view.setTextSize(size);
            view.setTextColor(color);
            if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            return view;
        }

        private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    }
}
