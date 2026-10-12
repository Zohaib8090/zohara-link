# Getting the Zohara Link Android app

There is no Play Store listing. The app is built by GitHub from this repository and you install the APK by hand.

1. On GitHub open **zohara-link > Actions > "Build Zohara Link Android app"** and pick the newest green run (or press
   **Run workflow** to build the latest code).
2. At the bottom of the run page download **zohara-link-android-apk** (a zip with one `.apk` inside). Do it on the phone, or copy it there.
3. Open the APK on the phone. Android asks to allow installing apps from this source: allow it for your browser or file manager once.
4. On the computer: **Settings > Zohara Link**, set it up if needed, press **Pair a phone**. On the phone open Zohara Link, pick the computer,
   type the PIN shown in Settings, and check the phone shows the same code as "This computer's code".

Notes
* It is a **debug build** signed with a key made fresh for each build, so a newer APK will not install over an older one: uninstall the old app
  first, then pair again. A stable signing key (so updates install over each other) needs a keystore the owner creates and stores as a GitHub secret.
* Artifacts are kept for 30 days; run the workflow again for a new one.
* The first builds were never run on a phone: report what does not work.
