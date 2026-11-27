package com.my.idreader;

import java.util.HashMap;
import java.util.Map;

/**
 * بيجمع نتايج كذا فريم ويختار لكل حقل القيمة اللي اتكررت أكتر.
 * كده غلطة قراءة في فريم واحد متأثرش على النتيجة.
 */
public class FieldVoter {

    /** كام مرة لازم القيمة تتكرر عشان نعتبرها أكيدة. */
    public static int AGREE = 2;

    private final Map<String, Integer> id = new HashMap<>(), first = new HashMap<>(), father = new HashMap<>(),
            last = new HashMap<>(), mother = new HashMap<>(), birth = new HashMap<>(), place = new HashMap<>();
    private int frames;

    public void add(SyrianIdParser.Result r) {
        frames++;
        vote(id, r.nationalId);
        vote(first, r.firstName);
        vote(father, r.fatherName);
        vote(last, r.lastName);
        vote(mother, r.motherName);
        vote(birth, r.birthDate);
        vote(place, r.birthPlace);
    }

    public int frames() { return frames; }

    /** أكيد لو الرقم الوطني والتاريخ والاسم واسم الأب والنسبة اتكرروا. */
    public boolean isConfident() {
        return topCount(id) >= AGREE && topCount(birth) >= AGREE
                && topCount(first) >= AGREE && topCount(father) >= AGREE && topCount(last) >= AGREE;
    }

    public SyrianIdParser.Result best() {
        SyrianIdParser.Result r = new SyrianIdParser.Result();
        r.nationalId = top(id);
        r.firstName = top(first);
        r.fatherName = top(father);
        r.lastName = top(last);
        r.motherName = top(mother);
        r.birthDate = top(birth);
        r.birthPlace = top(place);
        StringBuilder full = new StringBuilder(r.firstName);
        if (!r.fatherName.isEmpty()) full.append(full.length() > 0 ? " " : "").append(r.fatherName);
        if (!r.lastName.isEmpty()) full.append(full.length() > 0 ? " " : "").append(r.lastName);
        r.fullName = full.toString();
        return r;
    }

    private static void vote(Map<String, Integer> m, String v) {
        if (v == null || v.trim().isEmpty()) return;
        String k = v.trim();
        Integer c = m.get(k);
        m.put(k, c == null ? 1 : c + 1);
    }

    private static int topCount(Map<String, Integer> m) {
        int best = 0;
        for (int c : m.values()) best = Math.max(best, c);
        return best;
    }

    /** الأكتر تكرار، ولو فيه تعادل ناخد الأطول (غالباً الأكمل). */
    private static String top(Map<String, Integer> m) {
        String best = "";
        int bc = 0;
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            int c = e.getValue();
            if (c > bc || (c == bc && e.getKey().length() > best.length())) {
                best = e.getKey();
                bc = c;
            }
        }
        return best;
    }
}
