package com.my.idreader;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * تحليل النص الخارج من OCR للبطاقة الشخصية السورية.
 * بيدوّر على كل حقل باسمه (الاسم، اسم الأب، اسم الأم...) مش بترتيب السطور،
 * عشان يشتغل حتى لو ترتيب الكلام اختلف أو الحقل والقيمة جم في سطرين.
 * كلاس Java عادي (من غير Android).
 */
public class SyrianIdParser {

    /** عدد خانات الرقم الوطني. */
    public static int NATIONAL_ID_LENGTH = 11;

    public static class Result {
        public String nationalId = "";
        public String firstName = "";
        public String lastName = "";     // النسبة / الكنية
        public String fatherName = "";
        public String motherName = "";   // اسم الأم ونسبتها
        public String birthPlace = "";
        public String birthDate = "";    // dd/MM/yyyy
        public String registry = "";     // محل ورقم القيد
        public String gender = "";
        public String fullName = "";     // الاسم + اسم الأب + النسبة

        /** النتيجة تعتبر ناجحة لو لقينا الرقم الوطني، أو الاسم ومعاه اسم الأب أو النسبة. */
        public boolean isValid() {
            return nationalId.length() == NATIONAL_ID_LENGTH
                    || (!firstName.isEmpty() && (!fatherName.isEmpty() || !lastName.isEmpty()));
        }
    }

    // الأسماء مكتوبة بعد التطبيع (أ/إ/آ ← ا). الأطول الأول عشان ميتلخبطش.
    private static final String[] L_NATIONAL = {"الرقم الوطني", "الرقم الوطنى", "رقم وطني"};
    private static final String[] L_BIRTH = {"محل و تاريخ الولادة", "مكان و تاريخ الولادة", "مكان وتاريخ الولادة", "محل وتاريخ الولادة", "مكان وتاريخ الميلاد",
            "محل الولادة", "مكان الولادة", "تاريخ الولادة", "تاريخ الميلاد", "الولادة"};
    private static final String[] L_FATHER = {"اسم الاب", "الاب"};
    private static final String[] L_MOTHER = {"اسم و نسبة الام", "اسم ونسبة الام", "اسم الام ونسبتها", "اسم الام و نسبتها", "اسم الام", "الام"};
    private static final String[] L_LAST = {"النسبة", "الكنية", "الشهرة", "اللقب", "النسبه"};
    private static final String[] L_FIRST = {"الاسم"};
    private static final String[] L_REGISTRY = {"محل القيد", "مكان القيد", "القيد", "الخانة", "الامانة"};
    private static final String[] L_GENDER = {"الجنس"};
    private static final String[] L_OTHER = {"الجمهورية", "العربية السورية", "وزارة الداخلية", "البطاقة الشخصية", "بطاقة شخصية",
            "تاريخ المنح", "العلامات الفارقة", "العلامات المميزة", "لون العينين", "لون الوجه", "العنوان", "الشؤون المدنية", "امين السجل", "توقيع"};

    private static final Pattern DATE_DMY = Pattern.compile("(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})\\s*[/\\-.]\\s*((?:19|20)\\d{2})");
    private static final Pattern DATE_YMD = Pattern.compile("((?:19|20)\\d{2})\\s*[/\\-.]\\s*(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})");

