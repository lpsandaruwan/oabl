package com.softiva.oabl;

import android.Manifest;
import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Reads small, local-only connectivity details for the launcher glance surface. */
final class TravelStatusMonitor {
    static final class Snapshot {
        final boolean wifiConnected;
        final int wifiLevel;
        final String wifiName;
        final String wifi;
        final boolean cellularAvailable;
        final int cellularLevel;
        final String carrier;
        final String dataType;
        final String cellular;
        final String bluetooth;
        final String call;
        final boolean bluetoothPermissionNeeded;
        final boolean phonePermissionNeeded;

        Snapshot(boolean wifiConnected, int wifiLevel, String wifiName, String wifi,
                 boolean cellularAvailable, int cellularLevel, String carrier,
                 String dataType, String cellular, String bluetooth, String call,
                 boolean bluetoothPermissionNeeded, boolean phonePermissionNeeded) {
            this.wifiConnected = wifiConnected;
            this.wifiLevel = wifiLevel;
            this.wifiName = wifiName;
            this.wifi = wifi;
            this.cellularAvailable = cellularAvailable;
            this.cellularLevel = cellularLevel;
            this.carrier = carrier;
            this.dataType = dataType;
            this.cellular = cellular;
            this.bluetooth = bluetooth;
            this.call = call;
            this.bluetoothPermissionNeeded = bluetoothPermissionNeeded;
            this.phonePermissionNeeded = phonePermissionNeeded;
        }
    }

    private static final class WifiStatus {
        final boolean connected;
        final int level;
        final String name;
        final String detail;

        WifiStatus(boolean connected, int level, String name, String detail) {
            this.connected = connected;
            this.level = level;
            this.name = name;
            this.detail = detail;
        }
    }

    private static final class CellularStatus {
        final boolean available;
        final int level;
        final String carrier;
        final String dataType;
        final String detail;

        CellularStatus(boolean available, int level, String carrier, String dataType,
                       String detail) {
            this.available = available;
            this.level = level;
            this.carrier = carrier;
            this.dataType = dataType;
            this.detail = detail;
        }
    }

