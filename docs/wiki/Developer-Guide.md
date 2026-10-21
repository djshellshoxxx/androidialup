# Developer Guide

## Python prototype

```sh
cd prototype/python
python3 -m unittest discover -s tests -v     # 241 tests pass on this snapshot
```

## Android

CI (`.github/workflows/android-core-tests.yml`) runs Gradle with the module tests (`core-network`, `core-protocol`,
`core-modem`, `core-session`, `platform-android`, `app`) and compiles the Android adapter, using Gradle 9.6.0 and
Android platform 36. The README has the build and install steps for the Android app. Devices must run Android 10 (API 29)
or newer because the relay connection needs TLS 1.3.

Other workflows: `prototype-tests.yml` (Python prototype and an API 26 compatibility check) and
`cross-platform-build-audit.yml`. These were not run here (the Android build was not performed).
