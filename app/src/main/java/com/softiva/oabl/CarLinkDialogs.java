package com.softiva.oabl;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputFilter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.text.InputType;
import android.widget.EditText;
import java.util.Locale;

/**
 * Settings for the live link (energy-companion.md §3.8): the data source (Automatic, Manual only or
 * Car only), the link status, and pairing with the 6-digit code the head unit shows. Self-contained
 * so it can sit behind one row of the existing Vehicle and energy settings. Pairing only stores the
 * code the driver types; nothing here writes to the car.
 */
final class CarLinkDialogs {
    private CarLinkDialogs() { }

    static void dataSource(Activity activity, LauncherTheme.Palette palette, Runnable done) {
        CarLinkController controller = EnergyClient.link(activity);
        String mode = controller.mode();
        String modeLabel = CarLinkController.MODE_MANUAL.equals(mode) ? "Manual only"
                : CarLinkController.MODE_CAR.equals(mode) ? "Car only" : "Automatic";
        long age = controller.liveAgeSeconds(CarSignal.SOC);
        String link = !controller.paired() ? "not paired"
                : controller.usingCar() ? controller.state() + (age >= 0 ? " · " + age + "s ago" : "") : "off (manual)";

        DriveMode liveMode = controller.liveDriveMode();
        String matchLabel = "Match drive-mode colour · " + (controller.matchDriveMode()
                ? "On" + (liveMode != null ? " · " + liveMode.label : "") : "Off");

        java.util.List<String> items = new java.util.ArrayList<>();
        java.util.List<Runnable> actions = new java.util.ArrayList<>();
        Runnable again = () -> { run(done); dataSource(activity, palette, done); };

        items.add("Data source · " + modeLabel);
        actions.add(() -> chooseMode(activity, palette, controller, again));
        items.add("Link · " + link);
        actions.add(() -> status(activity, palette, controller));
        items.add(matchLabel);
        actions.add(() -> { controller.setMatchDriveMode(!controller.matchDriveMode()); run(again); });
        items.add(controller.paired() ? "Re-pair with the head unit" : "Pair with the head unit");
        actions.add(() -> pair(activity, palette, controller, done));
        if (controller.paired()) {
            items.add("Forget the head unit");
            actions.add(() -> forget(activity, palette, controller, again));
        }

        new ThemedDialog(activity, palette).setTitle("Vehicle data source")
                .setItems(items.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Done", null).show();
    }

    private static boolean bluetoothPermissions(Activity activity) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            for (String p : new String[]{android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT})
                if (activity.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED) missing.add(p);
        } else if (activity.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            missing.add(android.Manifest.permission.ACCESS_FINE_LOCATION);
        if (missing.isEmpty()) return true;
        activity.requestPermissions(missing.toArray(new String[0]), 508); return false;
    }

    private static void chooseMode(Activity activity, LauncherTheme.Palette palette, CarLinkController controller, Runnable done) {
        String[] modes = {CarLinkController.MODE_AUTO, CarLinkController.MODE_MANUAL, CarLinkController.MODE_CAR};
        String[] names = {"Automatic · use the paired car, or manual entry", "Manual only · never use the car",
                "Car only · live from the car"};
        int current = CarLinkController.MODE_MANUAL.equals(controller.mode()) ? 1
                : CarLinkController.MODE_CAR.equals(controller.mode()) ? 2 : 0;
        new ThemedDialog(activity, palette).setTitle("Data source")
                .setSingleChoiceItems(names, current, (d, i) -> { controller.setMode(modes[i]); d.dismiss(); run(done); })
                .setNegativeButton("Close", null).show();
    }

    static AlertDialog status(Activity activity, LauncherTheme.Palette palette, CarLinkController controller) {
        AlertDialog dialog = new ThemedDialog(activity, palette).setTitle("Head unit connection")
                .setMessage(statusText(controller))
                .setPositiveButton("Retry", null)
                .setNeutralButton("Connection", (d, w) -> pair(activity, palette, controller, null))
                .setNegativeButton("Close", null).show();
        Runnable refresh = () -> dialog.setMessage(statusText(controller));
        controller.observe(refresh);
        android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timer = new Runnable() {
            @Override public void run() { if (dialog.isShowing()) { refresh.run(); handler.postDelayed(this, 1000); } }
        };
        handler.postDelayed(timer, 1000);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> controller.retry());
        dialog.setOnDismissListener(d -> { controller.unobserve(refresh); handler.removeCallbacks(timer); });
        return dialog;
    }

    static String statusText(CarLinkController controller) {
        StringBuilder text = new StringBuilder();
        if (!controller.paired()) text.append("Enter the 6-digit code shown by OABL Link on the car.\n");
        else if (!controller.usingCar()) text.append("Link paused · data source is Manual only. Select Automatic to connect.\n");
        else text.append(controller.state()).append('\n');
        if (controller.hasFreshLive()) {
            text.append(String.format(Locale.US, "Battery: %.0f %% · updated %ds ago\n", controller.liveSoc(), controller.liveAgeSeconds(CarSignal.SOC)));
            if (!Double.isNaN(controller.liveRangeKm())) text.append(String.format(Locale.US, "Car range: %.0f km\n", controller.liveRangeKm()));
            text.append(controller.liveCharging() ? "Charging" : "Not charging");
        } else {
            text.append("No fresh battery reading yet.\nKeep OABL Link running. ")
                    .append("Turn on Bluetooth on both devices, allow nearby devices and enter the code from OABL Link. No hotspot needed. Keep CarPlay connected.");
        }
        return text.toString();
    }

    static AlertDialog pair(Activity activity, LauncherTheme.Palette palette, CarLinkController controller, Runnable done) {
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        fields.setPadding(pad, 0, pad, 0);
        EditText code = new EditText(activity);
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setHint("6-digit code");
        code.setContentDescription("Head unit pairing code");
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setSingleLine(true);
        fields.addView(code);
        AlertDialog dialog = new ThemedDialog(activity, palette).setTitle("Pair with the head unit")
                .setMessage("Open OABL Link on the car and enter its code. It needs Bluetooth on both devices, with no hotspot or phone pairing.")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = code.getText().toString().trim();
            if (!value.matches("\\d{6}")) { code.setError("Enter all 6 digits shown on the car"); return; }
            if (!bluetoothPermissions(activity)) { dialog.setMessage("Allow Bluetooth, then tap Connect again."); return; }
            controller.pair(value);
            dialog.dismiss();
            run(done);
            status(activity, palette, controller);
        });
        return dialog;
    }

    private static void forget(Activity activity, LauncherTheme.Palette palette, CarLinkController controller, Runnable done) {
        new ThemedDialog(activity, palette).setTitle("Forget the head unit?")
                .setMessage("The box stops reading the car and goes back to manual entry. The car data already saved is kept.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Forget", (d, w) -> { controller.forget(); run(done); }).show();
    }

    private static void run(Runnable done) { if (done != null) done.run(); }
}
