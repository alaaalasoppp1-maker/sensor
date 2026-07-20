SOPIX T1 Android USB Test v5

SOPIX T1 Android USB Test v0.1

هذه نسخة تشخيصية مدمجة:
- اكتشاف VID 1CE6 / PID 0001
- طلب صلاحية USB
- عرض Interfaces وEndpoints
- إرسال HEX يدويًا إلى Endpoint 0x06
- قراءة RAW من Endpoint 0x82
- حفظ 2,625,000 بايت باسم المريض
- معاينة أولية 16-bit بحجم 1250×1050 مع تكبير وسحب

البناء:
1) افتح المجلد في Android Studio ثم Build APK.
أو
2) ارفع المشروع إلى GitHub واضغط Actions > Build Android APK > Run workflow.

مهم: هذه ليست النسخة النهائية ولا ترسل أوامر تهيئة تلقائية بعد، كي لا نرسل أوامر خاطئة للحساس. استخدم حقل HEX بعد استخراج تسلسل التهيئة الصحيح من USB capture.
