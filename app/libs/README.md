# app/libs/

This directory is where `app/build.gradle.kts` expects to find:

```
sherpa-onnx-1.13.5.aar
```

**This file is not included in the repository.** It is a large binary build
artifact; the environment that built the Supertonic TTS integration (see
`docs/VOICE.md`) has no network access to fetch it, and this project's
convention (`CLAUDE.md`) is to never commit models/binaries the app doesn't
build itself. Add the real AAR here before running `./gradlew assembleDebug` —
the build will fail at the `:app` module's dependency resolution / AAR
exploding step until it exists.

Do not rename it and do not add a second sherpa-onnx AAR or a
`com.k2fsa.sherpa.onnx:*` Maven dependency alongside it — `SupertonicTtsEngine`
is written against this one local artifact only, and two copies of sherpa-onnx's
bundled native ONNX Runtime in the same APK is exactly the packaging conflict
`app/build.gradle.kts`'s `jniLibs.pickFirsts` note warns about.

Once added, verify with:

```bash
./gradlew :app:assembleDebug
```
