# Compact navigation controls

The old four independent clock, speed, Home and media windows obscured Google Maps' search/category bar and bottom tabs. A second accessibility Back/Home rail also duplicated controls.

Navigation now uses one movable edge handle (76 × 108 dp) displaying time and GPS speed. It starts collapsed, away from the top search/maneuver area and bottom tabs in the observed 1920 × 970 Maps layout. Tap it to show Back, Home and Hide. The panel auto-collapses after eight seconds or an outside touch. Drag the handle to either edge and a comfortable vertical position; placement persists. Home closes the overlay and returns to the launcher. Back uses the existing accessibility service, with a clear setup message when unavailable.

Media controls appear only when Android reports a media session and only inside the explicitly opened panel, not as a permanent empty card. Media and main actions use 76dp touch targets. GPS standby uses zero with a waiting label, not a stale speed.

The generic app-control rail is suppressed while navigation's handle is active. Maps remains full-screen; the launcher does not pretend to resize/inset another application's viewport. A user-selected handle position can still cover map content, so drag it if another provider or route layout puts controls there.

Verification uses `NavigationOverlayTestRunner` over the installed Google Maps application: initial collapsed bounds, one-window nesting, tap to expand, duplicate-rail suppression, idle media omission, display bounds, automatic collapse, real injected drag and saved edge, and Home cleanup. Screenshots are `navigation-overlay-compact.png` and `navigation-overlay-expanded.png` in `docs/screenshots`.

Verified 2026-10-13: 16 overlay checks passed above actual Maps at 1920 × 970 / density 238. Debug build and lint passed. A drag cancellation/reparenting recursion found by the injected gesture test was corrected before the passing run. The emulator was cold-booted with host graphics to recover its unrelated System UI freeze; installed data was retained.

Manual verification: Maps search opened successfully with the collapsed handle; system Back dismissed the keyboard, and the Maps Back button returned to the map. The emulator's separate Android accessibility shortcut button was also covering the Maps directions control. Only OABL was registered as its target, so that shortcut target was removed on this emulator; the accessibility service remains enabled for Back. The shortcut can be re-enabled in Android Accessibility settings. The production app does not modify secure settings or ask for new privileges. An AI box with that system shortcut enabled will need the same shortcut-only setting adjustment.

Build tests with `./gradlew assembleDebugAndroidTest -PoablTestRunner=com.softiva.oabl.NavigationOverlayTestRunner`, then install the test APK and run `adb -s emulator-5554 shell am instrument -w com.softiva.oabl.test/com.softiva.oabl.NavigationOverlayTestRunner`.
