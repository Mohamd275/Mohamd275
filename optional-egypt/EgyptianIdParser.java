package com.my.idreader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * تحليل النص الخارج من OCR لبطاقة الرقم القومي المصرية.
 * كلاس Java عادي (من غير Android) عشان يتجرب بسهولة.
 */
public class EgyptianIdParser {

    public static class Result {
        public String nationalId = "";
        public String birthDate = "";   // yyyy/MM/dd
        public String gender = "";      // ذكر / أنثى
        public String governorate = "";
        public String firstName = "";
        public String fullName = "";
        public String address = "";
    }

    private static final String[] HEADER_WORDS = {
        "جمهورية", "مصر", "العربية", "بطاقة", "تحقيق", "الشخصية", "الشخصيه"
    };

    /** يحوّل الأرقام العربية (٠-٩) والفارسية (۰-۹) لأرقام إنجليزي. */
    public static String normalizeDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        return sb.toString();
    }

    public static Result parse(String rawText) {
        Result r = new Result();
        if (rawText == null) return r;
        String text = normalizeDigits(rawText);

        // 1) الرقم القومي: 14 رقم بيبدأ بـ 2 أو 3 (مع تجاهل المسافات بين الأرقام)
        String digitsOnlyLines = text.replaceAll("(?<=\\d)[ \\t]+(?=\\d)", "");
        Matcher m = Pattern.compile("(?<!\\d)([23]\\d{13})(?!\\d)").matcher(digitsOnlyLines);
        while (m.find()) {
            if (fillFromId(r, m.group(1))) break;
        }

        // 2) الاسم والعنوان: سطور عربي بعد شيل العناوين الثابتة
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String clean = line.replaceAll("[^\\u0600-\\u06FF\\s]", " ")
                               .replaceAll("[\\u0660-\\u0669\\u06F0-\\u06F9]", " ")
                               .replaceAll("\\s+", " ").trim();
            if (clean.length() < 2 || isHeader(clean)) continue;
            lines.add(clean);
        }
        if (lines.size() >= 1) r.firstName = lines.get(0);
        if (lines.size() >= 2) r.fullName = lines.get(0) + " " + lines.get(1);
        else r.fullName = r.firstName;
        if (lines.size() >= 3) {
            StringBuilder addr = new StringBuilder(lines.get(2));
            if (lines.size() >= 4) addr.append(" - ").append(lines.get(3));
            r.address = addr.toString();
        }
        return r;
    }

    private static boolean isHeader(String line) {
        for (String w : HEADER_WORDS) if (line.contains(w)) return true;
        return false;
    }

    /** يملا تاريخ الميلاد والنوع والمحافظة من الرقم القومي. يرجّع false لو الرقم مش منطقي. */
    public static boolean fillFromId(Result r, String id) {
        int century = id.charAt(0) == '2' ? 1900 : 2000;
        int yy = Integer.parseInt(id.substring(1, 3));
        int mm = Integer.parseInt(id.substring(3, 5));
        int dd = Integer.parseInt(id.substring(5, 7));
        if (mm < 1 || mm > 12 || dd < 1 || dd > 31) return false;
        String gov = governorate(id.substring(7, 9));
        if (gov.isEmpty()) return false;
        r.nationalId = id;
        r.birthDate = String.format("%04d/%02d/%02d", century + yy, mm, dd);
        r.governorate = gov;
        r.gender = ((id.charAt(12) - '0') % 2 == 1) ? "ذكر" : "أنثى";
        return true;
    }

    public static String governorate(String code) {
        switch (code) {
            case "01": return "القاهرة";
            case "02": return "الإسكندرية";
            case "03": return "بورسعيد";
            case "04": return "السويس";
            case "11": return "دمياط";
            case "12": return "الدقهلية";
            case "13": return "الشرقية";
            case "14": return "القليوبية";
            case "15": return "كفر الشيخ";
            case "16": return "الغربية";
            case "17": return "المنوفية";
            case "18": return "البحيرة";
            case "19": return "الإسماعيلية";
            case "21": return "الجيزة";
            case "22": return "بني سويف";
            case "23": return "الفيوم";
            case "24": return "المنيا";
            case "25": return "أسيوط";
            case "26": return "سوهاج";
            case "27": return "قنا";
            case "28": return "أسوان";
            case "29": return "الأقصر";
            case "31": return "البحر الأحمر";
            case "32": return "الوادي الجديد";
            case "33": return "مطروح";
            case "34": return "شمال سيناء";
            case "35": return "جنوب سيناء";
            case "88": return "خارج الجمهورية";
            default: return "";
        }
    }
}
