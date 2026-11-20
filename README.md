# rxVision for Android

A phone launcher, part of [rxenv](https://github.com/RexSAS/rxenv). One drop of ink on a dark page.

- **Home** is a liquid drop holding the time, its edge always moving. Hold anywhere and it splits into a ring
  of your favourite apps around your finger, joined by sticky threads that stretch, sway and snap; slide onto
  one and let go, and its ink floods the screen as the app opens. Tap the drop for the calendar.
- **Gestures**: flick up for all apps, flick down for the notifications, double-tap empty space for widgets.
- **All apps**: type and the best match is first, ↵ opens it. Long-press an app to add it to the ring or take
  it out (up to 8). Settings: the theme (Dark, Light, Colour: multicoloured ink) and the ring's order (drag a
  drop onto another to swap).
- **Widgets**: the drop falls away, leaving the date and a page of widgets on a 4-column grid. Hold empty
  space for "add a widget" (a picker after Nothing OS's, with live previews); hold a widget to move or remove it.
- **Unlock**: the drop wells up out of the fingerprint scanner and rises to the centre.
- **Charging**: a slow stream of ink rises from the charging port into the drop, with drops riding it; unplug
  and it lets go at the bottom and falls up into the drop.
- The phone's wallpaper is kept a plain fill of the theme's colour; the launcher stays out of recent apps.

Needs Android 13+ (the liquid is a blur + alpha threshold `RenderEffect`). The scanner's position is measured
for the Nothing Phone (3a) (`SCANNER_Y` in `Liquid.java`): Android doesn't tell apps where it is.

## Build

Plain Android SDK command-line tools, no Gradle:

```sh
./build.sh          # -> build/rxVision.apk
./test.sh           # plain-Java tests
adb install -r build/rxVision.apk
```

Then press Home and pick rxVision as the default home app. After installing a new version, open any app and
come back: right after an install Android can show the new launcher black until it's redrawn.

`build.sh` creates a local `signing.keystore` on first run; it's git-ignored.

## Releases

Commit, then `./release.sh`: it builds, pushes, and creates the GitHub release `v<commit count>` with the APK.
Releases must be built with the same `signing.keystore`, or Android refuses the update.

The design prototype it started from (five directions, the liquid one picked) is on the `prototype` branch.

Instrument Serif is under the SIL Open Font License (`assets/OFL.txt`).
