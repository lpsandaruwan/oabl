# Actual OABL Link → OABL simulation

This runs the delivered **Link 0.1.2** and **launcher 0.1.7** APKs on the Android emulator. An additional, emulator-only instrumentation APK selects a phase of Link's existing `SimulatedCar`. Production APKs, their read-only HTTP/WebSocket protocol, signing, SQLite bridge and the launcher client are unchanged. The installed APK hashes were checked against the delivered bundles.

The Android emulator cannot reproduce the Dongfeng vendor service or establish physical vehicle/Bluetooth compatibility. The scenario test uses the real signed Wi-Fi path on IPv6 loopback between the two apps on one emulator. It does not replace Link with a mock HTTP server or inject values into OABL's database.

## Start

Start the existing Android AVD `OABL_Rooted_API_33`. From the repository root:

```sh
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew -p oabl-link :app:assembleDebugAndroidTest
bash tools/simulation/start.sh emulator-5554
```

Keep that terminal running. The controller stays active for two hours. It adjusts only the existing simulator's elapsed-time origin on its sample worker; timestamps, sampling and delivery still use the normal service. This is test instrumentation, with no control entry point added to either production app. Instrumentation finishes by stopping its target process, so the controller must remain active during the demonstration.

The current emulator is already paired. For a fresh emulator, open OABL Link and read its six-digit code. In OABL, open Settings → Driving → Vehicle data source → Pair with the head unit, select Wi-Fi and enter that code with address `[::1]:8731`. This address is valid because both apps run on the same Android emulator.

## Switch the simulated car

Run these in a second terminal, from the repository root:

```sh
python3 tools/simulation/scenario.py charging
python3 tools/simulation/scenario.py unplugged
python3 tools/simulation/scenario.py driving
python3 tools/simulation/scenario.py parked
python3 tools/simulation/scenario.py idle
```

The controller jumps only when the phase changes. The ordinary timeline continues from there; selecting the already active phase does not rewind it. `charging` starts around 47.5%, `unplugged` at 55%, `driving` near 54% and 60 km/h, and `parked` near 53.4%. Values evolve at the original script's pace. To restart the same phase, choose another phase first.

The tool reads the actual signed `/v1/hello` and `/v1/now` responses. To save telemetry, the current emulator screenshot and UI text:

```sh
python3 tools/simulation/scenario.py driving --output /tmp/oabl-driving
```

Only an `emulator-NNNN` serial with `ranchu` or `goldfish` hardware is accepted. No physical car, AI box or phone is changed.

## Profile settings

Open the Energy page → Vehicle and energy. The example profile left in this emulator uses Practical **300 km**, usable **40 kWh**, gross **42.3 kWh**, reserve **15%**, idle **C / 1.2 kW**. These are test inputs, not verified vehicle specifications. They affect OABL's own estimates and manual-history comparisons; they do not rewrite the car's reported SOC or range. Manual history remains separate from raw live samples.

Before changes, the emulator's launcher preferences and database were backed up to `docs/ev-cockpit/simulation-2026-10-23/emulator-launcher-before.tar` and `profile-before.xml`. The simulation changes were made through the actual profile UI.

## Stop

```sh
adb -s emulator-5554 shell am force-stop com.koushikdutta.vysor
```

That stops the controller and Link service in the emulator. Reopen Link and Start link service to return to its ordinary timeline. The extra controller package can be removed with `adb -s emulator-5554 uninstall com.koushikdutta.vysor.test`; it contains no vehicle functionality.

See [scenario evidence and findings](../../docs/ev-cockpit/simulation-2026-10-23/README.md).

## Car energy update

Launcher 0.1.7 adds complete energy archives, a typed reset challenge and foreground collection, retaining the five-point charge prompt; see [behavior](../../dist/oabl-launcher-0.1.7/README.md) and [verification](../../docs/ev-cockpit/energy-retention-2026-10-23/README.md). The original scenario report used 0.1.3. The new `idle` phase holds simulated input at 720s (READY on, speed zero), keeping ordinary sampling/timestamps active. Other phases continue at the original pace.
