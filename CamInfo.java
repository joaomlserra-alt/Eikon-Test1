package com.eikon.diag;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.util.Size;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class CamInfo {
    static final String[] HW = {"LIMITED", "FULL", "LEGACY", "LEVEL_3", "EXTERNAL"};

    static final class Info {
        String id;
        boolean listed;
        String facing = "?";
        String level = "?";
        Set<Integer> caps = new HashSet<>();
        CameraCharacteristics cc;
        Size maxYuv, maxJpeg, maxRaw;
        String[] phys = new String[0];
        boolean back() { return "TRAS".equals(facing); }
        boolean has(int cap) { return caps.contains(cap); }
        boolean raw() { return has(3); }
        boolean manual() { return has(1); }
        boolean logical() { return has(11); }
    }

    static String cap(int c) {
        switch (c) {
            case 0: return "BACKWARD_COMPATIBLE";
            case 1: return "MANUAL_SENSOR";
            case 2: return "MANUAL_POST_PROCESSING";
            case 3: return "RAW";
            case 4: return "PRIVATE_REPROCESSING";
            case 5: return "READ_SENSOR_SETTINGS";
            case 6: return "BURST_CAPTURE";
            case 7: return "YUV_REPROCESSING";
            case 8: return "DEPTH_OUTPUT";
            case 9: return "CONSTRAINED_HIGH_SPEED_VIDEO";
            case 10: return "MOTION_TRACKING";
            case 11: return "LOGICAL_MULTI_CAMERA";
            case 12: return "MONOCHROME";
            case 13: return "SECURE_IMAGE_DATA";
            case 14: return "SYSTEM_CAMERA";
            case 15: return "OFFLINE_PROCESSING";
            case 16: return "ULTRA_HIGH_RESOLUTION_SENSOR";
            default: return "cap#" + c;
        }
    }

    static <T> T g(CameraCharacteristics cc, CameraCharacteristics.Key<T> k) {
        try { return cc.get(k); } catch (Throwable t) { return null; }
    }

    static String fmt(Object o) {
        if (o == null) return "n/d";
        if (o instanceof int[]) return Arrays.toString((int[]) o);
        if (o instanceof long[]) return Arrays.toString((long[]) o);
        if (o instanceof float[]) return Arrays.toString((float[]) o);
        if (o instanceof boolean[]) return Arrays.toString((boolean[]) o);
        if (o instanceof byte[]) return Arrays.toString((byte[]) o);
        if (o instanceof Object[]) return Arrays.deepToString((Object[]) o);
        return String.valueOf(o);
    }

    static long pix(Size s) { return s == null ? 0 : (long) s.getWidth() * s.getHeight(); }

    static Size biggest(Size[] a) {
        Size b = null;
        if (a != null) for (Size s : a) if (b == null || pix(s) > pix(b)) b = s;
        return b;
    }

    static Size[] outSizes(CameraCharacteristics cc, int fmt) {
        StreamConfigurationMap m = g(cc, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (m == null) return new Size[0];
        try {
            Size[] s = m.getOutputSizes(fmt);
            return s == null ? new Size[0] : s;
        } catch (Throwable t) {
            return new Size[0];
        }
    }

    static Info info(CameraManager mgr, String id, boolean listed) throws Exception {
        Info i = new Info();
        i.id = id;
        i.listed = listed;
        i.cc = mgr.getCameraCharacteristics(id);
        Integer f = g(i.cc, CameraCharacteristics.LENS_FACING);
        i.facing = f == null ? "?" : (f == 0 ? "FRENTE" : f == 1 ? "TRAS" : "EXTERNA");
        Integer lv = g(i.cc, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        i.level = lv == null ? "?" : (lv >= 0 && lv < HW.length ? HW[lv] : String.valueOf(lv));
        int[] caps = g(i.cc, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) for (int c : caps) i.caps.add(c);
        i.maxYuv = biggest(outSizes(i.cc, ImageFormat.YUV_420_888));
        i.maxJpeg = biggest(outSizes(i.cc, ImageFormat.JPEG));
        i.maxRaw = biggest(outSizes(i.cc, ImageFormat.RAW_SENSOR));
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                Set<String> p = i.cc.getPhysicalCameraIds();
                if (p != null) i.phys = p.toArray(new String[0]);
            } catch (Throwable ignored) { }
        }
        return i;
    }

    static String sz(Size s) { return s == null ? "n/d" : s.getWidth() + "x" + s.getHeight(); }

    static String sizeLine(CameraCharacteristics cc, int fmt) {
        Size[] a = outSizes(cc, fmt);
        if (a.length == 0) return "nenhum";
        List<Size> l = new ArrayList<>(Arrays.asList(a));
        Collections.sort(l, new Comparator<Size>() {
            @Override public int compare(Size x, Size y) { return Long.compare(pix(y), pix(x)); }
        });
        StringBuilder sb = new StringBuilder(a.length + " tamanhos; maiores: ");
        for (int k = 0; k < Math.min(5, l.size()); k++) sb.append(sz(l.get(k))).append(k < 4 ? ", " : "");
        StreamConfigurationMap m = g(cc, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        try {
            Size mx = l.get(0);
            long minDur = m.getOutputMinFrameDuration(fmt, mx);
            long stall = m.getOutputStallDuration(fmt, mx);
            sb.append(String.format(Locale.ROOT, " | no máx.: min.frame %.1f ms (%.1f fps), stall %.1f ms",
                    minDur / 1e6, minDur > 0 ? 1e9 / minDur : 0.0, stall / 1e6));
        } catch (Throwable ignored) { }
        return sb.toString();
    }

    private static void row(Rep rep, String name, CameraCharacteristics cc, CameraCharacteristics.Key<?> k) {
        rep.kv(name, fmt(g(cc, k)));
    }

    static void dump(Rep rep, Info i) {
        CameraCharacteristics cc = i.cc;
        StringBuilder caps = new StringBuilder();
        List<Integer> cl = new ArrayList<>(i.caps);
        Collections.sort(cl);
        for (int c : cl) caps.append(cap(c)).append(' ');
        rep.h("Câmara " + i.id + (i.listed ? "" : " (oculta: fora da lista oficial)") + " — " + i.facing + ", nível " + i.level);
        rep.kv("Capacidades", caps.toString().trim());
        rep.kv("Câmaras físicas (lógica)", i.phys.length == 0 ? "nenhuma" : Arrays.toString(i.phys));
        row(rep, "Orientação do sensor", cc, CameraCharacteristics.SENSOR_ORIENTATION);
        row(rep, "Pixel array", cc, CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        row(rep, "Área ativa", cc, CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        row(rep, "Tamanho físico sensor (mm)", cc, CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        row(rep, "Filtro de cor (0=RGGB)", cc, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        row(rep, "White level", cc, CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        row(rep, "Black level", cc, CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        row(rep, "Gama ISO", cc, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        row(rep, "ISO analógico máx.", cc, CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        row(rep, "Gama exposição (ns)", cc, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        row(rep, "Duração máx. de frame (ns)", cc, CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION);
        row(rep, "Fonte do timestamp", cc, CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        row(rep, "Distâncias focais (mm)", cc, CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        row(rep, "Aberturas", cc, CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        row(rep, "Estabilização ótica (OIS)", cc, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        row(rep, "Estab. de vídeo (EIS)", cc, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        row(rep, "Foco mínimo (dioptrias)", cc, CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        row(rep, "Flash", cc, CameraCharacteristics.FLASH_INFO_AVAILABLE);
        row(rep, "Modos AE", cc, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        row(rep, "Compensação AE (gama)", cc, CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        row(rep, "Compensação AE (passo)", cc, CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        row(rep, "Gamas de fps (AE)", cc, CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        row(rep, "Modos AF", cc, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        row(rep, "Modos AWB", cc, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        row(rep, "Cenas disponíveis", cc, CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
        row(rep, "Efeitos", cc, CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);
        row(rep, "Zoom digital máx.", cc, CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        if (Build.VERSION.SDK_INT >= 30) row(rep, "Gama de zoom (ratio)", cc, CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        row(rep, "Redução de ruído (modos)", cc, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        row(rep, "Nitidez/edge (modos)", cc, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
        row(rep, "Tone map (modos)", cc, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        row(rep, "Tone map (pontos máx.)", cc, CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
        row(rep, "Hot pixel (modos)", cc, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES);
        row(rep, "Shading (modos)", cc, CameraCharacteristics.SHADING_AVAILABLE_MODES);
        row(rep, "Aberração cromática (modos)", cc, CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES);
        row(rep, "Deteção de caras (modos)", cc, CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
        row(rep, "Lens shading map (modos)", cc, CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES);
        row(rep, "Streams saída (proc/stall/raw)", cc, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC);
        row(rep, "Streams saída stalling", cc, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_PROC_STALLING);
        row(rep, "Streams saída RAW", cc, CameraCharacteristics.REQUEST_MAX_NUM_OUTPUT_RAW);
        row(rep, "Streams de entrada (reprocess)", cc, CameraCharacteristics.REQUEST_MAX_NUM_INPUT_STREAMS);
        row(rep, "Profundidade da pipeline", cc, CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH);
        row(rep, "Latência máx. de sync", cc, CameraCharacteristics.SYNC_MAX_LATENCY);
        row(rep, "Zonas máx. AE/AF/AWB", cc, CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        rep.kv("   (AF / AWB)", fmt(g(cc, CameraCharacteristics.CONTROL_MAX_REGIONS_AF)) + " / " + fmt(g(cc, CameraCharacteristics.CONTROL_MAX_REGIONS_AWB)));
        if (Build.VERSION.SDK_INT >= 28) {
            row(rep, "Correção de distorção (modos)", cc, CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES);
            row(rep, "Sync das lentes físicas", cc, CameraCharacteristics.LOGICAL_MULTI_CAMERA_SENSOR_SYNC_TYPE);
        }
        rep.kv("Saídas JPEG", sizeLine(cc, ImageFormat.JPEG));
        rep.kv("Saídas YUV_420_888", sizeLine(cc, ImageFormat.YUV_420_888));
        rep.kv("Saídas RAW_SENSOR", sizeLine(cc, ImageFormat.RAW_SENSOR));
        rep.kv("Saídas PRIVATE", sizeLine(cc, 0x22));
        StreamConfigurationMap m = g(cc, CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (m != null) {
            try { rep.kv("Formatos de entrada (reprocess)", fmt(m.getInputFormats())); } catch (Throwable ignored) { }
            try {
                Size[] hs = m.getHighSpeedVideoSizes();
                rep.kv("Vídeo alta velocidade", hs == null || hs.length == 0 ? "nenhum" : Arrays.deepToString(hs));
            } catch (Throwable ignored) { }
        }
        try {
            List<CameraCharacteristics.Key<?>> keys = cc.getKeys();
            List<String> vendor = new ArrayList<>();
            for (CameraCharacteristics.Key<?> k : keys) if (!k.getName().startsWith("android.")) vendor.add(k.getName());
            rep.kv("Chaves totais / de fabricante", keys.size() + " / " + vendor.size());
            if (!vendor.isEmpty()) rep.l("   chaves de fabricante: " + trunc(vendor, 40));
            List<CaptureRequest.Key<?>> rk = cc.getAvailableCaptureRequestKeys();
            List<String> rv = new ArrayList<>();
            for (CaptureRequest.Key<?> k : rk) if (!k.getName().startsWith("android.")) rv.add(k.getName());
            rep.kv("Chaves de pedido / de fabricante", rk.size() + " / " + rv.size());
            if (!rv.isEmpty()) rep.l("   pedidos de fabricante: " + trunc(rv, 40));
            List<CaptureResult.Key<?>> rr = cc.getAvailableCaptureResultKeys();
            rep.kv("Chaves de resultado", rr.size());
        } catch (Throwable t) {
            rep.l("   (não foi possível listar chaves: " + t + ")");
        }
    }

    private static String trunc(List<String> l, int max) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < Math.min(max, l.size()); k++) sb.append(l.get(k)).append(k < Math.min(max, l.size()) - 1 ? ", " : "");
        if (l.size() > max) sb.append(" … (+").append(l.size() - max).append(")");
        return sb.toString();
    }

    static String oneLine(Info i) {
        StringBuilder c = new StringBuilder();
        if (i.raw()) c.append("RAW ");
        if (i.manual()) c.append("MANUAL ");
        if (i.has(6)) c.append("BURST ");
        if (i.logical()) c.append("LÓGICA(").append(i.phys.length).append(") ");
        if (i.has(8)) c.append("PROFUNDIDADE ");
        if (i.has(7) || i.has(4)) c.append("REPROCESS ");
        return String.format(Locale.ROOT, "Câmara %s%s [%s] nível %s | %s| YUV máx %s, JPEG máx %s%s",
                i.id, i.listed ? "" : " (oculta)", i.facing, i.level, c, sz(i.maxYuv), sz(i.maxJpeg),
                i.maxRaw == null ? "" : ", RAW " + sz(i.maxRaw));
    }
}
