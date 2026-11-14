package com.eikon.diag;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Locale;

/** Buffer do relatório: resumo (curto) + detalhes (longo). Guarda cópia parcial em disco. */
final class Rep {
    private final StringBuilder sum = new StringBuilder();
    private final StringBuilder det = new StringBuilder();
    private final File partial;
    private final File marker;

    Rep(File dir) {
        partial = new File(dir, "partial.txt");
        marker = new File(dir, "marker.txt");
    }

    synchronized void sum(String s) { sum.append(s).append('\n'); }
    synchronized void h(String t) { det.append("\n=== ").append(t).append(" ===\n"); }
    synchronized void l(String s) { det.append(s).append('\n'); }
    synchronized void kv(String k, Object v) {
        det.append(String.format(Locale.ROOT, "%-36s %s\n", k, String.valueOf(v)));
    }
    synchronized String summary() { return sum.toString(); }
    synchronized String full() {
        return "RESUMO\n======\n" + sum + "\nDETALHES\n========" + det;
    }

    void save() { write(partial, full()); }
    void mark(String s) { write(marker, s); }
    void clearMark() { marker.delete(); }

    static void write(File f, String s) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8")) {
            w.write(s);
        } catch (Exception ignored) { }
    }

    static String read(File f) {
        if (f == null || !f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    static File partialFile(File dir) { return new File(dir, "partial.txt"); }
    static File markerFile(File dir) { return new File(dir, "marker.txt"); }
}