    public static String normalizeDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '٠' && c <= '٩') sb.append((char) ('0' + (c - '٠')));
            else if (c >= '۰' && c <= '۹') sb.append((char) ('0' + (c - '۰')));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** نص مطبّع للمقارنة + خريطة ترجّع كل حرف لمكانه في النص الأصلي. */
    private static class Norm {
        final String text;
        final int[] map; // map[i] = مكان الحرف i في الأصل، map[length] = طول الأصل
        Norm(String text, int[] map) { this.text = text; this.map = map; }
        int origIndex(int i) { return map[Math.min(i, text.length())]; }
    }

    /** يشيل التطويل (ـ) والتشكيل، يوحّد الهمزات، ويخلي المسافات مسافة واحدة. */
    private static Norm norm(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int[] map = new int[s.length() + 1];
        boolean lastSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u0640' || (c >= '\u064B' && c <= '\u0652') || c == '\u0670') continue; // تطويل وتشكيل
            if (c == 'أ' || c == 'إ' || c == 'آ' || c == 'ٱ') c = 'ا';
            else if (c == 'ى') c = 'ي';
            if (Character.isWhitespace(c)) {
                if (lastSpace) continue;
                c = ' ';
                lastSpace = true;
            } else {
                lastSpace = false;
            }
            map[sb.length()] = i;
            sb.append(c);
        }
        map[sb.length()] = s.length();
        int[] m = new int[sb.length() + 1];
        System.arraycopy(map, 0, m, 0, sb.length() + 1);
        return new Norm(sb.toString(), m);
    }

    private static String normForMatch(String s) { return norm(s).text; }

    private static boolean isArabicLetter(char c) {
        return c >= 'ء' && c <= 'ي';
    }

    /** يرجّع مكان نهاية الحقل في السطر، أو -1. */
    private static int findLabel(String normLine, String[] labels) {
        for (String l : labels) {
            int from = 0;
            while (true) {
                int i = normLine.indexOf(l, from);
                if (i < 0) break;
                int end = i + l.length();
                boolean okStart = i == 0 || !isArabicLetter(normLine.charAt(i - 1));
                boolean okEnd = end >= normLine.length() || !isArabicLetter(normLine.charAt(end));
                if (okStart && okEnd) return end;
                from = i + 1;
            }
        }
        return -1;
    }

    private static boolean hasAnyLabel(String normLine) {
        String[][] all = {L_NATIONAL, L_BIRTH, L_FATHER, L_MOTHER, L_LAST, L_FIRST, L_REGISTRY, L_GENDER, L_OTHER};
        for (String[] g : all) if (findLabel(normLine, g) >= 0) return true;
        return false;
    }

    private static String cleanValue(String v) {
        return v.replaceAll("^[\\s:：\\-–_./|]+", "")
                .replaceAll("[\\s:：\\-–_|]+$", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** يجيب قيمة الحقل: من نفس السطر بعد اسم الحقل، أو من السطر اللي بعده. */
    private static String valueOf(List<String> lines, List<Norm> norms, String[] labels) {
        for (int i = 0; i < lines.size(); i++) {
            Norm n = norms.get(i);
            int end = findLabel(n.text, labels);
            if (end < 0) continue;
            String v = cleanValue(lines.get(i).substring(n.origIndex(end)));
            // لو في نفس السطر حقل تاني بعده، خد اللي قبله بس
            Norm nv = norm(v);
            int cut = firstOtherLabelStart(nv.text);
            if (cut >= 0) v = cleanValue(v.substring(0, nv.origIndex(cut)));
            if (!v.isEmpty()) return v;
            if (i + 1 < lines.size() && !hasAnyLabel(norms.get(i + 1).text)) {
                return cleanValue(lines.get(i + 1));
            }
        }
        return "";
    }

    private static int firstOtherLabelStart(String normValue) {
        int best = -1;
        String[][] all = {L_NATIONAL, L_BIRTH, L_FATHER, L_MOTHER, L_LAST, L_FIRST, L_REGISTRY, L_GENDER};
        for (String[] g : all) {
            int end = findLabel(normValue, g);
            if (end < 0) continue;
            for (String l : g) {
                int s = end - l.length();
                if (s >= 0 && normValue.startsWith(l, s)) {
                    if (s > 0 && (best < 0 || s < best)) best = s;
                    break;
                }
            }
        }
        return best;
    }

    private static String onlyLetters(String s) {
        return s.replaceAll("[^\\u0621-\\u064A\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    public static Result parse(String rawText) {
        Result r = new Result();
        if (rawText == null) return r;
        String text = normalizeDigits(rawText);

        List<String> lines = new ArrayList<>();
        List<Norm> norms = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            lines.add(t);
            norms.add(norm(t));
        }

        // 1) الرقم القومي/الوطني
        String nat = valueOf(lines, norms, L_NATIONAL).replaceAll("[^0-9]", "");
        if (nat.length() == NATIONAL_ID_LENGTH) {
            r.nationalId = nat;
        } else {
            String joined = text.replaceAll("(?<=\\d)[ \\t]+(?=\\d)", "");
            Matcher m = Pattern.compile("(?<!\\d)(\\d{" + NATIONAL_ID_LENGTH + "})(?!\\d)").matcher(joined);
            if (m.find()) r.nationalId = m.group(1);
        }

        // 2) الأسماء
        r.firstName = onlyLetters(valueOf(lines, norms, L_FIRST));
        r.lastName = onlyLetters(valueOf(lines, norms, L_LAST));
        r.fatherName = onlyLetters(valueOf(lines, norms, L_FATHER));
        r.motherName = onlyLetters(valueOf(lines, norms, L_MOTHER));   // اسم الأم ونسبتها

        StringBuilder full = new StringBuilder(r.firstName);
        if (!r.fatherName.isEmpty()) full.append(full.length() > 0 ? " " : "").append(r.fatherName);
        if (!r.lastName.isEmpty()) full.append(full.length() > 0 ? " " : "").append(r.lastName);
        r.fullName = full.toString();

        // 3) مكان وتاريخ الولادة
        String birth = valueOf(lines, norms, L_BIRTH);
        String date = findDate(birth);
        if (date.isEmpty()) date = findDate(text);
        r.birthDate = date;
        r.birthPlace = onlyLetters(birth);

        // 4) القيد والجنس
        r.registry = cleanValue(valueOf(lines, norms, L_REGISTRY));
        String g = normForMatch(valueOf(lines, norms, L_GENDER) + " ");
        if (g.contains("انثى") || g.contains("انثي")) r.gender = "أنثى";
        else if (g.contains("ذكر")) r.gender = "ذكر";

        return r;
    }

    private static String findDate(String s) {
        Matcher m = DATE_DMY.matcher(s);
        if (m.find()) return fmt(m.group(1), m.group(2), m.group(3));
        m = DATE_YMD.matcher(s);
        if (m.find()) return fmt(m.group(3), m.group(2), m.group(1));
        return "";
    }

    private static String fmt(String d, String mo, String y) {
        int dd = Integer.parseInt(d), mm = Integer.parseInt(mo);
        if (dd < 1 || dd > 31 || mm < 1 || mm > 12) return "";
        return String.format("%02d/%02d/%s", dd, mm, y);
    }
}
