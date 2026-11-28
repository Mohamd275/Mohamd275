package com.my.idreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * قراءة البطاقة الشخصية السورية أوفلاين (ببلاش ومن غير نت).
 *
 *  - الأسماء (الاسم/النسبة/الأب/الأم/مكان الولادة): Tesseract على منطقة الحقول بس، بعد تنضيفها.
 *  - الرقم الوطني: قارئ أرقام مخصوص (DigitReader) بيفصل الأرقام الحمرا بلونها.
 *  - تاريخ الولادة: نفس قارئ الأرقام على آخر سطر في الحقول.
 *
 * لازم يتنادى من Thread واحد بس (مش الـ UI Thread).
 *
 * محتاج:
 *  - مكتبة: cz.adaptech.tesseract4android:tesseract4android:4.7.0
 *  - ملف: assets/tessdata/ara.traineddata  (من tessdata_fast)
 */
public class OfflineIdReader {

    // ===== أماكن المناطق كنسبة من البطاقة (0..1): {شمال، فوق، يمين، تحت} =====
    // الصورة الشخصية على الشمال، الحقول على اليمين، والرقم الوطني الأحمر تحت.
    public static float[] ZONE_FIELDS = {0.36f, 0.27f, 1.00f, 0.87f};
    public static float[] ZONE_NUMBER = {0.30f, 0.82f, 1.00f, 1.00f};
    // ==========================================================================

    private static final int CARD_WIDTH = 1200;   // كل البطاقات بتتحول للعرض ده قبل القراءة
    private static final int TESS_WIDTH = 700;    // أحسن عرض لـ Tesseract (من التجارب)

    private final Context ctx;
    private TessBaseAPI tess;
    private DigitReader digits;

