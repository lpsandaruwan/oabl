# Compatibility and test matrix

## Capability tiers

| Capability | Standard Android box | Android 10 target unit | OEM/root integration |
| --- | --- | --- | --- |
| HOME replacement | Expected | To verify on device | Expected |
| App drawer and shortcuts | Expected | Expected | Expected |
| Wallpaper | Expected | To verify vendor picker | Expected |
| GPS speed | Requires location permission and GPS provider | To verify while driving | Expected |
| Full-screen map + floating controls | Requires user-approved overlay access | To verify touch/overscan | Can be system integrated |
| Standard app widgets | Firmware must include widget picker/service | To verify | Can be provisioned by OEM |
| Media metadata and controls | Requires user-granted notification access and app MediaSession | Expected | Expected |
| Adjustable two-app split | Direct on Android 12L+; firmware/app dependent elsewhere | To verify | Can be made deterministic |
| Arbitrary app embedded in launcher panel | Not supported | Not supported | Requires privileged OEM implementation |

## Physical-device checklist

- [ ] Install through the box's accepted installation path.
- [ ] Confirm the Android HOME chooser appears.
- [ ] Select OABL once, then select it as the default.
- [ ] Reboot and confirm the launcher returns without a blank period or crash.
- [ ] Open every stock/vendor app from the drawer.
- [ ] Verify Maps, YouTube, Spotify/music, Bluetooth, Files, and Settings shortcuts.
- [ ] Grant media access and test play, pause, previous, next, artwork, and app opening.
- [ ] Test media after the source app is killed and restarted.
- [ ] Add, resize-through-provider, remove, and restore two widgets.
- [ ] Pick a large camera photo as wallpaper and observe memory use.
- [ ] Compare gradient and soft-blur wallpaper treatments.
- [ ] Grant location, drive at low and road speed, and compare the speed display with the vehicle.
- [ ] Grant overlay access and verify Navigation opens Maps full-screen.
- [ ] Verify map gestures work outside the four floating control islands.
- [ ] Press Home and verify every navigation overlay disappears immediately.
- [ ] Enable split assistant; test Maps + music and Maps + video.
- [ ] Drag the native split divider to both extremes.
- [ ] Exit split screen and confirm Home returns correctly.
- [ ] Disable media and accessibility access; confirm graceful fallback.
- [ ] Switch back to the stock launcher and uninstall OABL.

## Known Android 10 considerations

- Split-screen entry is controlled by System UI and can be removed or modified by the manufacturer.
- An app that declares itself non-resizable may refuse split screen.
- The widget picker is part of the firmware experience and may be missing on heavily customized boxes.
- A media app must publish a MediaSession for the launcher to show its metadata and controls.
- Overlay permission is a special setting controlled by the firmware; some boxes may rename or remove its settings page.
- Speed depends on GPS fixes from the AI box. Boxes without a GPS receiver will show an em dash without affecting navigation or launcher use.
