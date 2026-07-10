# AIDev6

Android terminal / dev environment app built with Jetpack Compose and embedded Ubuntu/Proot.

## Quick start

1. Copy SDK configuration:
   ```bash
   cp local.properties.sample local.properties
   # Edit local.properties so sdk.dir points to your Android SDK
   ```
2. Copy keystore properties if needed:
   ```bash
   cp keystore.properties.example keystore.properties
   ```
3. Optional: prepare bundled curl for ARM64 if your environment requires it:
   ```bash
   ./gradlew downloadCurlMusl
   # or enable it for a build with:
   ./gradlew :app:assembleDebug -PdownloadCurlMusl=true
   ```
4. (Optional) Generate local SDK configuration from environment variables:
   ```bash
   sh scripts/setup-local-env.sh
   ```
5. Build the app:
   ```bash
   ./gradlew :app:compileDebugKotlin
   ./gradlew :app:assembleDebug
   ```

## Notes

- `local.properties` and `keystore.properties` are intentionally excluded from source control.
- The project now uses modern AGP-compatible configuration and supports Gradle property overrides for `versionCode` and `versionName`.
- See `docs/build-environment.md` for more details.
