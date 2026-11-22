package com.my.idreader;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * شاشة مسح البطاقة زي تطبيقات البنوك:
 * كاميرا لايف + إطار البطاقة + خط مسح متحرك + فلاش + قراءة تلقائية.
 * بتختار أوضح فريم وتقصّ منطقة الإطار بس وتبعتها لـ Cloud Vision.
 *
 * الاستخدام (من أي Activity):
 *   IdScanActivity.start(MainActivity.this, new IdScanActivity.Listener() {
 *       public void onScanned(EgyptianIdParser.Result r) { ... }
 *   });
 *
 * لازم تتسجل في AndroidManifest (Sketchware Pro ← AndroidManifest Manager ← Activities).
 * مش محتاجة أي مكتبة ولا ملف XML.
 */
@SuppressWarnings("deprecation")
public class IdScanActivity extends Activity implements TextureView.SurfaceTextureListener {

    public interface Listener {
        void onScanned(EgyptianIdParser.Result result);
    }

    // ===== إعدادات =====
    private static final float CARD_RATIO = 85.6f / 54f;  // مقاس البطاقة القياسي
    private static final int SAMPLE_INTERVAL_MS = 250;    // كل قد إيه نفحص فريم
    private static final int SAMPLES_PER_TRY = 6;         // نختار الأوضح من 6 فريمات (~1.5 ثانية)
    private static final int MAX_AUTO_TRIES = 6;          // أقصى عدد طلبات تلقائية (كل طلب = وحدة في الفاتورة)
    private static final int MIN_BRIGHTNESS = 55;         // أقل من كده = الإضاءة ضعيفة
    // ===================

    private static Listener sListener;

    public static void start(Activity from, Listener listener) {
        sListener = listener;
        from.startActivity(new Intent(from, IdScanActivity.class));
    }

    private static final int ST_SEARCHING = 0, ST_PROCESSING = 1, ST_PAUSED = 2, ST_SUCCESS = 3;
    private int state = ST_SEARCHING;

    private TextureView textureView;
    private OverlayView overlay;
    private FlashButton flashBtn;
    private Camera camera;
    private int camRotation = 90;
    private int pw, ph; // مقاس الفريم من الكاميرا (اتجاه الحساس)
    private boolean flashOn;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private byte[] bestFrame;
    private double bestScore;
    private int bestBrightness;
    private int samples;
    private int tries;

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        root.addView(textureView, new FrameLayout.LayoutParams(-1, -1));

