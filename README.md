# Solina Launcher

**Solina Launcher** is an open-source Android home app developed by **Solina**.

## Current test-branch features

- Multi-row and multi-page dock with adjustable horizontal/vertical spacing and independent icon-label positioning.
- Persian (Solar Hijri) calendar formatting, optional Persian numerals, and a home-screen Persian calendar widget.
- Smart app-folder generation in the app drawer; matching is performed locally on the device.
- Optional app-lock prompts using Android's existing device credential confirmation. This protects launches initiated through Solina Launcher; it is not a system-wide app-lock service.
- Hardened backup and restore, including archive validation and wallpaper-library support.
- Standard and Quickstep build variants, each with Test (Debug) and Release builds.

## Build

The `Solina-Launcher-Test` branch builds four APK variants through GitHub Actions:

| Variant | Gradle task | Artifact |
|---|---|---|
| Standard Test (Debug) | `assembleLawnWithoutQuickstepGithubDebug` | `Solina-Launcher-Standard-Test` |
| Standard Release | `assembleLawnWithoutQuickstepGithubRelease` | `Solina-Launcher-Standard-Release` |
| Quickstep Test (Debug) | `assembleLawnWithQuickstepGithubDebug` | `Solina-Launcher-Quickstep-Test` |
| Quickstep Release | `assembleLawnWithQuickstepGithubRelease` | `Solina-Launcher-Quickstep-Release` |

Open the repository's **Actions** tab, select the latest run for `Solina-Launcher-Test`, and download the required artifact. Use JDK 21 and the Android SDK for local builds; clone with `--recurse-submodules`.

## Android 17 / API 37 Quickstep status

Compiling against API 37 or successfully packaging a Quickstep APK does not prove runtime compatibility. The experimental API 37 manifest is deliberately not enabled in the maintained flavors because it requests privileged/signature permissions and requires device/ROM validation. Keep the release compatibility bounds unchanged until the read-only device checklist in [docs/QUICKSTEP_API37_DEVICE_TEST.md](docs/QUICKSTEP_API37_DEVICE_TEST.md) has been completed.

## License and origin

Solina Launcher is free software released under the **GNU GPL v3**. It is a derivative work, so the original copyright notices and licenses are kept. See [NOTICE.md](NOTICE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details, and [PRIVACY.md](PRIVACY.md) for the privacy policy.

---

## لانچر سولینا

**Solina Launcher** یک لانچر متن‌باز اندروید است که توسط **Solina** توسعه داده می‌شود.

### قابلیت‌های شاخه آزمایشی

- داک چندردیفه و چندصفحه‌ای با تنظیم مستقل فاصله افقی، عمودی و موقعیت برچسب آیکن‌ها.
- تقویم هجری شمسی، نمایش ارقام فارسی و ویجت تاریخ شمسی.
- ساخت پوشه‌های هوشمند برنامه‌ها به‌صورت محلی روی دستگاه.
- قفل برنامه‌ها با تأیید رمز یا الگوی قفل صفحه اندروید؛ این قابلیت فقط اجرای برنامه از مسیر لانچر سولینا را پوشش می‌دهد.
- پشتیبان‌گیری و بازیابی مقاوم‌سازی‌شده، همراه با پشتیبانی از کتابخانه تصاویر پس‌زمینه.
- چهار خروجی استاندارد و Quickstep در دو حالت تست (Debug) و انتشار (Release).

### دریافت خروجی

از تب **Actions** مخزن، آخرین اجرای شاخه `Solina-Launcher-Test` را باز کنید و Artifact نسخه موردنظر را دریافت کنید. نام چهار خروجی در جدول بخش Build آمده است.

### نکته API 37

ساخته‌شدن APK به معنی تضمین سازگاری زمان اجرای Quickstep در اندروید ۱۷ نیست. مانیفست آزمایشی API 37 تا زمان بررسی واقعی روی دستگاه و رام هدف فعال نمی‌شود.
