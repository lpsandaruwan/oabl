# Dashboard design rationale

Updated 2026-10-12 for the 1920 × 970 AI box display. This is a design iteration informed by published automotive guidance, not a user-tested or certified driving interface.

## Sources

- [Google automotive visual principles](https://developers.google.com/cars/design/design-foundations/visual-principles): glanceable information, legible text, consistent interactions, clear active state, and large touch targets.
- [Google automotive layout](https://developers.google.com/cars/design/automotive-os/design-system/layout): consistent placement, spacing, and grouping.
- [Google automotive sizing](https://developers.google.com/cars/design/automotive-os/design-system/sizing): 76 × 76 dp touch targets on the full-size dashboard. Compact screens use smaller controls and need separate physical usability validation.

## Applied decisions

- Keep the large clock and date together in the header. Remove the surrounding clock card to establish one clear primary element.
- Move speed into the header as a quieter secondary readout, without the oversized circle obscuring the wallpaper.
- Keep connection status and the navigation action in stable header positions.
- Align calendar and media inside one shared surface. A subtle internal separator establishes grouping without outlining every item.
- Give media more width, larger typography, 76 dp controls, and a distinctive play/pause action.
- Group four shortcuts, split pair, Apps, and Settings in one dock. Individual items retain pressed and keyboard focus states without permanent tile borders.
- Preserve wallpaper selection/top anchoring, theme modes, animation preference, travel details, app selection, and split-pair long-press actions.
- Reduce the Back/Home rail background from roughly 90% to 33% opacity, remove its border and elevation, and retain full-opacity glyphs with a small contrast shadow. Touch targets stay 76 dp.

## Validation

The debug build and Android lint complete successfully (the project retains existing warnings). Installed and inspected on the Android 13 Bengal box at native 1920 × 970: Light and Dark modes, 96 sp clock, updated media fallback, transparent rail over YouTube, Back returning to Home, and Home returning to the launcher and removing its rail. Restored the user's Auto appearance setting. Four shortcuts, split pair, Apps, Settings, calendar, travel status, and navigation remain present; the newly selected SpeedPlay shortcut is preserved.

Screenshots: `screenshots/home-v4-1920x970.png` and `screenshots/app-controls-v4-1920x970.png`.

Phone projection testing is unnecessary for this launcher design change; the user found Android Auto already available and asked to leave that installation alone.