        overlay = new OverlayView(this);
        overlay.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) onScreenTap();
                return true;
            }
        });
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(26);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP | Gravity.RIGHT);
        clp.setMargins(0, dp(16), dp(8), 0);
        root.addView(close, clp);

        flashBtn = new FlashButton(this);
        flashBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleFlash(); }
        });
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(68), dp(68), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        flp.setMargins(0, 0, 0, dp(64));
        root.addView(flashBtn, flp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
            return;
        }
        if (textureView.isAvailable()) openCamera(textureView.getSurfaceTexture());
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7 && (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED)) {
            finish();
        }
        // لو وافق، onResume هيتنادى تاني ويفتح الكاميرا
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacksAndMessages(null);
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) sListener = null;
    }

    // ---------------------------------------------------------------- camera

    @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(s); }
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { applyTransform(); }
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { releaseCamera(); return true; }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }

    private void openCamera(SurfaceTexture surface) {
        if (camera != null || surface == null) return;
        try {
            int id = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
            }
            Camera.getCameraInfo(id, info);
            camRotation = info.orientation;
            camera = Camera.open(id);

            Camera.Parameters p = camera.getParameters();
            Camera.Size size = choosePreviewSize(p.getSupportedPreviewSizes());
            pw = size.width;
            ph = size.height;
            p.setPreviewSize(pw, ph);
            p.setPreviewFormat(ImageFormat.NV21);
            List<String> fm = p.getSupportedFocusModes();
            if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (fm != null && fm.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(camRotation);
            camera.setPreviewTexture(surface);
            camera.startPreview();
            applyTransform();

            flashBtn.setVisibility(hasTorch() ? View.VISIBLE : View.GONE);
            if (flashOn) setTorch(true);
            if (state == ST_SEARCHING) startSearching(null);
        } catch (Exception e) {
            releaseCamera();
            overlay.setHint("مش قادر أفتح الكاميرا: " + e.getMessage(), false);
        }
    }

    private void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
            } catch (Exception ignored) { }
            camera.release();
            camera = null;
        }
    }

    /** أكبر مقاس لحد 1920 ونسبته أقرب لنسبة الشاشة. */
    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        int vw = Math.max(textureView.getWidth(), 1), vh = Math.max(textureView.getHeight(), 1);
        float target = (float) Math.max(vw, vh) / Math.min(vw, vh);
        Camera.Size best = sizes.get(0);
        double bestCost = Double.MAX_VALUE;
        for (Camera.Size s : sizes) {
            if (s.width > 1920 || s.height > 1920) continue;
            float r = (float) s.width / s.height;
            double cost = Math.abs(r - target) * 4000 - s.width;
            if (cost < bestCost) { bestCost = cost; best = s; }
        }
        return best;
    }

    /** center-crop للمعاينة عشان متتمطّش. */
    private void applyTransform() {
        if (pw == 0) return;
        float vw = textureView.getWidth(), vh = textureView.getHeight();
        if (vw == 0 || vh == 0) return;
        float dw = camRotation % 180 == 0 ? pw : ph;
        float dh = camRotation % 180 == 0 ? ph : pw;
        float scale = Math.max(vw / dw, vh / dh);
        Matrix m = new Matrix();
        m.setScale(dw * scale / vw, dh * scale / vh, vw / 2f, vh / 2f);
        textureView.setTransform(m);
    }

    private boolean hasTorch() {
        if (camera == null) return false;
        List<String> modes = camera.getParameters().getSupportedFlashModes();
        return modes != null && modes.contains(Camera.Parameters.FLASH_MODE_TORCH);
    }

    private void setTorch(boolean on) {
        if (camera == null || !hasTorch()) return;
        try {
            Camera.Parameters p = camera.getParameters();
            p.setFlashMode(on ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
        } catch (Exception ignored) { }
    }

    private void toggleFlash() {
        flashOn = !flashOn;
        setTorch(flashOn);
        flashBtn.setOn(flashOn);
    }

    private void onScreenTap() {
        if (camera != null) {
            try {
                camera.cancelAutoFocus();
                camera.autoFocus(null);
            } catch (Exception ignored) { }
        }
        if (state == ST_PAUSED) {
            tries = 0;
            startSearching(null);
        }
    }

    // ---------------------------------------------------------------- auto scan

    private void startSearching(String hint) {
        state = ST_SEARCHING;
        bestFrame = null;
        bestScore = -1;
        samples = 0;
        overlay.setScanning(true);
        overlay.setHint(hint != null ? hint : "ضع وجه البطاقة داخل الإطار بحيث يظهر الرقم القومي", false);
        handler.removeCallbacks(sampler);
        handler.postDelayed(sampler, 600);
    }

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            if (state != ST_SEARCHING || camera == null) return;
            try {
                camera.setOneShotPreviewCallback(new Camera.PreviewCallback() {
                    @Override public void onPreviewFrame(byte[] data, Camera cam) { analyzeFrame(data); }
                });
            } catch (Exception e) {
                handler.postDelayed(this, SAMPLE_INTERVAL_MS);
            }
        }
    };

    private void analyzeFrame(byte[] nv21) {
        if (state != ST_SEARCHING) return;
        if (nv21 == null || nv21.length < pw * ph) {
            handler.postDelayed(sampler, SAMPLE_INTERVAL_MS);
            return;
        }
        Rect r = frameRectInSensor(0f);
        long sum = 0, sumSq = 0, lumSum = 0;
        int n = 0;
        for (int y = r.top + 2; y < r.bottom - 2; y += 3) {
            int row = y * pw;
            for (int x = r.left + 2; x < r.right - 2; x += 3) {
                int c = nv21[row + x] & 0xFF;
                int lap = 4 * c - (nv21[row + x - 2] & 0xFF) - (nv21[row + x + 2] & 0xFF)
                        - (nv21[row - 2 * pw + x] & 0xFF) - (nv21[row + 2 * pw + x] & 0xFF);
                sum += lap;
                sumSq += (long) lap * lap;
                lumSum += c;
                n++;
            }
        }
        if (n > 0) {
            double mean = (double) sum / n;
            double sharpness = (double) sumSq / n - mean * mean; // تباين اللابلاسيان = حدّة الصورة
            int brightness = (int) (lumSum / n);
            if (sharpness > bestScore) {
                bestScore = sharpness;
                bestBrightness = brightness;
                bestFrame = nv21.clone();
            }
        }
        samples++;
        if (samples < SAMPLES_PER_TRY) {
            handler.postDelayed(sampler, SAMPLE_INTERVAL_MS);
            return;
        }
        if (bestBrightness < MIN_BRIGHTNESS && !flashOn) {
            startSearching(hasTorch() ? "الإضاءة ضعيفة - شغّل الفلاش" : "الإضاءة ضعيفة - قرّب من مكان منوّر");
            return;
        }
        sendBestFrame();
    }

    private void sendBestFrame() {
        state = ST_PROCESSING;
        tries++;
        overlay.setScanning(false);
        overlay.setHint("جاري قراءة البيانات...", false);
        final byte[] frame = bestFrame;
        final Rect crop = frameRectInSensor(0.06f);
        final int fw = pw, fh = ph, rot = camRotation;
        bestFrame = null;

        new Thread(new Runnable() {
            @Override public void run() {
                final byte[] jpeg;
                try {
                    jpeg = cropRotateJpeg(frame, fw, fh, crop, rot);
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { onScanFailed("خطأ في الصورة"); }
                    });
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        CloudVisionScanner.scanJpeg(IdScanActivity.this, jpeg, new CloudVisionScanner.Callback() {
                            @Override public void onResult(EgyptianIdParser.Result r, String raw) {
                                if (r.nationalId.length() == 14) onScanSuccess(r);
                                else onScanFailed(null);
                            }
                            @Override public void onError(String msg) {
                                state = ST_PAUSED;
                                overlay.setHint("خطأ: " + msg + "\nاضغط على الشاشة للمحاولة تاني", true);
                            }
                        });
                    }
                });
            }
        }).start();
    }

    private void onScanFailed(String reason) {
        if (isFinishing()) return;
        if (tries >= MAX_AUTO_TRIES) {
            state = ST_PAUSED;
            overlay.setHint("مقدرتش أقرا البطاقة\nقرّبها وثبّت الموبايل واضغط على الشاشة", true);
        } else {
            startSearching(reason != null ? reason : "ثبّت الموبايل وخلي البطاقة كلها جوه الإطار");
        }
    }

    private void onScanSuccess(final EgyptianIdParser.Result r) {
        state = ST_SUCCESS;
        overlay.setSuccess();
        overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            handler.postDelayed(new Runnable() { @Override public void run() { tg.release(); } }, 400);
        } catch (Exception ignored) { }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                Listener l = sListener;
                sListener = null;
                if (l != null) l.onScanned(r);
                finish();
            }
        }, 700);
    }

    /** يحوّل مكان الإطار على الشاشة لإحداثيات فريم الكاميرا (الحساس). */
    private Rect frameRectInSensor(float margin) {
        RectF f = new RectF(overlay.getFrame());
        float mx = f.width() * margin, my = f.height() * margin;
        f.inset(-mx, -my);

        float vw = textureView.getWidth(), vh = textureView.getHeight();
        float dw = camRotation % 180 == 0 ? pw : ph;
        float dh = camRotation % 180 == 0 ? ph : pw;
        float scale = Math.max(vw / dw, vh / dh);
        float ox = (vw - dw * scale) / 2f, oy = (vh - dh * scale) / 2f;
        float x1 = (f.left - ox) / scale, y1 = (f.top - oy) / scale;
        float x2 = (f.right - ox) / scale, y2 = (f.bottom - oy) / scale;

        float[] a = toSensor(x1, y1), b = toSensor(x2, y2);
        int left = clamp((int) Math.min(a[0], b[0]), 0, pw);
        int right = clamp((int) Math.max(a[0], b[0]), 0, pw);
        int top = clamp((int) Math.min(a[1], b[1]), 0, ph);
        int bottom = clamp((int) Math.max(a[1], b[1]), 0, ph);
        left &= ~1; top &= ~1; right &= ~1; bottom &= ~1;
        if (right - left < 16 || bottom - top < 16) return new Rect(0, 0, pw & ~1, ph & ~1);
        return new Rect(left, top, right, bottom);
    }

    private float[] toSensor(float x, float y) {
        switch (camRotation) {
            case 90:  return new float[]{y, ph - x};
            case 180: return new float[]{pw - x, ph - y};
            case 270: return new float[]{pw - y, x};
            default:  return new float[]{x, y};
        }
    }

    private static byte[] cropRotateJpeg(byte[] nv21, int w, int h, Rect crop, int rot) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new YuvImage(nv21, ImageFormat.NV21, w, h, null).compressToJpeg(crop, 95, bos);
        if (rot == 0) return bos.toByteArray();
        byte[] raw = bos.toByteArray();
        Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
        Matrix m = new Matrix();
        m.postRotate(rot);
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        bmp.recycle();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, 92, out);
        rotated.recycle();
        return out.toByteArray();
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ================================================================ Overlay

    /** الطبقة اللي فوق الكاميرا: تعتيم + إطار + أركان خضرا + خط مسح + عنوان + تعليمات. */
    private class OverlayView extends View {
        private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint corner = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scan = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint hintP = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF frame = new RectF();
        private final Path holePath = new Path();
        private String hint = "";
        private boolean hintWarn, scanning = true, success;
        private long startTime = System.currentTimeMillis();
        private final int green = Color.parseColor("#3DDC84");

        OverlayView(Context c) {
            super(c);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            mask.setColor(Color.parseColor("#B3000000"));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(2));
            border.setColor(Color.WHITE);
            corner.setStyle(Paint.Style.STROKE);
            corner.setStrokeWidth(dp(5));
            corner.setStrokeCap(Paint.Cap.ROUND);
            corner.setColor(green);
            fill.setColor(Color.parseColor("#553DDC84"));
            title.setColor(Color.WHITE);
            title.setTextSize(dp(22));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextAlign(Paint.Align.CENTER);
            hintP.setTextSize(dp(15));
        }

        RectF getFrame() { return frame; }

        void setHint(String h, boolean warn) { hint = h; hintWarn = warn; invalidate(); }
        void setScanning(boolean s) { scanning = s; startTime = System.currentTimeMillis(); invalidate(); }
        void setSuccess() {
            success = true;
            scanning = false;
            hint = "✓ تم قراءة البطاقة بنجاح";
            hintWarn = false;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            float fw = w - dp(48);
            float fh = fw / CARD_RATIO;
            float top = h * 0.40f - fh / 2f;
            frame.set((w - fw) / 2f, top, (w + fw) / 2f, top + fh);
            holePath.reset();
            holePath.addRect(0, 0, w, h, Path.Direction.CW);
            holePath.addRoundRect(frame, dp(14), dp(14), Path.Direction.CCW);
        }

        @Override
        protected void onDraw(Canvas c) {
            float r = dp(14);
            c.drawPath(holePath, mask);                 // تعتيم برّه الإطار
            if (success) c.drawRoundRect(frame, r, r, fill);
            c.drawRoundRect(frame, r, r, border);       // الإطار الأبيض

            // الأركان الخضرا
            float len = dp(34), o = dp(8);
            RectF f = new RectF(frame.left - o, frame.top - o, frame.right + o, frame.bottom + o);
            Path p = new Path();
            p.moveTo(f.left, f.top + len); p.lineTo(f.left, f.top + r); p.quadTo(f.left, f.top, f.left + r, f.top); p.lineTo(f.left + len, f.top);
            p.moveTo(f.right - len, f.top); p.lineTo(f.right - r, f.top); p.quadTo(f.right, f.top, f.right, f.top + r); p.lineTo(f.right, f.top + len);
            p.moveTo(f.right, f.bottom - len); p.lineTo(f.right, f.bottom - r); p.quadTo(f.right, f.bottom, f.right - r, f.bottom); p.lineTo(f.right - len, f.bottom);
            p.moveTo(f.left + len, f.bottom); p.lineTo(f.left + r, f.bottom); p.quadTo(f.left, f.bottom, f.left, f.bottom - r); p.lineTo(f.left, f.bottom - len);
            c.drawPath(p, corner);

            // خط المسح المتحرك
            if (scanning) {
                float t = ((System.currentTimeMillis() - startTime) % 2000) / 2000f;
                float k = t < 0.5f ? t * 2 : (1 - t) * 2;
                float y = frame.top + dp(10) + (frame.height() - dp(20)) * k;
                scan.setShader(new android.graphics.LinearGradient(frame.left, 0, frame.right, 0,
                        new int[]{Color.TRANSPARENT, green, Color.TRANSPARENT}, null,
                        android.graphics.Shader.TileMode.CLAMP));
                scan.setStrokeWidth(dp(2.5f));
                c.drawLine(frame.left + dp(12), y, frame.right - dp(12), y, scan);
                postInvalidateOnAnimation();
            }

            // العنوان
            c.drawText("مسح بطاقة الرقم القومي", getWidth() / 2f, frame.top - dp(36), title);

            // التعليمات
            hintP.setColor(success ? green : hintWarn ? Color.parseColor("#FFC857") : Color.WHITE);
            int tw = (int) (getWidth() - dp(48));
            StaticLayout sl = new StaticLayout(hint, hintP, tw, Layout.Alignment.ALIGN_CENTER, 1.15f, 0, false);
            c.save();
            c.translate(dp(24), frame.bottom + dp(28));
            sl.draw(c);
            c.restore();
        }
    }

    // ================================================================ Flash button

    private class FlashButton extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean on;

        FlashButton(Context c) {
            super(c);
            bg.setColor(Color.WHITE);
            icon.setColor(Color.parseColor("#3DDC84"));
            setClickable(true);
            GradientDrawable ripple = new GradientDrawable();
            ripple.setShape(GradientDrawable.OVAL);
            ripple.setColor(Color.TRANSPARENT);
            setBackground(ripple);
        }

        void setOn(boolean v) { on = v; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            bg.setColor(on ? Color.parseColor("#3DDC84") : Color.WHITE);
            c.drawCircle(cx, cy, Math.min(w, h) / 2f, bg);
            icon.setColor(on ? Color.WHITE : Color.parseColor("#3DDC84"));
            // أيقونة برق ⚡
            float s = Math.min(w, h) / 68f;
            Path p = new Path();
            p.moveTo(cx + 4 * s, cy - 20 * s);
            p.lineTo(cx - 12 * s, cy + 3 * s);
            p.lineTo(cx - 1 * s, cy + 3 * s);
            p.lineTo(cx - 5 * s, cy + 20 * s);
            p.lineTo(cx + 12 * s, cy - 4 * s);
            p.lineTo(cx + 1 * s, cy - 4 * s);
            p.close();
            c.drawPath(p, icon);
        }
    }
}
