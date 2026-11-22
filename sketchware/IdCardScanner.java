package com.my.idreader;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * يقرا صورة البطاقة بـ Tesseract (عربي + إنجليزي) أوفلاين،
 * ويرجّع النتيجة على الـ UI Thread.
 *
 * محتاج:
 *  - مكتبة: cz.adaptech.tesseract4android:tesseract4android:4.7.0
 *  - ملف assets/tessdata/ara.traineddata  (و eng.traineddata اختياري)
 */
public class IdCardScanner {

    public interface Callback {
        void onResult(EgyptianIdParser.Result result, String rawText);
        void onError(String message);
    }

    public static void scan(final Activity activity, final String imagePath, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                TessBaseAPI tess = null;
                try {
                    String dataPath = prepareTessData(activity);
                    String lang = new File(dataPath, "tessdata/eng.traineddata").exists() ? "ara+eng" : "ara";
                    tess = new TessBaseAPI();
                    if (!tess.init(dataPath, lang)) throw new Exception("فشل تشغيل Tesseract - اتأكد من ملف ara.traineddata");
                    tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
                    Bitmap bmp = preprocess(imagePath);
                    tess.setImage(bmp);
                    final String text = tess.getUTF8Text();
                    bmp.recycle();
                    final EgyptianIdParser.Result res = EgyptianIdParser.parse(text);
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() { cb.onResult(res, text); }
                    });
                } catch (final Throwable e) {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() { cb.onError(String.valueOf(e.getMessage())); }
                    });
                } finally {
                    if (tess != null) tess.recycle();
                }
            }
        }).start();
    }

    /** ينسخ ملفات اللغة من assets/tessdata لمجلد التطبيق (مرة واحدة). */
    private static String prepareTessData(Activity a) throws Exception {
        File base = a.getFilesDir();
        File dir = new File(base, "tessdata");
        if (!dir.exists()) dir.mkdirs();
        for (String f : a.getAssets().list("tessdata")) {
            File out = new File(dir, f);
            if (out.exists() && out.length() > 0) continue;
            InputStream in = a.getAssets().open("tessdata/" + f);
            OutputStream os = new FileOutputStream(out);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            in.close();
            os.close();
        }
        return base.getAbsolutePath();
    }

    /** تصغير + تعديل الاتجاه + تحويل لرمادي بتباين أعلى عشان دقة أحسن. */
    private static Bitmap preprocess(String path) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(path, o);
        if (src == null) throw new Exception("مش قادر أفتح الصورة");

        int rotate = 0;
        int ori = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotate = 90;
        else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotate = 180;
        else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotate = 270;
        if (rotate != 0) {
            Matrix mx = new Matrix();
            mx.postRotate(rotate);
            Bitmap r = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), mx, true);
            src.recycle();
            src = r;
        }

        Bitmap gray = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        float c = 1.5f, t = -60f; // تباين
        cm.postConcat(new ColorMatrix(new float[]{
            c, 0, 0, 0, t,
            0, c, 0, 0, t,
            0, 0, c, 0, t,
            0, 0, 0, 1, 0}));
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(cm));
        new Canvas(gray).drawBitmap(src, 0, 0, p);
        src.recycle();
        return gray;
    }
}
