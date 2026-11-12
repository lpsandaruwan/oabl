# OABL Link

For using vehicle data alongside Open Headunit on the Dongfeng head unit, use the
[combined Open Headunit + OABL Link build](headunit/README.md). Both private standalone
APKs use the same firmware-accepted package ID, so they must be combined to coexist.

**Current transport status:** Bluetooth only, since 5 November 2026. The HTTP, WebSocket and mDNS parts described in older notes are gone, and Link has no network permission of its own. See [both APKs and pairing instructions](../dist/oabl-link-0.1.2/README.md) and [verified results / remaining vehicle checks](../docs/ev-cockpit/bluetooth-integration-2026-10-23/README.md). The shared `:wire` and `:bluetooth` modules are also included by the launcher build; build these projects serially. Older roadmap statements below predate this update.

For the verified Nammi firmware access method and the complete data inventory, start with
[`../docs/ev-cockpit/nammi-telemetry/README.md`](../docs/ev-cockpit/nammi-telemetry/README.md).
It includes the missing `df.car.permission.*` definitions that unlocked odometer and other reads,
the independent full-scan probe, saved captures, and exact viewing/export commands. The permission
assumptions further down this README predate those device tests.

The read-only head-unit app that reads the car's data and serves it to the AI box
(see [`../docs/ev-cockpit/energy-companion.md`](../docs/ev-cockpit/energy-companion.md) §3). It is a
**standalone Gradle project**, deliberately separate from the OpenAiBoxLauncher build, so work here
never disturbs the launcher.

It only ever reads. There is no code path that writes a vehicle property, sends a command, opens adb
or contacts the internet.

## Layout

- **`:core`** — pure Java, no Android, unit-tested on the JVM:
  - `Signal` — the signals and their fixed units (OVMS/ABRP names, tiers 1–3, §3.2).
  - `Reading`, `Sample`, `SampleBuffer` — a reading, a numbered change, and a short bridge store that
    records only what changes and lets the box catch up by sequence number. The AI box keeps the full
    history in energy.db; the head unit holds only a few hours, enough to cover a box reboot.
  - `SimulatedCar` — a scripted charge → unplug → drive → park, so the whole path is testable with no
    car and no device.
  - `DongfengProbe` + `PropertySource` — the real probe's decoding, verified against the exact values
    the 20 Oct 2026 survey read off the car.
  - `Protocol` — the `hello` / `now` / `samples` JSON, and reading it back on the box side.
  - `Pairing` — the 6-digit code, the shared key, and HMAC request signing.
- **`:app`** — the Android app (this device: the head unit, not the box):
  - `CarPropertySource` — binds to Dongfeng's `df.car` API (`dftc.car.ICar`) by reflection, so it
    compiles without the head unit's system jar and uses the real classes at runtime.
  - `ProbeActivity` — a read-only viewer that lists the live signals once a second, for confirming the
    reads and the still-unconfirmed scales on the head unit and on a drive.

## Build and test

```bash
cd oabl-link
./gradlew :core:test        # the JVM tests (no device)
./gradlew :app:assembleDebug  # the installable probe viewer
```

## What the car exposes (from the survey)

The head unit is Android Automotive 10 with Dongfeng's `dfcar_service`. The standard Android car
properties are stubs; the real values are Dongfeng vendor properties, mapped in `DongfengProbe`.

| Readable by an ordinary app (normal / grantable permission) | Needs a privileged permission (an ordinary app can't) |
|---|---|
| battery % (`CAR_ENERGY`), range estimate, charge state / plug / power, speed (`CAR_SPEED` / `CAR_INFO`), outside temp, READY | **odometer** and **gear position** (`CAR_MILEAGE`, signature\|privileged) |

So live mode supplies battery, range, charge and speed automatically; the **odometer stays a manual
entry** (energy-companion.md §2), and the probe never invents what it can't read. Speed, outside
temperature and the gear codes beyond Park were read only while parked — their scales and codes are
marked in `DongfengProbe` and confirmed on the first drive with adb attached before anything trusts
them.

## Not done yet

- The Android service, the HTTP + WebSocket server, and mDNS (the distributing side).
- The box's `CarLinkService` (the consuming side, inside OABL).
- Installing on the head unit needs the user's OK, and granting `CAR_SPEED` / `CAR_INFO` once over
  adb (`pm grant com.softiva.oabllink <permission>`). Never done yet.
