package com.my.idreader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * قارئ أرقام عربية (٠-٩) مخصوص للبطاقة السورية - أدق بكتير من Tesseract في الأرقام.
 *
 * الفكرة: كل رقم بيتقص لوحده (connected components) ويتقارن بقوالب مرسومة بخط عربي.
 *  - الرقم الوطني مطبوع بالأحمر ← بنفصله بلونه عن أي كلام تاني.
 *  - تاريخ الولادة في آخر سطر الحقول على الشمال، مفصول بشَرطات.
 *
 * Java عادي (من غير Android) - بيشتغل على مصفوفة بكسلات ARGB.
 */
public class DigitReader {

    private static final int N = 16; // حجم القالب بعد التطبيع

    public static class Template {
        final int digit;
        final float[] v;
        final float ar;
        Template(int digit, float[] v, float ar) { this.digit = digit; this.v = v; this.ar = ar; }
    }

    /** يعمل قالب من صورة رقم (true = حبر). */
    public static Template makeTemplate(int digit, boolean[] mask, int w, int h) {
        Comp c = bbox(mask, w, h);
        if (c == null) return null;
        return new Template(digit, normalize(c), (float) c.h() / c.w());
    }

    private final List<Template> templates;

    /** آخر نص خام اتقرا في سطر التاريخ (للتجربة والتشخيص). */
    public String lastDateRaw = "";

    public DigitReader(List<Template> templates) {
        this.templates = templates;
    }

    // =============================================================== الرقم الوطني (أحمر)

    /** يقرا الأرقام الحمرا في الصورة من الشمال لليمين. */
    public String readRedNumber(int[] argb, int w, int h) {
        boolean[] m = new boolean[w * h];
        for (int i = 0; i < m.length; i++) {
            int c = argb[i];
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            m[i] = r - Math.max(g, b) > 40 && r > 90;
        }
        List<Comp> comps = components(m, w, h, 3);
        if (comps.isEmpty()) return "";

        float medh = upperMedianHeight(comps);
        // المكونات اللي على نفس السطر بتاع أغلب الأرقام
        float[] cys = new float[comps.size()];
        for (int i = 0; i < cys.length; i++) cys[i] = comps.get(i).cy();
        float cy = median(cys);
        List<Comp> row = new ArrayList<>();
        for (Comp c : comps) {
            if (Math.abs(c.cy() - cy) < medh && c.area >= 0.03f * medh * medh) row.add(c);
        }
        sortByX(row);
        StringBuilder sb = new StringBuilder();
        for (Comp c : row) {
            char ch = classify(c, medh, false);
            if (ch >= '0' && ch <= '9') sb.append(ch);
        }
        return sb.toString();
    }

    // =============================================================== تاريخ الولادة

    /**
     * يدوّر على التاريخ في آخر سطر من منطقة الحقول (أقصى الشمال) ويرجّعه dd/MM/yyyy أو "".
     */
    public String readBirthDate(int[] argb, int w, int h) {
        boolean[] dark = sauvola(grayMin(argb), w, h);
        lastDateRaw = "";

        // السطور بالـ projection
        int[] prof = new int[h];
        int max = 0;
        for (int y = 0; y < h; y++) {
            int s = 0;
            for (int x = 0; x < w; x++) if (dark[y * w + x]) s++;
            prof[y] = s;
            max = Math.max(max, s);
        }
        int th = Math.max(3, (int) (max * 0.08f));
        int minLine = Math.max(6, h / 40);
        List<int[]> lines = new ArrayList<>();
        int start = -1;
        for (int y = 0; y <= h; y++) {
            boolean on = y < h && prof[y] > th;
            if (on && start < 0) start = y;
            else if (!on && start >= 0) {
                if (y - start >= minLine) lines.add(new int[]{start, y});
                start = -1;
            }
        }
        // نجرب من آخر سطر لفوق (التاريخ في سطر الولادة اللي هو الأخير غالباً)
        for (int li = lines.size() - 1; li >= 0 && li >= lines.size() - 2; li--) {
            int y0 = Math.max(0, lines.get(li)[0] - 3), y1 = Math.min(h, lines.get(li)[1] + 3);
            String d = dateInLine(dark, w, y0, y1);
            if (!d.isEmpty()) return d;
        }
        return "";
    }

