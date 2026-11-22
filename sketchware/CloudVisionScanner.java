package com.my.idreader;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * يقرا صورة البطاقة باستخدام Google Cloud Vision (DOCUMENT_TEXT_DETECTION + عربي).
 * مش محتاج أي مكتبة خارجية - بيستخدم HttpURLConnection و org.json الموجودين في أندرويد.
 *
 * طريقتين للتشغيل:
 *  1) PROXY_URL فاضي  ← بيكلم Google مباشرة بالـ API_KEY (لازم تقفل المفتاح على تطبيقك من Google Cloud Console).
 *  2) PROXY_URL مليان ← بيبعت الصورة لسيرفرك (Firebase Function) والسيرفر هو اللي يكلم Google. (الأأمن)
 */
public class CloudVisionScanner {

    // ===== الإعدادات =====
    public static String API_KEY   = "PUT_YOUR_API_KEY_HERE";
    public static String PROXY_URL = "";   // مثال: https://ocr-xxxxx-uc.a.run.app
    public static String APP_TOKEN = "";   // نفس القيمة اللي في السيرفر (اختياري)
    // =====================

    public interface Callback {
        void onResult(EgyptianIdParser.Result result, String rawText);
        void onError(String message);
    }

    public static void scan(final Activity activity, final String imagePath, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String b64 = encodeImage(imagePath);
                    final String text = (PROXY_URL == null || PROXY_URL.trim().isEmpty())
                            ? callVisionDirect(activity, b64)
                            : callProxy(b64);
                    final EgyptianIdParser.Result res = EgyptianIdParser.parse(text);
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() { cb.onResult(res, text); }
                    });
                } catch (final Throwable e) {
                    activity.runOnUiThread(new Runnable() {
                        @Override public void run() { cb.onError(String.valueOf(e.getMessage())); }
                    });
                }
            }
        }).start();
    }

    // ---------- Google مباشرة ----------
    private static String callVisionDirect(Activity a, String b64) throws Exception {
        JSONObject req = new JSONObject()
            .put("image", new JSONObject().put("content", b64))
            .put("features", new JSONArray().put(new JSONObject().put("type", "DOCUMENT_TEXT_DETECTION")))
            .put("imageContext", new JSONObject().put("languageHints", new JSONArray().put("ar").put("en")));
        JSONObject body = new JSONObject().put("requests", new JSONArray().put(req));

        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://vision.googleapis.com/v1/images:annotate?key=" + API_KEY).openConnection();
        // الهيدرز دي عشان المفتاح يشتغل لو مقفول على "Android apps" في Console
        c.setRequestProperty("X-Android-Package", a.getPackageName());
        String sha1 = signingSha1(a);
        if (sha1 != null) c.setRequestProperty("X-Android-Cert", sha1);

        JSONObject resp = postJson(c, body);
        if (resp.has("error")) throw new Exception(resp.getJSONObject("error").optString("message"));
        JSONObject r0 = resp.getJSONArray("responses").getJSONObject(0);
        if (r0.has("error")) throw new Exception(r0.getJSONObject("error").optString("message"));
        if (r0.has("fullTextAnnotation")) return r0.getJSONObject("fullTextAnnotation").optString("text", "");
        if (r0.has("textAnnotations")) return r0.getJSONArray("textAnnotations").getJSONObject(0).optString("description", "");
        throw new Exception("مفيش كلام اتقرا من الصورة");
    }

    // ---------- عن طريق السيرفر ----------
    private static String callProxy(String b64) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(PROXY_URL).openConnection();
        if (APP_TOKEN != null && !APP_TOKEN.isEmpty()) c.setRequestProperty("X-App-Token", APP_TOKEN);
        JSONObject resp = postJson(c, new JSONObject().put("image", b64));
        if (resp.has("error")) throw new Exception(resp.optString("error"));
        return resp.optString("text", "");
    }

    private static JSONObject postJson(HttpURLConnection c, JSONObject body) throws Exception {
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        OutputStream os = c.getOutputStream();
        os.write(body.toString().getBytes("UTF-8"));
        os.close();
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) throw new Exception("HTTP " + code);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        c.disconnect();
        String s = bos.toString("UTF-8");
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            throw new Exception("HTTP " + code + ": " + s);
        }
    }

    /** SHA-1 بتاع توقيع التطبيق (لازم يطابق اللي مسجل في Console). */
    @SuppressWarnings("deprecation")
    private static String signingSha1(Activity a) {
        try {
            PackageInfo pi = a.getPackageManager().getPackageInfo(a.getPackageName(), PackageManager.GET_SIGNATURES);
            Signature sig = pi.signatures[0];
            byte[] d = MessageDigest.getInstance("SHA-1").digest(sig.toByteArray());
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** تصغير لحد 1600px + تعديل الاتجاه + JPEG + Base64 (عشان الرفع يبقى سريع). */
    private static String encodeImage(String path) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 1600) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(path, o);
        if (bmp == null) throw new Exception("مش قادر أفتح الصورة");

        int max = Math.max(bmp.getWidth(), bmp.getHeight());
        if (max > 1600) {
            float k = 1600f / max;
            Bitmap s = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * k), Math.round(bmp.getHeight() * k), true);
            bmp.recycle();
            bmp = s;
        }

        int ori = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        int rotate = ori == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                   : ori == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                   : ori == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        if (rotate != 0) {
            Matrix mx = new Matrix();
            mx.postRotate(rotate);
            Bitmap r = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), mx, true);
            bmp.recycle();
            bmp = r;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
        bmp.recycle();
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }
}
