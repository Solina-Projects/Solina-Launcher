# Quickstep / Android 17 (API 37) audit

## Current facts

- The project compiles against Android API 37.
- The currently enabled Gradle variants are `solinaWithoutQuickstepGithubDebug` and `solinaWithoutQuickstepGithubRelease`.
- The current CI workflow builds those two variants only.
- The release manifest/configuration uses Quickstep SDK bounds 35–36. The Debug build overrides those bounds to 0–100000, which is a test override, not a compatibility declaration.
- Therefore neither a successful build nor the Debug SDK override establishes Android 17 Quickstep/QuickSwitch runtime support.

## Required next checks

1. Identify the exact launcher source commit corresponding to the installed `16.Dev.(#60)` APK before attributing device behavior to this branch.
2. Audit the API-specific compatibility implementation and manifest metadata for API 37, including any framework changes that affect Recents, gesture navigation, and QuickSwitch.
3. Add a dedicated Quickstep-enabled **debug-only** variant on a feature branch only after the source-set/flavor wiring is verified. Keep the existing distributable variants unchanged.
4. Build the candidate with CI and inspect the merged manifest and APK contents to confirm Quickstep code is actually packaged.
5. Test installation and runtime behavior on a disposable/test setup before changing the active HOME provider or installing a root module.
6. Only after those checks pass, consider a release variant or QuickSwitch module. Keep rollback instructions and preserve the currently selected launcher.

## Not yet verified

- A Quickstep-enabled API 37 APK has not been built by this audit.
- No on-device Recents/gesture test has been run.
- No QuickSwitch module has been installed and no system provider setting has been changed.
