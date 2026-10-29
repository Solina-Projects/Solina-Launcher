# Quickstep / Android 17 (API 37) audit

## Current facts

- The project compiles against Android API 37.
- The currently enabled Gradle variants are `solinaWithoutQuickstepGithubDebug` and `solinaWithoutQuickstepGithubRelease`.
- The current CI workflow builds those two variants only.
- The release manifest/configuration uses Quickstep SDK bounds 35–36. The Debug build overrides those bounds to 0–100000, which is a test override, not a compatibility declaration.
- The shared launcher manifest declares `app.lawnchair.LawnchairLauncher` as HOME. The separate Quickstep manifest declares `com.android.quickstep.TouchInteractionService` for `android.intent.action.QUICKSTEP_SERVICE` and a `RecentsActivity`.
- That Quickstep manifest also requests system/signature-level permissions such as `STATUS_BAR_SERVICE`, `WRITE_SECURE_SETTINGS`, `MANAGE_ACTIVITY_TASKS`, and `INTERNAL_SYSTEM_WINDOW`. Packaging these components in a regular APK does not grant those privileges; the device/ROM integration and installation context matter.
- The compat factory chain currently ends at `QuickstepCompatFactoryVBaklava`, annotated `@RequiresApi(36)`, extending the API-35 factory. The configured Quickstep maximum SDK remains 36. This repository audit has not found an API-37-specific compat factory or proof that the API-36 implementation is sufficient for API 37.
- The `withoutQuickstep` flavor name is misleading in isolation: its source set includes `quickstep/src`, `quickstep/dagger`, and Quickstep resources, and it depends on compat libraries. However, that flavor uses the root `AndroidManifest.xml`, while the Quickstep service/Recents declarations are in `quickstep/AndroidManifest.xml`. The merged manifest must be inspected before concluding that the packaged APK exposes Quickstep.
- Therefore neither a successful build nor the Debug SDK override establishes Android 17 Quickstep/QuickSwitch runtime support.

## Required next checks

1. Identify the exact launcher source commit corresponding to the installed `16.Dev.(#60)` APK before attributing device behavior to this branch.
2. Audit the API-specific compatibility implementation and manifest metadata for API 37, including any framework changes that affect Recents, gesture navigation, and QuickSwitch.
3. Determine whether API 37 needs a new compat factory or whether the API-36 implementation is demonstrably compatible; do not widen the SDK range until that is supported by source review and tests.
4. Add a dedicated Quickstep-enabled **debug-only** variant on a feature branch only after the source-set/flavor and manifest-merger wiring is verified. Keep the existing distributable variants unchanged.
5. Build the candidate with CI and inspect the merged manifest and APK contents to confirm Quickstep code and service declarations are actually packaged.
6. Test installation and runtime behavior on a disposable/test setup before changing the active HOME provider or installing a root module.
7. Only after those checks pass, consider a release variant or QuickSwitch module. Keep rollback instructions and preserve the currently selected launcher.

## Not yet verified

- A Quickstep-enabled API 37 APK has not been built by this audit.
- The merged manifest of the current CI APK has not been inspected here.
- No on-device Recents/gesture test has been run.
- No QuickSwitch module has been installed and no system provider setting has been changed.
