# OABL Link inside Open Headunit

The Dongfeng firmware's installer accepts these private APKs under
`com.koushikdutta.vysor`. Two separate apps cannot occupy that package. The combined
build packages Open Headunit's projection and the existing OABL Link implementation
in one APK, retaining the installed Open Headunit certificate and app data.

The `headunit` Android library compiles the existing `app/src/main/java` sources;
there is no second telemetry implementation. It uses the existing `core`, `wire`
and `bluetooth` modules. The standalone OABL Link build remains available.

The library manifest puts LinkService, its boot/update receiver, and its setup
activity in `:oabl_link`. Open Headunit skips projection initialization in that
process. Projection can exit/restart its process while Link continues sampling and
serving data. Link's foreground notification uses ID 8731; projection uses ID 1.
Link starts when Open Headunit opens, after boot, and after an in-place APK update.
Its notification opens the OABL Link setup screen, which also has a launcher entry.

From the sibling `open-headunit` checkout:

```sh
./gradlew -PheadunitVysor=true -PheadunitOablLink=true \
  :core:test :wire:test :app:testGithubDebugUnitTest :app:assembleGithubRelease
```

Set `-PoablLinkDir=/absolute/path/to/oabl/oabl-link` if the checkouts are not siblings.
The default build has no OABL dependency. The combined build requires the Dongfeng
package flag and Android 8+; the physical head unit runs Android 10. Build these
projects serially because they share module build directories. Sign the release
with the existing head-unit update certificate before installing with `adb install -r`.
Never uninstall or clear storage to apply the update.

On the AI box, retain the existing OABL dashboard and Energy app. Vehicle data uses
the existing Bluetooth or Wi-Fi connection, authentication, history and live stream.
If pairing was lost when replacing the earlier app, enter the code shown in OABL
Link once in the AI box's vehicle data connection screen. Open Headunit remains the
USB/wireless Android Auto projection receiver; telemetry does not claim the USB device.

The library keeps Bluetooth scanning available to Open Headunit, unlike the
standalone Link manifest's scan-permission removal. Its vehicle permission definitions
and requests must stay aligned with the standalone app manifest. All vehicle access
continues through the existing read-only Dongfeng property source.

Runtime data check on the connected head unit, with Link already running:

```sh
python3 tools/verify-headunit-link.py --serial <serial>
```

The script checks signed requests, rejection of unsigned requests, real Dongfeng
values, advancing history and the WebSocket stream. It reads Link's pairing notification
without printing the code. It does not install apps, restart services or change settings.
An AI box is still needed to verify its dashboard/Energy app during projection.
