# Maps follows the launcher's dark mode

The launcher draws its own light/dark palette (Appearance: Auto, Dark or Light) and does not use Android's night mode, so
Google Maps, which does, used to stay light on a dark dashboard.

How it works (launcher 0.1.121):

1. Whenever the launcher resolves its theme it writes `1` (dark) or `0` (light) to `files/night` (`SystemNightMode`).
2. `oabl-box-tune` (root, every 15 s) mirrors that into Android's night mode with `cmd uimode night yes|no`.
   Off while `persist.oabl.nightsync.off=1`.
3. Maps has to be told once to follow the device: avatar menu, Settings, Theme, **Same as device theme**.
4. Maps' guidance view has its own switch, which nothing outside Maps can change: Settings, Navigation settings, Map
   display, Color scheme. **Automatic** follows the sun at the car's position, **Night** is always dark. With the launcher on
   Dark, pick Night; with the launcher on Auto, leave it on Automatic.

The theme only re-resolves while the launcher is on screen, so a switch at dusk reaches Maps the next time Home is shown.
