# Solina Launcher

**Solina Launcher** is an open-source home app (launcher) for Android, developed by **Solina**.

## Planned features (in development)

- Jalali (Persian) calendar and a Persian-friendly clock/date widget
- Smart app grouping and folders in the app drawer
- App lock and a hidden apps folder
- Advanced custom gestures and shortcuts

## Build

Every push to `main` is built by GitHub Actions. Open the **Actions** tab, choose the latest run and download the APK from **Artifacts**.

The currently configured standalone variants are:

- Debug: `./gradlew assembleSolinaWithoutQuickstepGithubDebug`
- Release: `./gradlew assembleSolinaWithoutQuickstepGithubRelease`

Clone with `--recurse-submodules`; local builds require JDK 21 and the Android SDK.

**Android 17 / API 37 note:** the project compiles against API 37, but that alone does not prove Quickstep runtime compatibility. The current release configuration sets the Quickstep SDK range to 35–36. Debug builds override that range, which allows testing but is not a compatibility guarantee. Do not treat a successful APK build as proof that Recents gestures or QuickSwitch work on-device.

## License and origin

Solina Launcher is free software released under the **GNU GPL v3**. It is a derivative work, so the original copyright notices and licenses are kept. See [NOTICE.md](NOTICE.md) for details, and [PRIVACY.md](PRIVACY.md) for the privacy policy.

---

## لانچر سولینا

**Solina Launcher** یک لانچر متن‌باز برای اندروید است که توسط **Solina** توسعه داده می‌شود.

### ساخت

بیلدهای فعلی از بخش Actions گیت‌هاب و قسمت Artifacts قابل دریافت هستند. دستورهای ساخت محلی:

- نسخه آزمایشی: `./gradlew assembleSolinaWithoutQuickstepGithubDebug`
- نسخه انتشار: `./gradlew assembleSolinaWithoutQuickstepGithubRelease`

برای ساخت، JDK 21 و Android SDK لازم است و مخزن باید همراه زیرماژول‌ها دریافت شود.

**نکته Android 17 / API 37:** کامپایل شدن با API 37 به‌تنهایی سازگاری زمان اجرا با Quickstep را ثابت نمی‌کند. محدوده SDK نسخه انتشار فعلاً ۳۵ تا ۳۶ است؛ نسخه Debug این محدودیت را برای آزمایش باز می‌گذارد، اما این کار تضمین سازگاری نیست. موفقیت ساخت APK به معنی کارکرد قطعی ژست‌های Recents یا QuickSwitch روی گوشی نیست.
