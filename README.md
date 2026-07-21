SOPIX T1 Android USB Test v5

# SOPIX T1 Android USB Diagnostic

مشروع أندرويد تجريبي لاكتشاف حساس ACTEON SOPIX T1 عبر USB OTG واستقبال بيانات RAW.

## البناء من GitHub فقط
1. ارفع جميع محتويات هذا المجلد إلى جذر المستودع.
2. افتح تبويب **Actions**.
3. افتح **Build Android APK**.
4. اضغط **Run workflow**.
5. بعد اكتمال البناء نزّل Artifact باسم `SopixT1-debug-apk`.

> هذه نسخة تشخيصية. لا تحتوي بعد على تسلسل التهيئة النهائي للحساس أو فك الصورة النهائي.

Version v10: stable USB session, prevents reconnect during capture, fixes null bulkTransfer crash.
