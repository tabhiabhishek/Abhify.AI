# Development setup (Android)

**Desktop app setup:** see "Desktop and web app" in the [README](../README.md#desktop-and-web-app).

## Tools (as used on the build machine)
| Tool | Version | Location used |
|---|---|---|
| JDK | 17 | `D:\AndroidDev\jdk17` |
| Gradle | 8.14.3 (wrapper) | cache in `D:\AndroidDev\gradle-home` (`GRADLE_USER_HOME`) |
| Android SDK | platform 36, build-tools 36.1.0, platform-tools | `D:\AndroidDev\sdk` |
| NDK / CMake | 28.2.13676358 / 3.22 or newer | from the SDK |

**Debug key:** `C:\Users\<you>\.android\debug.keystore` (SHA-1 `AF:35:DE:7A:…:63:51`). **Keep `ANDROID_USER_HOME` unset**, otherwise Gradle creates another key. Phones then refuse the update, and the Drive OAuth client stops matching.

## Build
```bash
cd android
export JAVA_HOME=D:/AndroidDev/jdk17 GRADLE_USER_HOME=D:/AndroidDev/gradle-home
unset ANDROID_USER_HOME
./gradlew testDebugUnitTest            # JVM tests
./gradlew lintDebug                    # lint (report: app/build/reports/lint-results-debug.txt)
./gradlew assembleDebug                # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebugAndroidTest     # on-device tests APK
```
- **Offline builds:** add `--offline` after the first successful online build. The first native build downloads llama.cpp, verified by SHA-256.
- **Release:** there is no release signing on purpose. See [V4_0_PRODUCTION_READINESS.md](V4_0_PRODUCTION_READINESS.md).
- **Clean packaging:** if an APK grows unexpectedly, delete `app/build/outputs/apk`, `app/build/intermediates/apk` and `app/build/intermediates/incremental/packageDebug*` before `assembleDebug`. Incremental packaging can leave gaps.

## Install on a phone
```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk     # -r = update, keeps all data
```
**Never uninstall to "fix" an install error:** that deletes chats, documents and memories. See the README's troubleshooting.

## Version numbers
`versionCode = major·1,000,000 + minor·10,000 + patch·100 + 99`. For example 4.4.0 → 4040099 and 4.3.1 → 4030199.

## Database changes
1. Add `chat/SchemaVn.kt` (additive statements only).
2. Bump `ChatDb.VERSION`, and add the schema to `onCreate` and to `onUpgrade` (`if (oldVersion < n)`).
3. Add a JVM migration test, and a check in `androidTest/DbMigrationTest`.
