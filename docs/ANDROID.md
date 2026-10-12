# Getting and updating the Zohara Link Android app

There is no Play Store listing. GitHub builds the app from this repository.

## First install

1. On GitHub open **zohara-link > Releases > "Zohara Link for Android (latest build)"** and download `zohara-link-android.apk`
   (or, before the release exists: **Actions > Build Zohara Link Android app > newest green run > artifact zohara-link-android-apk**).
2. Open the APK on the phone. Android asks to allow installs from that source: allow it once.
3. On the computer: **Settings > Zohara Link**, press **Pair a phone**; in the app tap **Find my computer**, check the code and type the PIN.

## Updating

The app updates itself. When a newer build exists, the Home screen shows **A new version of Zohara Link is ready**: press **Update now**.
It downloads the APK and Android asks you to confirm the install (apps cannot install themselves silently). The first time, Android also
asks to allow Zohara Link to install apps. **Access > This app > Check for updates** looks again by hand.

How it works: every build on `main` that is signed with the release key is published as the release `android-latest` (one APK,
replaced each time, with `versionCode=N` in its notes). The app compares N with the installed build number and downloads from that
release only. Android refuses an APK signed with a different key than the installed app, so a tampered file cannot replace it.

## The signing key (one-time setup, done by the owner)

An update only installs over the old app when both are signed with the **same key**. Without the key, CI builds a *debug* APK with a
throwaway key (it installs, but cannot update the app, and is never published as an update).

Make the key once, keep two copies of it (losing it means everyone has to uninstall and reinstall), and add three GitHub secrets to
the `zohara-link` repository (Settings > Secrets and variables > Actions > New repository secret):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | the keystore file, as base64 text |
| `ANDROID_KEYSTORE_PASSWORD` | the keystore password |
| `ANDROID_KEY_PASSWORD` | the key password (alias `upload`) |

Make one with: `keytool -genkeypair -keystore zohara-link-release.jks -alias upload -keyalg RSA -keysize 2048 -validity 36500`
and encode it with `base64 -w0 zohara-link-release.jks`. Never commit the file or the passwords; the repository is public.
After the secrets exist, the next build on `main` is signed and published. **Switching to the release key means uninstalling the
old debug-signed app once**; after that, updates install over the app.

## Notes

* Build numbers grow with every CI run (run number + 100). A local build is build 1 and will not offer itself as an update.
* The first builds were never run on a phone: report what does not work.
