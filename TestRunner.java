package com.eikon.diag;

import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.media.CamcorderProfile;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.os.SystemClock;
import android.util.Range;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Orquestra todos os testes, em sequência, numa thread própria. */
final class TestRunner implements CamLive.Ctl {
    interface Listener {
        void stage(int idx, String state);   // "run", "ok", "fail", "skip"
        void note(String s);
        void done(boolean cancelled);
    }

    private interface Job { void run() throws Throwable; }

    static final String[] NAMES_FULL = {
            "Dispositivo e sistema",
            "Câmaras: capacidades",
            "Câmaras ocultas (IDs fora da lista)",
            "Vídeo e codecs",
            "Testes ao vivo (capturas)",
            "Benchmark de processamento",
            "Teste de memória",
            "Concluir"
    };
    static final String[] NAMES_LIGHT = {"Preparar (pousar o telemóvel)", "Testes com luz", "Concluir"};
    static final String[] NAMES_DARK = {"Preparar (tapar a câmara)", "Testes no escuro", "Concluir"};

    private final Context ctx;
    private final Rep rep;
    private final Listener ls;
    private final boolean full;
    final int mode;          // 0 = completo, 1 = v1.1 com luz, 2 = v1.1 escuro
    final String[] names;
    private volatile boolean cancel;
    private final List<CamInfo.Info> infos = new ArrayList<>();
    private final List<String> listedIds = new ArrayList<>();

    TestRunner(Context ctx, Rep rep, boolean full, int mode, Listener ls) {
        this.ctx = ctx;
        this.rep = rep;
        this.full = full;
        this.mode = mode;
        this.names = mode == 1 ? NAMES_LIGHT : mode == 2 ? NAMES_DARK : NAMES_FULL;
        this.ls = ls;
    }

    @Override public boolean cancelled() { return cancel; }
    @Override public void note(String s) { ls.note(s); }
    void cancel() { cancel = true; }

    void start() {
        new Thread(new Runnable() { @Override public void run() { runAll(); } }, "diag").start();
    }

    private void stage(int i, Job j) {
        if (cancel) { ls.stage(i, "skip"); return; }
        ls.stage(i, "run");
        ls.note(names[i]);
        rep.mark(names[i]);
        String st = "ok";
        try {
            j.run();
        } catch (Throwable t) {
            st = "fail";
            rep.h("ERRO em " + names[i]);
            rep.l(t.toString());
            rep.sum("ERRO em " + names[i] + ": " + t);
        }
        rep.save();
        ls.stage(i, st);
    }

    private void runV11(final boolean dark) {
        final CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        stage(0, new Job() {
            public void run() {
                DeviceInfo.run(ctx, rep, dark ? "EIKON DIAG v1.1 (escuro)" : "EIKON DIAG v1.1 (com luz)");
                for (int s = 8; s >= 1 && !cancel; s--) {
                    ls.note(dark
                            ? "TAPE a câmara traseira com o dedo e mantenha. A começar em " + s + " s…"
                            : "Pouse o telemóvel PARADO, a apontar a uma parede ou quadro com textura e boa luz. A começar em " + s + " s…");
                    SystemClock.sleep(1000);
                }
            }
        });
        stage(1, new Job() {
            public void run() throws Throwable {
                CamInfo.Info back = null;
                for (String id : mgr.getCameraIdList()) {
                    CamInfo.Info i = CamInfo.info(mgr, id, true);
                    infos.add(i);
                    if (back == null && i.back()) back = i;
                }
                if (back == null) throw new Exception("nenhuma câmara traseira encontrada");
                new CamLive(ctx, mgr, rep, TestRunner.this, back).runV11(dark);
            }
        });
        stage(2, new Job() {
            public void run() {
                rep.sum("Estado final: bateria " + DeviceInfo.battery(ctx) + " | térmico " + DeviceInfo.thermal(ctx));
                rep.sum(cancel ? "NOTA: execução parada pelo utilizador (relatório incompleto)." : "Execução concluída sem interrupções.");
            }
        });
        rep.clearMark();
        rep.save();
        ls.done(cancel);
    }

    private void runAll() {
        if (mode != 0) { runV11(mode == 2); return; }
        final CameraManager mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);

        stage(0, new Job() { public void run() { DeviceInfo.run(ctx, rep); } });

        stage(1, new Job() {
            public void run() throws Throwable {
                String[] ids = mgr.getCameraIdList();
                rep.kv("IDs na lista oficial", Arrays.toString(ids));
                for (String id : ids) {
                    listedIds.add(id);
                    try {
                        CamInfo.Info i = CamInfo.info(mgr, id, true);
                        infos.add(i);
                        CamInfo.dump(rep, i);
                    } catch (Throwable t) {
                        rep.h("Câmara " + id);
                        rep.l("não foi possível ler: " + t);
                    }
                }
            }
        });

