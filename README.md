# Solina Launcher

**Solina Launcher** is an open-source home app (launcher) for Android, developed by **Solina**.

## Planned features (in development)

- Jalali (Persian) calendar and a Persian-friendly clock/date widget
- Smart app grouping and folders in the app drawer
- App lock and a hidden apps folder
- Advanced custom gestures and shortcuts

## Build

Every push to `main` is built by GitHub Actions. Open the **Actions** tab, choose the latest run and
download the APK from **Artifacts** (`assembleLawnWithQuickstepGithubDebug` can be installed directly).

Local build: JDK 21 and the Android SDK, then `./gradlew assembleLawnWithQuickstepGithubDebug`
(clone with `--recurse-submodules`).

## License and origin

Solina Launcher is free software released under the **GNU GPL v3**. It is a derivative work, so the
original copyright notices and licenses are kept. See [NOTICE.md](NOTICE.md) for details, and
[PRIVACY.md](PRIVACY.md) for the privacy policy.

---

## لانچر سولینا

**Solina Launcher** یک لانچر متن‌باز برای اندروید است که توسط **Solina** توسعه داده می‌شود.
قابلیت‌های در حال توسعه: تقویم شمسی و ویجت فارسی، دسته‌بندی هوشمند اپ‌ها، قفل اپ و پوشه‌ی مخفی،
ژست‌ها و میانبرهای سفارشی. بیلد هر تغییر در تب Actions گیت‌هاب (بخش Artifacts) در دسترس است.
