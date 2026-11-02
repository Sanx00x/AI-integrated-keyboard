# NOVA Keyboard

NOVA Keyboard is an Android keyboard project focused on English, Romanized Tamil (Tanglish), contextual word suggestions, and a customizable typing interface.

## Project contents
- `app/src/main`: Android application source, resources, manifest, and bundled prediction asset.
- `app/src/test`: local unit tests.
- `app/src/androidTest`: Android instrumentation tests.
- `gradle/`: Gradle wrapper and version catalog files.

## Build status
This repository is a source snapshot prepared from the supplied project archive. The Android module build file (`app/build.gradle.kts` or `app/build.gradle`) was not included in that archive, so this snapshot is not currently guaranteed to build independently. Add the matching module build file from the original working project before claiming a reproducible build.

## Notes
The bundled model asset is included for the keyboard runtime. The separate model/training repository contains the model-development scripts and dataset documentation.

## License
See `LICENSE`. Verify that all third-party assets and datasets retain their original licenses and attribution.