    private final Context context;
    private final BluetoothAdapter bluetoothAdapter;
    private final TelephonyManager telephonyManager;
    private BluetoothProfile headsetProfile;
    private BluetoothProfile audioProfile;
    private boolean bluetoothStarted;
    private boolean telephonyStarted;
    private SignalStrength signalStrength;
    private ServiceState serviceState;
    private int dataState = TelephonyManager.DATA_DISCONNECTED;
    private int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
    private int overrideNetworkType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE;
    private ModernTelephonyCallback modernTelephonyCallback;
    private PhoneStateListener legacyPhoneStateListener;

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = proxy;
                    } else if (profile == BluetoothProfile.A2DP) {
                        audioProfile = proxy;
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProfile = null;
                    } else if (profile == BluetoothProfile.A2DP) {
                        audioProfile = null;
                    }
                }
            };

    TravelStatusMonitor(Context context) {
        this.context = context.getApplicationContext();
        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
    }

    void start() {
        startBluetooth();
        startTelephony();
    }

    private void startBluetooth() {
        if (bluetoothStarted || bluetoothAdapter == null || !hasBluetoothPermission()) {
            return;
        }
        bluetoothStarted = true;
        try {
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.HEADSET);
            bluetoothAdapter.getProfileProxy(context, profileListener, BluetoothProfile.A2DP);
        } catch (SecurityException ignored) {
            bluetoothStarted = false;
        }
    }

    private void startTelephony() {
        if (telephonyStarted || !hasCellularRadio() || !hasPhonePermission()) {
            return;
        }
        try {
            telephonyStarted = true;
            if (Build.VERSION.SDK_INT >= 31) {
                modernTelephonyCallback = new ModernTelephonyCallback();
                telephonyManager.registerTelephonyCallback(
                        context.getMainExecutor(), modernTelephonyCallback);
            } else {
                legacyPhoneStateListener = new PhoneStateListener() {
                    @Override
                    public void onSignalStrengthsChanged(SignalStrength strength) {
                        signalStrength = strength;
                    }

                    @Override
                    public void onServiceStateChanged(ServiceState state) {
                        serviceState = state;
                    }

                    @Override
                    public void onDataConnectionStateChanged(int state, int type) {
                        dataState = state;
                        networkType = type;
                    }
                };
                telephonyManager.listen(legacyPhoneStateListener,
                        PhoneStateListener.LISTEN_SIGNAL_STRENGTHS
                                | PhoneStateListener.LISTEN_SERVICE_STATE
                                | PhoneStateListener.LISTEN_DATA_CONNECTION_STATE);
            }
        } catch (SecurityException ignored) {
            telephonyStarted = false;
        }
    }

    void stop() {
        stopBluetooth();
        stopTelephony();
    }

    private void stopBluetooth() {
        if (bluetoothAdapter != null) {
            try {
                if (headsetProfile != null) {
                    bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, headsetProfile);
                }
                if (audioProfile != null) {
                    bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, audioProfile);
                }
            } catch (SecurityException ignored) {
                // Permission can be revoked while the launcher is open.
            }
        }
        headsetProfile = null;
        audioProfile = null;
        bluetoothStarted = false;
    }

    private void stopTelephony() {
        if (!telephonyStarted || telephonyManager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 31 && modernTelephonyCallback != null) {
                telephonyManager.unregisterTelephonyCallback(modernTelephonyCallback);
            } else if (legacyPhoneStateListener != null) {
                telephonyManager.listen(legacyPhoneStateListener,
                        PhoneStateListener.LISTEN_NONE);
            }
        } catch (SecurityException ignored) {
            // Permission can be revoked while the launcher is open.
        }
        modernTelephonyCallback = null;
        legacyPhoneStateListener = null;
        telephonyStarted = false;
    }

    Snapshot read() {
        WifiStatus wifi = readWifi();
        boolean bluetoothPermissionNeeded = bluetoothAdapter != null
                && !hasBluetoothPermission();
        boolean phonePermissionNeeded = hasCellularRadio() && !hasPhonePermission();
        CellularStatus cellular = readCellular(phonePermissionNeeded);
        return new Snapshot(wifi.connected, wifi.level, wifi.name, wifi.detail,
                cellular.available, cellular.level, cellular.carrier,
                cellular.dataType, cellular.detail,
                readBluetooth(bluetoothPermissionNeeded), readCall(),
                bluetoothPermissionNeeded, phonePermissionNeeded);
    }

    boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasPhonePermission() {
        return context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private WifiStatus readWifi() {
        ConnectivityManager connectivity =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = connectivity == null ? null : connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = connectivity == null
                ? null : connectivity.getNetworkCapabilities(active);
        if (capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return new WifiStatus(false, 0, "Wi-Fi", "Wi-Fi disconnected");
        }

        int level = 0;
        String name = "Wi-Fi";
        String radio = "";
        WifiManager wifiManager =
                (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        try {
            WifiInfo info = wifiManager == null ? null : wifiManager.getConnectionInfo();
            if (info != null) {
                int frequency = info.getFrequency();
                String band = frequency >= 5925 ? "6 GHz" : frequency >= 4900 ? "5 GHz" : frequency > 0 ? "2.4 GHz" : "";
                radio = (info.getRssi() > -127 && info.getRssi() < 0 ? " · " + info.getRssi() + " dBm" : "")
                        + (info.getLinkSpeed() > 0 ? " · " + info.getLinkSpeed() + " Mbps" : "")
                        + (band.isEmpty() ? "" : " · " + band);
            }
            int rssi = info == null ? -127 : info.getRssi();
            if (rssi > -127 && rssi < 0) {
                level = WifiManager.calculateSignalLevel(rssi, 4) + 1;
            }
            String ssid = info == null ? null : info.getSSID();
            if (ssid != null && !WifiManager.UNKNOWN_SSID.equals(ssid)
                    && !"<unknown ssid>".equalsIgnoreCase(ssid)) {
                name = stripQuotes(ssid);
            }
        } catch (SecurityException ignored) {
            // Some vendor builds restrict SSID/RSSI even for the active connection.
        }
        String detail = name + radio;
        android.net.LinkProperties properties = connectivity.getLinkProperties(active);
        if (properties != null) {
            for (android.net.LinkAddress address : properties.getLinkAddresses()) {
                if (address.getAddress() instanceof java.net.Inet4Address) {
                    detail += " · " + address.getAddress().getHostAddress(); break;
                }
            }
        }
        return new WifiStatus(true, level, name, detail);
    }

    private CellularStatus readCellular(boolean permissionNeeded) {
        if (!hasCellularRadio()) {
            return new CellularStatus(false, 0, null, null, "No SIM");
        }
        String carrier = carrierName();
        if (permissionNeeded) {
            return new CellularStatus(true, 0, carrier, null,
                    carrier + " · allow mobile signal details");
        }

        int level = signalStrength == null ? 0 : signalStrength.getLevel();
        boolean inService = serviceState == null
                || serviceState.getState() == ServiceState.STATE_IN_SERVICE;
        if (!inService) {
            level = 0;
        }
        String dataType = dataState == TelephonyManager.DATA_CONNECTED
                ? dataLabel(networkType, overrideNetworkType) : null;
        boolean roaming = serviceState != null && serviceState.getRoaming();
        String detail = carrier + " · " + (level > 0 ? level + " of 4 bars" : "No service")
                + (dataType == null ? "" : " · " + dataType)
                + (roaming ? " · roaming" : "");
        return new CellularStatus(true, Math.min(4, level), carrier, dataType, detail);
    }

    private boolean hasCellularRadio() {
        if (telephonyManager == null || !context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            return false;
        }
        try {
            int state = telephonyManager.getSimState();
            return state != TelephonyManager.SIM_STATE_ABSENT
                    && state != TelephonyManager.SIM_STATE_UNKNOWN;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String carrierName() {
        String carrier = null;
        if (serviceState != null) {
            try {
                CharSequence serviceCarrier = serviceState.getOperatorAlphaLong();
                carrier = serviceCarrier == null ? null : serviceCarrier.toString();
            } catch (SecurityException ignored) {
                carrier = null;
            }
        }
        if (carrier == null || carrier.trim().isEmpty()) {
            try {
                carrier = telephonyManager.getNetworkOperatorName();
            } catch (RuntimeException ignored) {
                carrier = null;
            }
        }
        return carrier == null || carrier.trim().isEmpty() ? "Mobile" : carrier.trim();
    }

    private String readBluetooth(boolean permissionNeeded) {
        if (bluetoothAdapter == null) {
            return "Bluetooth unavailable";
        }
        if (permissionNeeded) {
            return "BT details off";
        }
        try {
            if (!bluetoothAdapter.isEnabled()) {
                return "Bluetooth off";
            }
            List<BluetoothDevice> devices = new ArrayList<>();
            addConnected(devices, headsetProfile);
            addConnected(devices, audioProfile);
            if (devices.isEmpty()) {
                return "Bluetooth on";
            }
            String name = devices.get(0).getName();
            if (name == null || name.trim().isEmpty()) {
                name = "Connected device";
            }
            return "BT " + name + (devices.size() > 1 ? " +" + (devices.size() - 1) : "");
        } catch (SecurityException ignored) {
            return "BT details off";
        }
    }

    private void addConnected(List<BluetoothDevice> destination, BluetoothProfile profile) {
        if (profile == null) {
            return;
        }
        for (BluetoothDevice device : profile.getConnectedDevices()) {
            if (!destination.contains(device)) {
                destination.add(device);
            }
        }
    }

    private String readCall() {
        MediaAccessService.CallSnapshot call = MediaAccessService.callSnapshot();
        if (call.active) {
            String elapsed = elapsed(call.startedAtMillis);
            String state = call.state == null || call.state.isEmpty() ? "Call" : call.state;
            String caller = call.caller == null || call.caller.isEmpty()
                    ? call.appLabel : call.caller;
            return state + (caller == null || caller.isEmpty() ? "" : " · " + caller)
                    + (elapsed.isEmpty() ? "" : " · " + elapsed);
        }
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null && (audio.getMode() == AudioManager.MODE_IN_CALL
                || audio.getMode() == AudioManager.MODE_IN_COMMUNICATION)) {
            return "Call active";
        }
        return "No active call";
    }

    private static String dataLabel(int networkType, int override) {
        if (Build.VERSION.SDK_INT >= 31
                && override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) {
            return "5G+";
        }
        if (Build.VERSION.SDK_INT >= 30
                && (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
                || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE)) {
            return "5G";
        }
        if (Build.VERSION.SDK_INT >= 30
                && override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO) {
            return "4G+";
        }
        if (Build.VERSION.SDK_INT >= 30
                && override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA) {
            return "LTE+";
        }
        switch (networkType) {
            case TelephonyManager.NETWORK_TYPE_NR: return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE: return "LTE";
            case TelephonyManager.NETWORK_TYPE_HSPAP: return "H+";
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA: return "H";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B: return "3G";
            case TelephonyManager.NETWORK_TYPE_EDGE: return "E";
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_GSM: return "G";
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT: return "1x";
            default: return null;
        }
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String elapsed(long startedAtMillis) {
        if (startedAtMillis <= 0L) {
            return "";
        }
        long seconds = Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
        return String.format(Locale.US, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    @TargetApi(31)
    private final class ModernTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.DataConnectionStateListener,
            TelephonyCallback.DisplayInfoListener {
        @Override
        public void onSignalStrengthsChanged(SignalStrength strength) {
            signalStrength = strength;
        }

        @Override
        public void onServiceStateChanged(ServiceState state) {
            serviceState = state;
        }

        @Override
        public void onDataConnectionStateChanged(int state, int type) {
            dataState = state;
            networkType = type;
        }

        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo info) {
            networkType = info.getNetworkType();
            overrideNetworkType = info.getOverrideNetworkType();
        }
    }
}
