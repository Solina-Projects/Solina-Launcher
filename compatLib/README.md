# Solina Launcher compatibility module (legacy Quickstep integration)

The `compatLib` module contains Android-version compatibility code for Quickstep, the system component that provides the Recents screen. It is inherited from the upstream launcher codebase and exists only for variants that still include that integration.

The standalone Solina Launcher build is intended to run without Quickstep/Recents integration. Keep this module isolated from that build; do not remove its source or license notices until all references are audited and the standalone variants build successfully.

Each submodule is named after the Android version codename, using the first letter for Android 15 and below and the full codename starting from Android 16 onward.

| Module | Android version |
|---|---:|
| compatLibVQ | 10 |
| compatLibVR | 11 |
| compatLibVS | 12 |
| compatLibVT | 13 |
| compatLibVU | 14 |
| compatLibVV | 15 |
| compatLibVBaklava | 16 |

This list does not guarantee compatibility across all Android versions; implementations may be incomplete or not fully functional.
