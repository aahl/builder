# CleanYourTV launcher (vendored)

`launcher/` is the home screen from [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv),
MIT, kept as a Gradle **library** module so one APK can be the launcher and the
ESPHome bridge at the same time.

- Upstream commit: `3c122bc` ("Round AU: fixes folded in on merge, tables and cards regenerated")
- Vendored from: `launcher/app/src/main/{java,res,AndroidManifest.xml}` and `launcher/app/src/test`
- License: see `LICENSE` in this directory (kept verbatim, MIT requires the notice)
- Not vendored: `build-and-install.sh|ps1`, `gradlew*`, `gradle.properties`, upstream
  `app/build.gradle`, `proguard-rules.pro` — the host app owns the build

## Local patches

Keep this list short: re-syncing means replacing `launcher/src/main` and
re-applying exactly these.

1. `src/main/AndroidManifest.xml` — `<application>` no longer sets `label`, `icon`,
   `banner`, `theme`, `localeConfig` or `allowBackup`; the app module owns the
   identity and inheriting `Theme.TvHome` app-wide would break the host's
   AppCompat activity. `Theme.TvHome` is therefore set on `.MainActivity` and
   `.LauncherSettingsActivity` directly.
2. `src/main/java/com/gathin/tvhome/AppAdapter.java` — the grid used to drop
   every activity in the launcher's own package; it now drops only the launcher's
   own `MainActivity`, so the host app's console (which lives in the same package)
   stays reachable. That screen is the only place the pairing code is shown.
3. `build.gradle.kts` in this directory — new file, `minSdk` raised from 26 to the
   host's 28, namespace and dependencies unchanged.

## Re-syncing

```bash
git -C /tmp/cyt log --oneline -1
rsync -a --delete /tmp/cyt/launcher/app/src/main/java/ launcher/src/main/java/
rsync -a --delete /tmp/cyt/launcher/app/src/main/res/  launcher/src/main/res/
cp /tmp/cyt/launcher/app/src/main/AndroidManifest.xml launcher/src/main/
cp /tmp/cyt/LICENSE third_party/cleanyourtv/LICENSE
```

then re-apply patches 1 and 2, and rebuild.
