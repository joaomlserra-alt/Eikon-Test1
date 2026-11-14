package com.eikon.diag;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {
    private static final int BG = 0xFF131314, SURF = 0xFF1E1F20, PRIMARY = 0xFFA8C7FA, ON_PRIMARY = 0xFF062E6F,
            TEXT = 0xFFE3E3E3, SUB = 0xFFC4C7C5, OKC = 0xFF81C995, ERRC = 0xFFF28B82, OUTLINE = 0xFF444746;
    private static final String[] RUN_TITLES = {
            "Executar todos os testes (v1)",
            "Testes novos COM LUZ (v1.1)",
            "Teste no ESCURO — tapar a câmara (v1.1)"};

    private TextView status, stagesView, out;
    private ProgressBar bar;
    private final Button[] runBtn = new Button[3];
    private Button copyS, copyF, share;
    private CheckBox fullBox;
    private TestRunner runner;
    private boolean running;
    private int runningMode;
    private String[] names = TestRunner.NAMES_FULL;
    private String[] state = new String[names.length];
    private String lastSummary = "", lastFull = "";

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView tv(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button btn(String s, boolean filled) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(s);
        b.setTextSize(14);
        b.setTextColor(filled ? ON_PRIMARY : PRIMARY);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        if (filled) g.setColor(PRIMARY); else { g.setColor(Color.TRANSPARENT); g.setStroke(dp(1), OUTLINE); }
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setMinimumHeight(dp(48));
        return b;
    }

    private LinearLayout.LayoutParams full(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(tv("Eikon Diag v1.1", 28, TEXT, true));
        TextView sub = tv("Testa a câmara do telemóvel e gera um relatório para copiar. Os testes v1.1 verificam exposição manual, redução de ruído, curva de tons, zoom e limites de exposição.", 14, SUB, false);
        sub.setPadding(0, dp(6), 0, dp(14));
        root.addView(sub);

        for (int i = 0; i < 3; i++) {
            runBtn[i] = btn(RUN_TITLES[i], i == 1);
            root.addView(runBtn[i], full(i == 0 ? 4 : 8));
            final int mode = i == 0 ? 0 : i;
            runBtn[i].setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { onRun(mode); } });
        }

        fullBox = new CheckBox(this);
        fullBox.setText("Modo completo do teste v1 (câmaras ocultas e exposição longa até 30 s; demora mais)");
        fullBox.setTextColor(SUB);
        fullBox.setTextSize(12);
        fullBox.setChecked(false);
        fullBox.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        root.addView(fullBox, full(6));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(names.length * 10);
        bar.setProgressTintList(ColorStateList.valueOf(PRIMARY));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
        bp.topMargin = dp(16);
        root.addView(bar, bp);

        status = tv("Pronto. Os testes v1.1 demoram cerca de 1 a 2 minutos. Fique na app com o ecrã ligado.", 13, SUB, false);
        status.setPadding(0, dp(8), 0, dp(8));
        root.addView(status);

        stagesView = tv("", 12, TEXT, false);
        stagesView.setTypeface(Typeface.MONOSPACE);
        resetStages();
        root.addView(stagesView);

        TextView rt = tv("Resultado", 16, TEXT, true);
        rt.setPadding(0, dp(18), 0, dp(8));
        root.addView(rt);

        out = tv("(ainda sem resultados)", 11, TEXT, false);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable card = new GradientDrawable();
        card.setColor(SURF);
        card.setCornerRadius(dp(16));
        out.setBackground(card);
        root.addView(out);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        copyS = btn("Copiar resumo", true);
        copyF = btn("Copiar relatório completo", false);
        share = btn("Partilhar relatório", false);
        for (Button x : new Button[]{copyS, copyF, share}) {
            col.addView(x, full(8));
            x.setEnabled(false);
            x.setAlpha(0.5f);
        }
        root.addView(col, full(10));
        copyS.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copy("Resumo", lastSummary); } });
        copyF.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { copy("Relatório completo", lastFull); } });
        share.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { doShare(); } });

        setContentView(sv);
        checkLeftover();
    }

    private void resetStages() {
        state = new String[names.length];
        for (int i = 0; i < state.length; i++) state[i] = "·";
        bar.setMax(names.length * 10);
        bar.setProgress(0);
        renderStages();
    }

    private void checkLeftover() {
        File dir = getFilesDir();
        String mk = Rep.read(Rep.markerFile(dir));
        String part = Rep.read(Rep.partialFile(dir));
        if (mk != null && part != null) {
            status.setText("A execução anterior foi interrompida em: " + mk
                    + "\n(muito provavelmente o Android fechou a app por falta de memória). O relatório parcial está abaixo e pode ser copiado.");
            status.setTextColor(ERRC);
            int cut = part.indexOf("\nDETALHES");
            show((cut > 0 ? part.substring(0, cut) : part) + "\n[INTERROMPIDO EM: " + mk + "]", part + "\n\n[INTERROMPIDO EM: " + mk + "]");
        }
    }

    private void show(String summary, String fullText) {
        lastSummary = summary;
        lastFull = fullText;
        out.setText(lastSummary);
        for (Button x : new Button[]{copyS, copyF, share}) { x.setEnabled(true); x.setAlpha(1f); }
    }

    private void renderStages() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < state.length; i++) sb.append(state[i]).append("  ").append(names[i]).append('\n');
        stagesView.setText(sb.toString().trim());
    }

    private void onRun(int mode) {
        if (running) {
            if (runner != null) runner.cancel();
            runBtn[runningMode].setText("A parar…");
            runBtn[runningMode].setEnabled(false);
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingMode = mode;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
            return;
        }
        start(mode);
    }

    private int pendingMode = 1;

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 1) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) start(pendingMode);
            else { status.setText("Sem permissão da câmara não é possível testar. Dê a permissão em Definições > Apps > Eikon Diag."); status.setTextColor(ERRC); }
        }
    }

    private void setRunning(boolean on, int mode) {
        running = on;
        runningMode = mode;
        for (int i = 0; i < 3; i++) {
            runBtn[i].setText(RUN_TITLES[i]);
            runBtn[i].setEnabled(!on);
            runBtn[i].setAlpha(on ? 0.5f : 1f);
        }
        if (on) {
            runBtn[mode].setText("Parar");
            runBtn[mode].setEnabled(true);
            runBtn[mode].setAlpha(1f);
        }
    }

    private void start(int mode) {
        setRunning(true, mode);
        status.setTextColor(SUB);
        {
            final Rep rep = new Rep(getFilesDir());
            runner = new TestRunner(getApplicationContext(), rep, fullBox.isChecked(), mode, new TestRunner.Listener() {
                @Override public void stage(final int idx, final String st) {
                    runOnUiThread(new Runnable() { @Override public void run() {
                        if (idx >= state.length) return;
                        state[idx] = st.equals("run") ? "▶" : st.equals("ok") ? "✓" : st.equals("fail") ? "✗" : "–";
                        renderStages();
                        bar.setProgress(st.equals("run") ? idx * 10 + 3 : idx * 10 + 10);
                    } });
                }
                @Override public void note(final String s) {
                    runOnUiThread(new Runnable() { @Override public void run() { status.setText(s); } });
                }
                @Override public void done(final boolean cancelled) {
                    runOnUiThread(new Runnable() { @Override public void run() {
                        setRunning(false, 0);
                        status.setText(cancelled ? "Parado. Relatório parcial abaixo." : "Concluído. Toque em \"Copiar resumo\" e cole na conversa.");
                        status.setTextColor(cancelled ? ERRC : OKC);
                        bar.setProgress(bar.getMax());
                        show(rep.summary(), rep.full());
                    } });
                }
            });
            names = runner.names;
            resetStages();
            out.setText("A testar… o resumo aparece aqui no fim.");
            for (Button x : new Button[]{copyS, copyF, share}) { x.setEnabled(false); x.setAlpha(0.5f); }
            runner.start();
        }
    }

    private void copy(String label, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, label + " copiado (" + (text.length() / 1024 + 1) + " KB)", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "Não foi possível copiar: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private void doShare() {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "Relatório Eikon Diag");
            i.putExtra(Intent.EXTRA_TEXT, lastFull);
            startActivity(Intent.createChooser(i, "Partilhar relatório"));
        } catch (Throwable t) {
            Toast.makeText(this, "Não foi possível partilhar: " + t, Toast.LENGTH_LONG).show();
        }
    }
}
