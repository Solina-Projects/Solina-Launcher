# Android 17 Quickstep device test checklist

This checklist is for a disposable/test device running Android 17 (API 37). It is diagnostic-only: commands below inspect state and do not install packages, change HOME, alter secure settings, reboot, or install a root module.

## Before testing

- Record device model, ROM/build fingerprint, Android release, and API level.
- Record the exact APK filename, package name, version, and SHA-256.
- Keep the currently working HOME launcher selected.
- Do not treat a successful APK build or manifest inspection as proof that the OS has granted privileged permissions.

## Read-only diagnostics (ADB)

Run from a trusted workstation with USB debugging already configured:

```sh
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell cmd package list packages | grep -E 'solina.launcher|quickstep|teslacoilsw'
adb shell dumpsys package com.solina.launcher.debug
adb shell dumpsys activity services com.solina.launcher.debug
adb shell dumpsys activity activities
adb shell dumpsys window
```

If a command is unavailable on the ROM, record that fact rather than substituting a state-changing command. Save outputs privately and redact account identifiers or other personal data before sharing.

## Manual test matrix

| Test | Procedure | Pass evidence |
|---|---|---|
| Package visibility | Confirm the debug package is installed only if it was installed intentionally for this test | Package/version matches the recorded APK |
| Service registration | Inspect package/service dump for `com.android.quickstep.TouchInteractionService` and `android.intent.action.QUICKSTEP_SERVICE` | Service is present and the system recognizes/binds it; manifest presence alone is not enough |
| Privileged access | Inspect package and system logs for permission denial or service binding errors | No relevant permission/binding denial; do not infer grants merely from requested permissions |
| Overview | Use the device's normal, reversible navigation gesture/button flow | Recents opens and can return to the current app |
| Gesture navigation | Test Home/Back/overview gestures separately | Each expected gesture works without crashes or launcher loss |
| Animation callbacks | Repeat overview/app-transition actions and inspect logs for callback exceptions or hangs | No reproducible animation hang, crash, or callback failure |
| Stability | Repeat the same sequence several times, then return to the previous launcher normally | No boot loop, persistent HOME change, or loss of navigation |

## Logs to collect if a test fails

Capture a short, time-bounded log around the reproduction and filter for relevant terms such as `Quickstep`, `TouchInteractionService`, `RecentsActivity`, `RemoteTransition`, `ActivityTaskManager`, `SecurityException`, and `Permission Denial`. Avoid sharing an unrestricted full log because it may contain personal information.

## Result record

- Device / ROM:
- Build fingerprint:
- API level:
- APK SHA-256:
- Service registered and bound: PASS / FAIL / NOT TESTED
- Recents opens: PASS / FAIL / NOT TESTED
- Gestures: PASS / FAIL / NOT TESTED
- Animation callbacks: PASS / FAIL / NOT TESTED
- Relevant error excerpt:
- Rollback performed / needed:

## Safety boundary

This checklist does not authorize changing the default HOME app, writing secure settings, granting privileged permissions, installing a KernelSU module, or rebooting. Those actions require a separate explicit decision and a verified rollback path.