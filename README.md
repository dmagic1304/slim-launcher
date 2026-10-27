# Slim Launcher

Minimal Android TV launcher: a favorites grid, an "All apps" view, and per-app rename / icon overrides.
One dependency (RecyclerView), no background services, no animations beyond a small focus zoom.

## Using it

- **Home screen**: your favorites, with an "All apps" tile at the end.
- **All apps**: every installed app. Back returns to the favorites.
- **Long-press OK/select on any app** to get:
  - App settings (opens the system app info page: clear cache/data, permissions, force stop)
  - Rename
  - Change icon (borrow another installed app's icon, or pick an image file)
  - Move (favorites only): the tile gets a blue outline; use the arrows to move it anywhere in the
    grid, then press OK or Back to place it
  - Add to / remove from favorites
  - Reset name and icon
- Example: long-press TizenTube > Rename > "YouTube", then Change icon > "Use another app's icon" > YouTube.
- On first run, favorites are seeded with the first 10 TV apps found; edit from there.

## Easiest build: GitHub Actions (no Android Studio)

1. Create a new private repo on GitHub and upload the contents of this folder (including the hidden
   `.github` folder), or push it with git.
2. Open the repo's **Actions** tab. The "Build APK" workflow runs on every push
   (or press "Run workflow").
3. When it's green (a few minutes), open the run and download **SlimLauncher-apk** from Artifacts.
   Unzip it to get `app-release.apk`, then continue with "Install on the Shield" below.

Every successful build is also published under the repo's **Releases**. To install straight from the
Shield with no computer: install the free **Downloader** app, then enter
`https://github.com/<you>/slim-launcher/releases/latest/download/app-release.apk`
(for a private repo the download needs a login, so make the repo public first, or use adb).
Allow "install unknown apps" for Downloader when prompted.

If the build fails, open the failed step and send me the red error text.

## Photos (full-resolution viewer)

The APK also installs a separate **Photos** app. It shows JPG, PNG and WebP files at the TV's real
output resolution (e.g. 3840x2160 on a 4K TV). Most Android TV photo apps draw at 1080p and
upscale, which is why photos can look soft.

- First open: allow access to photos when asked.
- Browse internal storage (or a USB stick) and press OK on a photo.
- In the viewer: Left/Right = previous/next, OK or Play/Pause = slideshow on/off (8 s per photo),
  Up/Down = show the file name, Back = back to the folder.
- The screen stays on (no screensaver) while a photo is shown. The TV's own auto-off timer still applies.
- Phone photos are rotated correctly using their EXIF orientation.

Copy photos to the Shield with Send Files to TV, a USB stick, or adb:

    adb push "C:\path\to\photo.jpg" /sdcard/Pictures/

If permission was refused: `adb shell pm grant com.dom.slimlauncher android.permission.READ_EXTERNAL_STORAGE`

## Background video (themes)

A muted video loop can play behind the launcher, under a dark overlay so icons stay readable.

1. Put one or more `.mp4` videos (1080p, 30-60 seconds, made to loop, is ideal) on your PC.
2. Copy them to the Shield (connect adb first):

       adb shell mkdir -p /sdcard/Android/data/com.dom.slimlauncher/files/themes
       adb push halloween.mp4 /sdcard/Android/data/com.dom.slimlauncher/files/themes/

3. Open Slim Launcher. If you never picked one, it uses the first video it finds. To choose, or to
   switch it off: long-press the **All apps** tile > **Background**.

To change the theme for a season, push another video and pick it from that menu. No update needed.

Prefer clips with no audio track. The video is muted, but a clip that has audio still opens an
audio stream, which can wake a soundbar/receiver. To strip audio from a clip on your PC:
`ffmpeg -i in.mp4 -an -c:v copy out.mp4`

Resource use: playback stops the moment you open an app (the video decoder is released for that
app), freezes on the current frame after 5 minutes with no remote key pressed, and resumes on the
next key press. If a video won't play, the plain background shows instead.

## Build (Android Studio)

1. Install Android Studio and open this folder (`SlimLauncher`). Accept any SDK / Gradle downloads when it syncs.
2. Switch the build variant to `release` (Build > Select Build Variant), then
   Build > Build Bundle(s) / APK(s) > Build APK(s).
   The release variant is the optimized one and is signed with the debug key, so it installs as is.
   (Or from a terminal with the Android SDK installed: `./gradlew assembleRelease`.)
3. APK ends up in `app/build/outputs/apk/release/app-release.apk`.

## Install on the Shield

1. Shield: Settings > Device Preferences > About > press Build 7 times. Then Developer options > enable
   USB debugging / network debugging. Note the Shield's IP address (Settings > Network).
2. On your computer (adb is in Android Studio's `platform-tools`):

       adb connect <shield-ip>:5555
       adb install -r app-release.apk

3. Accept the debugging prompt on the TV the first time.
4. "Slim Launcher" now shows up in your apps row. Open it once to check it works.

## Home button takeover (recommended)

Like Projectivy's override, Slim Launcher has an optional accessibility service. When the stock
launcher appears (after a reboot, or after pressing Home), it switches back to Slim Launcher. It only
sees which app's window appeared and reads no screen content.

1. Go to Settings > Device Preferences > Accessibility. (Long-pressing the **All apps** tile and
   choosing "Turn on Home button takeover" opens Settings and shows the path.)
2. Enable **Slim Launcher Home button**.
3. Test: press Home inside an app, then restart the Shield.

Limits: you may see the stock launcher flash for a moment before Slim Launcher appears. For a cleaner
result, also disable the stock launcher with the adb command in the next section. If you ever use
another launcher with its own override, turn one of them off, since two overrides fight each other.

## Making it the default (read before uninstalling Projectivy)

The Shield has no "default launcher" setting. Projectivy usually takes over Home with its own
"override" accessibility trick, and Slim Launcher doesn't have that. Pick one of these:

**Option A: keep Projectivy installed, let it hand off.** Open Slim Launcher from Projectivy and
test it for a few days. Simplest and fully reversible.

**Option B: disable the stock launcher via adb (the usual approach).**

    adb shell pm disable-user --user 0 com.google.android.tvlauncher

Disable or uninstall Projectivy's override first. The next Home press then opens Slim Launcher, or
asks which Home app to use. To undo it at any time:

    adb shell pm enable com.google.android.tvlauncher

Side effects of B: the stock home screen's Google/Assistant search row and "recommendations" are
gone (Projectivy users usually lose those anyway). Voice search still works from the remote's mic
button.

## Notes

- "Pick an image file" needs a file picker app on the device. If none is installed you'll see
  "No file picker found"; "Use another app's icon" always works.
- To change the app, edit `MainActivity.kt` (UI + menu) and `Store.kt` (app list, favorites, icons).
