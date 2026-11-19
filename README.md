# Solina Launcher

<p align="center">
  <img src="ic_launcher_home-playstore.png" alt="Solina Launcher icon" width="120" height="120">
</p>

**Solina Launcher 1.1.0** is an open-source Android home app developed by **Solina**. It is based on Lawnchair and released under the **GNU GPL v3** as a derivative work — the original copyright notices and licenses are kept.

## Features

- Multi-row and multi-page dock with adjustable horizontal/vertical spacing and independent icon-label positioning.
- Persian (Solar Hijri) calendar formatting, optional Persian numerals, and a home-screen Persian calendar widget.
- Smart app-folder generation in the app drawer; matching is performed locally on the device.
- Optional app-lock prompts using Android's existing device credential confirmation. This protects launches initiated through Solina Launcher; it is not a system-wide app-lock service.
- Hardened backup and restore, including archive validation and wallpaper-library support.
- Solina motion: icon press spring, folder and app-drawer open stagger, and a theme switcher (sun/moon rotation). All motion can be turned off with the "Solina motion" toggle in App Drawer settings.
- Unified Solina icon style, with a new Launcher glyph and Solina wordmark.
- Standard and Quickstep build variants, each with Test (Debug) and Release builds.

## Get the release

Download the latest release from the repository's **Releases** page. The Standard release ships three APKs: **v8** (arm64-v8a), **v7** (armeabi-v7a) and **universal**.

## Build

GitHub Actions builds four APK variants:

| Variant | Gradle task | Artifact |
|---|---|---|
| Standard Test (Debug) | `assembleLawnWithoutQuickstepGithubDebug` | `Solina-Launcher-Standard-Test` |
| Standard Release | `assembleLawnWithoutQuickstepGithubRelease` | `Solina-Launcher-Standard-Release` |
| Quickstep Test (Debug) | `assembleLawnWithQuickstepGithubDebug` | `Solina-Launcher-Quickstep-Test` |
| Quickstep Release | `assembleLawnWithQuickstepGithubRelease` | `Solina-Launcher-Quickstep-Release` |

Open the repository's **Actions** tab, select the latest run, and download the required artifact. For local builds use JDK 21 and the Android SDK; clone with `--recurse-submodules`.

## Android 17 / API 37 Quickstep status

Compiling against API 37 or successfully packaging a Quickstep APK does not prove runtime compatibility. The experimental API 37 manifest is deliberately not enabled in the maintained flavors because it requests privileged/signature permissions and requires device/ROM validation. Keep the release compatibility bounds unchanged until the read-only device checklist in [docs/QUICKSTEP_API37_DEVICE_TEST.md](docs/QUICKSTEP_API37_DEVICE_TEST.md) has been completed.

## License and origin

