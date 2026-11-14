package com.eikon.diag;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Benchmarks de CPU e testes de memória para estimar se um pipeline tipo HDR+ cabe no telemóvel. */
final class Bench {
    private static final int W = 4000, H = 3000;           // ~12 MP de luma
    private static final int TILE = 16, R = 4;             // blocos 16x16, pesquisa ±4 px

    private static void fill(byte[] a, long seed) {
        long x = seed;
        for (int i = 0; i < a.length; i++) {
            x ^= x << 13; x ^= x >>> 7; x ^= x << 17;
            a[i] = (byte) x;
        }
    }

    private static int sad(byte[] ref, byte[] alt, int tx, int ty, int dx, int dy) {
        int s = 0;
        for (int y = 0; y < TILE; y++) {
            int ro = (ty + y) * W + tx;
            int ao = (ty + y + dy) * W + tx + dx;
            for (int x = 0; x < TILE; x++) {
                int d = (ref[ro + x] & 0xFF) - (alt[ao + x] & 0xFF);
                s += d < 0 ? -d : d;
            }
        }
        return s;
    }

    private static long search(byte[] ref, byte[] alt, int[] tiles, int from, int to) {
        long acc = 0;
        for (int k = from; k < to; k++) {
            int tx = tiles[2 * k], ty = tiles[2 * k + 1];
            int best = Integer.MAX_VALUE, bd = 0;
            for (int dy = -R; dy <= R; dy++)
                for (int dx = -R; dx <= R; dx++) {
                    int s = sad(ref, alt, tx, ty, dx, dy);
                    if (s < best) { best = s; bd = dx * 31 + dy; }
                }
            acc += bd + best;
        }
        return acc;
    }

    static void cpu(Rep rep, CamLive.Ctl ctl) {
        rep.h("Benchmark de processamento (CPU)");
        rep.mark("benchmark CPU");
        ctl.note("Benchmark: a preparar dados de 12 MP");
        byte[] ref = new byte[W * H];
        byte[] alt = new byte[W * H];
        fill(ref, 88172645463325252L);
        for (int i = 0; i < alt.length; i++) alt[i] = (byte) (ref[i] ^ (i & 3));
        int tilesFull = (W / TILE) * (H / TILE);
        int K = 3000;
        int[] tiles = new int[2 * K];
        java.util.Random rnd = new java.util.Random(7);
        for (int k = 0; k < K; k++) {
            tiles[2 * k] = R + rnd.nextInt(W - TILE - 2 * R);
            tiles[2 * k + 1] = R + rnd.nextInt(H - TILE - 2 * R);
        }
        ctl.note("Benchmark: alinhamento por blocos (1 thread)");
        long t0 = SystemClock.elapsedRealtime();
        long sink = search(ref, alt, tiles, 0, K);
        double t1 = SystemClock.elapsedRealtime() - t0;
        double est1 = t1 * tilesFull / K;
        rep.kv("Alinhamento (SAD ±4px) 1 thread", String.format(Locale.ROOT, "%.0f ms p/ %d blocos → ~%.1f s por frame de 12 MP", t1, K, est1 / 1000));

        final int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        ctl.note("Benchmark: alinhamento por blocos (" + cores + " threads)");
        ExecutorService ex = Executors.newFixedThreadPool(cores);
        long estN = 0;
        double tN = 0;
        try {
            t0 = SystemClock.elapsedRealtime();
            List<Future<Long>> fs = new ArrayList<>();
            final byte[] fr = ref, fa = alt;
            final int[] ft = tiles;
            int chunk = (K + cores - 1) / cores;
            for (int c = 0; c < cores; c++) {
                final int from = c * chunk, to = Math.min(K, from + chunk);
                if (from >= to) break;
                fs.add(ex.submit(new Callable<Long>() { public Long call() { return search(fr, fa, ft, from, to); } }));
            }
            for (Future<Long> f : fs) sink += f.get();
            tN = SystemClock.elapsedRealtime() - t0;
        } catch (Throwable t) {
            rep.l("   multi-thread falhou: " + t);
        } finally {
            ex.shutdownNow();
        }
        double estM = tN * tilesFull / K;
        rep.kv("Alinhamento " + cores + " threads", String.format(Locale.ROOT, "%.0f ms → ~%.1f s por frame de 12 MP (sem pirâmide; com pirâmide grossa→fina costuma ser várias vezes menos)", tN, estM / 1000));

        ctl.note("Benchmark: fusão de 8 frames");
        rep.mark("benchmark fusão");
        int[] acc = new int[W * H];
        t0 = SystemClock.elapsedRealtime();
        for (int f = 0; f < 8; f++) {
            byte[] src = (f & 1) == 0 ? ref : alt;
            for (int i = 0; i < acc.length; i++) acc[i] += src[i] & 0xFF;
        }
        for (int i = 0; i < acc.length; i += 4096) sink += acc[i];
        double tm = SystemClock.elapsedRealtime() - t0;
        rep.kv("Fusão (média) de 8 frames 12 MP", String.format(Locale.ROOT, "%.0f ms (só aritmética, sem alinhamento)", tm));

        ctl.note("Benchmark: tone mapping");
        t0 = SystemClock.elapsedRealtime();
        byte[] lut = new byte[256];
        for (int i = 0; i < 256; i++) lut[i] = (byte) Math.min(255, (int) (255.0 * Math.pow(i / 255.0, 0.8)));
        byte[] out = new byte[W * H];
        for (int i = 0; i < out.length; i++) out[i] = lut[ref[i] & 0xFF];
        double tl = SystemClock.elapsedRealtime() - t0;
        rep.kv("Curva de tons (LUT) 12 MP", String.format(Locale.ROOT, "%.0f ms", tl));
        t0 = SystemClock.elapsedRealtime();
        double fl = 0;
        for (int i = 0; i < 1000000; i++) fl += Math.exp(-(i & 255) / 64.0) * Math.sqrt(i + 1.0);
        double tf = SystemClock.elapsedRealtime() - t0;
        rep.kv("Matemática float (1M exp+sqrt)", String.format(Locale.ROOT, "%.0f ms (~%.1f s se fosse por pixel em 12 MP)", tf, tf * 12 / 1000.0));
        rep.kv("(checksum, ignorar)", sink + fl + out[5]);

        rep.sum(String.format(Locale.ROOT, "CPU: alinhamento ~%.1f s/frame (1 thread) | ~%.1f s/frame (%d threads) em 12 MP, sem pirâmide; fusão 8 frames %.1f s",
                est1 / 1000, estM / 1000, cores, tm / 1000));
        rep.save();
    }