    private String dateInLine(boolean[] dark, int w, int y0, int y1) {
        int lh = y1 - y0;
        boolean[] line = new boolean[w * lh];
        System.arraycopy(dark, y0 * w, line, 0, w * lh);
        List<Comp> comps = components(line, w, lh, 8);
        if (comps.isEmpty()) return "";
        sortByX(comps);

        int[] hs = new int[comps.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = comps.get(i).h();
        Arrays.sort(hs);
        float medh = hs[Math.min(hs.length - 1, hs.length * 3 / 4)];
        float noise = 0.04f * medh * medh;

        // أقصى الشمال: نجمع المكونات لحد ما نلاقي كلمة عريضة أو فراغ كبير
        StringBuilder sb = new StringBuilder();
        int lastRight = -1;
        for (Comp c : comps) {
            if (c.area < noise) continue;                       // نقط الزخرفة
            if (c.cy() > lh * 0.9f || c.cy() < lh * 0.1f) continue;
            if (c.w() > 1.5f * medh) break;                     // كلمة (اسم المكان)
            if (lastRight >= 0 && c.minX - lastRight > 1.2f * medh) break;
            sb.append(classify(c, medh, true));
            lastRight = Math.max(lastRight, c.maxX + 1);
        }
        lastDateRaw += "[" + sb + "]";
        return parseVisualDate(sb.toString());
    }

    /** النص هنا بالترتيب المرئي (شمال ← يمين). المجموعة اللي جنب السنة = الشهر. */
    static String parseVisualDate(String s) {
        s = s.replaceAll("-+", "-");
        // السنة لازم تبدأ بـ 19 أو 20: لو اتقرت 99xx فالأولى غالباً ١ اتقرت ٩
        s = s.replaceAll("(^|-)99(\\d{2})(?=-|$)", "$119$2");
        Matcher m = Pattern.compile("((?:19|20)\\d{2})-(\\d{1,2})-(\\d{1,2})").matcher(s);
        if (m.find()) return fmt(m.group(3), m.group(2), m.group(1));
        m = Pattern.compile("(\\d{1,2})-(\\d{1,2})-((?:19|20)\\d{2})").matcher(s);
        if (m.find()) return fmt(m.group(1), m.group(2), m.group(3));
        return "";
    }

    private static String fmt(String d, String mo, String y) {
        int dd = Integer.parseInt(d), mm = Integer.parseInt(mo), yy = Integer.parseInt(y);
        if (dd < 1 || dd > 31 || mm < 1 || mm > 12 || yy < 1900 || yy > 2035) return "";
        return String.format("%02d/%02d/%04d", dd, mm, yy);
    }

    // =============================================================== التصنيف

    private char classify(Comp c, float medh, boolean allowDash) {
        int h = c.h(), w = c.w();
        float ar = (float) h / w;
        if (allowDash && w > 1.6f * h && h < 0.4f * medh) return '-';
        if (h < 0.55f * medh) return '0';                                 // ٠ = نقطة صغيرة
        if (h < 0.8f * medh && ar > 1.4f) return '1';                    // ١ قصير (الرقم الأحمر)
        boolean thin = ar >= 2.1f;                                        // ١ طويل ورفيع (أو ٤)
        boolean hole = hasHole(c);                                        // ٥ و ٩ فيهم دايرة مقفولة
        float[] v = normalize(c);
        int best = -1;
        float bestScore = -1e9f;
        for (Template t : templates) {
            if (t.digit == 0) continue;
            if (!thin && t.digit == 1) continue;
            float s = 0;
            for (int i = 0; i < v.length; i++) s += v[i] * t.v[i];
            s -= 0.15f * Math.abs((float) Math.log((ar + 1e-3f) / (t.ar + 1e-3f)));
            if (thin && t.digit == 1) s += 0.10f;
            boolean loopDigit = t.digit == 5 || t.digit == 9;
            if (hole && !loopDigit) s -= 0.25f;
            if (!hole && loopDigit) s -= 0.12f;
            if (s > bestScore) { bestScore = s; best = t.digit; }
        }
        return best < 0 ? '?' : (char) ('0' + best);
    }

    /** فيه فراغ مقفول جوه الرقم؟ (خلفية مش متصلة بحدود المربع) */
    private static boolean hasHole(Comp c) {
        int w = c.w() + 2, h = c.h() + 2;
        boolean[] bg = new boolean[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            boolean ink = x > 0 && y > 0 && x < w - 1 && y < h - 1 && c.mask[(y - 1) * c.w() + (x - 1)];
            bg[y * w + x] = !ink;
        }
        // نملا الخلفية من برّه، اللي يفضل = فراغات جوه
        int[] stack = new int[w * h];
        boolean[] seen = new boolean[w * h];
        int sp = 0;
        stack[sp++] = 0;
        seen[0] = true;
        while (sp > 0) {
            int p = stack[--sp];
            int px = p % w, py = p / w;
            int[] nb = {p - 1, p + 1, p - w, p + w};
            for (int q : nb) {
                if (q < 0 || q >= w * h) continue;
                int qx = q % w;
                if (Math.abs(qx - px) > 1) continue;
                if (bg[q] && !seen[q]) { seen[q] = true; stack[sp++] = q; }
            }
        }
        int inner = 0;
        for (int i = 0; i < bg.length; i++) if (bg[i] && !seen[i]) inner++;
        return inner >= Math.max(2, c.area / 40);
    }

    // =============================================================== أدوات الصورة

    static class Comp {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, area;
        boolean[] mask; // bbox mask
        int w() { return maxX - minX + 1; }
        int h() { return maxY - minY + 1; }
        float cy() { return (minY + maxY) / 2f; }
    }

    private static Comp bbox(boolean[] m, int w, int h) {
        Comp c = new Comp();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (m[y * w + x]) {
            c.minX = Math.min(c.minX, x); c.maxX = Math.max(c.maxX, x);
            c.minY = Math.min(c.minY, y); c.maxY = Math.max(c.maxY, y);
            c.area++;
        }
        if (c.area == 0) return null;
        c.mask = new boolean[c.w() * c.h()];
        for (int y = c.minY; y <= c.maxY; y++) for (int x = c.minX; x <= c.maxX; x++)
            c.mask[(y - c.minY) * c.w() + (x - c.minX)] = m[y * w + x];
        return c;
    }

    /** connected components (8 اتجاهات). */
    static List<Comp> components(boolean[] m, int w, int h, int minArea) {
        int[] label = new int[w * h];
        int[] stack = new int[w * h];
        List<Comp> out = new ArrayList<>();
        int next = 0;
        for (int i = 0; i < m.length; i++) {
            if (!m[i] || label[i] != 0) continue;
            next++;
            Comp c = new Comp();
            List<Integer> pix = new ArrayList<>();
            int sp = 0;
            stack[sp++] = i;
            label[i] = next;
            while (sp > 0) {
                int p = stack[--sp];
                pix.add(p);
                int px = p % w, py = p / w;
                c.minX = Math.min(c.minX, px); c.maxX = Math.max(c.maxX, px);
                c.minY = Math.min(c.minY, py); c.maxY = Math.max(c.maxY, py);
                for (int dy = -1; dy <= 1; dy++) {
                    int ny = py + dy;
                    if (ny < 0 || ny >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx;
                        if (nx < 0 || nx >= w) continue;
                        int q = ny * w + nx;
                        if (m[q] && label[q] == 0) { label[q] = next; stack[sp++] = q; }
                    }
                }
            }
            c.area = pix.size();
            if (c.area < minArea) continue;
            c.mask = new boolean[c.w() * c.h()];
            for (int p : pix) c.mask[(p / w - c.minY) * c.w() + (p % w - c.minX)] = true;
            out.add(c);
        }
        return out;
    }

    /** مربع + تصغير لـ 16×16 + zero-mean/unit-norm. */
    private static float[] normalize(Comp c) {
        int w = c.w(), h = c.h(), s = Math.max(w, h);
        int ox = (s - w) / 2, oy = (s - h) / 2;
        float[] v = new float[N * N];
        int SS = 4;
        for (int ty = 0; ty < N; ty++) for (int tx = 0; tx < N; tx++) {
            int on = 0;
            for (int sy = 0; sy < SS; sy++) for (int sx = 0; sx < SS; sx++) {
                int x = (int) ((tx + (sx + 0.5f) / SS) * s / N) - ox;
                int y = (int) ((ty + (sy + 0.5f) / SS) * s / N) - oy;
                if (x >= 0 && y >= 0 && x < w && y < h && c.mask[y * w + x]) on++;
            }
            v[ty * N + tx] = on / (float) (SS * SS);
        }
        float mean = 0;
        for (float f : v) mean += f;
        mean /= v.length;
        float norm = 0;
        for (int i = 0; i < v.length; i++) { v[i] -= mean; norm += v[i] * v[i]; }
        norm = (float) Math.sqrt(norm) + 1e-9f;
        for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }

    /** رمادي بأقل قناة لون: الأسود والأزرق والأحمر يفضلوا غامقين والخلفية الفاتحة تفتح. */
    static int[] grayMin(int[] argb) {
        int[] g = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int c = argb[i];
            g[i] = Math.min((c >> 16) & 0xFF, Math.min((c >> 8) & 0xFF, c & 0xFF));
        }
        return g;
    }

