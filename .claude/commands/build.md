Run the full release build & deploy pipeline for `shiroikuma.appmanager`, exactly as documented in `CLAUDE.md` → "Build & deploy pipeline".

Steps:
1. `tools/bump-build.sh` to bump `customBuildNumber`, then read `customBaseVersionName` and `customBuildNumber` from `gradle.properties` to derive the APK name.
2. Write `local.properties` with `sdk.dir=$ANDROID_HOME`.
3. `./gradlew clean` then `./gradlew :app:assembleRelease`, with `set -o pipefail` and the benign-output filter on both streams (`grep -vE 'ノート:|Note:|\[CXX5304\]'`). If the build fails, stop and surface the error.
4. Locate `*-unsigned.apk` under `app/build/outputs/apk/release/`, then `zipalign` + `apksigner sign` using the keystore at `~/.android-keystores/appmanager-custom.jks` (alias `appmanager`, both passwords `appmanager123`), then `apksigner verify --verbose` (filter out the benign `not protected by signature` warning).
5. Copy the signed APK to `~/tmp/<apk_name>` so a record stays on disk.
6. **Stop**, summarise what was built, and ask the user to connect the phone (USB debugging on) before pushing. Only when the user confirms, run `adb push /tmp/am-signed.apk /sdcard/tmp/<apk_name>`.

Use the actual bash tool; do not generate paste-ready shell blocks.