    static void memory(Context ctx, Rep rep, CamLive.Ctl ctl) {
        rep.h("Teste de memória");
        double start = DeviceInfo.availMem(ctx) / 1048576.0;
        rep.kv("RAM livre no início", String.format(Locale.ROOT, "%.0f MB", start));

        rep.mark("memória: Bitmaps");
        ctl.note("Memória: bitmaps de 12 e 25 MP");
        String b12 = bitmap(4000, 3000), b25 = bitmap(5792, 4344);
        rep.kv("Bitmap ARGB 12 MP (48 MB)", b12);
        rep.kv("Bitmap ARGB 25 MP (100 MB)", b25);

        rep.mark("memória: heap Java");
        ctl.note("Memória: heap Java");
        int javaMb = javaHeap();
        rep.kv("Heap Java alocável (blocos de 16 MB)", javaMb + " MB");

        rep.mark("memória: nativa");
        ctl.note("Memória: nativa (direct buffers)");
        int natMb = nativeMem();
        rep.kv("Memória nativa alocável (até 600 MB)", natMb + " MB");

        double end = DeviceInfo.availMem(ctx) / 1048576.0;
        rep.kv("RAM livre no fim", String.format(Locale.ROOT, "%.0f MB", end));
        rep.sum(String.format(Locale.ROOT, "Memória: heap Java ~%d MB, nativa ~%d MB (teste até 600), bitmap 12MP %s, 25MP %s",
                javaMb, natMb, b12, b25));
        rep.save();
    }

    private static String bitmap(int w, int h) {
        Bitmap b = null;
        try {
            long t0 = SystemClock.elapsedRealtime();
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            return "OK (" + (SystemClock.elapsedRealtime() - t0) + " ms)";
        } catch (Throwable t) {
            return "FALHOU (" + t.getClass().getSimpleName() + ")";
        } finally {
            if (b != null) b.recycle();
        }
    }

    private static int javaHeap() {
        List<byte[]> l = new ArrayList<>();
        int mb = 0;
        try {
            while (mb < 900) { l.add(new byte[16 * 1048576]); mb += 16; }
        } catch (Throwable ignored) { }
        l.clear();
        System.gc();
        return mb;
    }

    private static int nativeMem() {
        List<ByteBuffer> l = new ArrayList<>();
        int mb = 0;
        try {
            while (mb < 600) {
                ByteBuffer bb = ByteBuffer.allocateDirect(32 * 1048576);
                for (int i = 0; i < bb.capacity(); i += 4096) bb.put(i, (byte) 1); // força páginas reais
                l.add(bb);
                mb += 32;
            }
        } catch (Throwable ignored) { }
        l.clear();
        System.gc();
        return mb;
    }
}
