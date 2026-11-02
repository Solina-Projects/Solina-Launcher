# Solina-Launcher-Test branch integration audit

Updated: 2026-11-02

This document records the selective integration of the remaining Solina branches into
`Solina-Launcher-Test`. Branches were compared by changed paths and file content. A
branch was not merged wholesale where it would replace newer target implementations.

## Integrated

- `feature/quickstep-api37-audit`
  - Added the API 37 compatibility audit and read-only device validation checklist.
  - Integrated the distinct multi-row / multi-page hotseat database capacity fix into
    `LoaderCursor`, including bounds validation and unit coverage.
  - Did not import the experimental API 37 manifest or its alternate Gradle flavor.
    The manifest requests signature/privileged permissions and the debug SDK override
    is not proof of runtime support. Device validation remains required.
- `feature/solina-persian-general`
  - Added the English/Persian locale configuration and connected it through the
    application manifest. The current target already contains the Persian calendar,
    numeral preferences, and the relevant General settings; older branch versions
    were not allowed to overwrite newer dock and settings code.
- `feature/solina-persian-lock`
  - Added device-credential based App Lock storage, UI, unlock activity, navigation,
    and launcher-originated launch interception.
  - Added the Solar Hijri calendar widget and its provider resources.
  - Added local smart-folder generation, preserving the target's folder visibility,
    validation, and retry behavior.
  - Smart-folder matching is performed locally from installed-app package names and
    labels; it does not upload app inventory.
- `feature/solina-sections-2-6`
  - Reviewed its changed files. The remaining differences overlap with the newer
    settings, dock, and model implementations already present in the target; no
    wholesale merge was performed.
- `fix/dock-settings-backup-stability` and `fix/dock-vertical-row-spacing`
  - Retained the target's newer row-space factor, horizontal dock spacing, label
    positioning, and backup hardening. The older DeviceProfile/CellLayout variants
    would regress or conflict with those changes.
- `feature/solina-core-2-6` and `fix/solina-dock-spacing-core`
  - Compared against the target; neither contains commits ahead of the test branch.

## App Lock scope

App Lock uses Android's existing secure device-credential confirmation; Solina does
not store a PIN or password. The current interception covers app launches initiated
through the launcher's `startActivitySafely` path. It is not a system-wide app-lock
service and does not claim to block launches from notifications, other apps, or
system Recents.

## Quickstep / API 37 safety

The Quickstep API 37 manifest from the source branch is deliberately not enabled.
Manifest declarations cannot grant privileged permissions, and successful builds do
not establish Android 17 gesture / Recents runtime compatibility. Use the device
checklist and record actual device/ROM evidence before changing compatibility bounds
or enabling experimental system components.

## Build status

The four Standard/Quickstep Debug/Release variants passed in the earlier CI run
`41381641486` before these additional integrations. A fresh build of this updated
commit is still required; previous APK artifacts must not be treated as builds of
this updated source state.