Solina Launcher is free software released under the **GNU GPL v3**. It is a derivative work, so the original copyright notices and licenses are kept. See [NOTICE.md](NOTICE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for details, and [PRIVACY.md](PRIVACY.md) for the privacy policy.

---

## لانچر سولینا

**Solina Launcher 1.1.0** یک لانچر متن‌باز اندروید است که توسط **Solina** توسعه داده می‌شود. این لانچر بر پایه Lawnchair ساخته شده و تحت مجوز **GNU GPL v3** منتشر می‌شود؛ اعلان‌های کپی‌رایت و مجوزهای اصلی حفظ شده‌اند.

### قابلیت‌ها

- داک چندردیفه و چندصفحه‌ای با تنظیم مستقل فاصله افقی، عمودی و موقعیت برچسب آیکن‌ها.
- تقویم هجری شمسی، نمایش ارقام فارسی و ویجت تاریخ شمسی روی صفحه اصلی.
- ساخت پوشه‌های هوشمند برنامه‌ها در کشوی برنامه‌ها، به‌صورت محلی روی دستگاه.
- قفل برنامه‌ها با تأیید رمز یا الگوی قفل صفحه اندروید؛ این قابلیت فقط اجرای برنامه از مسیر لانچر سولینا را پوشش می‌دهد و قفل سیستمی نیست.
- پشتیبان‌گیری و بازیابی مقاوم‌سازی‌شده، همراه با پشتیبانی از کتابخانه تصاویر پس‌زمینه.
- حرکت‌های سولینا: فنر فشار روی آیکن، باز شدن پلکانی پوشه و کشوی برنامه‌ها و سوییچ تم (چرخش خورشید/ماه). همه حرکت‌ها با گزینه «Solina motion» در تنظیمات کشوی برنامه‌ها خاموش می‌شوند.
- سبک یکپارچه آیکن‌های سولینا، با گلیف جدید لانچر و لوگوتایپ Solina.
- دو نسخه استاندارد و Quickstep، هر یک در دو حالت تست (Debug) و انتشار (Release).

### دریافت نسخه

آخرین نسخه را از صفحه **Releases** مخزن دریافت کنید. نسخه استاندارد سه فایل APK دارد: **v8** ‏(arm64-v8a)‏، **v7** ‏(armeabi-v7a)‏ و **universal**.

### ساخت

ساخت محلی با JDK 21 و Android SDK انجام می‌شود؛ مخزن را با `--recurse-submodules` کلون کنید. چهار خروجی در تب **Actions** مخزن ساخته می‌شوند؛ نام آن‌ها در جدول بخش Build آمده است.

### نکته API 37

ساخته‌شدن APK به معنی تضمین سازگاری زمان اجرای Quickstep در اندروید ۱۷ نیست. مانیفست آزمایشی API 37 تا زمان بررسی واقعی روی دستگاه و رام هدف فعال نمی‌شود؛ جزئیات در [docs/QUICKSTEP_API37_DEVICE_TEST.md](docs/QUICKSTEP_API37_DEVICE_TEST.md) آمده است.

### مجوز

این نرم‌افزار آزاد تحت **GNU GPL v3** منتشر می‌شود. جزئیات در [NOTICE.md](NOTICE.md) و [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) و سیاست حریم خصوصی در [PRIVACY.md](PRIVACY.md) آمده است.

---

## لانشر سولينا

**Solina Launcher 1.1.0** هو تطبيق شاشة رئيسية مفتوح المصدر لنظام أندرويد، تطوّره **Solina**. يستند إلى Lawnchair ويُصدر بموجب رخصة **GNU GPL v3** كعمل مشتق، مع الحفاظ على إشعارات حقوق النشر والتراخيص الأصلية.

### المزايا

- رصيف (Dock) متعدد الصفوف والصفحات مع ضبط مستقل للتباعد الأفقي والعمودي وموضع تسميات الأيقونات.
- تقويم هجري شمسي فارسي، مع أرقام فارسية اختيارية وودجت للتقويم على الشاشة الرئيسية.
- إنشاء مجلدات ذكية للتطبيقات في درج التطبيقات، تتم المطابقة محليًا على الجهاز.
- قفل التطبيقات عبر تأكيد بيانات اعتماد الجهاز في أندرويد؛ يحمي هذا الإطلاق عبر لانشر سولينا فقط، وليس خدمة قفل على مستوى النظام.
- نسخ احتياطي واستعادة محسّنان، مع التحقق من الأرشيف ودعم مكتبة الخلفيات.
- حركات سولينا: نابض عند الضغط على الأيقونة، وفتح متدرج للمجلد ودرج التطبيقات، ومبدّل السمة (دوران الشمس/القمر). يمكن إيقاف كل الحركات من خيار "Solina motion" في إعدادات درج التطبيقات.
- أسلوب موحّد لأيقونات سولينا، مع رمز جديد للانشر وكلمة Solina.
- نسختان: Standard وQuickstep، كل منهما بإصداري اختبار (Debug) وإصدار (Release).

### الحصول على الإصدار

نزّل أحدث إصدار من صفحة **Releases** في المستودع. يتضمن إصدار Standard ثلاث حزم APK: **v8** ‏(arm64-v8a)‏ و**v7** ‏(armeabi-v7a)‏ و**universal**.

### البناء

يُبنى محليًا باستخدام JDK 21 وAndroid SDK؛ استنسخ المستودع مع `--recurse-submodules`. تُبنى الحزم الأربع في تب **Actions**؛ أسماؤها في جدول قسم Build أعلاه.

### ملاحظة API 37

نجاح البناء على API 37 أو تجميع حزمة Quickstep لا يثبت التوافق أثناء التشغيل. بيان API 37 التجريبي غير مفعّل عمدًا في النكهات المُصانة لأنه يطلب أذونات مميزة، ويتطلب التحقق على الجهاز وذاكرة القراءة فقط؛ التفاصيل في [docs/QUICKSTEP_API37_DEVICE_TEST.md](docs/QUICKSTEP_API37_DEVICE_TEST.md).

### الترخيص

هذا برنامج حر بموجب **GNU GPL v3**. التفاصيل في [NOTICE.md](NOTICE.md) و[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)، وسياسة الخصوصية في [PRIVACY.md](PRIVACY.md).
