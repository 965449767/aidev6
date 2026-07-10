# Build Environment Setup

This project relies on a local Android SDK and optional keystore configuration.

## Required files

- `local.properties` (not committed)
- `keystore.properties` (not committed)

The repository includes these examples:
- `local.properties.sample`
- `keystore.properties.example`

## Local SDK configuration

Create `local.properties` with your SDK path:

```properties
sdk.dir=/path/to/Android/Sdk
```

Alternatively set one of these environment variables:

- `ANDROID_HOME`
- `ANDROID_SDK_ROOT`

You can also use the helper script to initialize `local.properties` from your environment:

```bash
sh scripts/setup-local-env.sh
```

## Keystore configuration

Copy `keystore.properties.example` to `keystore.properties` and update values if needed.

For local debug builds, the default values are:

```properties
storeFile=keystore/debug.keystore
storePassword=aidev123
keyAlias=aidev-debug
keyPassword=aidev123
```

## Optional asset preparation

The project provides an optional Gradle task to download a bundled `curl` binary:

```bash
./gradlew downloadCurlMusl
```

Run this only when your environment needs the asset, or enable it automatically during a build with:

```bash
./gradlew assembleDebug -PdownloadCurlMusl=true
```

This avoids forcing a network-dependent download on every Gradle build.

## ARM64 / QEMU build compatibility

Some ARM64 or QEMU environments require overriding Gradle's default `aapt2` binary.
You can configure this locally using `local.properties` or environment variables:

```properties
android.aapt2FromMavenOverride=/path/to/aapt2
android.aapt2DaemonMode=false
```

If you encounter errors during resource processing, add these values to `local.properties`.

## Version scheme

By default the app version is generated from Git metadata:

- `versionCode` defaults to the Git commit count on `HEAD`
- `versionName` defaults to `1.0.0-<git describe>`

For CI or manual builds, you can override these values:

```bash
./gradlew :app:assembleDebug -PversionCode=100 -PversionName=1.0.0
```

## Recommended build commands

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:lint
```
