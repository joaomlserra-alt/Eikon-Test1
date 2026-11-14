package com.eikon.diag;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.TonemapCurve;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Testes ao vivo (abrem a câmara e capturam) para uma câmara. */
final class CamLive {
    interface Ctl { boolean cancelled(); void note(String s); }
    interface Step { String run() throws Exception; }

    private static final int YUV = ImageFormat.YUV_420_888;

    private final Context ctx;
    private final CameraManager mgr;
    private final Rep rep;
    private final Ctl ctl;
    private final CamInfo.Info info;
    private final String id;
    private HandlerThread ht;
    private Handler h;
    private volatile CameraDevice dev;
    private volatile String openErr = "";
    private long openMs;
    private final List<String> sum = new ArrayList<>();

    CamLive(Context ctx, CameraManager mgr, Rep rep, Ctl ctl, CamInfo.Info info) {
        this.ctx = ctx;
        this.mgr = mgr;
        this.rep = rep;
        this.ctl = ctl;
        this.info = info;
        this.id = info.id;
    }

    // ------------------------------------------------------------------ util
    private static double mb(long b) { return b / 1048576.0; }
    private double freeMb() { return DeviceInfo.availMem(ctx) / 1048576.0; }

    private void step(String name, Step s) {
        if (ctl.cancelled()) return;
        ctl.note("Câmara " + id + ": " + name);
        rep.mark("câmara " + id + " → " + name);
        long t0 = SystemClock.elapsedRealtime();
        String r;
        try {
            r = s.run();
        } catch (Throwable t) {
            r = "FALHOU: " + t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " " + t.getMessage());
        }
        rep.l(String.format(Locale.ROOT, "[cam %s] %s: %s  (%.1f s)", id, name, r, (SystemClock.elapsedRealtime() - t0) / 1000.0));
        sum.add(name + ": " + r);
        rep.save();
        SystemClock.sleep(250);
    }

    private Size[] sizes(int fmt) { return CamInfo.outSizes(info.cc, fmt); }

    private static Size nearest(Size[] a, int w, int hh) {
        Size best = null;
        long target = (long) w * hh;
        for (Size s : a) {
            if (best == null || Math.abs(CamInfo.pix(s) - target) < Math.abs(CamInfo.pix(best) - target)) best = s;
        }
        return best;
    }

    private static Size biggestUnder(Size[] a, long maxPix) {
        Size best = null;
        for (Size s : a) if (CamInfo.pix(s) <= maxPix && (best == null || CamInfo.pix(s) > CamInfo.pix(best))) best = s;
        return best;
    }

    private static void closeQ(CameraCaptureSession s) { try { if (s != null) s.close(); } catch (Throwable ignored) { } }
    private static void closeQ(ImageReader r) { try { if (r != null) r.close(); } catch (Throwable ignored) { } }
    private static void closeQ(Image i) { try { if (i != null) i.close(); } catch (Throwable ignored) { } }

    private boolean open() {
        final CountDownLatch l = new CountDownLatch(1);
        final long t0 = SystemClock.elapsedRealtime();
        final long[] ms = new long[1];
        try {
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { dev = d; ms[0] = SystemClock.elapsedRealtime() - t0; l.countDown(); }
                @Override public void onDisconnected(CameraDevice d) { openErr = "desligada"; d.close(); l.countDown(); }
                @Override public void onError(CameraDevice d, int e) { openErr = "erro " + e; d.close(); l.countDown(); }
            }, h);
            if (!l.await(10, TimeUnit.SECONDS)) { openErr = "timeout 10 s"; return false; }
        } catch (Throwable t) {
            openErr = t.getClass().getSimpleName() + ": " + t.getMessage();
            return false;
        }
        if (dev == null) return false;
        openMs = ms[0];
        return true;
    }

    private CameraCaptureSession session(List<Surface> surfaces) throws Exception {
        final CountDownLatch l = new CountDownLatch(1);
        final CameraCaptureSession[] out = new CameraCaptureSession[1];
        dev.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
            @Override public void onConfigured(CameraCaptureSession s) { out[0] = s; l.countDown(); }
            @Override public void onConfigureFailed(CameraCaptureSession s) { l.countDown(); }
        }, h);
        if (!l.await(20, TimeUnit.SECONDS)) throw new Exception("timeout a configurar a sessão");
        if (out[0] == null) throw new Exception("combinação de streams recusada");
        return out[0];
    }

    private ImageReader drain(Size s, int maxImages) {
        ImageReader r = ImageReader.newInstance(s.getWidth(), s.getHeight(), YUV, maxImages);
        r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader rd) {
                try { Image im = rd.acquireLatestImage(); if (im != null) im.close(); } catch (Throwable ignored) { }
            }
        }, h);
        return r;
    }

    private boolean hasAf(int mode) {
        int[] a = CamInfo.g(info.cc, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (a != null) for (int m : a) if (m == mode) return true;
        return false;
    }

    private void warm(CameraCaptureSession cs, Surface s) throws Exception {
        CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        b.addTarget(s);
        if (hasAf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        cs.setRepeatingRequest(b.build(), null, h);
        SystemClock.sleep(1200);
    }

    // ------------------------------------------------------------------ run
    void run(boolean full, boolean deep) {
        rep.h("Teste ao vivo — câmara " + id + " (" + info.facing + ")");
        ht = new HandlerThread("cam" + id);
        ht.start();
        h = new Handler(ht.getLooper());
        double free0 = freeMb();
        try {
            rep.mark("câmara " + id + " → abrir");
            if (!open()) {
                rep.l("Não abriu: " + openErr);
                sum.add("NÃO ABRE (" + openErr + ")");
            } else {
                rep.l("Abriu em " + openMs + " ms. RAM livre antes: " + String.format(Locale.ROOT, "%.0f MB", free0));
                sum.add("abre em " + openMs + " ms");
                step("preview YUV", new Step() { public String run() throws Exception { return tPreview(); } });
                step("foto YUV máx.", new Step() { public String run() throws Exception { return shoot(YUV, info.maxYuv, false); } });
                step("foto JPEG máx.", new Step() { public String run() throws Exception { return shoot(ImageFormat.JPEG, info.maxJpeg, false); } });
                if (info.raw() && info.maxRaw != null)
                    step("foto RAW + DNG", new Step() { public String run() throws Exception { return shoot(ImageFormat.RAW_SENSOR, info.maxRaw, true); } });
                if (deep) {
                    final boolean f = full;
                    step("burst YUV (frames em memória)", new Step() { public String run() throws Exception { return tBurst(f); } });
                    step("bracketing de exposição (HDR)", new Step() { public String run() throws Exception { return tBracket(); } });
                    step("zoom", new Step() { public String run() throws Exception { return tZoom(); } });
                    step("exposição longa", new Step() { public String run() throws Exception { return tLong(f); } });
                    if (info.phys.length > 0)
                        step("lentes físicas", new Step() { public String run() throws Exception { return tPhysical(); } });
                }
            }
        } finally {
            try { if (dev != null) dev.close(); } catch (Throwable ignored) { }
            dev = null;
            ht.quitSafely();
            SystemClock.sleep(300);
        }
        rep.sum(CamInfo.oneLine(info));
        for (String s : sum) rep.sum("   " + s);
        rep.save();
    }

    // ------------------------------------------------------------------ preview
    private String tPreview() throws Exception {
        Size ps = nearest(sizes(YUV), 1280, 720);
        if (ps == null) throw new Exception("sem tamanhos YUV");
        final List<Long> ts = Collections.synchronizedList(new ArrayList<Long>());
        final ImageReader r = ImageReader.newInstance(ps.getWidth(), ps.getHeight(), YUV, 3);
        r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader rd) {
                try {
                    Image im = rd.acquireLatestImage();
                    if (im != null) { ts.add(im.getTimestamp()); im.close(); }
                } catch (Throwable ignored) { }
            }
        }, h);
        CameraCaptureSession cs = null;
        try {
            cs = session(Collections.singletonList(r.getSurface()));
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(r.getSurface());
            cs.setRepeatingRequest(b.build(), null, h);
            SystemClock.sleep(3000);
            cs.stopRepeating();
        } finally {
            closeQ(cs);
            closeQ(r);
        }
        List<Long> t;
        synchronized (ts) { t = new ArrayList<>(ts); }
        if (t.size() < 3) return CamInfo.sz(ps) + ": só " + t.size() + " frames em 3 s";
        double span = (t.get(t.size() - 1) - t.get(0)) / 1e9;
        double fps = (t.size() - 1) / span;
        double avg = span / (t.size() - 1) * 1000.0;
        double jit = 0;
        for (int k = 1; k < t.size(); k++) jit = Math.max(jit, Math.abs((t.get(k) - t.get(k - 1)) / 1e6 - avg));
        return String.format(Locale.ROOT, "%s: %.1f fps (jitter máx %.0f ms)", CamInfo.sz(ps), fps, jit);
    }

    // ------------------------------------------------------------------ fotos
    private String shoot(final int fmt, final Size sz, final boolean dng) throws Exception {
        if (sz == null) return "sem tamanho para este formato";
        final ImageReader r = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), fmt, 2);
        Size ps = nearest(sizes(YUV), 1280, 720);
        ImageReader pv = ps != null ? drain(ps, 3) : null;
        CameraCaptureSession cs = null;
        final CountDownLatch gotImg = new CountDownLatch(1);
        final CountDownLatch gotRes = new CountDownLatch(1);
        final long[] bytes = new long[1];
        final Image[] keep = new Image[1];
        final TotalCaptureResult[] res = new TotalCaptureResult[1];
        final AtomicInteger fail = new AtomicInteger();
        r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader rd) {
                try {
                    Image im = rd.acquireNextImage();
                    if (im == null) return;
                    long b = 0;
                    for (Image.Plane p : im.getPlanes()) b += p.getBuffer().remaining();
                    bytes[0] = b;
                    if (dng) keep[0] = im; else im.close();
                } catch (Throwable ignored) {
                } finally {
                    gotImg.countDown();
                }
            }
        }, h);
        try {
            boolean withPv = pv != null;
            if (withPv) {
                try { cs = session(Arrays.asList(pv.getSurface(), r.getSurface())); } catch (Exception e) { withPv = false; }
            }
            if (cs == null) cs = session(Collections.singletonList(r.getSurface()));
            if (withPv) warm(cs, pv.getSurface());
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(r.getSurface());
            if (fmt == ImageFormat.JPEG) b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            long t0 = SystemClock.elapsedRealtime();
            cs.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, TotalCaptureResult tr) {
                    res[0] = tr;
                    gotRes.countDown();
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest rq, CaptureFailure f) {
                    fail.incrementAndGet();
                    gotRes.countDown();
                    gotImg.countDown();
                }
            }, h);
            boolean ok = gotImg.await(20, TimeUnit.SECONDS);
            long ms = SystemClock.elapsedRealtime() - t0;
            gotRes.await(5, TimeUnit.SECONDS);
            if (!ok) return "sem imagem em 20 s";
            if (fail.get() > 0) return "captura falhou (CaptureFailure)";
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%s OK, %d ms, %.1f MB, RAM livre %.0f MB",
                    CamInfo.sz(sz), ms, mb(bytes[0]), freeMb()));
            if (dng) {
                if (res[0] == null || keep[0] == null) {
                    sb.append(" | DNG: faltou resultado/imagem");
                } else {
                    File f = new File(ctx.getCacheDir(), "t.dng");
                    long t1 = SystemClock.elapsedRealtime();
                    DngCreator d = new DngCreator(info.cc, res[0]);
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        d.writeImage(fo, keep[0]);
                    } finally {
                        d.close();
                    }
                    sb.append(String.format(Locale.ROOT, " | DNG gravado OK (%.1f MB, %d ms)", mb(f.length()), SystemClock.elapsedRealtime() - t1));
                    f.delete();
                }
            }
            return sb.toString();
        } finally {
            closeQ(keep[0]);
            closeQ(cs);
            closeQ(r);
            closeQ(pv);
        }
    }

    // ------------------------------------------------------------------ burst
    private String tBurst(boolean full) throws Exception {
        Size mx = info.maxYuv;
        if (mx == null) return "sem YUV";
        List<Size> tests = new ArrayList<>();
        tests.add(mx);
        Size mid = biggestUnder(sizes(YUV), 12600000L);
        if (mid != null && CamInfo.pix(mid) < CamInfo.pix(mx)) tests.add(mid);
        int[] ns = full ? new int[]{4, 8, 12, 16} : new int[]{4, 8};
        StringBuilder sb = new StringBuilder();
        for (Size s : tests) {
            int best = 0;
            String lastFail = "";
            double bestFps = 0;
            for (int n : ns) {
                if (ctl.cancelled()) break;
                ctl.note("Câmara " + id + ": burst " + CamInfo.sz(s) + " × " + n);
                rep.mark("câmara " + id + " → burst " + CamInfo.sz(s) + " × " + n);
                String r;
                try {
                    r = burstHold(s, n);
                } catch (Throwable t) {
                    r = "FALHOU " + t.getClass().getSimpleName() + ": " + t.getMessage();
                }
                rep.l("   burst " + CamInfo.sz(s) + " × " + n + ": " + r);
                rep.save();
                if (r.startsWith("OK")) {
                    best = n;
                    try { bestFps = Double.parseDouble(r.substring(r.indexOf("fps=") + 4, r.indexOf(' ', r.indexOf("fps=")))); } catch (Throwable ignored) { }
                } else {
                    lastFail = " (falhou em " + n + ")";
                    break;
                }
                SystemClock.sleep(500);
            }
            sb.append(String.format(Locale.ROOT, "%s → até %d frames em memória%s, %.1f fps; ", CamInfo.sz(s), best, lastFail, bestFps));
        }
        return sb.toString().trim();
    }

    private String burstHold(Size sz, final int n) throws Exception {
        final List<Image> held = new ArrayList<>();
        final AtomicInteger fails = new AtomicInteger();
        final AtomicInteger starved = new AtomicInteger();
        final List<Long> ts = Collections.synchronizedList(new ArrayList<Long>());
        final CountDownLatch done = new CountDownLatch(n);
        final long[] last = new long[1];
        Size ps = nearest(sizes(YUV), 1280, 720);
        ImageReader pv = ps != null ? drain(ps, 3) : null;
        final ImageReader r = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), YUV, n);
        r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader rd) {
                try {
                    Image im = rd.acquireNextImage();
                    if (im != null) {
                        synchronized (held) { held.add(im); }
                        last[0] = SystemClock.elapsedRealtime();
                        done.countDown();
                    }
                } catch (Throwable t) {
                    starved.incrementAndGet();
                }
            }
        }, h);
        CameraCaptureSession cs = null;
        double before = freeMb();
        try {
            boolean withPv = pv != null;
            if (withPv) {
                try { cs = session(Arrays.asList(pv.getSurface(), r.getSurface())); } catch (Exception e) { withPv = false; }
            }
            if (cs == null) cs = session(Collections.singletonList(r.getSurface()));
            if (withPv) { warm(cs, pv.getSurface()); cs.stopRepeating(); }
            List<CaptureRequest> reqs = new ArrayList<>();
            for (int k = 0; k < n; k++) {
                CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(r.getSurface());
                reqs.add(b.build());
            }
            long t0 = SystemClock.elapsedRealtime();
            cs.captureBurst(reqs, new CameraCaptureSession.CaptureCallback() {
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, TotalCaptureResult tr) {
                    Long t = tr.get(CaptureResult.SENSOR_TIMESTAMP);
                    if (t != null) ts.add(t);
                }
                @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest rq, CaptureFailure f) {
                    fails.incrementAndGet();
                }
            }, h);
            done.await(15 + 3L * n, TimeUnit.SECONDS);
            int got;
            synchronized (held) { got = held.size(); }
            double ms = (last[0] > 0 ? last[0] : SystemClock.elapsedRealtime()) - t0;
            double after = freeMb();
            double fps = 0;
            List<Long> t;
            synchronized (ts) { t = new ArrayList<>(ts); }
            if (t.size() > 1) {
                Collections.sort(t);
                fps = (t.size() - 1) / ((t.get(t.size() - 1) - t.get(0)) / 1e9);
            }
            String stat = String.format(Locale.ROOT, "recebidos %d/%d, %.1f s, fps=%.1f falhas=%d, RAM livre %.0f→%.0f MB",
                    got, n, ms / 1000.0, fps, fails.get(), before, after);
            boolean ok = got == n && fails.get() == 0 && starved.get() == 0;
            return (ok ? "OK " : "INCOMPLETO ") + stat + (starved.get() > 0 ? " (buffers esgotados)" : "");
        } finally {
            synchronized (held) { for (Image im : held) closeQ(im); held.clear(); }
            closeQ(cs);
            closeQ(r);
            closeQ(pv);
        }
    }

    // ------------------------------------------------------------------ bracketing
    private volatile TotalCaptureResult lastRes;

    private final CameraCaptureSession.CaptureCallback keepLast = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, TotalCaptureResult tr) { lastRes = tr; }
    };

    private static String expStr(TotalCaptureResult tr) {
        if (tr == null) return "n/d";
        Long e = tr.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = tr.get(CaptureResult.SENSOR_SENSITIVITY);
        return String.format(Locale.ROOT, "%.1f ms/ISO %s", e == null ? -1 : e / 1e6, iso == null ? "?" : iso);
    }

    private String tBracket() throws Exception {
        Size ps = nearest(sizes(YUV), 1280, 720);
        if (ps == null) throw new Exception("sem YUV");
        final ImageReader r = drain(ps, 4);
        CameraCaptureSession cs = null;
        try {
            cs = session(Collections.singletonList(r.getSurface()));
            if (info.manual()) {
                CaptureRequest.Builder p = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                p.addTarget(r.getSurface());
                lastRes = null;
                cs.setRepeatingRequest(p.build(), keepLast, h);
                SystemClock.sleep(1500);
                cs.stopRepeating();
                TotalCaptureResult base = lastRes;
                if (base == null) return "sem resultado de AE";
                Long e0 = base.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                Integer i0 = base.get(CaptureResult.SENSOR_SENSITIVITY);
                if (e0 == null || i0 == null) return "AE não reporta exposição/ISO";
                Range<Long> er = CamInfo.g(info.cc, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                double[] f = {0.25, 1.0, 4.0};
                final List<String> got = Collections.synchronizedList(new ArrayList<String>());
                final List<Long> exps = Collections.synchronizedList(new ArrayList<Long>());
                final CountDownLatch l = new CountDownLatch(3);
                List<CaptureRequest> reqs = new ArrayList<>();
                StringBuilder asked = new StringBuilder();
                for (double k : f) {
                    long e = (long) (e0 * k);
                    if (er != null) e = Math.max(er.getLower(), Math.min(er.getUpper(), e));
                    CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                    b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e);
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, i0);
                    b.addTarget(r.getSurface());
                    reqs.add(b.build());
                    asked.append(String.format(Locale.ROOT, "%.1f ", e / 1e6));
                }
                cs.captureBurst(reqs, new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, TotalCaptureResult tr) {
                        got.add(expStr(tr));
                        Long e = tr.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                        exps.add(e == null ? -1L : e);
                        l.countDown();
                    }
                    @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest rq, CaptureFailure fl) { l.countDown(); }
                }, h);
                l.await(10, TimeUnit.SECONDS);
                boolean distinct = exps.size() == 3 && exps.get(0) < exps.get(1) && exps.get(1) < exps.get(2);
                return String.format(Locale.ROOT, "manual %s: pedido [%sms] → obtido %s", distinct ? "OK (3 exposições distintas)" : "NÃO CONFIRMADO", asked, got);
            } else {
                Range<Integer> cr = CamInfo.g(info.cc, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                if (cr == null || (cr.getLower() == 0 && cr.getUpper() == 0)) return "sem MANUAL_SENSOR nem compensação AE";
                int[] evs = {cr.getLower(), 0, cr.getUpper()};
                StringBuilder sb = new StringBuilder("compensação AE: ");
                for (int ev : evs) {
                    CaptureRequest.Builder p = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                    p.addTarget(r.getSurface());
                    p.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
                    lastRes = null;
                    cs.setRepeatingRequest(p.build(), keepLast, h);
                    SystemClock.sleep(1400);
                    sb.append(ev).append("→").append(expStr(lastRes)).append("; ");
                }
                cs.stopRepeating();
                return sb.toString();
            }
        } finally {
            closeQ(cs);
            closeQ(r);
        }
    }

    // ------------------------------------------------------------------ zoom
    private String tZoom() throws Exception {
        Size ps = nearest(sizes(YUV), 1280, 720);
        if (ps == null) throw new Exception("sem YUV");
        final ImageReader r = drain(ps, 3);
        CameraCaptureSession cs = null;
        try {
            cs = session(Collections.singletonList(r.getSurface()));
            Rect active = CamInfo.g(info.cc, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(r.getSurface());
            String how;
            Range<Float> zr = Build.VERSION.SDK_INT >= 30 ? CamInfo.g(info.cc, CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
            if (zr != null && zr.getUpper() >= 2f) {
                b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 2f);
                how = "CONTROL_ZOOM_RATIO " + zr;
            } else if (active != null) {
                int cw = active.width() / 2, ch = active.height() / 2;
                int left = active.left + (active.width() - cw) / 2, top = active.top + (active.height() - ch) / 2;
                b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(left, top, left + cw, top + ch));
                how = "SCALER_CROP_REGION";
            } else {
                return "sem informação para zoom";
            }
            lastRes = null;
            cs.setRepeatingRequest(b.build(), keepLast, h);
            SystemClock.sleep(1200);
            cs.stopRepeating();
            TotalCaptureResult tr = lastRes;
            if (tr == null) return "sem resultado";
            Rect crop = tr.get(CaptureResult.SCALER_CROP_REGION);
            if (crop == null || active == null) return how + ": resultado sem crop";
            double ratio = (double) active.width() / crop.width();
            return String.format(Locale.ROOT, "pedido 2x via %s → recorte efetivo %.2fx %s", how, ratio, Math.abs(ratio - 2.0) < 0.15 ? "OK" : "NÃO APLICADO");
        } finally {
            closeQ(cs);
            closeQ(r);
        }
    }

    // ------------------------------------------------------------------ exposição longa
    private String tLong(boolean full) throws Exception {
        if (!info.manual()) return "sem MANUAL_SENSOR";
        Range<Long> er = CamInfo.g(info.cc, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> ir = CamInfo.g(info.cc, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Long maxFd = CamInfo.g(info.cc, CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        if (er == null || ir == null) return "gamas de exposição/ISO indisponíveis";
        Size ps = nearest(sizes(YUV), 640, 480);
        if (ps == null) throw new Exception("sem YUV");
        final AtomicInteger imgs = new AtomicInteger();
        final ImageReader r = ImageReader.newInstance(ps.getWidth(), ps.getHeight(), YUV, 2);
        r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader rd) {
                try { Image im = rd.acquireLatestImage(); if (im != null) { im.close(); imgs.incrementAndGet(); } } catch (Throwable ignored) { }
            }
        }, h);
        CameraCaptureSession cs = null;
        double[] secs = full ? new double[]{0.5, 1, 2, 4, 8, 15, 30} : new double[]{0.5, 1, 2};
        StringBuilder sb = new StringBuilder("gama do sensor ");
        sb.append(String.format(Locale.ROOT, "%.4f–%.1f s; ", er.getLower() / 1e9, er.getUpper() / 1e9));
        double maxOk = 0;
        try {
            cs = session(Collections.singletonList(r.getSurface()));
            int iso = Math.max(ir.getLower(), Math.min(ir.getUpper(), 100));
            for (double s : secs) {
                if (ctl.cancelled()) break;
                long want = (long) (s * 1e9);
                if (want > er.getUpper()) { sb.append("(").append(s).append(" s acima do máx.) "); break; }
                ctl.note("Câmara " + id + ": exposição longa " + s + " s");
                rep.mark("câmara " + id + " → exposição longa " + s + " s");
                final TotalCaptureResult[] got = new TotalCaptureResult[1];
                final CountDownLatch l = new CountDownLatch(1);
                CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF);
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, want);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                long fd = want + 33000000L;
                if (maxFd != null) fd = Math.min(fd, maxFd);
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, fd);
                b.addTarget(r.getSurface());
                int before = imgs.get();
                long t0 = SystemClock.elapsedRealtime();
                cs.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession se, CaptureRequest rq, TotalCaptureResult tr) { got[0] = tr; l.countDown(); }
                    @Override public void onCaptureFailed(CameraCaptureSession se, CaptureRequest rq, CaptureFailure f) { l.countDown(); }
                }, h);
                boolean ok = l.await((long) (s * 1000) + 15000, TimeUnit.MILLISECONDS);
                long guard = SystemClock.elapsedRealtime() + 3000;
                while (imgs.get() == before && SystemClock.elapsedRealtime() < guard) SystemClock.sleep(50);
                double took = (SystemClock.elapsedRealtime() - t0) / 1000.0;
                Long actual = got[0] == null ? null : got[0].get(CaptureResult.SENSOR_EXPOSURE_TIME);
                if (ok && got[0] != null && actual != null && actual >= want * 0.9 && imgs.get() > before) {
                    maxOk = s;
                    sb.append(String.format(Locale.ROOT, "%s s OK (real %.2f s, %.1f s total); ", trim(s), actual / 1e9, took));
                } else {
                    sb.append(String.format(Locale.ROOT, "%s s FALHOU (real %s, %.1f s)", trim(s),
                            actual == null ? "n/d" : String.format(Locale.ROOT, "%.2f s", actual / 1e9), took));
                    break;
                }
            }
        } finally {
            closeQ(cs);
            closeQ(r);
        }
        sb.append(String.format(Locale.ROOT, " → máx. confirmado: %s s", trim(maxOk)));
        return sb.toString();
    }

    private static String trim(double d) { return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d); }

    // ------------------------------------------------------------------ lentes físicas
    private String tPhysical() throws Exception {
        if (Build.VERSION.SDK_INT < 28 || info.phys.length == 0) return "sem lentes físicas expostas";
        StringBuilder sb = new StringBuilder();
        for (String pid : info.phys) {
            if (ctl.cancelled()) break;
            String fl = "";
            Size[] ps = sizes(YUV);
            try {
                CameraCharacteristics pc = mgr.getCameraCharacteristics(pid);
                float[] f = pc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (f != null && f.length > 0) fl = " f=" + f[0] + "mm";
                Size[] s2 = CamInfo.outSizes(pc, YUV);
                if (s2.length > 0) ps = s2;
            } catch (Throwable ignored) { }
            String r;
            try {
                r = physShot(pid, ps);
            } catch (Throwable t) {
                r = "FALHOU " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            sb.append("física ").append(pid).append(fl).append(": ").append(r).append("; ");
            SystemClock.sleep(300);
        }
        return sb.toString().trim();
    }

    private String physShot(String pid, Size[] choices) throws Exception {
        Size sz = nearest(choices, 640, 480);
        if (sz == null) return "sem tamanhos";
        final CountDownLatch gotImg = new CountDownLatch(1);
        final ImageReader r = ImageReader.newInstance(sz.getWidth(), sz.getHeight(), YUV, 2);
        r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override public void onImageAvailable(ImageReader rd) {
                try { Image im = rd.acquireLatestImage(); if (im != null) im.close(); } catch (Throwable ignored) { }
                gotImg.countDown();
            }
        }, h);
        CameraCaptureSession cs = null;
        try {
            OutputConfiguration oc = new OutputConfiguration(r.getSurface());
            oc.setPhysicalCameraId(pid);
            final CountDownLatch l = new CountDownLatch(1);
            final CameraCaptureSession[] out = new CameraCaptureSession[1];
            Executor ex = new Executor() { @Override public void execute(Runnable rn) { h.post(rn); } };
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    Collections.singletonList(oc), ex, new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { out[0] = s; l.countDown(); }
                @Override public void onConfigureFailed(CameraCaptureSession s) { l.countDown(); }
            });
            dev.createCaptureSession(sc);
            if (!l.await(15, TimeUnit.SECONDS) || out[0] == null) return "sessão recusada (a lente não é acessível via lógica)";
            cs = out[0];
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(r.getSurface());
            cs.setRepeatingRequest(b.build(), null, h);
            boolean ok = gotImg.await(8, TimeUnit.SECONDS);
            cs.stopRepeating();
            return ok ? "OK, imagem recebida " + CamInfo.sz(sz) : "sem imagem em 8 s";
        } finally {
            closeQ(cs);
            closeQ(r);
        }
    }

    // =================================================================== v1.1
    static final int RW = 256, RH = 256;

    interface Setter { void apply(CaptureRequest.Builder b); }

    static byte[] lumaRoi(Image im, int rw, int rh) {
        Image.Plane p = im.getPlanes()[0];
        ByteBuffer b = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int x0 = (im.getWidth() - rw) / 2, y0 = (im.getHeight() - rh) / 2;
        byte[] out = new byte[rw * rh];
        for (int y = 0; y < rh; y++) {
            int base = (y0 + y) * rs + x0 * ps;
            for (int x = 0; x < rw; x++) out[y * rw + x] = b.get(base + x * ps);
        }
        return out;
    }

    static byte[] lumaSmall(Image im, int ow, int oh) {
        Image.Plane p = im.getPlanes()[0];
        ByteBuffer b = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int w = im.getWidth(), hh = im.getHeight();
        int bw = Math.max(1, w / ow), bh = Math.max(1, hh / oh);
        byte[] out = new byte[ow * oh];
        for (int y = 0; y < oh; y++) {
            for (int x = 0; x < ow; x++) {
                int sum = 0, c = 0;
                for (int yy = 0; yy < bh; yy += 2)
                    for (int xx = 0; xx < bw; xx += 2) {
                        sum += b.get((y * bh + yy) * rs + (x * bw + xx) * ps) & 0xFF;
                        c++;
                    }
                out[y * ow + x] = (byte) (sum / Math.max(1, c));
            }
        }
        return out;
    }

    static final class Frames {
        final int skip, n;
        final List<byte[]> roi = Collections.synchronizedList(new ArrayList<byte[]>());
        final List<Long> ts = Collections.synchronizedList(new ArrayList<Long>());
        final AtomicInteger seen = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        volatile byte[] small;
        volatile TotalCaptureResult last;
        final CameraCaptureSession.CaptureCallback cb = new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest rq, TotalCaptureResult tr) { last = tr; }
        };

        Frames(int skip, int n) { this.skip = skip; this.n = n; }

        void onImage(Image im) {
            int k = seen.incrementAndGet();
            if (k <= skip || roi.size() >= n) return;
            roi.add(lumaRoi(im, RW, RH));
            ts.add(im.getTimestamp());
            small = lumaSmall(im, 160, 120);
            if (roi.size() >= n) done.countDown();
        }

        double fps() {
            List<Long> t;
            synchronized (ts) { t = new ArrayList<>(ts); }
            if (t.size() < 2) return 0;
            return (t.size() - 1) / ((t.get(t.size() - 1) - t.get(0)) / 1e9);
        }
    }

    /** {média de luma, ruído temporal médio (σ por pixel), nitidez (gradiente médio da média dos frames)}. */
    static double[] stats(List<byte[]> fr) {
        int n = fr.size();
        if (n < 2) return new double[]{n == 1 ? mean1(fr.get(0)) : 0, 0, 0};
        int len = RW * RH;
        double[] avg = new double[len];
        double mean = 0, sig = 0;
        for (int i = 0; i < len; i++) {
            double s1 = 0, s2 = 0;
            for (int f = 0; f < n; f++) { int v = fr.get(f)[i] & 0xFF; s1 += v; s2 += (double) v * v; }
            double m = s1 / n;
            double var = Math.max(0, s2 / n - m * m) * n / (n - 1.0);
            sig += Math.sqrt(var);
            avg[i] = m;
            mean += m;
        }
        double g = 0;
        int c = 0;
        for (int y = 0; y < RH - 1; y++)
            for (int x = 0; x < RW - 1; x++) {
                int i = y * RW + x;
                g += Math.abs(avg[i + 1] - avg[i]) + Math.abs(avg[i + RW] - avg[i]);
                c++;
            }
        return new double[]{mean / len, sig / len, g / c};
    }

    private static double mean1(byte[] a) {
        double s = 0;
        for (byte b : a) s += b & 0xFF;
        return s / a.length;
    }

    private static double mad(byte[] a, byte[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs((a[i] & 0xFF) - (b[i] & 0xFF));
        return s / a.length;
    }

    private final class Rig {
        final ImageReader r;
        final CameraCaptureSession cs;
        volatile Frames cur;

        Rig() throws Exception {
            Size ps = nearest(sizes(YUV), 1280, 720);
            if (ps == null) throw new Exception("sem YUV");
            r = ImageReader.newInstance(ps.getWidth(), ps.getHeight(), YUV, 4);
            r.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override public void onImageAvailable(ImageReader rd) {
                    Image im = null;
                    try {
                        im = rd.acquireNextImage();
                        if (im == null) return;
                        Frames f = cur;
                        if (f != null) f.onImage(im);
                    } catch (Throwable ignored) {
                    } finally {
                        if (im != null) im.close();
                    }
                }
            }, h);
            cs = session(Collections.singletonList(r.getSurface()));
        }

        Frames run(Setter s, int skip, int n, long timeoutMs) throws Exception {
            Frames f = new Frames(skip, n);
            cur = f;
            CaptureRequest.Builder b = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(r.getSurface());
            s.apply(b);
            cs.setRepeatingRequest(b.build(), f.cb, h);
            f.done.await(timeoutMs, TimeUnit.MILLISECONDS);
            cur = null;
            return f;
        }

        void close() {
            try { cs.stopRepeating(); } catch (Throwable ignored) { }
            closeQ(cs);
            closeQ(r);
        }
    }

    private Rig rig;

    private final Setter autoSet = new Setter() {
        @Override public void apply(CaptureRequest.Builder b) {
            if (hasAf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE))
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
    };

    private Setter locked(final int nr, final int edge) {
        return new Setter() {
            @Override public void apply(CaptureRequest.Builder b) {
                autoSet.apply(b);
                b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                if (nr >= 0) b.set(CaptureRequest.NOISE_REDUCTION_MODE, nr);
                if (edge >= 0) b.set(CaptureRequest.EDGE_MODE, edge);
            }
        };
    }

    private static Setter manual(final long expNs, final int iso) {
        return new Setter() {
            @Override public void apply(CaptureRequest.Builder b) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, Math.min(142857142L, Math.max(33333333L, expNs)));
            }
        };
    }

    private static String res(TotalCaptureResult tr) {
        if (tr == null) return "sem resultado";
        Long fd = tr.get(CaptureResult.SENSOR_FRAME_DURATION);
        return String.format(Locale.ROOT, "%s, frame %.0f ms", expStr(tr), fd == null ? -1 : fd / 1e6);
    }

    private String v11Ae() throws Exception {
        Frames f = rig.run(autoSet, 20, 4, 9000);
        double[] st = stats(f.roi);
        String warn = st[0] < 40 ? " ⚠ CENA MUITO ESCURA (aponte para algo iluminado)" : st[0] > 225 ? " ⚠ CENA MUITO CLARA (sobreexposta)" : "";
        return String.format(Locale.ROOT, "luma média %.0f/255, nitidez %.1f, ruído temporal %.2f | AE: %s | %.1f fps%s",
                st[0], st[2], st[1], res(f.last), f.fps(), warn);
    }

    private String v11Manual() throws Exception {
        long[] ms = {2, 4, 8, 16, 32, 64, 100};
        StringBuilder sb = new StringBuilder("ISO 100 → ");
        List<Double> lum = new ArrayList<>();
        int echo = 0;
        for (long m : ms) {
            if (ctl.cancelled()) break;
            long e = m * 1000000L;
            Frames f = rig.run(manual(e, 100), 8, 3, 7000);
            double[] st = stats(f.roi);
            Long ae = f.last == null ? null : f.last.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            boolean ok = ae != null && Math.abs(ae - e) < e * 0.15;
            if (ok) echo++;
            lum.add(st[0]);
            sb.append(String.format(Locale.ROOT, "%dms: luma %.0f %s %.1ffps; ", m, st[0],
                    ok ? "eco✓" : "eco=" + (ae == null ? "?" : String.format(Locale.ROOT, "%.1fms", ae / 1e6)), f.fps()));
        }
        sb.append("| ISO a 16 ms → ");
        int[] isos = {100, 200, 400, 800, 1600};
        List<Double> li = new ArrayList<>();
        int echoIso = 0;
        for (int iso : isos) {
            if (ctl.cancelled()) break;
            Frames f = rig.run(manual(16000000L, iso), 8, 3, 7000);
            double[] st = stats(f.roi);
            Integer ai = f.last == null ? null : f.last.get(CaptureResult.SENSOR_SENSITIVITY);
            if (ai != null && Math.abs(ai - iso) <= iso * 0.15) echoIso++;
            li.add(st[0]);
            sb.append(String.format(Locale.ROOT, "ISO%d: luma %.0f; ", iso, st[0]));
        }
        int incE = 0, incI = 0;
        for (int k = 1; k < lum.size(); k++) if (lum.get(k) > lum.get(k - 1) + 3 && lum.get(k - 1) < 245) incE++;
        for (int k = 1; k < li.size(); k++) if (li.get(k) > li.get(k - 1) + 3 && li.get(k - 1) < 245) incI++;
        String verdict;
        if (echo >= 5 && incE >= 3) verdict = "VEREDITO: exposição manual FUNCIONA (eco e brilho acompanham)";
        else if (echo >= 5) verdict = "VEREDITO: eco aceite mas brilho não acompanha (cena saturada ou valores ignorados)";
        else verdict = "VEREDITO: exposição manual IGNORADA pelo HAL";
        verdict += incI >= 2 ? "; ISO manual FUNCIONA" : (echoIso >= 4 ? "; ISO: eco sem efeito no brilho" : "; ISO ignorado");
        return verdict + " | " + sb;
    }

    private String v11Nr() throws Exception {
        rig.run(autoSet, 24, 2, 9000);
        int[][] combos = {{0, 0}, {0, 2}, {1, 1}, {2, 2}};
        String[] nm = {"NR OFF/EDGE OFF", "NR OFF/EDGE HQ", "NR FAST/EDGE FAST", "NR HQ/EDGE HQ"};
        StringBuilder sb = new StringBuilder();
        double[] sig = new double[combos.length], sharp = new double[combos.length];
        for (int k = 0; k < combos.length; k++) {
            if (ctl.cancelled()) break;
            Frames f = rig.run(locked(combos[k][0], combos[k][1]), 10, 10, 12000);
            double[] st = stats(f.roi);
            sig[k] = st[1];
            sharp[k] = st[2];
            Integer enr = f.last == null ? null : f.last.get(CaptureResult.NOISE_REDUCTION_MODE);
            sb.append(String.format(Locale.ROOT, "%s: ruído σ=%.2f, nitidez %.1f, luma %.0f, %s, eco NR=%s; ",
                    nm[k], st[1], st[2], st[0], res(f.last), enr));
        }
        String v;
        if (sig[0] > 0 && sig[3] > 0 && sig[0] > sig[3] * 1.15) v = "VEREDITO: NR tem efeito (OFF dá mais ruído → bom para empilhar)";
        else if (sig[0] > 0 && sig[3] > 0) v = "VEREDITO: NR/edge parecem IGNORADOS (ruído quase igual)";
        else v = "VEREDITO: inconclusivo";
        if (sharp[0] > 0 && sharp[3] > 0 && sharp[0] > sharp[3] * 1.1) v += "; EDGE/NR OFF dá mais nitidez";
        return v + " | " + sb;
    }

    private static float[] curve(double pow) {
        double[] xs = {0, 0.05, 0.1, 0.2, 0.35, 0.5, 0.65, 0.8, 0.9, 1.0};
        float[] c = new float[xs.length * 2];
        for (int i = 0; i < xs.length; i++) { c[2 * i] = (float) xs[i]; c[2 * i + 1] = (float) Math.pow(xs[i], pow); }
        return c;
    }

    private String v11Tone() throws Exception {
        rig.run(autoSet, 24, 2, 9000);
        double[] pows = {1.0, 0.5, 2.0};
        String[] nm = {"curva identidade", "curva que clareia (x^0.5)", "curva que escurece (x^2)"};
        StringBuilder sb = new StringBuilder();
        Frames base = rig.run(locked(-1, -1), 10, 4, 9000);
        double lb = stats(base.roi)[0];
        sb.append(String.format(Locale.ROOT, "tone map do telemóvel: luma %.0f; ", lb));
        double[] l = new double[3];
        for (int k = 0; k < 3; k++) {
            if (ctl.cancelled()) break;
            final float[] c = curve(pows[k]);
            final TonemapCurve tc = new TonemapCurve(c, c, c);
            Frames f = rig.run(new Setter() {
                @Override public void apply(CaptureRequest.Builder b) {
                    b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                    b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                    b.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
                    b.set(CaptureRequest.TONEMAP_CURVE, tc);
                }
            }, 10, 4, 9000);
            l[k] = stats(f.roi)[0];
            Integer em = f.last == null ? null : f.last.get(CaptureResult.TONEMAP_MODE);
            sb.append(String.format(Locale.ROOT, "%s: luma %.0f (eco modo=%s); ", nm[k], l[k], em));
        }
        String v = (l[1] > l[0] + 12 && l[2] < l[0] - 12)
                ? "VEREDITO: curva de tons PRÓPRIA é respeitada pelo ISP"
                : "VEREDITO: curva própria ignorada ou sem efeito claro";
        return v + " | " + sb;
    }

    private String v11Zoom() throws Exception {
        Rect active = CamInfo.g(info.cc, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (active == null) return "sem área ativa";
        rig.run(autoSet, 24, 2, 9000);
        Frames f1 = rig.run(locked(-1, -1), 8, 3, 8000);
        final int cw = active.width() / 2, ch = active.height() / 2;
        final Rect crop = new Rect(active.left + (active.width() - cw) / 2, active.top + (active.height() - ch) / 2,
                active.left + (active.width() - cw) / 2 + cw, active.top + (active.height() - ch) / 2 + ch);
        Frames f2 = rig.run(new Setter() {
            @Override public void apply(CaptureRequest.Builder b) {
                b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                b.set(CaptureRequest.SCALER_CROP_REGION, crop);
            }
        }, 8, 3, 8000);
        String out = zoomVerdict("SCALER_CROP_REGION 2x", f1, f2, active);
        if (Build.VERSION.SDK_INT >= 30) {
            Frames f3 = rig.run(new Setter() {
                @Override public void apply(CaptureRequest.Builder b) {
                    b.set(CaptureRequest.CONTROL_AE_LOCK, true);
                    b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
                    b.set(CaptureRequest.CONTROL_ZOOM_RATIO, 2f);
                }
            }, 8, 3, 8000);
            out += " || " + zoomVerdict("CONTROL_ZOOM_RATIO 2x", f1, f3, active);
        }
        return out;
    }

    private String zoomVerdict(String name, Frames f1, Frames f2, Rect active) {
        byte[] a = f1.small, b = f2.small;
        if (a == null || b == null) return name + ": sem frames";
        byte[] cu = new byte[160 * 120];
        for (int y = 0; y < 120; y++)
            for (int x = 0; x < 160; x++) cu[y * 160 + x] = a[(30 + y / 2) * 160 + (40 + x / 2)];
        double madCenter = mad(cu, b), madFull = mad(a, b);
        Rect rc = f2.last == null ? null : f2.last.get(CaptureResult.SCALER_CROP_REGION);
        double r = rc == null ? 0 : (double) active.width() / rc.width();
        boolean real = madCenter < madFull * 0.8;
        return String.format(Locale.ROOT, "%s: recorte no resultado %.2fx, diferença de imagem vs centro %.1f / vs campo todo %.1f → %s",
                name, r, madCenter, madFull, real ? "ZOOM REAL" : "zoom NÃO aplicado (ou cena sem textura)");
    }

    private String v11Ois() throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int m = 0; m <= 1; m++) {
            final int mode = m;
            Frames f = rig.run(new Setter() {
                @Override public void apply(CaptureRequest.Builder b) { b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, mode); }
            }, 8, 3, 7000);
            Integer e = f.last == null ? null : f.last.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
            sb.append("pedido OIS ").append(m == 0 ? "OFF" : "ON").append(" → eco ").append(e).append("; ");
        }
        sb.append("(o eco só diz o que o telemóvel respondeu, não prova que há estabilização física)");
        return sb.toString();
    }

    private String vdAe() throws Exception {
        android.util.Range<Integer>[] rs = CamInfo.g(info.cc, CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        Range<Integer> comp = CamInfo.g(info.cc, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (rs == null) return "sem gamas de fps";
        final int c = comp == null ? 0 : comp.getUpper();
        StringBuilder sb = new StringBuilder("(câmara tapada, compensação AE +" + c + ") ");
        double best = 0;
        for (final Range<Integer> r : rs) {
            if (ctl.cancelled()) break;
            Frames f = rig.run(new Setter() {
                @Override public void apply(CaptureRequest.Builder b) {
                    b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, r);
                    b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c);
                }
            }, 16, 4, 14000);
            Long e = f.last == null ? null : f.last.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            if (e != null) best = Math.max(best, e / 1e6);
            sb.append(String.format(Locale.ROOT, "fps %s: %s, medido %.1f fps; ", r, res(f.last), f.fps()));
        }
        sb.append(String.format(Locale.ROOT, "→ exposição máx. conseguida pelo AE: %.0f ms", best));
        return sb.toString();
    }

    private String vdManual() throws Exception {
        Frames f = rig.run(manual(100000000L, 1600), 8, 4, 12000);
        double[] st = stats(f.roi);
        Long e = f.last == null ? null : f.last.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long fd = f.last == null ? null : f.last.get(CaptureResult.SENSOR_FRAME_DURATION);
        return String.format(Locale.ROOT, "pedido 100 ms/ISO 1600/frame 143 ms → resultado %s, medido %.1f fps, luma %.0f (câmara tapada: valor baixo é normal)",
                res(f.last), f.fps(), st[0]) + (e != null && e >= 90000000L ? " → exposição de 100 ms ACEITE" : " → exposição de 100 ms NÃO aceite")
                + (fd != null && fd >= 130000000L ? "; frame longo aceite" : "");
    }

    void runV11(final boolean dark) {
        rep.h("Diag v1.1 (" + (dark ? "escuro" : "com luz") + ") — câmara " + id);
        ht = new HandlerThread("cam" + id);
        ht.start();
        h = new Handler(ht.getLooper());
        try {
            rep.mark("câmara " + id + " → abrir");
            if (!open()) {
                rep.l("Não abriu: " + openErr);
                sum.add("NÃO ABRE (" + openErr + ")");
            } else {
                try { rig = new Rig(); } catch (Throwable t) {
                    rep.l("Não foi possível criar a sessão: " + t);
                    sum.add("sessão falhou: " + t);
                }
                if (rig != null) {
                    if (!dark) {
                        step("luz da cena", new Step() { public String run() throws Exception { return v11Ae(); } });
                        step("exposição/ISO manual (AE desligado)", new Step() { public String run() throws Exception { return v11Manual(); } });
                        step("redução de ruído e nitidez OFF vs HQ", new Step() { public String run() throws Exception { return v11Nr(); } });
                        step("curva de tons própria", new Step() { public String run() throws Exception { return v11Tone(); } });
                        step("zoom por recorte e por ratio", new Step() { public String run() throws Exception { return v11Zoom(); } });
                        step("estabilização ótica (eco)", new Step() { public String run() throws Exception { return v11Ois(); } });
                    } else {
                        step("limite do AE por gama de fps", new Step() { public String run() throws Exception { return vdAe(); } });
                        step("exposição manual de 100 ms", new Step() { public String run() throws Exception { return vdManual(); } });
                    }
                }
            }
        } finally {
            if (rig != null) rig.close();
            try { if (dev != null) dev.close(); } catch (Throwable ignored) { }
            dev = null;
            ht.quitSafely();
            SystemClock.sleep(300);
        }
        rep.sum("Câmara " + id + " [" + info.facing + "]");
        for (String x : sum) rep.sum("   " + x);
        rep.save();
    }
}
