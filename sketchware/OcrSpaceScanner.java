package com.my.idreader;

import android.app.Activity;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * قراءة البطاقة بـ OCR.space (https://ocr.space/ocrapi).
 * مش محتاج أي مكتبة - HttpURLConnection و org.json موجودين في أندرويد.
 *
 * المفتاح المجاني: سجّل بإيميلك في https://ocr.space/ocrapi/freekey وهيوصلك على الإيميل.
 * الحد المجاني: 25,000 طلب في الشهر، 500 في اليوم لكل IP، وحجم الصورة لحد 1 ميجا.
 */
public class OcrSpaceScanner {

    // ===== الإعدادات =====
    public static String API_KEY  = "PUT_YOUR_OCR_SPACE_KEY_HERE";
    public static String ENDPOINT = "https://api.ocr.space/parse/image"; // للخطة المدفوعة غيّره للرابط اللي هيدوهولك
    public static String ENGINE   = "2";                                  // 2 = الأحسن في الأرقام والعربي
    // =====================

    /** يبعت صورة JPEG ويرجّع النتيجة على الـ UI Thread (نفس Callback بتاع CloudVisionScanner). */
    public static void scanJpeg(final Activity activity, final byte[] jpeg, final CloudVisionScanner.Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final String text = call(jpeg);
                    final SyrianIdParser.Result res = SyrianIdParser.parse(text);
                    SyrianIdParser.Result loose = SyrianIdParser.parseLoose(text);
                    fillMissing(res, loose);
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

    private static void fillMissing(SyrianIdParser.Result r, SyrianIdParser.Result o) {
        if (r.firstName.isEmpty()) r.firstName = o.firstName;
        if (r.lastName.isEmpty()) r.lastName = o.lastName;
        if (r.fatherName.isEmpty()) r.fatherName = o.fatherName;
        if (r.motherName.isEmpty()) r.motherName = o.motherName;
        if (r.birthPlace.isEmpty()) r.birthPlace = o.birthPlace;
        if (r.birthDate.isEmpty()) r.birthDate = o.birthDate;
        if (r.fullName.isEmpty()) r.fullName = o.fullName;
    }

    private static String call(byte[] jpeg) throws Exception {
        if (jpeg.length > 1024 * 1024) throw new Exception("الصورة أكبر من 1 ميجا");
        String b64 = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP);
        String body = "language=ara"
                + "&OCREngine=" + ENGINE
                + "&scale=true"
                + "&detectOrientation=false"
                + "&isOverlayRequired=false"
                + "&base64Image=" + URLEncoder.encode(b64, "UTF-8");

        HttpURLConnection c = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setDoOutput(true);
        c.setRequestProperty("apikey", API_KEY);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        OutputStream os = c.getOutputStream();
        os.write(body.getBytes("UTF-8"));
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

        JSONObject resp;
        try {
            resp = new JSONObject(s);
        } catch (Exception e) {
            throw new Exception("HTTP " + code + ": " + (s.length() > 200 ? s.substring(0, 200) : s));
        }
        if (resp.optBoolean("IsErroredOnProcessing", false)) {
            Object m = resp.opt("ErrorMessage");
            String msg = m instanceof JSONArray ? ((JSONArray) m).optString(0) : String.valueOf(m);
            throw new Exception(msg);
        }
        JSONArray results = resp.optJSONArray("ParsedResults");
        if (results == null || results.length() == 0) throw new Exception("مفيش نتيجة من OCR.space");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.length(); i++) {
            sb.append(results.getJSONObject(i).optString("ParsedText", "")).append('\n');
        }
        return sb.toString();
    }
}
