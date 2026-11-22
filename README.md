# قارئ بطاقة الرقم القومي - Sketchware Pro

تطبيق يفتح الكاميرا، يصوّر البطاقة، يقرا البيانات أوفلاين (Tesseract عربي)، ويحطها في EditText.

## ليه Sketchware **Pro**؟
Sketchware العادي مش بيسمح بإضافة مكتبات خارجية ولا ملفات Java. محتاج **Sketchware Pro** (مجاني ومفتوح المصدر).

> ملحوظة مهمة: Google ML Kit **مش بيدعم العربي**، عشان كده استخدمنا Tesseract.

## الخطوات

### 1) المكتبة
`Library Manager` ← `Local libraries` ← Download واكتب:
```
cz.adaptech.tesseract4android:tesseract4android:4.7.0
```
(لو مش لاقيها جرّب إصدار أحدث، أو نزّل ملف الـ AAR من jitpack.io وضيفه يدوي.) وبعدين فعّلها للمشروع.

### 2) ملف اللغة العربي
نزّل `ara.traineddata` من:
https://github.com/tesseract-ocr/tessdata_fast
وفي `Asset Manager` اعمل فولدر اسمه `tessdata` وحط الملف جواه ← `assets/tessdata/ara.traineddata`.
(ممكن تضيف `eng.traineddata` كمان، وهيتستخدم لوحده لو موجود.)

### 3) ملفات الـ Java
`Java/Kotlin Manager` ← ضيف الملفين:
- `sketchware/EgyptianIdParser.java`
- `sketchware/IdCardScanner.java`

**غيّر السطر الأول** `package com.my.idreader;` لاسم الباكدج بتاع مشروعك.

### 4) التصميم (main.xml)
- `Button` id: `btn_scan` ← نص "صوّر البطاقة"
- `EditText`: `edit_name` ، `edit_id` ، `edit_birth` ، `edit_gender` ، `edit_gov` ، `edit_address`
- `ProgressBar` id: `progress` (Visibility = GONE)

### 5) المكونات (Components)
- `Camera` اسمه `cam`
- الصلاحيات: `CAMERA` و `READ/WRITE_EXTERNAL_STORAGE`

### 6) البلوكات (Events)

**btn_scan → onClick**
بلوك `Camera cam takePicture`

**cam → onPictureTaken(filePath)** ← بلوك `add source directly` بالكود ده:
```java
progress.setVisibility(View.VISIBLE);
IdCardScanner.scan(MainActivity.this, _filePath, new IdCardScanner.Callback() {
    @Override
    public void onResult(EgyptianIdParser.Result r, String rawText) {
        progress.setVisibility(View.GONE);
        edit_name.setText(r.fullName);
        edit_id.setText(r.nationalId);
        edit_birth.setText(r.birthDate);
        edit_gender.setText(r.gender);
        edit_gov.setText(r.governorate);
        edit_address.setText(r.address);
        if (r.nationalId.isEmpty()) {
            SketchwareUtil.showMessage(getApplicationContext(), "مقدرتش أقرا الرقم القومي، صوّر تاني بإضاءة أحسن");
        }
    }
    @Override
    public void onError(String msg) {
        progress.setVisibility(View.GONE);
        SketchwareUtil.showMessage(getApplicationContext(), "خطأ: " + msg);
    }
});
```
(لو اسم المتغير في الحدث مختلف عن `_filePath` استخدم الاسم اللي ظاهر عندك.)

## بيطلّع إيه؟
| الحقل | المصدر | الدقة |
|---|---|---|
| الرقم القومي | OCR | عالية لو الصورة واضحة |
| تاريخ الميلاد، النوع، المحافظة | **محسوبة من الرقم القومي** | 100% لو الرقم صح |
| الاسم والعنوان | OCR عربي | متوسطة - خلي المستخدم يراجع |

## نصايح للدقة
- صوّر البطاقة قريبة ومالية الصورة، إضاءة كويسة، من غير فلاش (بيعمل لمعة).
- خلي الـ EditText قابلة للتعديل عشان المستخدم يصحح.
- لو عايز دقة زي البنوك بالظبط: استخدم Google Cloud Vision API (بيدعم العربي وأدق بكتير بس بفلوس ومحتاج نت)، أو SDK تجاري زي Microblink / Regula.
