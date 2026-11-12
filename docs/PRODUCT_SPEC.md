# OABL product specification

## Product promise

OABL should make an Android AI box feel like a calm in-car system rather than a tablet full of apps. The common driving actions must be one tap away, and customization must never turn the home screen into a maintenance task.

## Home information hierarchy

1. **Glance:** wallpaper, time, date, connectivity, and speed.
2. **Continue:** current audio/video session in a compact media widget.
3. **At hand:** two user-selected widgets.
4. **Go:** Navigation, four personal shortcuts, split pair, all apps, and settings.

The top-bar travel-status pill preserves the glance hierarchy while surfacing a level-aware Wi-Fi icon and SSID, the connected Bluetooth audio/headset device, and—only on hardware with a SIM—a cellular signal icon, carrier, and carrier-branded data type such as LTE, 5G, or 5G+. During a call it changes priority and shows the call state, available caller label, and elapsed time. A tap reveals full status plus relevant Wi-Fi, mobile-network, Bluetooth, and notification-access actions.

The launcher has two experience profiles. **Visuals** uses short property animations for entrance hierarchy, press feedback, media-state changes, app-drawer reveal, activity fades, theme changes, and navigation-overlay arrival. **Performance** keeps identical behavior and layout while bypassing all nonessential motion. Appearance is independently selectable as Dark, Light, or Auto; Auto resolves Android's current night-mode configuration and updates when it changes.

The home page is deliberately fixed to one screen. All-app browsing happens in a temporary drawer rather than additional home pages.

## Interactions

| Surface | Tap | Long-press |
| --- | --- | --- |
| Wallpaper / speed area | Start the navigation display | None |
| Navigation | Open chosen map full-screen with floating driving controls | None |
| Media controls | Previous, play/pause, next | None |
| Widget slot | Add or use widget | Remove widget |
| Shortcut 1–4 | Open app | Replace shortcut |
| Split pair | Launch saved pair | Edit left/right apps, swap sides, launch, or exit the editor |
| Apps | Open all-app drawer | None |
| Settings | Open launcher settings | None |

## Navigation display

Navigation is an explicit, driver-initiated mode. The chosen map application owns the whole display so mapping, route guidance, and touch behavior stay native. OABL adds four restrained floating islands: a large clock and date, central GPS speed, Home, and compact media controls. Returning Home stops the overlay and its foreground location service.

This design intentionally does not infer navigation from another app's UI or notification text. It is predictable across firmware variants and makes location/overlay use visible to the driver.

## Background and lock-style presentation

The home screen may use the built-in dark teal gradient or a user-selected image. The image can be shown with a gradient treatment or a lightweight soft-blur treatment suitable for Android 10 hardware. This is a glance/idle presentation within the HOME app; it is not a replacement for Android's secure lock screen.

## Split-screen modes

### Portable mode

OABL stores a pair of launchable activities and uses Android's multi-window intent flags. On Android 12L+, OABL opens the right app adjacent to its foreground task, then replaces its own pane with the left app. This is a best-effort system request, but it needs no accessibility service.

### Legacy assisted mode

On Android 7–12, the optional accessibility service opens the first app, asks Android to toggle split screen, then opens the second app adjacent to it. OABL first checks that the system actually exposes the split action. The user adjusts the native Android divider. The service declares `canRetrieveWindowContent=false`.

Long-pressing the split-pair tile opens its editor. **Exit split view** removes OABL's active split pane, lets Android collapse the remaining app to fullscreen, and then returns to the full OABL dashboard.

### Unsupported-device boundary

An ordinary distributed app cannot force two unrelated third-party apps into separate interactive windows when Android or the OEM disables multi-window. OABL explains the failure and links to the optional developer setting **Force activities to be resizable**. Rooted/OEM builds may provide a privileged adapter, but that capability is not part of the public APK.

### Future OEM mode

Manufacturers or rooted-device distributors can supply a separately signed adapter with privileged window-management access. That adapter must not be required by the public launcher and must never silently change system partitions.

## Distribution principles

- Keep the public launcher independent of Dongfeng/LY private APIs.
- No network permission in the base APK.
- No telemetry in the base APK.
- All optional system access is user-initiated and explained at the point of use.
- Preserve a route back to Android settings and the original launcher.
- Test HOME selection, boot behavior, Recents, split-screen exit, and uninstall on each firmware family.

## Acceptance criteria for 0.1

- Builds cleanly for API 24–36.
- Can be selected as HOME on Android 10.
- App drawer reflects installed launcher activities.
- Four shortcut selections persist after reboot.
- Wallpaper selection persists after reboot.
- Gradient/soft-blur selection persists after reboot.
- Central speed handles granted, denied, unavailable, and moving GPS states.
- Navigation opens the selected map full-screen and keeps clock, speed, Home, and media available.
- Returning Home removes the navigation overlay and stops location updates.
- Two widget IDs survive activity recreation and reboot.
- Media panel handles granted, denied, playing, paused, and no-session states.
- Split helper fails with an explanation when firmware rejects multi-window.
- The launcher remains usable without media, accessibility, widget, or wallpaper permissions.
