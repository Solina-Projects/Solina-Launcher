# Quickstep / Android 17 (API 37) audit

## Current facts

- The project compiles against Android API 37.
- The existing distributable variants remain `solinaWithoutQuickstepGithubDebug` and `solinaWithoutQuickstepGithubRelease`.
- A dedicated experimental `solinaQuickstepGithubDebug` variant is now enabled on this feature branch only; it is debug-only and is not a release/distribution declaration.
- CI run [40815028487](https://github.com/Solina-Projects/Solina-Launcher/actions/runs/40815028487) completed successfully at commit `0c4c2de20beaecce7d3d4c78e07ec9bc7a221b1a`. Its `assembleSolinaQuickstepGithubDebug` artifact was downloaded and the ZIP was inspected: it contains `solinaQuickstepGithub/debug/solina-launcher.16.Dev.(#74).github.debug.apk` (121,388,144 bytes). This verifies the experimental variant was packaged, not that it works at runtime.
- The release manifest/configuration uses Quickstep SDK bounds 35–36. The Debug build overrides those bounds to 0–100000, which is a test override, not a compatibility declaration.
- The shared launcher manifest declares `app.lawnchair.LawnchairLauncher` as HOME. The experimental Quickstep manifest declares `com.android.quickstep.TouchInteractionService` for `android.intent.action.QUICKSTEP_SERVICE` and a `RecentsActivity`.
- That Quickstep manifest also requests system/signature-level permissions such as `STATUS_BAR_SERVICE`, `WRITE_SECURE_SETTINGS`, `MANAGE_ACTIVITY_TASKS`, and `INTERNAL_SYSTEM_WINDOW`. Packaging these components in a regular APK does not grant those privileges; the device/ROM integration and installation context matter.
- The compat factory chain currently ends at `QuickstepCompatFactoryVBaklava`, annotated `@RequiresApi(36)`, extending the API-35 factory. The configured Quickstep maximum SDK remains 36. This repository audit has not found an API-37-specific compat factory or proof that the API-36 implementation is sufficient for API 37.
- The `withoutQuickstep` flavor name is misleading in isolation: its source set includes `quickstep/src`, `quickstep/dagger`, and Quickstep resources, and it depends on compat libraries. However, that flavor uses the root `AndroidManifest.xml`, while the Quickstep service/Recents declarations are in `quickstep/AndroidManifest.xml`. The experimental flavor is configured to use `quickstep/AndroidManifest-api37-experimental.xml`.
- The APK's binary Android manifest was decoded from the downloaded artifact. It reports package `com.solina.launcher.debug`, `minSdkVersion=29`, `targetSdkVersion=37`, and includes the Quickstep service/action, Recents activity, search-indexables provider, gesture tutorial activities, widget-picker activity, and overview FileProvider. The Quickstep service is exported and protected by `android.permission.STATUS_BAR_SERVICE`; the widget-picker activity is exported and protected by the app-specific `signature|privileged` permission. This confirms manifest packaging, not that the OS grants the requested privileges or will bind/use the service.
- Therefore neither a successful build nor the Debug SDK override establishes Android 17 Quickstep/QuickSwitch runtime support.

## Required next checks

1. Compare the decoded manifest against Android 17 runtime expectations and ROM privilege grants; in particular, confirm whether the system recognizes and binds `com.android.quickstep.TouchInteractionService` for this package.
2. Audit the API-specific compatibility implementation and manifest metadata for API 37, including framework changes that affect Recents, gesture navigation, and QuickSwitch.
3. Determine whether API 37 needs a new compat factory or whether the API-36 implementation is demonstrably compatible; do not widen the SDK range until supported by source review and tests.
4. Resolve licensing/redistribution and system-integration requirements before preparing any installable QuickSwitch root module.
5. Test installation and runtime behavior on a disposable/test setup before changing the active HOME provider or installing a root module.
6. Only after those checks pass, consider a release variant or QuickSwitch module. Keep rollback instructions and preserve the currently selected launcher.

## Not yet verified

- Runtime recognition/binding of the Quickstep service and actual API 37 gesture/Recents behavior remain unverified.
- No on-device Recents/gesture test has been run.
- No QuickSwitch module has been built or installed, and no system provider setting has been changed.