    /** تحويل أبيض وأسود بعتبة محلية (Sauvola). true = حبر. */
    static boolean[] sauvola(int[] g, int w, int h) {
        long[] sum = new long[(w + 1) * (h + 1)];
        long[] sq = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long rs = 0, rq = 0;
            for (int x = 0; x < w; x++) {
                int v = g[y * w + x];
                rs += v; rq += (long) v * v;
                sum[(y + 1) * (w + 1) + x + 1] = sum[y * (w + 1) + x + 1] + rs;
                sq[(y + 1) * (w + 1) + x + 1] = sq[y * (w + 1) + x + 1] + rq;
            }
        }
        int half = Math.max(8, w / 30);
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int ya = Math.max(0, y - half), yb = Math.min(h, y + half + 1);
            for (int x = 0; x < w; x++) {
                int xa = Math.max(0, x - half), xb = Math.min(w, x + half + 1);
                int n = (xb - xa) * (yb - ya);
                long s1 = sum[yb * (w + 1) + xb] - sum[ya * (w + 1) + xb] - sum[yb * (w + 1) + xa] + sum[ya * (w + 1) + xa];
                long s2 = sq[yb * (w + 1) + xb] - sq[ya * (w + 1) + xb] - sq[yb * (w + 1) + xa] + sq[ya * (w + 1) + xa];
                double mean = (double) s1 / n;
                double sd = Math.sqrt(Math.max(0, (double) s2 / n - mean * mean));
                out[y * w + x] = g[y * w + x] < mean * (1 + 0.25 * (sd / 128 - 1));
            }
        }
        return out;
    }

    private static float upperMedianHeight(List<Comp> comps) {
        int[] hs = new int[comps.size()];
        for (int i = 0; i < hs.length; i++) hs[i] = comps.get(i).h();
        Arrays.sort(hs);
        return hs[Math.min(hs.length - 1, hs.length * 3 / 4)];
    }

    private static float median(float[] a) {
        float[] b = a.clone();
        Arrays.sort(b);
        return b[b.length / 2];
    }

    private static void sortByX(List<Comp> l) {
        Collections.sort(l, new Comparator<Comp>() {
            @Override public int compare(Comp a, Comp b) { return a.minX - b.minX; }
        });
    }
}
