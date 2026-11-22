# قارئ بطاقة الرقم القومي - Sketchware Pro

تطبيق يفتح الكاميرا، يصوّر البطاقة، يقرا البيانات بـ **Google Cloud Vision**، ويحطها في EditText.

| الملف | الوظيفة |
|---|---|
| `sketchware/CloudVisionScanner.java` | يرفع الصورة لـ Cloud Vision ويرجّع النتيجة (**الأساسي**) |
| `sketchware/EgyptianIdParser.java` | يطلّع الرقم القومي والاسم والعنوان، ويحسب الميلاد والنوع والمحافظة |
| `sketchware/IdCardScanner.java` | بديل أوفلاين ببلاش (Tesseract) - اختياري |
| `server/` | Firebase Function عشان المفتاح ميبقاش جوه التطبيق (اختياري بس أأمن) |

> Cloud Vision **مش محتاج أي مكتبة** في Sketchware - بيستخدم حاجات موجودة في أندرويد أصلاً. محتاج بس **Sketchware Pro** عشان ملفات الـ Java.

---

## الجزء الأول: تجهيز Google Cloud (مرة واحدة)

1. ادخل https://console.cloud.google.com واعمل مشروع جديد.
2. فعّل الفوترة (Billing) - محتاج كارت، بس أول 1000 صورة كل شهر ببلاش.
3. `APIs & Services` ← `Library` ← دوّر على **Cloud Vision API** ← `Enable`.
4. `APIs & Services` ← `Credentials` ← `Create credentials` ← **API key**.
5. **اقفل المفتاح** (مهم جداً):
   - `Application restrictions` ← **Android apps** ← `Add`:
     - Package name: اسم الباكدج بتاع تطبيقك (مثلاً `com.my.idreader`)
     - SHA-1: بصمة التوقيع (شوف تحت إزاي تجيبها)
   - `API restrictions` ← **Restrict key** ← اختار **Cloud Vision API** بس.
6. (اختياري) `Billing` ← `Budgets & alerts` ← حط حد شهري، مثلاً 5$، عشان يجيلك تنبيه.

**إزاي تجيب SHA-1 في Sketchware Pro؟**
- لو بتصدّر APK موقّع بـ keystore بتاعك: `keytool -list -v -keystore my.jks` واخد سطر SHA1.
- لو مش عارف: شغّل التطبيق مرة بالمفتاح من غير قيود، وفي `onError` هتظهر رسالة فيها
  `The Android package name and signing-certificate fingerprint ... are blocked`. أو اطبع البصمة مؤقتاً.
- ⚠️ نسخة الـ Debug ونسخة الـ Release ليهم بصمتين مختلفتين - ضيف الاتنين في Console.

---

## الجزء التاني: Sketchware Pro

### 1) ملفات الـ Java
`Java/Kotlin Manager` ← ضيف:
- `CloudVisionScanner.java`
- `EgyptianIdParser.java`

**غيّر السطر الأول** `package com.my.idreader;` في الملفين لاسم الباكدج بتاع مشروعك.
وفي `CloudVisionScanner.java` حط مفتاحك:
```java
public static String API_KEY = "AIza....";
```

### 2) التصميم (main.xml)
- `Button` id: `btn_scan` ← "صوّر البطاقة"
- `EditText`: `edit_name` ، `edit_id` ، `edit_birth` ، `edit_gender` ، `edit_gov` ، `edit_address`
- `ProgressBar` id: `progress` (Visibility = GONE)

### 3) المكونات والصلاحيات
- Component: `Camera` اسمه `cam`
- Permissions: `INTERNET` ، `CAMERA` ، `READ/WRITE_EXTERNAL_STORAGE`

### 4) البلوكات
**btn_scan → onClick** ← بلوك `Camera cam takePicture`

**cam → onPictureTaken(filePath)** ← بلوك `add source directly`:
```java
progress.setVisibility(View.VISIBLE);
btn_scan.setEnabled(false);
CloudVisionScanner.scan(MainActivity.this, _filePath, new CloudVisionScanner.Callback() {
    @Override
    public void onResult(EgyptianIdParser.Result r, String rawText) {
        progress.setVisibility(View.GONE);
        btn_scan.setEnabled(true);
        edit_name.setText(r.fullName);
        edit_id.setText(r.nationalId);
        edit_birth.setText(r.birthDate);
        edit_gender.setText(r.gender);
        edit_gov.setText(r.governorate);
        edit_address.setText(r.address);
        if (r.nationalId.isEmpty()) {
            SketchwareUtil.showMessage(getApplicationContext(), "مقدرتش أقرا الرقم القومي، صوّر تاني");
        }
    }
    @Override
    public void onError(String msg) {
        progress.setVisibility(View.GONE);
        btn_scan.setEnabled(true);
        SketchwareUtil.showMessage(getApplicationContext(), "خطأ: " + msg);
    }
});
```
(لو اسم المتغير في الحدث مش `_filePath` استخدم الاسم اللي ظاهر عندك.)

---

## الجزء التالت (اختياري - الأأمن): سيرفر Firebase

حتى لو المفتاح مقفول على تطبيقك، ممكن حد يفك الـ APK ويزوّر الهيدرز. الأأمن إن المفتاح ميبقاش في التطبيق خالص:

1. اعمل مشروع Firebase مربوط بنفس مشروع Google Cloud (لازم خطة **Blaze**).
2. على الكمبيوتر:
   ```bash
   npm install -g firebase-tools
   firebase login
   cd server && npm install && cp .env.example .env   # وغيّر APP_TOKEN لكلمة عشوائية
   cd .. && firebase use --add        # اختار مشروعك
   firebase deploy --only functions
   ```
3. هيطلعلك رابط زي `https://ocr-xxxxx-uc.a.run.app`. في `CloudVisionScanner.java`:
   ```java
   public static String API_KEY   = "";                         // مش محتاجه
   public static String PROXY_URL = "https://ocr-xxxxx-uc.a.run.app";
   public static String APP_TOKEN = "نفس الكلمة اللي في .env";
   ```
وخلاص - نفس البلوكات من غير أي تغيير.

---

## بيطلّع إيه؟
| الحقل | المصدر |
|---|---|
| الرقم القومي | Cloud Vision |
| تاريخ الميلاد، النوع، المحافظة | **محسوبة من الرقم القومي** |
| الاسم والعنوان | Cloud Vision (أول سطرين بعد عنوان البطاقة = الاسم، اللي بعدهم = العنوان) |

## التكلفة
أول 1000 صورة/شهر ببلاش، بعدها 1.5$ لكل 1000 صورة. (https://cloud.google.com/vision/pricing)

## نصايح
- صوّر البطاقة قريبة، إضاءة كويسة، من غير فلاش.
- خلي الـ EditText قابلة للتعديل عشان المستخدم يراجع.
- بيانات البطاقة بيانات شخصية حساسة: متخزنش الصور، واكتب سياسة خصوصية لو هتنزل التطبيق على Play Store.

## بديل أوفلاين (Tesseract)
لو عايز من غير نت وببلاش: استخدم `IdCardScanner.java` بدل `CloudVisionScanner` (نفس الـ Callback)،
وضيف مكتبة `cz.adaptech.tesseract4android:tesseract4android:4.7.0` وملف `assets/tessdata/ara.traineddata`. الدقة أقل.
