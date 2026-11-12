# Home navigation guidance

The home GPS speed is 76 sp (previously 58 sp); the compact-screen variant is 48 sp (previously 40 sp). Its header area grows to 160 × 136 dp on the AI box.

An active navigation notification creates a 242 dp panel at the far right of the shared calendar/media surface. Without guidance the panel and separator are gone, restoring the full media width. The existing four shortcuts, split pair, Apps, Settings, wallpaper, clock, themes, and media actions remain.

## Data source

Uses the existing user-approved `MediaAccessService` notification listener. No additional permission, screen reading, network request, location upload, or persistent route history is added.

- Accepts ongoing non-summary navigation notifications from Maps, Waze, or the user's selected navigation provider.
- Recognizes the Android navigation category or a legacy explicitly named navigation channel/tag with a neutral status/service category.
- Rejects unrelated apps, promotions, location sharing, nonongoing suggestions, group summaries, and empty guidance.
- Displays the provider's title, text (expanded text as fallback), subtext/info (where supplied), and large icon. Uses the provider app icon when no guidance icon is supplied. Does not infer maneuvers, translate directions, calculate ETA, or claim a missing field is available.
- Tapping uses the provider's notification content intent, with launch-provider fallback when the intent is unavailable or canceled.
- Clears guidance on notification removal, listener disconnection, or notification access revocation.

Sources: [Android notifications](https://developer.android.com/reference/android/app/Notification) and [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService).

Phone-based Android Auto guidance is not visible to the box's notification listener unless its receiver forwards it as a navigation notification. This change does not add a phone companion or a proprietary SpeedPlay protocol integration.

## Verification

Debug APK, test APK, and Android lint build successfully. `NavigationHintsTestRunner` completes 27 assertions in the existing tablet emulator at 1920 × 970 / 238 dpi. Checks include provider filtering, rejected nonnavigation notifications, localized/fallback text, Light/Dark placement, all four media control bounds, and hidden-panel cleanup. The runner restores its original theme and live snapshot after testing.

Example screenshots contain explicitly labelled **TEST GUIDANCE**, not a live route: `screenshots/navigation-hints-test-light.png` and `screenshots/navigation-hints-test-dark.png`. An actual Maps route remains to be checked on the Android 13 box when ADB reconnects.

Run after installing both debug APKs on the chosen test device:

```sh
adb -s DEVICE shell am instrument -w com.softiva.oabl.test/com.softiva.oabl.NavigationHintsTestRunner
```

The test device must be awake/unlocked and OABL notification access must be enabled. Sample snapshots are injected only from the separately installed test APK; production code contains no demo route.