        stage(2, new Job() {
            public void run() {
                List<String> found = new ArrayList<>();
                for (int k = 0; k <= 120 && !cancel; k++) {
                    String s = String.valueOf(k);
                    if (listedIds.contains(s)) continue;
                    try {
                        CamInfo.Info i = CamInfo.info(mgr, s, false);
                        infos.add(i);
                        found.add(s);
                        CamInfo.dump(rep, i);
                    } catch (Throwable ignored) { }
                }
                rep.h("Resumo das câmaras ocultas");
                rep.kv("IDs ocultos acessíveis (0–120)", found.isEmpty() ? "nenhum" : found.toString());
                rep.sum("IDs ocultos acessíveis: " + (found.isEmpty() ? "nenhum" : found.toString()));
            }
        });

        stage(3, new Job() { public void run() { video(); } });

        stage(4, new Job() {
            public void run() {
                List<CamInfo.Info> order = new ArrayList<>();
                for (CamInfo.Info i : infos) if (i.back() && i.listed) order.add(i);
                for (CamInfo.Info i : infos) if (i.back() && !i.listed) order.add(i);
                for (CamInfo.Info i : infos) if (!i.back()) order.add(i);
                for (CamInfo.Info i : order) {
                    if (cancel) break;
                    boolean deep = i.back() && (i.listed || full);
                    new CamLive(ctx, mgr, rep, TestRunner.this, i).run(full, deep);
                }
            }
        });

        stage(5, new Job() { public void run() { Bench.cpu(rep, TestRunner.this); } });
        stage(6, new Job() { public void run() { Bench.memory(ctx, rep, TestRunner.this); } });

        stage(7, new Job() {
            public void run() {
                rep.sum("Estado final: bateria " + DeviceInfo.battery(ctx) + " | térmico " + DeviceInfo.thermal(ctx)
                        + String.format(Locale.ROOT, " | RAM livre %.0f MB", DeviceInfo.availMem(ctx) / 1048576.0));
                rep.sum(cancel ? "NOTA: execução parada pelo utilizador (relatório incompleto)." : "Execução concluída sem interrupções.");
            }
        });

        rep.clearMark();
        rep.save();
        ls.done(cancel);
    }

    private void video() {
        rep.h("Vídeo (CamcorderProfile) por câmara");
        int[] q = {CamcorderProfile.QUALITY_480P, CamcorderProfile.QUALITY_720P, CamcorderProfile.QUALITY_1080P, CamcorderProfile.QUALITY_2160P};
        String[] qn = {"480p", "720p", "1080p", "2160p(4K)"};
        for (CamInfo.Info i : infos) {
            int num;
            try { num = Integer.parseInt(i.id); } catch (Throwable t) { continue; }
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < q.length; k++) {
                boolean has = false;
                try { has = CamcorderProfile.hasProfile(num, q[k]); } catch (Throwable ignored) { }
                if (has) {
                    String extra = "";
                    try {
                        CamcorderProfile p = CamcorderProfile.get(num, q[k]);
                        if (p != null) extra = "@" + p.videoFrameRate + "fps";
                    } catch (Throwable ignored) { }
                    sb.append(qn[k]).append(extra).append(' ');
                }
            }
            rep.kv("Câmara " + i.id + " (" + i.facing + ")", sb.length() == 0 ? "sem perfis" : sb.toString().trim());
        }
        rep.h("Codificadores de vídeo (MediaCodec)");
        StringBuilder sum = new StringBuilder("Codecs de vídeo (encoders): ");
        try {
            MediaCodecInfo[] cs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo ci : cs) {
                if (!ci.isEncoder()) continue;
                for (String mime : ci.getSupportedTypes()) {
                    if (!mime.equals("video/avc") && !mime.equals("video/hevc")) continue;
                    String line = ci.getName() + " [" + mime + "]";
                    boolean main10 = false;
                    try {
                        MediaCodecInfo.CodecCapabilities cc = ci.getCapabilitiesForType(mime);
                        MediaCodecInfo.VideoCapabilities vc = cc.getVideoCapabilities();
                        Range<Integer> w = vc.getSupportedWidths(), h = vc.getSupportedHeights();
                        Range<Integer> fr = vc.getSupportedFrameRates();
                        line += " até " + w.getUpper() + "x" + h.getUpper() + ", fps " + fr;
                        for (MediaCodecInfo.CodecProfileLevel pl : cc.profileLevels)
                            if (mime.equals("video/hevc") && pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) main10 = true;
                        if (Build.VERSION.SDK_INT >= 29) line += ", hardware=" + ci.isHardwareAccelerated();
                    } catch (Throwable ignored) { }
                    if (mime.equals("video/hevc")) line += ", Main10(10-bit)=" + main10;
                    rep.l("   " + line);
                    if (ci.getName().toLowerCase(Locale.ROOT).startsWith("omx.google") || ci.getName().toLowerCase(Locale.ROOT).startsWith("c2.android")) continue;
                    sum.append(mime.replace("video/", "")).append("(").append(main10 ? "10-bit " : "").append("hw) ");
                }
            }
        } catch (Throwable t) {
            rep.l("   não foi possível listar codecs: " + t);
        }
        rep.sum(sum.toString().trim());
    }
}