    public OfflineIdReader(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** يقرا صورة البطاقة (متقصوصة على الإطار وعدلة) ويرجّع النتيجة. */
    public SyrianIdParser.Result read(Bitmap cardSrc) throws Exception {
        ensureInit();
        Bitmap card = scaleToWidth(cardSrc, CARD_WIDTH, false);

        // 1) الأسماء بـ Tesseract
        Bitmap fieldsZone = crop(card, ZONE_FIELDS);
        Bitmap scaled = scaleToWidth(fieldsZone, TESS_WIDTH, false);
        Bitmap tessImg = grayForTess(scaled);
        if (scaled != fieldsZone) scaled.recycle();
        String text = ocr(tessImg, TessBaseAPI.PageSegMode.PSM_SINGLE_COLUMN);
        tessImg.recycle();
        SyrianIdParser.Result r = SyrianIdParser.parseLoose(text);
        mergeMissing(r, SyrianIdParser.parse(text));

        // 2) تاريخ الولادة بقارئ الأرقام
        String date = digits.readBirthDate(pixels(fieldsZone), fieldsZone.getWidth(), fieldsZone.getHeight());
        if (!date.isEmpty()) r.birthDate = date;
        fieldsZone.recycle();

        // 3) الرقم الوطني (أحمر) بقارئ الأرقام
        Bitmap numberZone = crop(card, ZONE_NUMBER);
        String number = digits.readRedNumber(pixels(numberZone), numberZone.getWidth(), numberZone.getHeight());
        numberZone.recycle();
        if (number.length() == SyrianIdParser.NATIONAL_ID_LENGTH) r.nationalId = number;
        else if (r.nationalId.length() != SyrianIdParser.NATIONAL_ID_LENGTH) r.nationalId = "";

        if (card != cardSrc) card.recycle();
        return r;
    }

    private static DigitReader sDigits;

    private static synchronized DigitReader digitReader() {
        if (sDigits == null) sDigits = new DigitReader(buildTemplates());
        return sDigits;
    }

    /**
     * يقرا الرقم الوطني وتاريخ الولادة بس بقارئ الأرقام (من غير Tesseract).
     * بيستخدمه وضع OCR.space و Google Cloud كتأكيد. يرجّع {رقم وطني، تاريخ} (ممكن يبقوا فاضيين).
     */
    public static String[] readDigits(Bitmap cardSrc) {
        DigitReader d = digitReader();
        Bitmap card = scaleToWidth(cardSrc, CARD_WIDTH, false);
        Bitmap f = crop(card, ZONE_FIELDS);
        String date = d.readBirthDate(pixels(f), f.getWidth(), f.getHeight());
        f.recycle();
        Bitmap n = crop(card, ZONE_NUMBER);
        String num = d.readRedNumber(pixels(n), n.getWidth(), n.getHeight());
        n.recycle();
        if (card != cardSrc) card.recycle();
        if (num.length() != SyrianIdParser.NATIONAL_ID_LENGTH) num = "";
        return new String[]{num, date};
    }

    public void release() {
        if (tess != null) {
            tess.recycle();
            tess = null;
        }
    }

    private static void mergeMissing(SyrianIdParser.Result r, SyrianIdParser.Result o) {
        if (r.firstName.isEmpty()) r.firstName = o.firstName;
        if (r.lastName.isEmpty()) r.lastName = o.lastName;
        if (r.fatherName.isEmpty()) r.fatherName = o.fatherName;
        if (r.motherName.isEmpty()) r.motherName = o.motherName;
        if (r.birthPlace.isEmpty()) r.birthPlace = o.birthPlace;
        if (r.birthDate.isEmpty()) r.birthDate = o.birthDate;
        if (r.nationalId.isEmpty()) r.nationalId = o.nationalId;
    }

    // ---------------------------------------------------------------- init

    private void ensureInit() throws Exception {
        if (digits == null) digits = digitReader();
        if (tess != null) return;
        String dataPath = copyTessData();
        TessBaseAPI t = new TessBaseAPI();
        if (!t.init(dataPath, "ara", TessBaseAPI.OEM_LSTM_ONLY)) {
            t.recycle();
            throw new Exception("فشل تشغيل القراءة - اتأكد من ملف assets/tessdata/ara.traineddata");
        }
        t.setVariable("preserve_interword_spaces", "1");
        tess = t;
    }

    /** قوالب الأرقام العربية مرسومة بخطوط الموبايل نفسه (مش محتاجة ملفات). */
    private static List<DigitReader.Template> buildTemplates() {
        List<DigitReader.Template> list = new ArrayList<>();
        Typeface[] faces = {
                Typeface.DEFAULT_BOLD, Typeface.DEFAULT,
                Typeface.create(Typeface.SERIF, Typeface.BOLD), Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        };
        int S = 96;
        Bitmap bmp = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        p.setTextSize(64);
        int[] px = new int[S * S];
        boolean[] mask = new boolean[S * S];
        for (Typeface tf : faces) {
            p.setTypeface(tf);
            for (int d = 0; d <= 9; d++) {
                c.drawColor(Color.BLACK);
                c.drawText(String.valueOf((char) ('٠' + d)), 16, 72, p);
                bmp.getPixels(px, 0, S, 0, 0, S, S);
                for (int i = 0; i < px.length; i++) mask[i] = (px[i] & 0xFF) > 128;
                DigitReader.Template t = DigitReader.makeTemplate(d, mask, S, S);
                if (t != null) list.add(t);
            }
        }
        bmp.recycle();
        return list;
    }

    private String ocr(Bitmap bmp, int psm) {
        tess.setPageSegMode(psm);
        tess.setImage(bmp);
        String s = tess.getUTF8Text();
        tess.clear();
        return s == null ? "" : s;
    }

    private String copyTessData() throws Exception {
        File base = ctx.getFilesDir();
        File dir = new File(base, "tessdata");
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("مش قادر أعمل فولدر tessdata");
        String[] files = ctx.getAssets().list("tessdata");
        if (files == null || files.length == 0) {
            throw new Exception("ملف ara.traineddata مش موجود في assets/tessdata");
        }
        for (String f : files) {
            File out = new File(dir, f);
            if (out.exists() && out.length() > 0) continue;
            InputStream in = ctx.getAssets().open("tessdata/" + f);
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            os.close();
        }
        return base.getAbsolutePath();
    }

    // ---------------------------------------------------------------- image

    private static Bitmap scaleToWidth(Bitmap src, int w, boolean recycleSrc) {
        if (src.getWidth() == w) return src;
        int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));
        Bitmap out = Bitmap.createScaledBitmap(src, w, h, true);
        if (recycleSrc) src.recycle();
        return out;
    }

    private static Bitmap crop(Bitmap src, float[] z) {
        int w = src.getWidth(), h = src.getHeight();
        int x0 = clamp(Math.round(z[0] * w), 0, w - 1), y0 = clamp(Math.round(z[1] * h), 0, h - 1);
        int x1 = clamp(Math.round(z[2] * w), x0 + 1, w), y1 = clamp(Math.round(z[3] * h), y0 + 1, h);
        return Bitmap.createBitmap(src, x0, y0, x1 - x0, y1 - y0);
    }

    private static int[] pixels(Bitmap b) {
        int[] px = new int[b.getWidth() * b.getHeight()];
        b.getPixels(px, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return px;
    }

    /** رمادي بأقل قناة + تمديد التباين (1%). من التجارب ده أحسن من الأبيض والأسود لـ Tesseract. */
    private static Bitmap grayForTess(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int[] px = pixels(src);
        int[] g = DigitReader.grayMin(px);
        int[] hist = new int[256];
        for (int v : g) hist[v]++;
        int cut = g.length / 100, lo = 0, hi = 255, acc = 0;
        for (int i = 0; i < 256; i++) { acc += hist[i]; if (acc > cut) { lo = i; break; } }
        acc = 0;
        for (int i = 255; i >= 0; i--) { acc += hist[i]; if (acc > cut) { hi = i; break; } }
        float k = hi > lo ? 255f / (hi - lo) : 1f;
        for (int i = 0; i < g.length; i++) {
            int v = clamp(Math.round((g[i] - lo) * k), 0, 255);
            px[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
