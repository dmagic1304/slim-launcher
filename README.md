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
  - Move left / right (favorites only)
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
