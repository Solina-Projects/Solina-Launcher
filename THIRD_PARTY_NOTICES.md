# Third-party source and licensing notes

This file is a working inventory of known source provenance, not a substitute for the license files distributed with individual components. The repository must be audited component-by-component before a stable release.

## Known inherited source

- **Android Open Source Project (AOSP) Launcher3** — source and manifest comments in the repository identify AOSP copyright notices. Review the relevant file headers and preserve applicable notices.
- **Lawnchair** — the current tree includes Lawnchair-specific source and configuration. The root `LICENSE.txt` includes a Lawnchair copyright line and Apache License 2.0 text.
- **SystemUI libraries** — `.gitmodules` currently references the `platform_frameworks_libs_systemui` submodule hosted under the LawnchairLauncher GitHub organization. Its own source files and licenses must be reviewed alongside the pinned submodule revision.
- **Other dependencies** — Gradle declares AndroidX, Kotlin, Dagger, Retrofit/OkHttp, libsu, Smartspacer SDK, and other third-party libraries. Their applicable license information should be verified with the existing Gradle Licensee task and the notices shipped with the final APK.

## Compliance checklist before distribution

- Preserve each applicable upstream copyright and license notice.
- Include required license texts and NOTICE content with distributed source/binaries as required by each license.
- Ensure modified inherited files carry the notices required by their licenses.
- Re-run `./gradlew --no-daemon licensee` and review the result; a passing task does not by itself prove every bundled component has been inventoried.
- Inspect the final APK/AAB for bundled license notices and any unexpected app/package artifacts.
- Update this inventory when dependencies, submodules, or source origins change.

## Independence work

Public branding and product-specific behavior may be developed independently. Removing an upstream dependency requires replacing its use, validating the replacement, and passing build and runtime tests. Git history should remain accurate about the source lineage; a separate repository or history may document genuinely independent, clean-room implementation, but must not be used to misrepresent copied or modified code as independently authored.
